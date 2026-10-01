# Артефакты benchmark

| Файл | Создаёт | В Git |
|---|---|---|
| `dev_examples.*`, `test_examples.*` | select_benchmark_samples.py | да, сразу после создания |
| `labels_dev_A.csv`, `labels_dev_B.csv`, `labels_dev_final.csv` (и test) | make_labeling_sheet.py + разметчики | нет (содержат рамки CarDD) |
| `disagreements_dev.csv` (и test) | compare_labels.py | нет |
| `ground_truth_dev.json`, `ground_truth_test.json` | build_ground_truth.py | нет (содержат рамки CarDD) |
| `target_thresholds.json` | вручную, после dev и до test | да |
| `predictions_*_vN.json` | run_benchmark.py | да |
| `report_*_vN.json` | evaluate.py | да |

`fixtures/` — синтетические данные для разработки и тестов, не CarDD.
Снимки CarDD здесь не хранятся.
