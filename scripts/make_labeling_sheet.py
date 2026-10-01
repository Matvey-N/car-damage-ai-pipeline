#!/usr/bin/env python3
"""
Creates the manual labeling sheet (CSV) for a fixed benchmark sample.

One row per damage instance annotated in CarDD on the selected images.
damage_type and the box come from CarDD and must not be edited.
The annotator fills in: part, severity, action (and optionally comment),
following the rules in TZ section 9.

Each annotator gets their own copy of the sheet and fills it independently.

Usage:
  python make_labeling_sheet.py --annotations CarDD_val_annotations.json \
      --examples ../benchmark/dev_examples.json --out ../benchmark/labels_dev_A.csv
"""

import argparse
import sys

from labels_io import index_coco, load_json, selected_image_ids, write_sheet


def build_rows(coco, examples):
    images, anns, categories = index_coco(coco)
    rows = []
    for image_id in selected_image_ids(examples):
        if image_id not in images:
            raise ValueError(f"image_id {image_id} from the examples file is not in the annotation file "
                             f"(wrong split file?)")
        for ann in anns.get(image_id, []):
            x, y, w, h = ann["bbox"]
            rows.append({
                "image_id": image_id,
                "file_name": images[image_id]["file_name"],
                "annotation_id": ann["id"],
                "damage_type": categories[ann["category_id"]],
                "bbox_x": round(x, 1), "bbox_y": round(y, 1),
                "bbox_w": round(w, 1), "bbox_h": round(h, 1),
            })
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--annotations", required=True, help="CarDD COCO annotation file of the split")
    parser.add_argument("--examples", required=True, help="dev_examples.json or test_examples.json")
    parser.add_argument("--out", required=True, help="CSV sheet to create")
    args = parser.parse_args(argv)

    try:
        rows = build_rows(load_json(args.annotations), load_json(args.examples))
    except ValueError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1
    write_sheet(args.out, rows)
    images = len({r["image_id"] for r in rows})
    print(f"{len(rows)} damage instances on {images} images written to {args.out}")
    print("Fill in the columns part, severity, action. Do not edit damage_type or the box.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
