"""Shared test fixtures: synthetic COCO data and a fake analysis service."""
import json
import os
import random
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from schema_values import DAMAGE_TYPES as CLASSES  # noqa: E402


def fake_coco(n_images, seed, width=1000, height=800):
    rng = random.Random(seed)
    categories = [{"id": i + 1, "name": c} for i, c in enumerate(CLASSES)]
    images = [{"id": i, "file_name": f"{i:06d}.jpg", "width": width, "height": height} for i in range(n_images)]
    annotations, ann_id = [], 1
    for img in images:
        for cat in rng.sample(categories, rng.randint(1, 2)):
            annotations.append({"id": ann_id, "image_id": img["id"], "category_id": cat["id"],
                                "bbox": [100.0 + 10 * (ann_id % 5), 200.0, 200.0, 150.0]})
            ann_id += 1
    return {"images": images, "annotations": annotations, "categories": categories}


def write(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f)


def write_fake_images(folder, coco, ids=None):
    os.makedirs(folder, exist_ok=True)
    for img in coco["images"]:
        if ids is None or img["id"] in ids:
            with open(os.path.join(folder, img["file_name"]), "wb") as f:
                f.write(b"\xff\xd8\xff fake jpeg")


class FakeService:
    """Mimics /api/v1/info and /api/v1/analyze of the Spring service."""

    def __init__(self, model_client="anthropic", answer=None, fail_after=None):
        self.model_client = model_client
        self.answer = answer or {"status": "success", "overall_score": 30, "attempts": 1, "damages": [
            {"damage_type": "scratch", "part": "door", "severity": "moderate", "action": "repair",
             "confidence": 0.8, "bounding_box": [0.1, 0.25, 0.2, 0.1875]}]}
        self.fail_after = fail_after
        self.analyze_calls = 0
        service = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def _send(self, code, body):
                data = json.dumps(body).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                if self.path == "/api/v1/info":
                    self._send(200, {"model_client": service.model_client,
                                     "model": "claude-opus-5-5" if service.model_client != "stub" else "stub",
                                     "prompt_version": "v1", "max_attempts": 3})
                else:
                    self._send(404, {})

            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(length)
                if self.path != "/api/v1/analyze" or b'name="image"' not in body:
                    self._send(400, {"status": "error", "damages": [], "overall_score": 0})
                    return
                service.analyze_calls += 1
                if service.fail_after is not None and service.analyze_calls > service.fail_after:
                    self._send(502, {"status": "error", "damages": [], "overall_score": 0,
                                     "attempts": 3, "error_message": "fake model failure"})
                    return
                self._send(200, service.answer)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
