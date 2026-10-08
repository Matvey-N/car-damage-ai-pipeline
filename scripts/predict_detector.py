#!/usr/bin/env python3
"""
Runs the trained detector on a sample and writes predictions in the same
format as run_benchmark.py, so evaluate.py, bootstrap.py and selective.py
work on them unchanged. A detector gives box, damage type and confidence only;
part, severity and action are left out (evaluate.py skips them).

Pick --conf on DEV (python evaluate.py on predictions_dev_detector.json for a
few values), then use the same value once on test.

Usage:
  python predict_detector.py --weights ../data/detector_runs/syndcar_dev/weights/best.pt \\
      --examples ../benchmark/test_examples.json --images-dir ../data/hitl/SYNDCAR/images \\
      --out ../benchmark/predictions_test_detector.json
"""

import argparse
import os
import sys
from datetime import datetime, timezone

from labels_io import load_json, write_json
from schema_values import DAMAGE_TYPES


def to_damages(boxes_xywhn, confidences, classes, names):
    """Converts YOLO output (normalized centre x, y, w, h) into benchmark damages."""
    damages = []
    for (cx, cy, w, h), conf, cls in zip(boxes_xywhn, confidences, classes):
        name = names[int(cls)]
        if name not in DAMAGE_TYPES:
            continue
        x = min(max(cx - w / 2, 0.0), 1.0)
        y = min(max(cy - h / 2, 0.0), 1.0)
        damages.append({"damage_type": name, "confidence": round(float(conf), 4),
                        "bounding_box": [round(x, 6), round(y, 6),
                                         round(min(w, 1.0 - x), 6), round(min(h, 1.0 - y), 6)]})
    damages.sort(key=lambda d: -d["confidence"])
    return damages


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--weights", required=True)
    parser.add_argument("--examples", required=True)
    parser.add_argument("--images-dir", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--conf", type=float, default=0.25)
    parser.add_argument("--imgsz", type=int, default=1280)
    args = parser.parse_args(argv)

    try:
        from ultralytics import YOLO
    except ImportError:
        print("ERROR: install the detector dependencies first: pip install ultralytics", file=sys.stderr)
        return 1

    model = YOLO(args.weights)
    names = model.names
    predictions = {}
    seen = set()
    for entry in load_json(args.examples):
        image_id = str(entry["image_id"])
        if image_id in seen:
            continue
        seen.add(image_id)
        path = os.path.join(args.images_dir, entry["file_name"])
        result = model.predict(source=path, conf=args.conf, imgsz=args.imgsz, verbose=False)[0]
        b = result.boxes
        damages = to_damages(b.xywhn.tolist(), b.conf.tolist(), b.cls.tolist(), names)
        predictions[image_id] = {"status": "success", "damages": damages, "overall_score": 0, "attempts": 0}
        print(f"{image_id}: {len(damages)} boxes")

    write_json(args.out, {"meta": {"model": "detector:" + os.path.basename(os.path.dirname(os.path.dirname(args.weights)) or args.weights),
                                   "model_client": "detector", "prompt_version": None, "mode": "detector",
                                   "weights": args.weights, "conf": args.conf, "imgsz": args.imgsz,
                                   "examples": args.examples,
                                   "started": datetime.now(timezone.utc).isoformat(timespec="seconds")},
                          "predictions": predictions})
    print(f"done: {len(predictions)} images -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
