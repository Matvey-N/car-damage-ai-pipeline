#!/usr/bin/env python3
"""
Converts SYNDCAR (YOLO format) into two COCO annotation files that the rest
of the benchmark scripts read: a dev pool and a test pool.

SYNDCAR: https://data.mendeley.com/datasets/hzpj48krdt/1 (CC BY 4.0)
  images/          the photos
  labels_damage/   YOLO detection: "class cx cy w h" (normalized)
  labels_parts/    YOLO segmentation: "class x1 y1 x2 y2 ..." (normalized polygon)
  data_damage.yaml, data_parts.yaml   class names

What the script does:
  1. Maps SYNDCAR damage classes to the project's damage types and
     SYNDCAR's 28 parts to the project's parts (rules below; the mapping
     actually used is printed so it can be checked).
  2. For every damage box, finds the part polygon on the same image that
     covers the largest share of the box -> "auto_part". The labeling sheet
     shows it pre-filled, the annotator only checks it.
  3. Splits the images into a dev pool and a test pool (fixed seed,
     disjoint). SYNDCAR has no official split. Whole shooting sessions are
     kept together (see session_key): consecutive shots of the same car,
     possibly by several devices, must not end up in both pools, otherwise
     the test result would be inflated by near-duplicate scenes.
  4. Reports image sizes, files over 5 MB and EXIF rotation, which matter
     for sending images to the model.

Usage:
  python convert_syndcar.py --syndcar ../data/hitl/SYNDCAR --out-dir ../data/syndcar_coco
Standard library only.
"""

import argparse
import json
import os
import random
import re
import struct
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone

from schema_values import DAMAGE_TYPES, PARTS

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png"}
API_MAX_BYTES = 5 * 1024 * 1024

# Matched against the lowercased class name with "_" and "-" replaced by spaces.
# First matching rule wins.
DAMAGE_RULES = [
    ("glass", "glass_shatter"),
    ("light", "lamp_broken"),
    ("lamp", "lamp_broken"),
    ("crack", "crack"),
    ("scratch", "scratch"),
]
PART_RULES = [
    ("mirror", "mirror"),
    ("wheel", "wheel"),
    ("windshield", "windshield"),
    ("window", "window"),
    ("door", "door"),
    ("light", "light"),
    ("quarter", "fender"),
    ("rocker", "other"),
    ("front panel", "bumper"),
    ("rear panel", "bumper"),
    ("hood", "hood"),
    ("bumper", "bumper"),
]

# A part is assigned automatically only if it covers at least this share
# of the damage box; otherwise the annotator fills the part in by hand.
MIN_PART_COVERAGE = 0.10


# ------------------------------------------------------------------ class names

def normalize_name(name):
    return re.sub(r"[_\-]+", " ", name.strip().lower())


def map_name(name, rules):
    n = normalize_name(name)
    for keyword, target in rules:
        if keyword in n:
            return target
    return None


def read_yaml_names(path):
    """
    Reads the 'names' entry of a YOLO data yaml. Supports the three usual forms:
      names: [a, b]       names:\n  - a       names:\n  0: a
    Returns {class_index: name}.
    """
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"^names\s*:\s*(.*)$", line.strip())
        if not m:
            continue
        inline = m.group(1).strip()
        if inline.startswith("["):
            items = [x.strip().strip("'\"") for x in inline.strip("[]").split(",") if x.strip()]
            return dict(enumerate(items))
        if inline.startswith("{"):
            result = {}
            for pair in inline.strip("{}").split(","):
                k, _, v = pair.partition(":")
                result[int(k.strip())] = v.strip().strip("'\"")
            return result
        result, index = {}, 0
        for nxt in lines[i + 1:]:
            if not nxt.strip() or nxt.strip().startswith("#"):
                continue
            if not nxt.startswith((" ", "\t", "-")):
                break
            item = nxt.strip()
            if item.startswith("-"):
                result[index] = item[1:].strip().strip("'\"")
                index += 1
            else:
                k, _, v = item.partition(":")
                result[int(k.strip())] = v.strip().strip("'\"")
        return result
    raise ValueError(f"no 'names' entry found in {path}")


# ------------------------------------------------------------------ image headers

def image_info(path):
    """Returns (width, height, exif_orientation or None) from the file header."""
    with open(path, "rb") as f:
        head = f.read(26)
        if head[:8] == b"\x89PNG\r\n\x1a\n":
            width, height = struct.unpack(">II", head[16:24])
            return width, height, None
        if head[:2] != b"\xff\xd8":
            raise ValueError("not a JPEG or PNG file")
        f.seek(2)
        orientation, size = None, None
        while size is None:
            marker = f.read(2)
            if len(marker) < 2 or marker[0] != 0xFF:
                break
            code = marker[1]
            if code in (0xD8, 0x01) or 0xD0 <= code <= 0xD7:
                continue
            length = struct.unpack(">H", f.read(2))[0]
            segment = f.read(length - 2)
            if code == 0xE1 and segment[:6] == b"Exif\x00\x00":
                orientation = exif_orientation(segment[6:])
            elif code in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                height, width = struct.unpack(">HH", segment[1:5])
                size = (width, height)
        if size is None:
            raise ValueError("JPEG size not found")
        return size[0], size[1], orientation


def exif_orientation(tiff):
    try:
        endian = "<" if tiff[:2] == b"II" else ">"
        ifd = struct.unpack(endian + "I", tiff[4:8])[0]
        count = struct.unpack(endian + "H", tiff[ifd:ifd + 2])[0]
        for i in range(count):
            entry = tiff[ifd + 2 + 12 * i: ifd + 14 + 12 * i]
            if struct.unpack(endian + "H", entry[:2])[0] == 0x0112:
                return struct.unpack(endian + "H", entry[8:10])[0]
    except (struct.error, IndexError):
        return None
    return None


# ------------------------------------------------------------------ geometry

def polygon_area(points):
    s = 0.0
    for i in range(len(points)):
        x1, y1 = points[i]
        x2, y2 = points[(i + 1) % len(points)]
        s += x1 * y2 - x2 * y1
    return abs(s) / 2.0


def clip_polygon_to_box(points, box):
    """Sutherland-Hodgman clipping of a polygon to an axis-aligned box (x0, y0, x1, y1)."""
    x0, y0, x1, y1 = box
    edges = [
        (lambda p: p[0] >= x0, lambda a, b: (x0, a[1] + (b[1] - a[1]) * (x0 - a[0]) / (b[0] - a[0]))),
        (lambda p: p[0] <= x1, lambda a, b: (x1, a[1] + (b[1] - a[1]) * (x1 - a[0]) / (b[0] - a[0]))),
        (lambda p: p[1] >= y0, lambda a, b: (a[0] + (b[0] - a[0]) * (y0 - a[1]) / (b[1] - a[1]), y0)),
        (lambda p: p[1] <= y1, lambda a, b: (a[0] + (b[0] - a[0]) * (y1 - a[1]) / (b[1] - a[1]), y1)),
    ]
    output = list(points)
    for inside, intersect in edges:
        if not output:
            break
        current, output = output, []
        for i, p in enumerate(current):
            prev = current[i - 1]
            if inside(p):
                if not inside(prev):
                    output.append(intersect(prev, p))
                output.append(p)
            elif inside(prev):
                output.append(intersect(prev, p))
    return output


def coverage(polygon, box):
    """Share of the box area covered by the polygon (both in the same coordinates)."""
    box_area = (box[2] - box[0]) * (box[3] - box[1])
    if box_area <= 0:
        return 0.0
    clipped = clip_polygon_to_box(polygon, box)
    return polygon_area(clipped) / box_area if len(clipped) >= 3 else 0.0


# ------------------------------------------------------------------ split

SESSION_PATTERN = re.compile(r"^ID\d+_(\d{8})_\d{6}$")
# some devices name files by Unix time in milliseconds: ID5_1726736225270
EPOCH_MS_PATTERN = re.compile(r"^ID\d+_(\d{13})$")


def session_key(file_name, group_by):
    """
    SYNDCAR file names look like ID1_20240917_150956.png (device, date, time)
    or ID5_1726736225270.png (device, Unix time in milliseconds; the UTC date
    is used).
    group_by="date": all images of one day form one group, across devices
    (several devices may have photographed the same car the same day).
    group_by="none": every image is its own group.
    Names that do not match the pattern always form their own group.
    """
    stem = os.path.splitext(file_name)[0]
    if group_by == "none":
        return "file:" + stem
    m = SESSION_PATTERN.match(stem)
    if m:
        return "date:" + m.group(1)
    m = EPOCH_MS_PATTERN.match(stem)
    if m:
        day = datetime.fromtimestamp(int(m.group(1)) / 1000, tz=timezone.utc)
        return "date:" + day.strftime("%Y%m%d")
    return "file:" + stem


def split_groups(images, group_by, seed, dev_share=0.5):
    """
    Assigns whole groups to the dev or the test pool so that the dev pool
    gets as close as possible to dev_share of the images. Groups are taken
    in a seeded random order; each goes to the pool that is further below
    its target. Returns (dev_image_ids, groups) where groups maps
    group key -> list of image ids.
    """
    groups = defaultdict(list)
    for img in images:
        groups[session_key(img["file_name"], group_by)].append(img["id"])
    keys = sorted(groups)
    random.Random(seed).shuffle(keys)
    total = len(images)
    dev, test = set(), set()
    for key in keys:
        dev_gap = dev_share * total - len(dev)
        test_gap = (1 - dev_share) * total - len(test)
        (dev if dev_gap >= test_gap else test).update(groups[key])
    return dev, dict(groups)


# ------------------------------------------------------------------ conversion

def read_label_lines(path):
    if not os.path.exists(path):
        return []
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            values = line.split()
            if values:
                rows.append((int(float(values[0])), [float(v) for v in values[1:]]))
    return rows


def convert(syndcar_dir, seed=42, dev_share=0.5, group_by="date"):
    damage_names = read_yaml_names(os.path.join(syndcar_dir, "data_damage.yaml"))
    part_names = read_yaml_names(os.path.join(syndcar_dir, "data_parts.yaml"))

    damage_map = {i: map_name(n, DAMAGE_RULES) for i, n in damage_names.items()}
    part_map = {i: map_name(n, PART_RULES) or "other" for i, n in part_names.items()}
    unmapped = [damage_names[i] for i, t in damage_map.items() if t is None]
    if unmapped:
        raise ValueError(f"damage classes without a mapping rule: {unmapped}")

    images_dir = os.path.join(syndcar_dir, "images")
    files = sorted(f for f in os.listdir(images_dir) if os.path.splitext(f)[1].lower() in IMAGE_EXTENSIONS)

    images, annotations = [], []
    stats = Counter()
    rotated, oversized, without_label_file = [], [], []
    ann_id = 1

    for image_id, file_name in enumerate(files, start=1):
        path = os.path.join(images_dir, file_name)
        width, height, orientation = image_info(path)
        if orientation not in (None, 1):
            rotated.append((file_name, orientation))
        if os.path.getsize(path) > API_MAX_BYTES:
            oversized.append(file_name)
        images.append({"id": image_id, "file_name": file_name, "width": width, "height": height})

        stem = os.path.splitext(file_name)[0]
        damage_file = os.path.join(syndcar_dir, "labels_damage", stem + ".txt")
        if not os.path.exists(damage_file):
            without_label_file.append(file_name)
        parts = [(part_map[c], part_names[c], [(v[i] * width, v[i + 1] * height) for i in range(0, len(v) - 1, 2)])
                 for c, v in read_label_lines(os.path.join(syndcar_dir, "labels_parts", stem + ".txt"))
                 if len(v) >= 6]

        for cls, values in read_label_lines(damage_file):
            if len(values) != 4:
                stats["skipped_bad_damage_line"] += 1
                continue
            cx, cy, w, h = values
            x0 = max(0.0, (cx - w / 2) * width)
            y0 = max(0.0, (cy - h / 2) * height)
            x1 = min(float(width), (cx + w / 2) * width)
            y1 = min(float(height), (cy + h / 2) * height)
            if x1 <= x0 or y1 <= y0:
                stats["skipped_empty_box"] += 1
                continue

            best_part, best_source, best_cov = "", "", 0.0
            for part, source, polygon in parts:
                c = coverage(polygon, (x0, y0, x1, y1))
                if c > best_cov:
                    best_part, best_source, best_cov = part, source, c
            if best_cov < MIN_PART_COVERAGE:
                best_part, best_source = "", ""
                stats["no_auto_part"] += 1

            damage_type = damage_map[cls]
            stats[damage_type] += 1
            annotations.append({
                "id": ann_id, "image_id": image_id,
                "category_id": DAMAGE_TYPES.index(damage_type) + 1,
                "bbox": [round(x0, 1), round(y0, 1), round(x1 - x0, 1), round(y1 - y0, 1)],
                "area": round((x1 - x0) * (y1 - y0), 1), "iscrowd": 0,
                "auto_part": best_part, "auto_part_source": best_source,
                "auto_part_coverage": round(best_cov, 2),
            })
            ann_id += 1

    categories = [{"id": i + 1, "name": t} for i, t in enumerate(DAMAGE_TYPES)]

    # fixed, disjoint split of images into dev and test pools
    dev_ids, groups = split_groups(images, group_by, seed, dev_share)

    def pool(selected):
        return {"images": [img for img in images if img["id"] in selected],
                "annotations": [a for a in annotations if a["image_id"] in selected],
                "categories": categories,
                "info": {"source": "SYNDCAR, https://data.mendeley.com/datasets/hzpj48krdt/1, CC BY 4.0",
                         "converted_by": "scripts/convert_syndcar.py", "seed": seed,
                         "group_by": group_by}}

    report = {
        "damage_class_mapping": {damage_names[i]: damage_map[i] for i in damage_names},
        "part_class_mapping": {part_names[i]: part_map[i] for i in part_names},
        "images": len(images), "damage_boxes": len(annotations),
        "per_damage_type": {t: stats[t] for t in DAMAGE_TYPES},
        "no_auto_part": stats["no_auto_part"],
        "skipped": {k: v for k, v in stats.items() if k.startswith("skipped")},
        "images_without_damage_label_file": len(without_label_file),
        "images_over_5mb": len(oversized),
        "images_with_exif_rotation": rotated,
        "image_sizes": Counter(f"{img['width']}x{img['height']}" for img in images).most_common(5),
        "group_by": group_by,
        "groups": {k: {"images": len(v), "pool": "dev" if v[0] in dev_ids else "test"}
                   for k, v in sorted(groups.items())},
    }
    all_ids = set(img["id"] for img in images)
    return pool(dev_ids), pool(all_ids - dev_ids), report


def per_class_images(coco):
    names = {c["id"]: c["name"] for c in coco["categories"]}
    seen = defaultdict(set)
    for a in coco["annotations"]:
        seen[names[a["category_id"]]].add(a["image_id"])
    return {t: len(seen[t]) for t in DAMAGE_TYPES}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--syndcar", required=True, help="SYNDCAR folder (contains images/, labels_damage/, ...)")
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--seed", type=int, default=42, help="seed of the dev/test split; do not change later")
    parser.add_argument("--group-by", choices=["date", "none"], default="date",
                        help="keep whole shooting days together in one pool (default) or split single images")
    args = parser.parse_args(argv)

    try:
        dev, test, report = convert(args.syndcar, args.seed, group_by=args.group_by)
    except (OSError, ValueError) as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1

    os.makedirs(args.out_dir, exist_ok=True)
    for name, data in (("dev_pool.json", dev), ("test_pool.json", test)):
        with open(os.path.join(args.out_dir, name), "w", encoding="utf-8") as f:
            json.dump(data, f, indent=1, ensure_ascii=False)
    with open(os.path.join(args.out_dir, "conversion_report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    print("damage classes:", report["damage_class_mapping"])
    print("parts:", report["part_class_mapping"])
    print(f"images: {report['images']}, damage boxes: {report['damage_boxes']}, per type: {report['per_damage_type']}")
    print(f"boxes without auto part (annotator fills in): {report['no_auto_part']}")
    print(f"skipped: {report['skipped']}, images without damage label file: {report['images_without_damage_label_file']}")
    print(f"images over 5 MB: {report['images_over_5mb']}, with EXIF rotation: {len(report['images_with_exif_rotation'])}")
    print(f"most common sizes: {report['image_sizes']}")
    print(f"split by {report['group_by']}: {len(report['groups'])} groups")
    for key, g in report["groups"].items():
        print(f"   {key}: {g['images']} images -> {g['pool']}")
    print(f"dev pool: {len(dev['images'])} images, images per type: {per_class_images(dev)}")
    print(f"test pool: {len(test['images'])} images, images per type: {per_class_images(test)}")
    print(f"written to {args.out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
