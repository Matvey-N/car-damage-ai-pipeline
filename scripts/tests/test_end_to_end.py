"""
The whole benchmark chain on synthetic data:
select sample -> labeling sheets (2 annotators) -> compare -> ground truth
-> run sample through a fake service -> evaluate.
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, HERE)
import build_ground_truth as bgt  # noqa: E402
import compare_labels as cmp  # noqa: E402
import evaluate as ev  # noqa: E402
import make_labeling_sheet as mls  # noqa: E402
import run_benchmark as rb  # noqa: E402
from helpers import FakeService, fake_coco, write, write_fake_images  # noqa: E402
from labels_io import load_json, read_sheet, write_sheet  # noqa: E402

SCRIPTS = os.path.join(HERE, "..")


class EndToEndTest(unittest.TestCase):
    def test_full_chain(self):
        tmp = tempfile.mkdtemp()
        val, test = fake_coco(80, 1), fake_coco(60, 2)
        write(os.path.join(tmp, "val.json"), val)
        write(os.path.join(tmp, "test.json"), test)

        # 1. fixed sample
        subprocess.run([sys.executable, os.path.join(SCRIPTS, "select_benchmark_samples.py"),
                        "--val-annotations", os.path.join(tmp, "val.json"),
                        "--test-annotations", os.path.join(tmp, "test.json"),
                        "--seed", "42", "--out-dir", tmp], check=True, capture_output=True)
        dev = os.path.join(tmp, "dev_examples.json")

        # 2. two labeling sheets, filled independently (B disagrees on one severity)
        for name in ("A", "B"):
            self.assertEqual(mls.main(["--annotations", os.path.join(tmp, "val.json"),
                                       "--examples", dev, "--out", os.path.join(tmp, f"{name}.csv")]), 0)
        for name in ("A", "B"):
            rows = read_sheet(os.path.join(tmp, f"{name}.csv"))
            for i, r in enumerate(rows):
                r.update(part="door", severity="moderate", action="repair")
                if name == "B" and i == 0:
                    r["severity"] = "severe"
            write_sheet(os.path.join(tmp, f"{name}.csv"), rows)

        # 3. compare -> one disagreement
        stats, dis, _, _ = cmp.compare(read_sheet(os.path.join(tmp, "A.csv")),
                                       read_sheet(os.path.join(tmp, "B.csv")))
        self.assertEqual(len(dis), 1)

        # 4. after discussion A is taken as final -> ground truth
        gt_path = os.path.join(tmp, "gt.json")
        self.assertEqual(bgt.main(["--annotations", os.path.join(tmp, "val.json"), "--examples", dev,
                                   "--labels", os.path.join(tmp, "A.csv"), "--out", gt_path]), 0)
        gt = load_json(gt_path)
        self.assertEqual(len(gt["images"]), 18)

        # 5. run the sample through a fake service
        images = os.path.join(tmp, "images")
        write_fake_images(images, val, ids={e["image_id"] for e in load_json(dev)})
        service = FakeService()
        try:
            pred_path = os.path.join(tmp, "pred.json")
            self.assertEqual(rb.main(["--examples", dev, "--images-dir", images,
                                      "--out", pred_path, "--api", service.url]), 0)
        finally:
            service.stop()

        # 6. evaluate
        report = ev.evaluate(gt, load_json(pred_path))
        self.assertEqual(report["images_total"], 18)
        self.assertEqual(report["images_pipeline_error_or_missing"], 0)
        total_gt = sum(len(img["damages"]) for img in gt["images"])
        d = report["detection"]
        self.assertEqual(d["tp"] + d["fn"], total_gt)
        self.assertEqual(d["tp"] + d["fp"], 18)  # the fake service returns one damage per image


if __name__ == "__main__":
    unittest.main()
