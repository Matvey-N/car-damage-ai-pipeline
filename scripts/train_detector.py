#!/usr/bin/env python3
"""
Trains the baseline detector (YOLO) on the dataset from make_yolo_dataset.py.

Needs the ultralytics package (not part of the standard library):
  pip install ultralytics

A small model and a high input resolution are used because the damages are
small: at the default 640 px most of them would shrink to a few pixels.
Training on a CPU works but is slow (about an hour); with an NVIDIA GPU it
takes minutes.

Usage:
  python train_detector.py --data ../data/yolo_dev/data.yaml
"""

import argparse
import sys


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data", required=True, help="data.yaml from make_yolo_dataset.py")
    parser.add_argument("--model", default="yolov8n.pt", help="pretrained starting point (downloaded on first use)")
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--imgsz", type=int, default=1280)
    parser.add_argument("--batch", type=int, default=4)
    parser.add_argument("--project", default="../data/detector_runs")
    parser.add_argument("--name", default="syndcar_dev")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args(argv)

    try:
        from ultralytics import YOLO
    except ImportError:
        print("ERROR: install the detector dependencies first: pip install ultralytics", file=sys.stderr)
        return 1

    model = YOLO(args.model)
    model.train(data=args.data, epochs=args.epochs, imgsz=args.imgsz, batch=args.batch,
                project=args.project, name=args.name, seed=args.seed, deterministic=True,
                exist_ok=True, plots=False)
    print(f"done. Weights: {args.project}/{args.name}/weights/best.pt")
    return 0


if __name__ == "__main__":
    sys.exit(main())
