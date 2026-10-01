#!/usr/bin/env python3
"""
Compares two independently filled labeling sheets (TZ section 9:
the second annotator checks every example).

Prints, per field (part, severity, action): percent agreement and
Cohen's kappa. Writes every disagreement to a CSV, to be resolved by
discussion; the agreed values go into the final sheet that
build_ground_truth.py reads.

Usage:
  python compare_labels.py --a ../benchmark/labels_dev_A.csv \
      --b ../benchmark/labels_dev_B.csv --out ../benchmark/disagreements_dev.csv
"""

import argparse
import csv
import sys
from collections import Counter

from labels_io import read_sheet
from schema_values import LABEL_FIELDS


def cohens_kappa(pairs):
    """Cohen's kappa for a list of (label_a, label_b). None if undefined."""
    n = len(pairs)
    if n == 0:
        return None
    observed = sum(1 for a, b in pairs if a == b) / n
    count_a = Counter(a for a, _ in pairs)
    count_b = Counter(b for _, b in pairs)
    expected = sum(count_a[k] * count_b[k] for k in count_a) / (n * n)
    if expected == 1:
        return None  # both annotators used a single identical label everywhere
    return (observed - expected) / (1 - expected)


def compare(rows_a, rows_b):
    a = {r["annotation_id"]: r for r in rows_a}
    b = {r["annotation_id"]: r for r in rows_b}
    missing_in_b = sorted(set(a) - set(b))
    missing_in_a = sorted(set(b) - set(a))
    common = [k for k in a if k in b]

    stats, disagreements = {}, []
    for field in LABEL_FIELDS:
        pairs = [(a[k][field], b[k][field]) for k in common]
        agree = sum(1 for x, y in pairs if x == y)
        stats[field] = {
            "n": len(pairs),
            "agreement": agree / len(pairs) if pairs else None,
            "kappa": cohens_kappa(pairs),
        }
        for k in common:
            if a[k][field] != b[k][field]:
                disagreements.append({
                    "image_id": a[k]["image_id"], "file_name": a[k]["file_name"],
                    "annotation_id": k, "damage_type": a[k]["damage_type"],
                    "field": field, "annotator_a": a[k][field], "annotator_b": b[k][field],
                })
    return stats, disagreements, missing_in_a, missing_in_b


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--a", required=True, help="sheet filled by annotator A")
    parser.add_argument("--b", required=True, help="sheet filled by annotator B")
    parser.add_argument("--out", required=True, help="CSV with all disagreements")
    args = parser.parse_args(argv)

    stats, disagreements, missing_in_a, missing_in_b = compare(read_sheet(args.a), read_sheet(args.b))

    for field, s in stats.items():
        agreement = "n/a" if s["agreement"] is None else f"{s['agreement']:.1%}"
        kappa = "n/a" if s["kappa"] is None else f"{s['kappa']:.2f}"
        print(f"{field:9s} n={s['n']:3d}  agreement={agreement:>6s}  kappa={kappa}")
    if missing_in_a or missing_in_b:
        print(f"WARNING: rows only in B: {missing_in_a}; rows only in A: {missing_in_b}", file=sys.stderr)

    with open(args.out, "w", encoding="utf-8-sig", newline="") as f:
        columns = ["image_id", "file_name", "annotation_id", "damage_type", "field", "annotator_a", "annotator_b"]
        writer = csv.DictWriter(f, fieldnames=columns, delimiter=";")
        writer.writeheader()
        writer.writerows(disagreements)
    print(f"{len(disagreements)} disagreements written to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
