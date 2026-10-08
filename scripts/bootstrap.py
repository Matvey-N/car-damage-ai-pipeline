#!/usr/bin/env python3
"""
95% confidence intervals for the benchmark metrics, and paired comparison of
two methods on the same ground truth.

Images are resampled with replacement (damages on one image are not
independent, so the image is the unit), the metrics are recomputed with
evaluate.py on every resample, and the 2.5% / 97.5% percentiles are reported.
For a comparison, both prediction files are evaluated on the SAME resample,
so the interval is for the difference B - A. If it does not contain 0, the
difference is unlikely to be noise at this sample size.

Usage:
  python bootstrap.py --ground-truth ../benchmark/ground_truth_test.json \\
      --predictions ../benchmark/predictions_test_v3.json
  python bootstrap.py --ground-truth ../benchmark/ground_truth_test.json \\
      --predictions ../benchmark/predictions_test_v3.json --compare ../benchmark/predictions_test_tiled.json
"""

import argparse
import random
import sys

from evaluate import evaluate
from labels_io import load_json, write_json

METRICS = {
    "recall": lambda r: r["detection"]["recall"],
    "precision": lambda r: r["detection"]["precision"],
    "macro_f1": lambda r: r["damage_type"]["macro_f1"],
    "type_accuracy": lambda r: r["matched_pairs"]["damage_type_accuracy"],
    "severity_accuracy": lambda r: r["matched_pairs"]["severity_accuracy_exact"],
    "part_accuracy": lambda r: r["matched_pairs"]["part_accuracy"],
    "action_accuracy": lambda r: r["matched_pairs"]["action_accuracy"],
    "brier": lambda r: r["confidence"]["brier_score"],
}


def percentile(sorted_values, q):
    if not sorted_values:
        return None
    k = (len(sorted_values) - 1) * q
    lo, hi = int(k), min(int(k) + 1, len(sorted_values) - 1)
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (k - lo)


def resample(ground_truth, rng):
    images = ground_truth["images"]
    picked = [rng.choice(images) for _ in images]
    # the same image may be drawn twice: give each copy its own id
    out = []
    mapping = []
    for i, img in enumerate(picked):
        copy = dict(img, image_id=f"{img['image_id']}#{i}")
        out.append(copy)
        mapping.append((copy["image_id"], str(img["image_id"])))
    return {"images": out}, mapping


def remap(predictions_doc, mapping):
    preds = predictions_doc.get("predictions", {})
    return {"predictions": {new: preds[old] for new, old in mapping if old in preds}}


def bootstrap(ground_truth, predictions_a, predictions_b=None, n=2000, seed=42):
    rng = random.Random(seed)
    samples = {m: [] for m in METRICS}
    for _ in range(n):
        gt, mapping = resample(ground_truth, rng)
        ra = evaluate(gt, remap(predictions_a, mapping))
        rb = evaluate(gt, remap(predictions_b, mapping)) if predictions_b else None
        for m, get in METRICS.items():
            a = get(ra)
            if rb is None:
                if a is not None:
                    samples[m].append(a)
            else:
                b = get(rb)
                if a is not None and b is not None:
                    samples[m].append(b - a)

    point_a = evaluate(ground_truth, predictions_a)
    point_b = evaluate(ground_truth, predictions_b) if predictions_b else None
    result = {"resamples": n, "seed": seed, "unit": "image",
              "mode": "difference B - A" if predictions_b else "single", "metrics": {}}
    for m, get in METRICS.items():
        values = sorted(samples[m])
        a = get(point_a)
        entry = {"ci95_low": percentile(values, 0.025), "ci95_high": percentile(values, 0.975),
                 "resamples_used": len(values)}
        if predictions_b:
            b = get(point_b)
            entry.update(a=a, b=b, difference=None if a is None or b is None else b - a)
        else:
            entry["value"] = a
        result["metrics"][m] = entry
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ground-truth", required=True)
    parser.add_argument("--predictions", required=True, help="method A")
    parser.add_argument("--compare", help="method B: report the interval for B - A")
    parser.add_argument("--resamples", type=int, default=2000)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out")
    args = parser.parse_args(argv)

    result = bootstrap(load_json(args.ground_truth), load_json(args.predictions),
                       load_json(args.compare) if args.compare else None, args.resamples, args.seed)
    f = lambda v: "  n/a " if v is None else f"{v:.3f}"
    d = lambda v: "  n/a " if v is None else f"{v:+.3f}"
    print(f"bootstrap over images: {args.resamples} resamples, seed {args.seed}, 95% interval")
    for m, e in result["metrics"].items():
        if args.compare:
            print(f"{m:18s} A={f(e['a'])}  B={f(e['b'])}  B-A={d(e['difference'])}  "
                  f"[{d(e['ci95_low'])}, {d(e['ci95_high'])}]")
        else:
            print(f"{m:18s} {f(e['value'])}  [{f(e['ci95_low'])}, {f(e['ci95_high'])}]")
    if args.out:
        write_json(args.out, result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
