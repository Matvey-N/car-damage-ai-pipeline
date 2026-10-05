#!/usr/bin/env python3
"""
Sends every image of a fixed benchmark sample, one at a time, to the
running service (POST /api/v1/analyze) and saves the answers in the
format evaluate.py reads.

Safety checks:
  * Refuses to run against the stub model unless --allow-stub is given,
    so stub answers cannot end up in a real benchmark by mistake.
  * Checks all image files before sending anything (exist, JPEG/PNG,
    <= 40 MB; the service scales large photos down before the API call).
  * Saves after every image. Re-running with the same --out continues
    where it stopped (images that already have an answer are skipped),
    but only if the model and prompt version are the same as before.

Usage (service must be running, see README):
  python run_benchmark.py --examples ../benchmark/dev_examples.json \
      --images-dir ../data/cardd/val2017 --out ../benchmark/predictions_dev.json
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

MAX_IMAGE_BYTES = 40 * 1024 * 1024  # service upload limit; it downscales before the API call
MEDIA_TYPES = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png"}


def get_info(api):
    with urllib.request.urlopen(api.rstrip("/") + "/api/v1/info", timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def post_image(api, path, media_type, timeout):
    """Returns (http_status, parsed_json_body). Raises URLError on connection problems."""
    boundary = uuid.uuid4().hex
    with open(path, "rb") as f:
        data = f.read()
    body = (f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="image"; filename="{os.path.basename(path)}"\r\n'
            f"Content-Type: {media_type}\r\n\r\n").encode("utf-8") + data + f"\r\n--{boundary}--\r\n".encode("utf-8")
    request = urllib.request.Request(api.rstrip("/") + "/api/v1/analyze", data=body, method="POST",
                                     headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:  # 400 / 502 still carry a DamageAssessment JSON body
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"status": "error", "damages": [], "overall_score": 0,
                            "error_message": f"HTTP {e.code}: {raw[:300]}"}


def check_images(examples, images_dir):
    problems, plan, seen = [], [], set()
    for entry in examples:
        image_id = str(entry["image_id"])
        if image_id in seen:
            continue
        seen.add(image_id)
        path = os.path.join(images_dir, entry["file_name"])
        media_type = MEDIA_TYPES.get(os.path.splitext(path)[1].lower())
        if not os.path.isfile(path):
            problems.append(f"{image_id}: file not found: {path}")
        elif media_type is None:
            problems.append(f"{image_id}: not a JPEG/PNG file: {path}")
        elif os.path.getsize(path) > MAX_IMAGE_BYTES:
            problems.append(f"{image_id}: larger than 40 MB: {path}")
        else:
            plan.append((image_id, path, media_type))
    return plan, problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--examples", required=True, help="dev_examples.json or test_examples.json")
    parser.add_argument("--images-dir", required=True, help="folder with the images of that split")
    parser.add_argument("--out", required=True, help="predictions JSON (created or continued)")
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--timeout", type=int, default=600, help="seconds per image, incl. retries")
    parser.add_argument("--allow-stub", action="store_true", help="allow running against the stub model")
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

    plan, problems = check_images(load_json(args.examples), args.images_dir)
    if problems:
        print("ERROR: nothing was sent, fix these first:", file=sys.stderr)
        for p in problems:
            print("  - " + p, file=sys.stderr)
        return 1

    meta = {"model": info.get("model"), "model_client": info.get("model_client"),
            "prompt_version": info.get("prompt_version"), "max_attempts": info.get("max_attempts"),
            "examples": args.examples}
    if os.path.exists(args.out):
        doc = load_json(args.out)
        old = doc.get("meta", {})
        for key in ("model", "model_client", "prompt_version"):
            if old.get(key) != meta[key]:
                print(f"ERROR: {args.out} was produced with {key}={old.get(key)}, the service now has "
                      f"{key}={meta[key]}. Use a new --out file.", file=sys.stderr)
                return 1
    else:
        doc = {"meta": {**meta, "started": datetime.now(timezone.utc).isoformat(timespec="seconds")},
               "predictions": {}}

    todo = [p for p in plan if p[0] not in doc["predictions"]]
    print(f"{len(plan)} images in sample, {len(plan) - len(todo)} already done, {len(todo)} to send "
          f"(model: {meta['model']}, prompt {meta['prompt_version']})")

    for n, (image_id, path, media_type) in enumerate(todo, start=1):
        started = time.monotonic()
        try:
            status, result = post_image(args.api, path, media_type, args.timeout)
        except (urllib.error.URLError, OSError) as e:
            print(f"ERROR: lost connection to the service at image {image_id}: {e}. "
                  f"Progress is saved, re-run the same command to continue.", file=sys.stderr)
            return 1
        doc["predictions"][image_id] = result
        doc["meta"]["updated"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
        write_json(args.out, doc)
        print(f"[{n}/{len(todo)}] {image_id}: HTTP {status}, status={result.get('status')}, "
              f"damages={len(result.get('damages', []))}, attempts={result.get('attempts')}, "
              f"{time.monotonic() - started:.1f}s")

    errors = sum(1 for r in doc["predictions"].values() if r.get("status") != "success")
    print(f"done: {len(doc['predictions'])} answers in {args.out}, {errors} with status=error")
    return 0


if __name__ == "__main__":
    sys.exit(main())
