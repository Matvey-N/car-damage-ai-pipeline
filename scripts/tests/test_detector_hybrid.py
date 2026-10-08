import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.dirname(__file__))

import make_yolo_dataset as myd
import predict_detector as pdet
import run_hybrid as rh
from helpers import FakeService


def pool():
    return {"images": [{"id": 1, "file_name": "a.png", "width": 200, "height": 100},
                       {"id": 2, "file_name": "b.png", "width": 200, "height": 100}],
            "categories": [{"id": 1, "name": "glass_shatter"}, {"id": 2, "name": "lamp_broken"},
                           {"id": 3, "name": "crack"}, {"id": 4, "name": "scratch"}],
            "annotations": [{"id": 10, "image_id": 1, "category_id": 4, "bbox": [20, 10, 40, 20]},
                            {"id": 11, "image_id": 2, "category_id": 2, "bbox": [0, 0, 100, 50]}]}


class YoloDatasetTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.images = os.path.join(self.tmp, "images")
        os.makedirs(self.images)
        for name in ("a.png", "b.png"):
            with open(os.path.join(self.images, name), "wb") as f:
                f.write(b"png")

    def test_dev_sample_is_val_rest_is_train_and_labels_are_yolo(self):
        out = os.path.join(self.tmp, "yolo")
        counts = myd.build(pool(), [{"image_id": 2, "file_name": "b.png"}], self.images, out)
        self.assertEqual((counts["train"], counts["val"]), (1, 1))
        with open(os.path.join(out, "labels", "train", "a.txt")) as f:
            # scratch = class 3; centre (40, 20) of 200x100 -> 0.2, 0.2; size 0.2 x 0.2
            self.assertEqual(f.read().strip(), "3 0.200000 0.200000 0.200000 0.200000")
        self.assertTrue(os.path.exists(os.path.join(out, "images", "val", "b.png")))
        with open(os.path.join(out, "data.yaml")) as f:
            self.assertIn("1: lamp_broken", f.read())

    def test_test_pool_is_refused(self):
        self.assertEqual(myd.main(["--pool", "x/test_pool.json", "--examples", "e.json",
                                   "--images-dir", self.images, "--out-dir", self.tmp]), 1)


class DetectorOutputTest(unittest.TestCase):
    def test_centre_boxes_become_corner_boxes_clipped_to_the_image(self):
        names = {0: "glass_shatter", 1: "lamp_broken", 2: "crack", 3: "scratch"}
        d = pdet.to_damages([[0.5, 0.5, 0.2, 0.4], [0.98, 0.5, 0.1, 0.1]], [0.4, 0.9], [3, 1], names)
        self.assertEqual(d[0]["damage_type"], "lamp_broken", "sorted by confidence")
        self.assertEqual(d[1]["bounding_box"], [0.4, 0.3, 0.2, 0.4])
        x, _, w, _ = d[0]["bounding_box"]
        self.assertLessEqual(x + w, 1.0 + 1e-9)


class HybridRunTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.images = os.path.join(self.tmp, "images")
        os.makedirs(self.images)
        with open(os.path.join(self.images, "a.png"), "wb") as f:
            f.write(b"png")
        self.examples = os.path.join(self.tmp, "ex.json")
        with open(self.examples, "w") as f:
            json.dump([{"image_id": 1, "file_name": "a.png"}], f)
        self.detector = os.path.join(self.tmp, "det.json")
        with open(self.detector, "w") as f:
            json.dump({"meta": {"model": "detector:x"}, "predictions": {"1": {"status": "success", "damages": [
                {"damage_type": "scratch", "confidence": 0.7, "bounding_box": [0.1, 0.2, 0.3, 0.4]}]}}}, f)
        self.out = os.path.join(self.tmp, "hybrid.json")
        self.service = FakeService()

    def tearDown(self):
        self.service.stop()

    def test_detector_boxes_are_sent_as_regions(self):
        code = rh.main(["--detector", self.detector, "--examples", self.examples, "--images-dir", self.images,
                        "--out", self.out, "--api", self.service.url])
        self.assertEqual(code, 0)
        self.assertIn(b"[[0.1, 0.2, 0.3, 0.4]]", self.service.describe_bodies[0])
        with open(self.out) as f:
            doc = json.load(f)
        self.assertEqual(doc["meta"]["mode"], "hybrid")
        self.assertEqual(doc["meta"]["prompt_version"], "r1")
        self.assertEqual(doc["predictions"]["1"]["status"], "success")


if __name__ == "__main__":
    unittest.main()
