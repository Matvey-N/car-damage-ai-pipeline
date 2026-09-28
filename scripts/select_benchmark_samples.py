#!/usr/bin/env python3
"""
Selects a fixed, stratified sample of CarDD images for the benchmark
(TZ section 8): a dev set drawn from CarDD's *validation* split (for prompt
tuning) and a test set drawn from CarDD's *test* split (for the final,
frozen-prompt evaluation).

Why this exists as a standalone offline script, not part of the Spring
Boot runtime (TZ section 3): sample selection happens once, before any
experiments, and its whole point is to be a fixed artifact that gets
committed to the repo - not something recomputed on every run.

Usage:
    python select_benchmark_samples.py \
        --val-annotations /path/to/CarDD/annotations/instances_val.json \
        --test-annotations /path/to/CarDD/annotations/instances_test.json \
        --dev-per-class 3 \
        --test-per-class 4 \
        --seed 42 \
        --out-dir ../benchmark

Expects CarDD-style COCO annotation files (images[], annotations[],
categories[]). Adjust `CATEGORY_NAME_KEY` below if your copy of CarDD
names the category field differently.

Output:
    <out-dir>/dev_examples.txt   - one CarDD image_id per line, dev set
    <out-dir>/test_examples.txt  - one CarDD image_id per line, test set
    <out-dir>/dev_examples.json  - dev set with file_name + damage classes present
    <out-dir>/test_examples.json - test set with file_name + damage classes present

Re-running this script with the same --seed on the same annotation files
reproduces the same selection. Once these files are committed, they are
the frozen sample referenced by the TZ - do not regenerate them with a
different seed after ground-truth labeling has started (see TZ section 8:
"Изменение списков примеров во время работы недопустимо").
"""

import argparse
import json
import random
import sys
from collections import defaultdict
from pathlib import Path

CARDD_CLASSES = [
    "dent", "scratch", "crack", "glass_shatter", "tire_flat", "lamp_broken",
]


def load_coco_annotations(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def index_images_by_class(coco: dict) -> dict:
    """
    Returns {class_name: [image_id, ...]} - one entry per image that
    contains at least one annotation of that class. An image with several
    damage types will appear under several classes; that's expected, we
    just need at least N images touching each class in the final sample.
    """
    cat_id_to_name = {c["id"]: c["name"] for c in coco["categories"]}

    unknown_names = set(cat_id_to_name.values()) - set(CARDD_CLASSES)
    if unknown_names:
        print(
            f"WARNING: category names in annotation file not in expected "
            f"CarDD class list: {unknown_names}. Check CATEGORY name "
            f"mapping if this is unexpected.",
            file=sys.stderr,
        )

    image_id_to_filename = {img["id"]: img["file_name"] for img in coco["images"]}

    class_to_image_ids = defaultdict(set)
    for ann in coco["annotations"]:
        cls_name = cat_id_to_name.get(ann["category_id"])
        if cls_name is None:
            continue
        class_to_image_ids[cls_name].add(ann["image_id"])

    return class_to_image_ids, image_id_to_filename


def stratified_sample(class_to_image_ids: dict, per_class: int, rng: random.Random,
                       exclude: set) -> dict:
    """
    Picks `per_class` image ids per damage class, avoiding images already
    selected for another split (`exclude`) and avoiding picking the same
    image twice within this split where possible.

    Returns {class_name: [image_id, ...]}.
    """
    already_used_in_this_split = set()
    selection = {}

    for cls in CARDD_CLASSES:
        candidates = list(class_to_image_ids.get(cls, set()) - exclude - already_used_in_this_split)
        rng.shuffle(candidates)

        if len(candidates) < per_class:
            print(
                f"WARNING: only {len(candidates)} candidate images available "
                f"for class '{cls}', requested {per_class}. Taking all "
                f"available - stratification for this class is incomplete.",
                file=sys.stderr,
            )

        picked = candidates[:per_class]
        selection[cls] = picked
        already_used_in_this_split.update(picked)

    return selection


def flatten_unique(selection: dict) -> list:
    seen = []
    seen_set = set()
    for cls, ids in selection.items():
        for image_id in ids:
            if image_id not in seen_set:
                seen.append(image_id)
                seen_set.add(image_id)
    return seen


def write_outputs(out_dir: Path, split_name: str, selection: dict,
                   image_id_to_filename: dict):
    out_dir.mkdir(parents=True, exist_ok=True)
    flat_ids = flatten_unique(selection)

    txt_path = out_dir / f"{split_name}_examples.txt"
    with open(txt_path, "w", encoding="utf-8") as f:
        for image_id in flat_ids:
            f.write(f"{image_id}\n")

    json_path = out_dir / f"{split_name}_examples.json"
    detailed = []
    for cls, ids in selection.items():
        for image_id in ids:
            detailed.append({
                "image_id": image_id,
                "file_name": image_id_to_filename.get(image_id, "UNKNOWN"),
                "class_selected_for": cls,
            })
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(detailed, f, indent=2, ensure_ascii=False)

    print(f"{split_name}: {len(flat_ids)} unique images written to {txt_path} and {json_path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--val-annotations", required=True, type=Path,
                         help="CarDD validation-split COCO annotations JSON (source for dev set)")
    parser.add_argument("--test-annotations", required=True, type=Path,
                         help="CarDD test-split COCO annotations JSON (source for test set)")
    parser.add_argument("--dev-per-class", type=int, default=3,
                         help="Images per damage class for the dev set (default 3 -> 18 total)")
    parser.add_argument("--test-per-class", type=int, default=4,
                         help="Images per damage class for the test set (default 4 -> 24 total)")
    parser.add_argument("--seed", type=int, default=42,
                         help="Random seed, for reproducibility. Do not change after labeling starts.")
    parser.add_argument("--out-dir", type=Path, default=Path(__file__).parent.parent / "benchmark",
                         help="Where to write dev/test example lists")
    args = parser.parse_args()

    rng = random.Random(args.seed)

    val_coco = load_coco_annotations(args.val_annotations)
    test_coco = load_coco_annotations(args.test_annotations)

    val_class_to_images, val_id_to_filename = index_images_by_class(val_coco)
    test_class_to_images, test_id_to_filename = index_images_by_class(test_coco)

    # Dev and test come from different CarDD splits already, so there's no
    # actual overlap risk between them - `exclude` is empty here but kept
    # explicit in case someone points both flags at the same file by mistake.
    dev_selection = stratified_sample(val_class_to_images, args.dev_per_class, rng, exclude=set())
    test_selection = stratified_sample(test_class_to_images, args.test_per_class, rng, exclude=set())

    write_outputs(args.out_dir, "dev", dev_selection, val_id_to_filename)
    write_outputs(args.out_dir, "test", test_selection, test_id_to_filename)

    print("\nReminder: commit dev_examples.* and test_examples.* to the repo "
          "now, before any prompt tuning or labeling starts. TZ section 8 "
          "requires these identifiers to be fixed before experiments begin.")


if __name__ == "__main__":
    main()
