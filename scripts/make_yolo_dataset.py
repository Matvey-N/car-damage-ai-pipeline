#!/usr/bin/env python3
"""
Builds a YOLO training set for the baseline detector from the DEV pool only.

  train = dev-pool images that are NOT in the fixed dev sample
  val   = the 16 images of the dev sample (used to pick the detector's settings)

The test pool is never touched, so the test run stays a one-time check.
Images are hard-linked into the dataset folder (no copy on the same disk;
falls back to copying), labels are written in YOLO format from the COCO pool,
so the boxes are exactly the ones the benchmark uses.

Usage:
  python make_yolo_dataset.py --pool ../benchmark/syndcar_coco/dev_pool.json \\
      --examples ../benchmark/dev_examples.json --images-dir ../data/hitl/SYNDCAR/images \\
      --out-dir ../data/yolo_dev
"""

import argparse
import os
import shutil
import sys

from labels_io import index_coco, load_json, selected_image_ids
from schema_values import DAMAGE_TYPES


def yolo_line(ann, width, height, categories):
    x, y, w, h = ann["bbox"]
    cls = DAMAGE_TYPES.index(categories[ann["category_id"]])
    return f"{cls} {(x + w / 2) / width:.6f} {(y + h / 2) / height:.6f} {w / width:.6f} {h / height:.6f}"


def place(src, dst):
    if os.path.exists(dst):
        return
    try:
        os.link(src, dst)
    except OSError:
        shutil.copyfile(src, dst)


def build(pool, examples, images_dir, out_dir):
    images, anns, categories = index_coco(pool)
    val_ids = set(selected_image_ids(examples))
    missing = sorted(i for i in val_ids if i not in images)
    if missing:
        raise ValueError(f"dev sample images not in the pool: {missing[:5]}")
    counts = {"train": 0, "val": 0, "boxes_train": 0, "boxes_val": 0}
    for split in ("train", "val"):
        os.makedirs(os.path.join(out_dir, "images", split), exist_ok=True)
        os.makedirs(os.path.join(out_dir, "labels", split), exist_ok=True)
    for image_id, img in sorted(images.items()):
        split = "val" if image_id in val_ids else "train"
        src = os.path.join(images_dir, img["file_name"])
        if not os.path.isfile(src):
            raise FileNotFoundError(src)
        place(src, os.path.join(out_dir, "images", split, img["file_name"]))
        lines = [yolo_line(a, img["width"], img["height"], categories) for a in anns.get(image_id, [])]
        stem = os.path.splitext(img["file_name"])[0]
        with open(os.path.join(out_dir, "labels", split, stem + ".txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + ("\n" if lines else ""))
        counts[split] += 1
        counts["boxes_" + split] += len(lines)
    with open(os.path.join(out_dir, "data.yaml"), "w", encoding="utf-8") as f:
        f.write(f"path: {os.path.abspath(out_dir)}\ntrain: images/train\nval: images/val\nnames:\n")
        for i, name in enumerate(DAMAGE_TYPES):
            f.write(f"  {i}: {name}\n")
    return counts


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pool", required=True, help="dev_pool.json (never the test pool)")
    parser.add_argument("--examples", required=True, help="dev_examples.json: these images become the val split")
    parser.add_argument("--images-dir", required=True)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args(argv)
    if "test" in os.path.basename(args.pool).lower():
        print("ERROR: the detector is trained on the dev pool only; the test pool stays for the final check.",
              file=sys.stderr)
        return 1
    counts = build(load_json(args.pool), load_json(args.examples), args.images_dir, args.out_dir)
    print(f"train: {counts['train']} images, {counts['boxes_train']} boxes; "
          f"val: {counts['val']} images, {counts['boxes_val']} boxes -> {os.path.join(args.out_dir, 'data.yaml')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
