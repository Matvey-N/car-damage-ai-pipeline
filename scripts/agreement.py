#!/usr/bin/env python3
"""
Agreement between two or more independent labelings of the same sheet,
and a majority-vote sheet for build_ground_truth.py.

Per field (part, severity, action):
  - percent of damages on which ALL annotators agree
  - Fleiss' kappa (any number of annotators, chance-corrected)
  - Cohen's kappa for every pair

With --majority-out, writes a sheet where each field holds the value chosen
by the majority. Where there is no majority (e.g. a 1:1 tie between two
annotators) the field is left EMPTY and listed, so build_ground_truth.py
refuses the sheet until the case is resolved by discussion.

Usage:
  python agreement.py --sheets ../benchmark/labels_dev_v2_A.csv ../benchmark/labels_dev_v2_B.csv \\
      ../benchmark/labels_dev_v2_C.csv --majority-out ../benchmark/labels_dev_v2_final.csv
"""

import argparse
import itertools
import os
import sys
from collections import Counter

from compare_labels import cohens_kappa
from labels_io import read_sheet, write_json, write_sheet
from schema_values import LABEL_FIELDS


def fleiss_kappa(ratings):
    """ratings: list of per-item label lists (same number of raters per item). None if undefined."""
    if not ratings:
        return None
    m = len(ratings[0])
    if m < 2 or any(len(r) != m for r in ratings):
        raise ValueError("every item needs the same number (>= 2) of ratings")
    n = len(ratings)
    categories = Counter()
    p_items = []
    for r in ratings:
        counts = Counter(r)
        categories.update(counts)
        p_items.append((sum(c * c for c in counts.values()) - m) / (m * (m - 1)))
    p_bar = sum(p_items) / n
    p_e = sum((c / (n * m)) ** 2 for c in categories.values())
    if p_e == 1:
        return None
    return (p_bar - p_e) / (1 - p_e)


def agreement(sheets):
    by_id = [{r["annotation_id"]: r for r in rows} for rows in sheets]
    common = [k for k in by_id[0] if all(k in s for s in by_id[1:])]
    if len(common) != len(by_id[0]) or any(len(s) != len(common) for s in by_id):
        raise ValueError("the sheets do not contain the same damages; create them from the same pool and sample")
    result = {}
    for field in LABEL_FIELDS:
        empty = [k for k in common if any(not s[k][field] for s in by_id)]
        if empty:
            raise ValueError(f"field {field} is not filled for {len(empty)} damages, e.g. annotation {empty[0]}")
        ratings = [[s[k][field] for s in by_id] for k in common]
        pairwise = {}
        for i, j in itertools.combinations(range(len(sheets)), 2):
            pairwise[f"{i + 1}-{j + 1}"] = cohens_kappa([(r[i], r[j]) for r in ratings])
        result[field] = {
            "n": len(common),
            "all_agree": sum(1 for r in ratings if len(set(r)) == 1) / len(common),
            "fleiss_kappa": fleiss_kappa(ratings),
            "cohen_kappa_pairs": pairwise,
        }
    return result


def majority(sheets):
    """Majority sheet and the list of unresolved (annotation_id, field, votes)."""
    by_id = [{r["annotation_id"]: r for r in rows} for rows in sheets]
    out, unresolved = [], []
    for row in sheets[0]:
        k = row["annotation_id"]
        merged = dict(row, comment="")
        for field in LABEL_FIELDS:
            votes = Counter(s[k][field] for s in by_id)
            (top, top_n), *rest = votes.most_common()
            if top_n * 2 > len(sheets):
                merged[field] = top
            else:
                merged[field] = ""
                unresolved.append((k, field, dict(votes)))
        out.append(merged)
    return out, unresolved


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sheets", nargs="+", required=True, help="two or more filled sheets of the same sample")
    parser.add_argument("--majority-out", help="write the majority-vote sheet here")
    parser.add_argument("--out", help="write the agreement report (JSON) here")
    args = parser.parse_args(argv)
    if len(args.sheets) < 2:
        print("ERROR: need at least two sheets", file=sys.stderr)
        return 1

    sheets = [read_sheet(p) for p in args.sheets]
    try:
        report = agreement(sheets)
    except ValueError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1
    names = ", ".join(f"{i + 1}={os.path.basename(p)}" for i, p in enumerate(args.sheets))
    print(f"{len(sheets)} annotators ({names}), {report['part']['n']} damages")
    fmt = lambda v: " n/a" if v is None else f"{v:.2f}"
    for field, r in report.items():
        pairs = "  ".join(f"{k}:{fmt(v)}" for k, v in r["cohen_kappa_pairs"].items())
        print(f"{field:9s} all agree {r['all_agree']:.1%}   Fleiss kappa {fmt(r['fleiss_kappa'])}   Cohen pairs {pairs}")
    if args.out:
        write_json(args.out, report)
    if args.majority_out:
        rows, unresolved = majority(sheets)
        write_sheet(args.majority_out, rows)
        print(f"majority sheet -> {args.majority_out}; {len(unresolved)} fields without a majority left empty")
        for k, field, votes in unresolved[:20]:
            print(f"  annotation {k}: {field} {votes}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
