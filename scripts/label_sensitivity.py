#!/usr/bin/env python3
"""
How much do the severity/action/part metrics depend on who labeled?

Detection (boxes, damage types) comes from the dataset and does not depend
on the annotator; severity, part and action do. This script matches the
predictions to the ground truth exactly as evaluate.py does and reports
accuracy on the matched pairs three ways: against labeling A, against
labeling B, and only on the pairs where A and B agree on that field.

Usage:
  python label_sensitivity.py --ground-truth ../benchmark/ground_truth_test.json \
      --a ../benchmark/labels_test_A.csv --b ../benchmark/labels_test_B.csv \
      --predictions ../benchmark/predictions_test_v3.json --out ../benchmark/label_sensitivity_test.json
"""

import argparse
import json
import sys

from evaluate import match_image, normalize_box
from labels_io import load_json, read_sheet, write_json
from schema_values import LABEL_FIELDS


def by_annotation(rows):
    return {str(r["annotation_id"]): r for r in rows}


def sensitivity(ground_truth, predictions_doc, rows_a, rows_b):
    a, b = by_annotation(rows_a), by_annotation(rows_b)
    counts = {f: {"pairs": 0, "correct_vs_a": 0, "correct_vs_b": 0, "a_b_agree": 0, "correct_where_agree": 0}
              for f in LABEL_FIELDS}
    for image in ground_truth["images"]:
        answer = predictions_doc["predictions"].get(str(image["image_id"]))
        if not answer or answer.get("status") != "success":
            continue
        boxes = [normalize_box(d["bbox"], image["width"], image["height"]) for d in image["damages"]]
        pairs, _, _ = match_image(boxes, answer["damages"])
        for gi, pi, _ in pairs:
            ann = str(image["damages"][gi]["annotation_id"])
            pred = answer["damages"][pi]
            for f in LABEL_FIELDS:
                c = counts[f]
                c["pairs"] += 1
                c["correct_vs_a"] += pred.get(f) == a[ann][f]
                c["correct_vs_b"] += pred.get(f) == b[ann][f]
                if a[ann][f] == b[ann][f]:
                    c["a_b_agree"] += 1
                    c["correct_where_agree"] += pred.get(f) == a[ann][f]
    result = {}
    for f, c in counts.items():
        n, k = c["pairs"], c["a_b_agree"]
        result[f] = dict(c,
                         accuracy_vs_a=c["correct_vs_a"] / n if n else None,
                         accuracy_vs_b=c["correct_vs_b"] / n if n else None,
                         accuracy_where_agree=c["correct_where_agree"] / k if k else None)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ground-truth", required=True)
    parser.add_argument("--a", required=True)
    parser.add_argument("--b", required=True)
    parser.add_argument("--predictions", required=True)
    parser.add_argument("--out")
    args = parser.parse_args(argv)

    result = sensitivity(load_json(args.ground_truth), load_json(args.predictions),
                         read_sheet(args.a), read_sheet(args.b))
    fmt = lambda v: "n/a" if v is None else f"{v:.3f}"
    for f, r in result.items():
        print(f"{f:9s} pairs={r['pairs']:3d}  vs A={fmt(r['accuracy_vs_a'])}  vs B={fmt(r['accuracy_vs_b'])}  "
              f"where A=B (n={r['a_b_agree']})={fmt(r['accuracy_where_agree'])}")
    if args.out:
        write_json(args.out, result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
