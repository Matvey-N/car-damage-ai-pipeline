# AI-pipeline для анализа повреждений автомобилей по изображениям

Исследовательский прототип: конвейер анализа одного снимка мультимодальной моделью
(Claude, `claude-opus-5-5`) и воспроизводимый benchmark его качества.
Telegram-бот и расчёт стоимости — вторичны и в первую веху не входят.

## Структура

```
src/main/java/com/cardamage/
  core/                     ядро pipeline, не зависит от Spring (только Jackson)
    pipeline/
      SingleImageAnalyzer   один снимок: вызов модели -> парсинг -> проверка формата -> retry -> fallback
      ResponseParser        текст ответа -> объект (только структура JSON)
      ResponseFormatValidator  явная проверка значений (enum, диапазоны, рамка)
      DamagePrompt          промпт с номером версии
      VisionModelClient     интерфейс вызова модели
      StubVisionModelClient заглушка модели (без API и без CarDD)
    demo/DamageMergeService объединение серии фото (только демо)
  web/                      тонкий Spring-слой
    AnalysisController      POST /api/v1/analyze, POST /api/v1/demo/analyze, GET /api/v1/info
    AnthropicVisionModelClient  реальный вызов Claude API
    PipelineConfig          выбор stub / anthropic
scripts/
  select_benchmark_samples.py  фиксированная стратифицированная выборка dev/test из CarDD
  make_labeling_sheet.py       таблица для ручной разметки part/severity/action
  compare_labels.py            сравнение двух разметчиков: согласие, каппа Коэна, расхождения
  build_ground_truth.py        сборка эталона из CarDD + итоговой разметки, с проверками
  run_benchmark.py             прогон выборки через сервис, сохранение предсказаний
  evaluate.py                  сопоставление (IoU >= 0.5, один к одному) и метрики
  tests/                       тесты Python-скриптов (42), включая прогон всей цепочки
benchmark/fixtures/          синтетические данные для разработки (не CarDD)
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

Сервис с реальной моделью (только после разрешения на передачу снимков, см. ниже):
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

## Порядок benchmark после получения CarDD

Все команды выполняются из папки `scripts`. CarDD лежит в `data/cardd/` (папка не попадает в Git).
Имена файлов аннотаций и папок со снимками ниже — примерные, подставь фактические из архива CarDD.

**1. Зафиксировать выборку** и сразу закоммитить `benchmark/dev_examples.*`, `benchmark/test_examples.*`:
```bash
python3 select_benchmark_samples.py --val-annotations ../data/cardd/annotations/instances_val2017.json \
    --test-annotations ../data/cardd/annotations/instances_test2017.json --seed 42 --out-dir ../benchmark
```

**2. Таблицы разметки dev** — по одной на каждого разметчика:
```bash
python3 make_labeling_sheet.py --annotations ../data/cardd/annotations/instances_val2017.json \
    --examples ../benchmark/dev_examples.json --out ../benchmark/labels_dev_A.csv
```
Таблица открывается в Excel. Заполнить столбцы part, severity, action по правилам раздела 9 ТЗ;
damage_type и рамку не менять. Второй разметчик заполняет свою копию (`labels_dev_B.csv`) независимо.

**3. Сравнить разметки**, обсудить расхождения, согласованные значения сохранить в `labels_dev_final.csv`:
```bash
python3 compare_labels.py --a ../benchmark/labels_dev_A.csv --b ../benchmark/labels_dev_B.csv \
    --out ../benchmark/disagreements_dev.csv
```

**4. Собрать эталон** (скрипт откажется, если что-то не заполнено или заполнено неверно):
```bash
python3 build_ground_truth.py --annotations ../data/cardd/annotations/instances_val2017.json \
    --examples ../benchmark/dev_examples.json --labels ../benchmark/labels_dev_final.csv \
    --out ../benchmark/ground_truth_dev.json
```

**5. Прогнать выборку через модель.** Сервис запущен с `PIPELINE_MODEL_CLIENT=anthropic`
(с заглушкой скрипт откажется работать). При обрыве повторить ту же команду — продолжит с места остановки.
```bash
python3 run_benchmark.py --examples ../benchmark/dev_examples.json \
    --images-dir ../data/cardd/val2017 --out ../benchmark/predictions_dev_v1.json
```

**6. Оценить:**
```bash
python3 evaluate.py --ground-truth ../benchmark/ground_truth_dev.json \
    --predictions ../benchmark/predictions_dev_v1.json --out ../benchmark/report_dev_v1.json
```

Шаги 5–6 повторяются при настройке промпта: после каждого изменения промпта увеличить
`DamagePrompt.VERSION` и сохранять предсказания в новый файл (`..._v2.json` и т.д.).
Затем зафиксировать пороги в `benchmark/target_thresholds.json` и один раз пройти шаги 2–6 для test set
с замороженным промптом (аннотации и снимки test split).

Таблицы разметки и эталон содержат рамки CarDD и в Git не коммитятся (см. `.gitignore`);
предсказания модели и отчёты можно коммитить.

## Ответ API

| Ситуация | HTTP | status |
|---|---|---|
| Анализ выполнен (в т.ч. «повреждений нет») | 200 | success |
| Нет корректного ответа модели после 3 попыток | 502 | error |
| Некорректный вход (формат, размер, число фото) | 400 | error |

## CarDD

CarDD — не открытый датасет. Для использования нужно согласие правообладателя (PIC Lab, CAS),
передача снимков третьим лицам (включая внешний AI-сервис) без разрешения запрещена.
Снимки CarDD в репозиторий не добавляются (`data/` в `.gitignore`).
Лицензия: https://cardd-ustc.github.io/docs/CarDD_license.pdf

## Статус

Готово: запускаемый каркас Spring Boot, обработка одного изображения, проверка формата,
retry/fallback, заглушка модели, выборка, разметка (шаблон, сравнение разметчиков, сборка эталона),
прогон выборки через сервис, оценка с правилом сопоставления. Вся цепочка проверена на синтетических данных.

Ждёт данных: фиксация выборки на реальном CarDD, ручная разметка, прогон dev/test с реальной моделью.

Вторично, позже: Telegram-бот, расчёт стоимости.
