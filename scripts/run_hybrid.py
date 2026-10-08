#!/usr/bin/env python3
"""
Hybrid scheme: the detector finds the boxes, the model describes them.

For every image of the sample, the boxes from a detector predictions file
(predict_detector.py) are sent with the image to POST /api/v1/describe. The
model returns type, part, severity, action and confidence per box, or rejects
the box as "no damage". The boxes themselves stay the detector's. The output
has the same format as run_benchmark.py, so evaluate.py and the other scripts
work on it unchanged.

Saves after every image; re-running with the same --out continues.

Usage:
  python run_hybrid.py --detector ../benchmark/predictions_test_detector.json \\
      --examples ../benchmark/test_examples.json --images-dir ../data/hitl/SYNDCAR/images \\
      --out ../benchmark/predictions_test_hybrid.json
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

from labels_io import load_json, write_json
from run_benchmark import MEDIA_TYPES, get_info


def post_regions(api, path, media_type, regions, timeout):
    boundary = uuid.uuid4().hex
    with open(path, "rb") as f:
        data = f.read()
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"regions\"\r\n\r\n"
            f"{json.dumps(regions)}\r\n"
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="image"; filename="{os.path.basename(path)}"\r\n'
            f"Content-Type: {media_type}\r\n\r\n").encode("utf-8") + data + f"\r\n--{boundary}--\r\n".encode("utf-8")
    request = urllib.request.Request(api.rstrip("/") + "/api/v1/describe", data=body, method="POST",
                                     headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"status": "error", "damages": [], "overall_score": 0,
                            "error_message": f"HTTP {e.code}: {raw[:300]}"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--detector", required=True, help="predictions file from predict_detector.py")
    parser.add_argument("--examples", required=True)
    parser.add_argument("--images-dir", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--allow-stub", action="store_true")
    args = parser.parse_args(argv)

    try:
        info = get_info(args.api)
    except (urllib.error.URLError, OSError) as e:
        print(f"ERROR: service not reachable at {args.api}: {e}", file=sys.stderr)
        return 1
    if info.get("model_client") == "stub" and not args.allow_stub:
        print("ERROR: the service runs with the STUB model. Start it with PIPELINE_MODEL_CLIENT=anthropic, "
              "or pass --allow-stub for a dry run.", file=sys.stderr)
        return 1
    if "region_prompt_version" not in info:
        print("ERROR: this service has no /api/v1/describe (update and restart it).", file=sys.stderr)
        return 1

    detector = load_json(args.detector)
    plan = []
    for entry in load_json(args.examples):
        image_id = str(entry["image_id"])
        if any(p[0] == image_id for p in plan):
            continue
        if image_id not in detector["predictions"]:
            print(f"ERROR: the detector file has no prediction for image {image_id}", file=sys.stderr)
            return 1
        path = os.path.join(args.images_dir, entry["file_name"])
        media_type = MEDIA_TYPES.get(os.path.splitext(path)[1].lower())
        if not os.path.isfile(path) or media_type is None:
            print(f"ERROR: image not found or not JPEG/PNG: {path}", file=sys.stderr)
            return 1
        regions = [d["bounding_box"] for d in detector["predictions"][image_id].get("damages", [])]
        plan.append((image_id, path, media_type, regions))

    meta = {"model": info.get("model"), "model_client": info.get("model_client"),
            "prompt_version": info.get("region_prompt_version"), "mode": "hybrid",
            "detector": detector.get("meta", {}), "examples": args.examples}
    if os.path.exists(args.out):
        doc = load_json(args.out)
        for key in ("model", "prompt_version", "mode"):
            if doc.get("meta", {}).get(key) != meta[key]:
                print(f"ERROR: {args.out} was produced with a different {key}. Use a new --out file.",
                      file=sys.stderr)
                return 1
    else:
        doc = {"meta": {**meta, "started": datetime.now(timezone.utc).isoformat(timespec="seconds")},
               "predictions": {}}

    todo = [p for p in plan if p[0] not in doc["predictions"]]
    print(f"{len(plan)} images, {len(plan) - len(todo)} already done, {len(todo)} to send "
          f"(model: {meta['model']}, region prompt {meta['prompt_version']})")
    for n, (image_id, path, media_type, regions) in enumerate(todo, start=1):
        started = time.monotonic()
        try:
            status, result = post_regions(args.api, path, media_type, regions, args.timeout)
        except (urllib.error.URLError, OSError) as e:
            print(f"ERROR: lost connection at image {image_id}: {e}. Re-run the same command to continue.",
                  file=sys.stderr)
            return 1
        doc["predictions"][image_id] = result
        doc["meta"]["updated"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
        write_json(args.out, doc)
        print(f"[{n}/{len(todo)}] {image_id}: {len(regions)} regions -> HTTP {status}, "
              f"status={result.get('status')}, damages={len(result.get('damages', []))}, "
              f"{time.monotonic() - started:.1f}s")
    errors = sum(1 for r in doc["predictions"].values() if r.get("status") != "success")
    print(f"done: {len(doc['predictions'])} answers in {args.out}, {errors} with status=error")
    return 0


if __name__ == "__main__":
    sys.exit(main())
