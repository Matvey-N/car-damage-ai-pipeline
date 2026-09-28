import json
import os
import random
import subprocess
import sys
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(__file__), "..", "select_benchmark_samples.py")
CLASSES = ["dent", "scratch", "crack", "glass_shatter", "tire_flat", "lamp_broken"]


def fake_coco(n_images, seed):
    rng = random.Random(seed)
    categories = [{"id": i + 1, "name": c} for i, c in enumerate(CLASSES)]
    images = [{"id": i, "file_name": f"{i:06d}.jpg"} for i in range(n_images)]
    annotations, ann_id = [], 0
    for img in images:
        for cat in rng.sample(categories, rng.randint(1, 2)):
            annotations.append({"id": ann_id, "image_id": img["id"], "category_id": cat["id"]})
            ann_id += 1
    return {"images": images, "annotations": annotations, "categories": categories}


class SelectSamplesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.val = os.path.join(self.tmp, "val.json")
        self.test = os.path.join(self.tmp, "test.json")
        with open(self.val, "w") as f:
            json.dump(fake_coco(80, 1), f)
        with open(self.test, "w") as f:
            json.dump(fake_coco(60, 2), f)

    def run_script(self, out_dir, seed=42):
        subprocess.run([sys.executable, SCRIPT, "--val-annotations", self.val,
                        "--test-annotations", self.test, "--seed", str(seed), "--out-dir", out_dir],
                       check=True, capture_output=True)
        with open(os.path.join(out_dir, "dev_examples.json")) as f:
            dev = json.load(f)
        with open(os.path.join(out_dir, "test_examples.json")) as f:
            test = json.load(f)
        return dev, test

    def test_sizes_and_stratification(self):
        dev, test = self.run_script(os.path.join(self.tmp, "a"))
        self.assertEqual(len(dev), 18)
        self.assertEqual(len(test), 24)
        for cls in CLASSES:
            self.assertEqual(sum(1 for e in dev if e["class_selected_for"] == cls), 3)
            self.assertEqual(sum(1 for e in test if e["class_selected_for"] == cls), 4)

    def test_no_image_selected_twice_within_a_split(self):
        dev, test = self.run_script(os.path.join(self.tmp, "b"))
        self.assertEqual(len({e["image_id"] for e in dev}), 18)
        self.assertEqual(len({e["image_id"] for e in test}), 24)

    def test_same_seed_gives_same_selection(self):
        first = self.run_script(os.path.join(self.tmp, "c"), seed=42)
        second = self.run_script(os.path.join(self.tmp, "d"), seed=42)
        self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main()
