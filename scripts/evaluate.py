#!/usr/bin/env python3
"""
Benchmark evaluation: compares pipeline predictions with ground truth.
Offline script (not part of the Spring Boot runtime). Standard library only.

Matching rule (TZ section 7), fixed before any experiment:
  * One IoU threshold: IoU >= 0.5.
  * One-to-one: each prediction is matched to at most one ground-truth
    damage and each ground-truth damage to at most one prediction.
  * Greedy, class-agnostic: within an image, predictions are processed in
    order of decreasing confidence; each takes the still-unmatched
    ground-truth damage with the highest IoU, if that IoU >= 0.5.
  * A second prediction on an already matched damage is a false positive
    (duplicate).
  * Severity, part, action and confidence are evaluated ONLY on matched
    pairs.

Boxes: ground truth uses COCO pixel boxes [x, y, w, h]; they are
normalized by the image width/height. Predictions are already
normalized [x, y, w, h] in [0, 1] (see DamagePrompt / the JSON schema).

Usage:
  python evaluate.py --ground-truth GT.json --predictions PRED.json [--out report.json]

Input formats: see benchmark/fixtures/*.json for complete examples.
"""

import argparse
import json
import sys
from collections import defaultdict

IOU_THRESHOLD = 0.5

from schema_values import DAMAGE_TYPES, SEVERITIES
CALIBRATION_BINS = [(0.0, 0.5), (0.5, 0.7), (0.7, 0.9), (0.9, 1.0)]


# ---------------------------------------------------------------- geometry

def iou(a, b):
    """IoU of two [x, y, w, h] boxes in the same coordinate system."""
    ax2, ay2 = a[0] + a[2], a[1] + a[3]
    bx2, by2 = b[0] + b[2], b[1] + b[3]
    iw = max(0.0, min(ax2, bx2) - max(a[0], b[0]))
    ih = max(0.0, min(ay2, by2) - max(a[1], b[1]))
    inter = iw * ih
    union = a[2] * a[3] + b[2] * b[3] - inter
    return inter / union if union > 0 else 0.0


def normalize_box(pixel_box, width, height):
    x, y, w, h = pixel_box
    return [x / width, y / height, w / width, h / height]


# ---------------------------------------------------------------- matching

def match_image(gt_boxes, predictions, threshold=IOU_THRESHOLD):
    """
    gt_boxes:    list of normalized [x, y, w, h]
    predictions: list of dicts with "bounding_box" and "confidence"
    Returns (pairs, unmatched_gt, unmatched_pred) where pairs is a list of
    (gt_index, pred_index, iou).
    """
    order = sorted(range(len(predictions)),
                   key=lambda i: (-predictions[i]["confidence"], i))
    matched_gt = set()
    pairs = []
    unmatched_pred = []

    for pi in order:
        best_gi, best_iou = None, 0.0
        for gi, gt_box in enumerate(gt_boxes):
            if gi in matched_gt:
                continue
            value = iou(gt_box, predictions[pi]["bounding_box"])
            if value > best_iou:
                best_gi, best_iou = gi, value
        if best_gi is not None and best_iou >= threshold:
            matched_gt.add(best_gi)
            pairs.append((best_gi, pi, best_iou))
        else:
            unmatched_pred.append(pi)

    unmatched_gt = [gi for gi in range(len(gt_boxes)) if gi not in matched_gt]
    return pairs, unmatched_gt, sorted(unmatched_pred)


# ---------------------------------------------------------------- helpers

def ratio(num, den):
    return num / den if den else None


def f1(p, r):
    if p is None or r is None or (p + r) == 0:
        return None if p is None or r is None else 0.0
    return 2 * p * r / (p + r)


def pearson(xs, ys):
    n = len(xs)
    if n < 2:
        return None
    mx, my = sum(xs) / n, sum(ys) / n
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    sxx = sum((x - mx) ** 2 for x in xs)
    syy = sum((y - my) ** 2 for y in ys)
    if sxx == 0 or syy == 0:
        return None
    return sxy / (sxx * syy) ** 0.5


def calibration_bin(confidence):
    for lo, hi in CALIBRATION_BINS:
        if lo <= confidence < hi or (hi == 1.0 and confidence == 1.0):
            return f"{lo:.1f}-{hi:.1f}"
    return "out_of_range"


# ---------------------------------------------------------------- evaluation

def evaluate(ground_truth, predictions_doc, threshold=IOU_THRESHOLD):
    preds_by_image = predictions_doc.get("predictions", {})
    gt_ids = {str(img["image_id"]) for img in ground_truth["images"]}
    extra = sorted(set(preds_by_image) - gt_ids)

    det = {"tp": 0, "fp": 0, "fn": 0}
    per_class = {t: {"tp": 0, "fp": 0, "fn": 0} for t in DAMAGE_TYPES}
    matched = []            # (gt_damage, pred_damage, iou)
    per_image = []
    image_level = {"tp": 0, "fp": 0, "fn": 0}
    pipeline_errors = 0

    for img in ground_truth["images"]:
        image_id = str(img["image_id"])
        gt_damages = img["damages"]
        gt_boxes = [normalize_box(d["bbox"], img["width"], img["height"]) for d in gt_damages]
        result = preds_by_image.get(image_id)

        if result is None or result.get("status") != "success":
            # missing prediction or pipeline error: every GT damage is missed
            pipeline_errors += 1
            det["fn"] += len(gt_damages)
            for d in gt_damages:
                per_class[d["damage_type"]]["fn"] += 1
            image_level["fn"] += len({d["damage_type"] for d in gt_damages})
            per_image.append({"image_id": image_id,
                              "status": "missing" if result is None else "error",
                              "tp": 0, "fp": 0, "fn": len(gt_damages)})
            continue

        pred_damages = result.get("damages", [])
        pairs, unmatched_gt, unmatched_pred = match_image(gt_boxes, pred_damages, threshold)

        det["tp"] += len(pairs)
        det["fp"] += len(unmatched_pred)
        det["fn"] += len(unmatched_gt)

        # damage-type counts on top of the class-agnostic matching
        for gi, pi, value in pairs:
            g, p = gt_damages[gi], pred_damages[pi]
            matched.append((g, p, value))
            if g["damage_type"] == p["damage_type"]:
                per_class[g["damage_type"]]["tp"] += 1
            else:
                per_class[p["damage_type"]]["fp"] += 1
                per_class[g["damage_type"]]["fn"] += 1
        for pi in unmatched_pred:
            per_class[pred_damages[pi]["damage_type"]]["fp"] += 1
        for gi in unmatched_gt:
            per_class[gt_damages[gi]["damage_type"]]["fn"] += 1

        # auxiliary image-level presence (no localization involved)
        gt_types = {d["damage_type"] for d in gt_damages}
        pred_types = {d["damage_type"] for d in pred_damages}
        image_level["tp"] += len(gt_types & pred_types)
        image_level["fp"] += len(pred_types - gt_types)
        image_level["fn"] += len(gt_types - pred_types)

        per_image.append({"image_id": image_id, "status": "success",
                          "tp": len(pairs), "fp": len(unmatched_pred), "fn": len(unmatched_gt)})

    # detection
    precision = ratio(det["tp"], det["tp"] + det["fp"])
    recall = ratio(det["tp"], det["tp"] + det["fn"])

    # damage type, per class and macro F1 (classes that occur in GT or predictions)
    class_report = {}
    f1_values = []
    for t, c in per_class.items():
        p = ratio(c["tp"], c["tp"] + c["fp"])
        r = ratio(c["tp"], c["tp"] + c["fn"])
        cf1 = f1(p, r) if (c["tp"] + c["fp"] + c["fn"]) > 0 else None
        class_report[t] = {**c, "precision": p, "recall": r, "f1": cf1}
        if c["tp"] + c["fp"] + c["fn"] > 0:
            f1_values.append(cf1 if cf1 is not None else 0.0)
    macro_f1 = sum(f1_values) / len(f1_values) if f1_values else None

    # matched pairs only: severity / part / action / confidence
    n = len(matched)
    severity_confusion = {g: {p: 0 for p in SEVERITIES} for g in SEVERITIES}
    for g, p, _ in matched:
        severity_confusion[g["severity"]][p["severity"]] += 1

    confidences = [p["confidence"] for _, p, _ in matched]
    type_correct = [1.0 if g["damage_type"] == p["damage_type"] else 0.0 for g, p, _ in matched]
    brier = ratio(sum((c - y) ** 2 for c, y in zip(confidences, type_correct)), n)

    calibration = defaultdict(lambda: {"count": 0, "correct": 0, "mean_confidence": 0.0})
    for c, y in zip(confidences, type_correct):
        b = calibration[calibration_bin(c)]
        b["count"] += 1
        b["correct"] += int(y)
        b["mean_confidence"] += c
    calibration_table = []
    for lo, hi in CALIBRATION_BINS:
        key = f"{lo:.1f}-{hi:.1f}"
        b = calibration.get(key, {"count": 0, "correct": 0, "mean_confidence": 0.0})
        calibration_table.append({
            "confidence_range": key,
            "count": b["count"],
            "empirical_accuracy": ratio(b["correct"], b["count"]),
            "mean_confidence": ratio(b["mean_confidence"], b["count"]),
        })

    return {
        "iou_threshold": threshold,
        "matching": "one-to-one, greedy by confidence, class-agnostic",
        "images_total": len(ground_truth["images"]),
        "images_pipeline_error_or_missing": pipeline_errors,
        "predictions_for_unknown_images": extra,
        "detection": {**det, "precision": precision, "recall": recall},
        "damage_type": {"macro_f1": macro_f1, "per_class": class_report},
        "matched_pairs": {
            "count": n,
            "damage_type_accuracy": ratio(sum(type_correct), n),
            "severity_accuracy_exact": ratio(sum(1 for g, p, _ in matched if g["severity"] == p["severity"]), n),
            "severity_confusion_gt_rows_pred_cols": severity_confusion,
            "part_accuracy": ratio(sum(1 for g, p, _ in matched if g["part"] == p["part"]), n),
            "action_accuracy": ratio(sum(1 for g, p, _ in matched if g["action"] == p["action"]), n),
        },
        "confidence": {
            "definition": "matched pairs only; outcome = predicted damage_type is correct",
            "brier_score": brier,
            "calibration_table": calibration_table,
            "pearson_auxiliary": pearson(confidences, type_correct),
        },
        "image_level_auxiliary": {
            "note": "class presence per image, no localization; NOT a detection metric",
            **image_level,
            "precision": ratio(image_level["tp"], image_level["tp"] + image_level["fp"]),
            "recall": ratio(image_level["tp"], image_level["tp"] + image_level["fn"]),
        },
        "per_image": per_image,
    }


def fmt(value):
    return "n/a" if value is None else f"{value:.3f}"


def print_summary(report, meta):
    d, m, c = report["detection"], report["matched_pairs"], report["confidence"]
    print(f"model: {meta.get('model', '?')}   prompt: {meta.get('prompt_version', '?')}")
    print(f"images: {report['images_total']}  (pipeline error/missing: {report['images_pipeline_error_or_missing']})")
    print(f"matching: IoU >= {report['iou_threshold']}, {report['matching']}")
    print(f"detection     TP={d['tp']} FP={d['fp']} FN={d['fn']}  "
          f"precision={fmt(d['precision'])} recall={fmt(d['recall'])}")
    print(f"damage type   macro-F1={fmt(report['damage_type']['macro_f1'])}")
    print(f"matched pairs n={m['count']}  type acc={fmt(m['damage_type_accuracy'])}  "
          f"severity exact={fmt(m['severity_accuracy_exact'])}  part={fmt(m['part_accuracy'])}  "
          f"action={fmt(m['action_accuracy'])}")
    print(f"confidence    Brier={fmt(c['brier_score'])}  pearson(aux)={fmt(c['pearson_auxiliary'])}")
    for row in c["calibration_table"]:
        print(f"   {row['confidence_range']}: n={row['count']} acc={fmt(row['empirical_accuracy'])}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ground-truth", required=True)
    parser.add_argument("--predictions", required=True)
    parser.add_argument("--out", help="write the full report as JSON")
    args = parser.parse_args()

    with open(args.ground_truth, encoding="utf-8") as f:
        ground_truth = json.load(f)
    with open(args.predictions, encoding="utf-8") as f:
        predictions = json.load(f)

    report = evaluate(ground_truth, predictions)
    report["meta"] = predictions.get("meta", {})
    print_summary(report, report["meta"])
    if report["predictions_for_unknown_images"]:
        print("WARNING: predictions for images not in ground truth were ignored: "
              f"{report['predictions_for_unknown_images']}", file=sys.stderr)

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2, ensure_ascii=False)
        print(f"full report written to {args.out}")


if __name__ == "__main__":
    main()
