#!/usr/bin/env python3
"""
Builds the ground-truth file for evaluate.py from:
  - the COCO annotations of the pool (boxes, damage types, image sizes),
  - the fixed sample (dev_examples.json / test_examples.json),
  - the final agreed labeling sheet (part, severity, action).

Refuses to build if anything is inconsistent: a damage without a label,
a value outside the allowed lists, a damage_type or box edited in the
sheet, or a row that does not belong to the sample.

Usage:
  python build_ground_truth.py --annotations ../benchmark/syndcar_coco/dev_pool.json \
      --examples ../benchmark/dev_examples.json \
      --labels ../benchmark/labels_dev_final.csv \
      --out ../benchmark/ground_truth_dev.json
"""

import argparse
import sys
from datetime import datetime, timezone

from labels_io import index_coco, load_json, read_sheet, selected_image_ids, write_json
from schema_values import LABEL_FIELDS


def build(coco, examples, label_rows):
    images, anns, categories = index_coco(coco)
    labels = {}
    errors = []

    for i, row in enumerate(label_rows, start=2):  # row 1 is the header
        try:
            ann_id = int(row["annotation_id"])
        except (KeyError, ValueError):
            errors.append(f"row {i}: bad annotation_id '{row.get('annotation_id')}'")
            continue
        if ann_id in labels:
            errors.append(f"row {i}: annotation_id {ann_id} appears twice")
        labels[ann_id] = (i, row)

    gt_images = []
    expected_ids = set()
    for image_id in selected_image_ids(examples):
        if image_id not in images:
            errors.append(f"image_id {image_id} is not in the annotation file (wrong split?)")
            continue
        img = images[image_id]
        damages = []
        for ann in anns.get(image_id, []):
            expected_ids.add(ann["id"])
            if ann["id"] not in labels:
                errors.append(f"image {image_id}: annotation {ann['id']} has no row in the labeling sheet")
                continue
            line, row = labels[ann["id"]]
            coco_type = categories[ann["category_id"]]
            if row.get("damage_type") != coco_type:
                errors.append(f"row {line}: damage_type '{row.get('damage_type')}' differs from the dataset "
                              f"('{coco_type}'); it must not be edited")
            for field, allowed in LABEL_FIELDS.items():
                if row.get(field) not in allowed:
                    errors.append(f"row {line}: {field} '{row.get(field)}' is not one of {allowed}")
            damages.append({
                "annotation_id": ann["id"],
                "bbox": ann["bbox"],
                "damage_type": coco_type,
                "part": row.get("part"),
                "severity": row.get("severity"),
                "action": row.get("action"),
            })
        gt_images.append({
            "image_id": str(image_id),
            "file_name": img["file_name"],
            "width": img["width"],
            "height": img["height"],
            "damages": damages,
        })

    for ann_id, (line, _) in labels.items():
        if ann_id not in expected_ids:
            errors.append(f"row {line}: annotation {ann_id} does not belong to the selected images")

    return {"images": gt_images}, errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--annotations", required=True)
    parser.add_argument("--examples", required=True)
    parser.add_argument("--labels", required=True, help="final agreed labeling sheet (CSV)")
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)

    ground_truth, errors = build(load_json(args.annotations), load_json(args.examples), read_sheet(args.labels))
    if errors:
        print(f"Ground truth NOT written, {len(errors)} problem(s):", file=sys.stderr)
        for e in errors:
            print("  - " + e, file=sys.stderr)
        return 1

    ground_truth["meta"] = {
        "annotations": args.annotations,
        "examples": args.examples,
        "labels": args.labels,
        "created": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    write_json(args.out, ground_truth)
    n = sum(len(img["damages"]) for img in ground_truth["images"])
    print(f"ground truth: {len(ground_truth['images'])} images, {n} damages -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
