"""
Shared helpers for the labeling scripts.

CSV files are written with ';' as separator and UTF-8 with BOM, so they
open correctly in Excel with a Russian locale. When reading, both ';' and
',' are accepted, in case the file was re-saved by another program.
"""

import csv
import json

SHEET_COLUMNS = ["image_id", "file_name", "annotation_id", "damage_type",
                 "bbox_x", "bbox_y", "bbox_w", "bbox_h",
                 "part", "severity", "action", "comment"]


def load_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def write_json(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)


def write_sheet(path, rows):
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=SHEET_COLUMNS, delimiter=";")
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k, "") for k in SHEET_COLUMNS})


def read_sheet(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        sample = f.read(4096)
        f.seek(0)
        delimiter = ";" if sample.count(";") >= sample.count(",") else ","
        rows = list(csv.DictReader(f, delimiter=delimiter))
    for row in rows:
        for key in list(row):
            if isinstance(row[key], str):
                row[key] = row[key].strip()
    return rows


def index_coco(coco):
    """Returns (images_by_id, annotations_by_image_id, category_name_by_id)."""
    images = {img["id"]: img for img in coco["images"]}
    categories = {c["id"]: c["name"] for c in coco["categories"]}
    anns = {}
    for ann in coco["annotations"]:
        anns.setdefault(ann["image_id"], []).append(ann)
    for image_id in anns:
        anns[image_id].sort(key=lambda a: a["id"])
    return images, anns, categories


def selected_image_ids(examples):
    """Unique image ids from a dev_examples.json / test_examples.json file, in order."""
    seen, ordered = set(), []
    for entry in examples:
        if entry["image_id"] not in seen:
            seen.add(entry["image_id"])
            ordered.append(entry["image_id"])
    return ordered
