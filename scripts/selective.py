#!/usr/bin/env python3
"""
When can a model answer be accepted without a human? (selective prediction)

Every predicted damage carries the model's confidence. For a range of
thresholds t, only predictions with confidence >= t are accepted and the rest
go to a person. The script reports, per t:

  coverage         share of all predictions that are accepted automatically
  precision        share of accepted predictions that are real damages (IoU >= 0.5)
  type_accuracy    share of accepted, matched predictions with the right damage type
  recall           share of all ground-truth damages found by accepted predictions

and, separately, the share of images whose report is fully correct (every
damage found with the right type, nothing extra): the number that decides
whether a whole case could be closed without a person.

Matching is the same as in evaluate.py. Because it is greedy by confidence,
dropping low-confidence predictions never changes the matches of the kept ones,
so one matching pass serves all thresholds.

Usage:
  python selective.py --ground-truth ../benchmark/ground_truth_test.json \\
      --predictions ../benchmark/predictions_test_v3.json --out ../benchmark/selective_test_v3.json
"""

import argparse
import sys

from evaluate import match_image, normalize_box, ratio
from labels_io import load_json, write_json

THRESHOLDS = [0.0, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9]


def collect(ground_truth, predictions_doc):
    """One record per predicted damage, plus per-image facts."""
    preds = predictions_doc.get("predictions", {})
    records, images, total_gt = [], [], 0
    for img in ground_truth["images"]:
        gt = img["damages"]
        total_gt += len(gt)
        answer = preds.get(str(img["image_id"]))
        if not answer or answer.get("status") != "success":
            images.append({"gt": len(gt), "preds": []})
            continue
        damages = answer.get("damages", [])
        boxes = [normalize_box(d["bbox"], img["width"], img["height"]) for d in gt]
        pairs, _, _ = match_image(boxes, damages)
        matched = {pi: gi for gi, pi, _ in pairs}
        image_preds = []
        for pi, d in enumerate(damages):
            gi = matched.get(pi)
            rec = {"confidence": d["confidence"], "matched": gi is not None,
                   "type_correct": gi is not None and gt[gi]["damage_type"] == d["damage_type"]}
            records.append(rec)
            image_preds.append(rec)
        images.append({"gt": len(gt), "preds": image_preds})
    return records, images, total_gt


def curve(records, images, total_gt, thresholds=THRESHOLDS):
    rows = []
    for t in thresholds:
        kept = [r for r in records if r["confidence"] >= t]
        matched = [r for r in kept if r["matched"]]
        # an image is fully automatic at t if every prediction clears t and its report is complete and correct
        auto_images = [im for im in images if all(p["confidence"] >= t for p in im["preds"])]
        perfect = [im for im in auto_images
                   if len(im["preds"]) == im["gt"] and all(p["matched"] and p["type_correct"] for p in im["preds"])]
        rows.append({
            "threshold": t,
            "accepted": len(kept),
            "coverage": ratio(len(kept), len(records)),
            "precision": ratio(len(matched), len(kept)),
            "type_accuracy": ratio(sum(r["type_correct"] for r in matched), len(matched)),
            "recall": ratio(len(matched), total_gt),
            "images_all_predictions_accepted": len(auto_images),
            "images_fully_correct_and_accepted": len(perfect),
        })
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ground-truth", required=True)
    parser.add_argument("--predictions", required=True)
    parser.add_argument("--out")
    args = parser.parse_args(argv)

    records, images, total_gt = collect(load_json(args.ground_truth), load_json(args.predictions))
    rows = curve(records, images, total_gt)
    f = lambda v: " n/a " if v is None else f"{v:.3f}"
    print(f"{len(records)} predicted damages, {total_gt} ground-truth damages, {len(images)} images")
    print("threshold  accepted  coverage  precision  type_acc  recall  images: all accepted / fully correct")
    for r in rows:
        print(f"  >= {r['threshold']:.1f}   {r['accepted']:5d}     {f(r['coverage'])}     {f(r['precision'])}"
              f"    {f(r['type_accuracy'])}   {f(r['recall'])}   "
              f"{r['images_all_predictions_accepted']:3d} / {r['images_fully_correct_and_accepted']}")
    if args.out:
        write_json(args.out, {"thresholds": rows, "predictions": len(records),
                              "ground_truth_damages": total_gt, "images": len(images)})
    return 0


if __name__ == "__main__":
    sys.exit(main())
