# Артефакты benchmark

| Файл | Создаёт | В Git |
|---|---|---|
| `syndcar_coco/dev_pool.json`, `test_pool.json`, `conversion_report.json` | convert_syndcar.py | да |
| `dev_examples.*`, `test_examples.*` | select_benchmark_samples.py | да, сразу после создания |
| `labels_dev_A.csv`, `labels_dev_B.csv`, `labels_dev_final.csv` (и test) | make_labeling_sheet.py + разметчики | да |
| `disagreements_dev.csv` (и test) | compare_labels.py | да |
| `ground_truth_dev.json`, `ground_truth_test.json` | build_ground_truth.py | да |
| `target_thresholds.json` | вручную, после dev и до test | да |
| `predictions_*_vN.json` | run_benchmark.py | да |
| `report_*_vN.json` | evaluate.py | да |

Данные: SYNDCAR (DTx CoLAB, Universidade do Minho), DOI 10.17632/hzpj48krdt.1, CC BY 4.0.
Снимки здесь не хранятся, они лежат в `data/` (не в Git).

`fixtures/` — синтетические данные для разработки и тестов.
