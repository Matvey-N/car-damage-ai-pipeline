# Артефакты benchmark

Порядок появления файлов:

1. `dev_examples.*`, `test_examples.*` — фиксированная выборка (scripts/select_benchmark_samples.py).
   Коммитятся до разметки и настройки промпта.
2. `ground_truth_dev.json`, `ground_truth_test.json` — ручная разметка part/severity/action
   поверх рамок и типов CarDD. Формат — как в `fixtures/ground_truth_synthetic.json`.
3. `target_thresholds.json` — целевые пороги метрик, фиксируются после dev set и до test set.
4. `predictions_test.json` + отчёт `evaluate.py` — формат как в `fixtures/predictions_synthetic.json`.

`fixtures/` — синтетические данные для разработки и тестов, не CarDD.
Снимки CarDD здесь не хранятся (условия лицензии).
