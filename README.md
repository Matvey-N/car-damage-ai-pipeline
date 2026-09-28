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
  evaluate.py                  сопоставление (IoU >= 0.5, один к одному) и метрики
  tests/                       тесты Python-скриптов
benchmark/fixtures/          синтетические данные для разработки (не CarDD)
docs/first_test_run.md       результат первого прогона тестов
```

## Требования

- JDK 17+ и Maven 3.8+ (или IntelliJ IDEA — Maven встроен)
- Python 3.9+ (только стандартная библиотека)

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

Фиксация выборки (после получения CarDD):
```bash
python3 select_benchmark_samples.py \
    --val-annotations  /path/to/CarDD/annotations/instances_val2017.json \
    --test-annotations /path/to/CarDD/annotations/instances_test2017.json \
    --seed 42 --out-dir ../benchmark
```
Имена файлов аннотаций указать по фактической структуре архива CarDD.
Полученные `benchmark/dev_examples.*` и `benchmark/test_examples.*` закоммитить сразу,
до разметки и настройки промпта.

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

## Статус первой вехи

Сделано: запускаемый каркас Spring Boot, обработка одного изображения, проверка формата,
retry/fallback, заглушка модели, тесты, скрипт фиксированной выборки, скрипт оценки
с правилом сопоставления.

Не сделано: прогон на реальных данных (ждём разрешения на CarDD), ручная разметка,
скрипт прогона выборки через API, Telegram-бот, расчёт стоимости.
