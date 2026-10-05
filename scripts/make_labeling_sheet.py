#!/usr/bin/env python3
"""
Creates the manual labeling sheet (CSV) for a fixed benchmark sample.

One row per damage instance annotated on the selected images.
damage_type and the box come from the dataset and must not be edited.
The annotator fills in: part, severity, action (and optionally comment),
following the rules in TZ section 9. If the annotation file contains an
automatically derived part (SYNDCAR, see convert_syndcar.py), the part
column is pre-filled and the comment says where it came from: the
annotator checks it and corrects it if needed.

Each annotator gets their own copy of the sheet and fills it independently.

Usage:
  python make_labeling_sheet.py --annotations ../benchmark/syndcar_coco/dev_pool.json \
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
                "part": ann.get("auto_part", ""),
                "comment": (f"part auto: {ann['auto_part_source']} "
                            f"({ann['auto_part_coverage']:.0%} of box), check it")
                if ann.get("auto_part") else "",
            })
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--annotations", required=True, help="COCO annotation file of the pool (dev_pool.json / test_pool.json)")
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
    print("Fill in severity and action, check part (may be pre-filled). Do not edit damage_type or the box.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
