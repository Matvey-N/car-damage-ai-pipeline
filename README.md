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
  web/                      тонкий Spring-слой
    AnalysisController      POST /api/v1/analyze, POST /api/v1/demo/analyze, GET /api/v1/info
    AnthropicVisionModelClient  реальный вызов Claude API
    PipelineConfig          выбор stub / anthropic
scripts/
  convert_syndcar.py           SYNDCAR (YOLO) -> COCO, автоматическое определение детали, пулы dev/test
  select_benchmark_samples.py  фиксированная стратифицированная выборка dev/test
  make_labeling_sheet.py       таблица для ручной разметки part/severity/action
  compare_labels.py            сравнение двух разметчиков: согласие, каппа Коэна, расхождения
  build_ground_truth.py        сборка эталона из пула COCO + итоговой разметки, с проверками
  run_benchmark.py             прогон выборки через сервис, сохранение предсказаний
  evaluate.py                  сопоставление (IoU >= 0.5, один к одному) и метрики
  tests/                       тесты Python-скриптов (52), включая прогон всей цепочки
benchmark/fixtures/          синтетические данные для разработки и тестов
docs/first_test_run.md       результат первого прогона тестов
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

## Порядок benchmark

Все команды выполняются из папки `scripts`. SYNDCAR распакован в `data/hitl/SYNDCAR`
(папки `images`, `labels_damage`, `labels_parts` и файлы `.yaml`); папка `data/` не попадает в Git.

**0. Конвертировать SYNDCAR** в формат COCO и разделить на пулы dev и test (один раз):
```bash
python3 convert_syndcar.py --syndcar ../data/hitl/SYNDCAR --out-dir ../benchmark/syndcar_coco
```
Проверь вывод: как сопоставлены классы, сколько снимков в каждом пуле по типам повреждений,
есть ли снимки с EXIF-поворотом.

**1. Зафиксировать выборку** (16 dev + 24 test) и сразу закоммитить `benchmark/dev_examples.*`, `benchmark/test_examples.*`:
```bash
python3 select_benchmark_samples.py --val-annotations ../benchmark/syndcar_coco/dev_pool.json \
    --test-annotations ../benchmark/syndcar_coco/test_pool.json --seed 42 --out-dir ../benchmark
```

**2. Таблицы разметки dev** — по одной на каждого разметчика:
```bash
python3 make_labeling_sheet.py --annotations ../benchmark/syndcar_coco/dev_pool.json \
    --examples ../benchmark/dev_examples.json --out ../benchmark/labels_dev_A.csv
```
Таблица открывается в Excel. Деталь (part) уже заполнена автоматически там, где это удалось, — в комментарии
указано, откуда она взята; проверь и исправь при необходимости, пустые заполни. Заполни severity и action
по правилам раздела 9 ТЗ; damage_type и рамку не меняй. Второй разметчик заполняет свою копию (`labels_dev_B.csv`).

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
`DamagePrompt.VERSION` и сохраняй предсказания в новый файл (`..._v3.json` и т.д.).
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

## Статус

Готово: запускаемый каркас Spring Boot, обработка одного изображения с уменьшением больших снимков,
проверка формата, retry/fallback, заглушка модели; конвертер SYNDCAR с автоматическим определением детали,
выборка, разметка (шаблон, сравнение разметчиков, сборка эталона), прогон выборки через сервис, оценка
с правилом сопоставления. Вся цепочка проверена на синтетических данных.

Дальше: конвертация SYNDCAR, фиксация выборки, разметка, прогон dev/test с реальной моделью, отчёт.

Вторично, позже: Telegram-бот, расчёт стоимости.
