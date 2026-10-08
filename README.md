# AI-pipeline для анализа повреждений автомобилей по изображениям

Исследовательский прототип: конвейер анализа одного снимка мультимодальной моделью
(Claude, `claude-opus-5-5`) и воспроизводимый benchmark его качества на датасете SYNDCAR.
Telegram-бот и расчёт стоимости — вторичны и в первую веху не входят.

## Структура

```
src/main/java/com/cardamage/
  core/                     ядро pipeline, не зависит от Spring (только Jackson)
    pipeline/
      SingleImageAnalyzer   один снимок: уменьшение -> вызов модели -> парсинг -> проверка формата -> retry -> fallback
      DownscalingImagePreprocessor  уменьшение больших снимков перед отправкой (лимит API 5 МБ)
      ResponseParser        текст ответа -> объект (только структура JSON)
      ResponseFormatValidator  явная проверка значений (enum, диапазоны, рамка)
      DamagePrompt          промпт с номером версии
      VisionModelClient     интерфейс вызова модели
      StubVisionModelClient заглушка модели (без API и без данных)
    demo/DamageMergeService объединение серии фото (только демо)
    demo/PriceEstimator     ориентир стоимости по условному справочнику цен (только демо)
    bot/DemoBot             логика Telegram-бота: 3–10 фото, затем /report, /app (только демо)
    miniapp/                проверка подписи Telegram (initData) и лимит запросов для Mini App
    pipeline/TiledImageAnalyzer  анализ снимка перекрывающимися фрагментами (?mode=tiled)
    pipeline/RegionDescriber     гибрид: модель описывает рамки, найденные детектором (/api/v1/describe)
  web/                      тонкий Spring-слой
    AnalysisController      POST /api/v1/analyze, POST /api/v1/demo/analyze, GET /api/v1/info
    AnthropicVisionModelClient  реальный вызов Claude API
    PipelineConfig          выбор stub / anthropic
    TelegramBotRunner, TelegramHttpApi  запуск бота внутри сервиса (long polling), выключен без токена
    MiniAppController       API для Mini App; страница: src/main/resources/static/miniapp/index.html
scripts/
  convert_syndcar.py           SYNDCAR (YOLO) -> COCO, автоматическое определение детали, пулы dev/test
  select_benchmark_samples.py  фиксированная стратифицированная выборка dev/test
  make_labeling_sheet.py       таблица для ручной разметки part/severity/action
  make_labeling_page.py        страница в браузере для разметки: снимок с рамками + выпадающие списки
  compare_labels.py            сравнение двух разметчиков: согласие, каппа Коэна, расхождения
  label_sensitivity.py         точность severity/part/action по разметке A, по B и там, где они совпали
  agreement.py                 согласие 2+ разметчиков (каппа Флейса) и сводная разметка по большинству
  bootstrap.py                 95% доверительные интервалы метрик и парное сравнение двух методов
  selective.py                 сколько ответов можно принять автоматически при пороге уверенности
  make_yolo_dataset.py, train_detector.py, predict_detector.py  базовый детектор YOLO (нужен ultralytics)
  run_hybrid.py                гибрид: рамки детектора описывает модель
  build_ground_truth.py        сборка эталона из пула COCO + итоговой разметки, с проверками
  run_benchmark.py             прогон выборки через сервис, сохранение предсказаний
  evaluate.py                  сопоставление (IoU >= 0.5, один к одному) и метрики
  tests/                       тесты Python-скриптов (85), включая прогон всей цепочки
benchmark/
  syndcar_coco/              пулы dev/test в формате COCO и отчёт конвертации
  dev_examples.*, test_examples.*  зафиксированная выборка (16 + 24 снимка)
  prompt_changelog.md        журнал версий промпта
  fixtures/                  синтетические данные для разработки и тестов
docs/TZ.md                   техническое задание
docs/experiments.md          продолжение исследования: интервалы, автоматизация, фрагменты, детектор
docs/severity_scale_v2.md    шкала серьёзности с измеримыми признаками
docs/benchmark_report.md     отчёт о benchmark: результаты, разбор ошибок, ограничения
docs/first_test_run.md       результат первого прогона тестов (первая веха)
```

## Требования

- JDK 17+ и Maven 3.8+ (или IntelliJ IDEA — Maven встроен)
- Python 3.9+ (только стандартная библиотека). На Windows в командах ниже вместо `python3` пиши `python`.

## Запуск

Тесты Java:
```bash
mvn test
```

Сервис в режиме заглушки (ключ API не нужен, модель не вызывается):
```bash
mvn spring-boot:run
curl -F "image=@car.jpg;type=image/jpeg" http://localhost:8080/api/v1/analyze
curl http://localhost:8080/api/v1/info
```

Сервис с реальной моделью:
```bash
export ANTHROPIC_API_KEY=...
export PIPELINE_MODEL_CLIENT=anthropic
mvn spring-boot:run
```

Тесты Python и пример оценки на синтетических данных:
```bash
cd scripts
python3 -m unittest discover -s tests -v
python3 evaluate.py --ground-truth ../benchmark/fixtures/ground_truth_synthetic.json \
                    --predictions  ../benchmark/fixtures/predictions_synthetic.json
```

## Telegram-бот (демо, вторично)

Бот работает внутри того же сервиса и использует тот же pipeline одного снимка. Хостинг и публичный адрес
не нужны (long polling): бот отвечает, пока сервис запущен на вашем компьютере.

1. В Telegram у @BotFather: `/newbot`, получить токен.
2. Запустить сервис с переменными `TELEGRAM_BOT_TOKEN=...`, `PIPELINE_MODEL_CLIENT=anthropic`, `ANTHROPIC_API_KEY=...`.
   Токен в файлы проекта не записывать.
3. Написать боту `/start`, прислать 3–10 фото одного автомобиля, затем `/report`.

Бот анализирует каждое фото отдельно, объединяет результаты (одинаковые деталь и тип — одна запись)
и добавляет ориентир стоимости из `src/main/resources/demo/price_table.json`. Цены в справочнике условные,
придуманы для демонстрации. Telegram сжимает фотографии; чтобы сохранить качество, отправляйте их как файл.
Без токена бот выключен и на benchmark и тесты не влияет.

## Telegram Mini App (демо)

Приложение внутри Telegram: загрузка фото, рамки повреждений поверх снимка, итог с ориентиром цены.
Страница отдаётся тем же сервисом (`/miniapp/index.html`). Telegram открывает Mini App только по адресу
**https**, поэтому нужен туннель. Бесплатный вариант — Cloudflare Quick Tunnel (без регистрации):

1. Установить: `winget install --id Cloudflare.cloudflared` (Windows).
2. Запустить сервис как для бота (`TELEGRAM_BOT_TOKEN`, `PIPELINE_MODEL_CLIENT=anthropic`, `ANTHROPIC_API_KEY`).
3. В отдельном окне: `cloudflared tunnel --url http://localhost:8080` — в выводе будет адрес вида
   `https://<слова>.trycloudflare.com`.
4. Добавить переменную `TELEGRAM_MINIAPP_URL=https://<слова>.trycloudflare.com/miniapp/index.html`
   и перезапустить сервис. Бот сам поставит кнопку «Осмотр» рядом с полем ввода; команда `/app` присылает кнопку.

Адрес туннеля меняется при каждом запуске `cloudflared` — тогда повторите шаг 4.
Сервис принимает запросы Mini App только с подписью Telegram (проверка `initData` токеном бота)
и не больше 30 анализов фото на пользователя в час (`telegram.miniapp.photos-per-hour`): адрес туннеля
публичный, а каждый анализ тратит ключ API.

## Порядок benchmark

Все команды выполняются из папки `scripts`. SYNDCAR распакован в `data/hitl/SYNDCAR`
(папки `images`, `labels_damage`, `labels_parts` и файлы `.yaml`); папка `data/` не попадает в Git.

**0. Конвертировать SYNDCAR** в формат COCO и разделить на пулы dev и test (один раз):
```bash
python3 convert_syndcar.py --syndcar ../data/hitl/SYNDCAR --out-dir ../benchmark/syndcar_coco
```
Снимки делятся на пулы целыми съёмочными днями (дата из имени файла), чтобы серия кадров одной
сцены не попала и в dev, и в test. Итог: dev — 61 снимок (18 и 19.09.2024), test — 184 (17.09.2024).
Проверь вывод: как сопоставлены классы, сколько снимков в каждом пуле по типам повреждений,
есть ли снимки с EXIF-поворотом.

**1. Зафиксировать выборку** (16 dev + 24 test) и сразу закоммитить `benchmark/dev_examples.*`, `benchmark/test_examples.*`:
```bash
python3 select_benchmark_samples.py --val-annotations ../benchmark/syndcar_coco/dev_pool.json \
    --test-annotations ../benchmark/syndcar_coco/test_pool.json --seed 42 --out-dir ../benchmark
```

**2. Разметка dev** — таблица и страница для разметки, по одной на каждого разметчика:
```bash
python3 make_labeling_sheet.py --annotations ../benchmark/syndcar_coco/dev_pool.json \
    --examples ../benchmark/dev_examples.json --out ../benchmark/labels_dev_A.csv
python3 make_labeling_page.py --sheet ../benchmark/labels_dev_A.csv \
    --images-dir ../data/hitl/SYNDCAR/images --out ../benchmark/labeling_dev_A.html
```
Открой `labeling_dev_A.html` в браузере: слева снимок с пронумерованными рамками, справа по строке
на каждое повреждение. Деталь (part) уже заполнена автоматически — проверь её; выбери severity и action
по правилам раздела 9 ТЗ (кратко они показаны на странице). Работа сохраняется в браузере между сеансами.
В конце нажми **Save CSV** и положи скачанный файл на место `benchmark/labels_dev_A.csv`.
Страница не трогает damage_type и рамки. Второй разметчик делает то же со своей копией (`labels_dev_B`).

**3. Сравнить разметки**, обсудить расхождения, согласованные значения сохранить в `labels_dev_final.csv`:
```bash
python3 compare_labels.py --a ../benchmark/labels_dev_A.csv --b ../benchmark/labels_dev_B.csv \
    --out ../benchmark/disagreements_dev.csv
```

**4. Собрать эталон** (скрипт откажется, если что-то не заполнено или заполнено неверно):
```bash
python3 build_ground_truth.py --annotations ../benchmark/syndcar_coco/dev_pool.json \
    --examples ../benchmark/dev_examples.json --labels ../benchmark/labels_dev_final.csv \
    --out ../benchmark/ground_truth_dev.json
```

**5. Прогнать выборку через модель.** Сервис запущен с `PIPELINE_MODEL_CLIENT=anthropic`
(с заглушкой скрипт откажется работать). При обрыве повтори ту же команду — продолжит с места остановки.
```bash
python3 run_benchmark.py --examples ../benchmark/dev_examples.json \
    --images-dir ../data/hitl/SYNDCAR/images --out ../benchmark/predictions_dev_v2.json
```

**6. Оценить:**
```bash
python3 evaluate.py --ground-truth ../benchmark/ground_truth_dev.json \
    --predictions ../benchmark/predictions_dev_v2.json --out ../benchmark/report_dev_v2.json
```

Шаги 5–6 повторяются при настройке промпта: после каждого изменения промпта увеличь
`DamagePrompt.VERSION`, запиши изменение в `benchmark/prompt_changelog.md` и сохраняй предсказания
в новый файл (`..._v3.json` и т.д.). Менять можно только общие правила (формат, определения классов
и серьёзности, правила рамок) — ничего о конкретном автомобиле, см. ограничения ниже.
Затем зафиксируй пороги в `benchmark/target_thresholds.json` и один раз пройди шаги 2–6 для test set
(`test_pool.json`, `test_examples.json`) с замороженным промптом.

## Ответ API

| Ситуация | HTTP | status |
|---|---|---|
| Анализ выполнен (в т.ч. «повреждений нет») | 200 | success |
| Нет корректного ответа модели после 3 попыток | 502 | error |
| Некорректный вход (формат, размер, число фото) | 400 | error |

## Данные

SYNDCAR: Synthetic Vehicle Damage Detection and Parts Segmentation Dataset. DTx CoLAB, Universidade do Minho,
Mendeley Data, V1, 2025. DOI: 10.17632/hzpj48krdt.1 — https://data.mendeley.com/datasets/hzpj48krdt/1
Лицензия CC BY 4.0. Производные данные проекта (пулы COCO, разметка, эталон) распространяются с этой ссылкой.
Снимки в репозиторий не добавляются.

Изначально планировался CarDD, но его использование требует согласия правообладателя, которое не было получено.

## Ограничения

- SYNDCAR синтетический: результат не подтверждает качество на реальных повреждениях.
  Эксперимент на реальных фотографиях не проведён (см. отчёт, раздел 6).
- Деталь для разбитых фар задаётся правилом (lamp_broken → light): по полигонам деталей датасета побеждала окружающая панель.
- Все снимки — один и тот же автомобиль, поэтому dev и test не независимы по автомобилю; результат
  не переносится автоматически на другие машины.
- Выборка пилотная (40 снимков, около 200 повреждений); выводы предварительные.
- Снимки (до ~32 МБ, ~3264×2448) перед отправкой уменьшаются до 1568 пикселей по длинной стороне;
  мелкие царапины могут стать хуже видны.

## Статус

Готово: каркас Spring Boot, обработка одного изображения с уменьшением больших снимков, проверка формата,
retry/fallback, заглушка модели; конвертер SYNDCAR (разбиение по съёмочным дням, автоматическая деталь),
зафиксированная выборка 16 dev + 24 test, инструменты разметки, прогон выборки через сервис, оценка
с правилом сопоставления. Telegram-бот, Mini App и демо-расчёт стоимости. Тесты: Java (`mvn test`) — 82, Python — 85.

После практики: доверительные интервалы, анализ автоматизации по уверенности, режим фрагментов, детектор
и гибрид, шкала серьёзности v2 — см. `docs/experiments.md`.

Benchmark проведён (пилотно): dev с промптами v2 и v3, пороги зафиксированы, test прогнан один раз.
На test: recall 0,394, precision 0,602, macro-F1 0,447. Результаты, разбор ошибок и ограничения —
в [docs/benchmark_report.md](docs/benchmark_report.md).

Разметка выполнена одним человеком дважды; независимого второго разметчика не было, самосогласие
по серьёзности низкое (каппа 0,39–0,59), поэтому метрика серьёзности ненадёжна.

