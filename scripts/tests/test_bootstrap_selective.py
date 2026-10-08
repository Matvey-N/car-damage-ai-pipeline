import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import bootstrap
import evaluate
import selective


def gt_two_images():
    return {"images": [
        {"image_id": "1", "width": 100, "height": 100, "damages": [
            {"annotation_id": 1, "bbox": [0, 0, 20, 20], "damage_type": "scratch", "part": "door",
             "severity": "minor", "action": "repair"},
            {"annotation_id": 2, "bbox": [50, 50, 20, 20], "damage_type": "crack", "part": "bumper",
             "severity": "moderate", "action": "repair"}]},
        {"image_id": "2", "width": 100, "height": 100, "damages": [
            {"annotation_id": 3, "bbox": [10, 10, 30, 30], "damage_type": "lamp_broken", "part": "light",
             "severity": "severe", "action": "replacement"}]}]}


def pred(box, conf, dtype="scratch", **fields):
    return dict({"bounding_box": box, "confidence": conf, "damage_type": dtype}, **fields)


class EvaluateMissingFieldsTest(unittest.TestCase):
    def test_detector_output_without_severity_is_evaluated(self):
        preds = {"predictions": {
            "1": {"status": "success", "damages": [pred([0, 0, 0.2, 0.2], 0.9)]},
            "2": {"status": "success", "damages": []}}}
        r = evaluate.evaluate(gt_two_images(), preds)
        self.assertEqual(r["detection"]["tp"], 1)
        self.assertIsNone(r["matched_pairs"]["severity_accuracy_exact"], "no prediction carries severity")
        self.assertAlmostEqual(r["matched_pairs"]["damage_type_accuracy"], 1.0)


class BootstrapTest(unittest.TestCase):
    def test_interval_contains_point_and_is_reproducible(self):
        preds = {"predictions": {
            "1": {"status": "success", "damages": [pred([0, 0, 0.2, 0.2], 0.9)]},
            "2": {"status": "success", "damages": [pred([0.1, 0.1, 0.3, 0.3], 0.8, "lamp_broken")]}}}
        a = bootstrap.bootstrap(gt_two_images(), preds, n=200, seed=1)
        b = bootstrap.bootstrap(gt_two_images(), preds, n=200, seed=1)
        self.assertEqual(a, b)
        rec = a["metrics"]["recall"]
        self.assertLessEqual(rec["ci95_low"], rec["value"])
        self.assertGreaterEqual(rec["ci95_high"], rec["value"])

    def test_comparison_of_identical_methods_is_zero(self):
        preds = {"predictions": {"1": {"status": "success", "damages": [pred([0, 0, 0.2, 0.2], 0.9)]}}}
        r = bootstrap.bootstrap(gt_two_images(), preds, preds, n=100)
        self.assertEqual(r["metrics"]["recall"]["difference"], 0)
        self.assertEqual(r["metrics"]["recall"]["ci95_low"], 0)
        self.assertEqual(r["metrics"]["recall"]["ci95_high"], 0)

    def test_duplicated_image_in_a_resample_is_counted_twice(self):
        gt = {"images": [gt_two_images()["images"][1]]}
        preds = {"predictions": {"2": {"status": "success", "damages": []}}}
        r = bootstrap.bootstrap(gt, preds, n=20)
        self.assertEqual(r["metrics"]["recall"]["ci95_high"], 0.0)


class SelectiveTest(unittest.TestCase):
    def test_raising_the_threshold_drops_the_false_positive(self):
        preds = {"predictions": {
            "1": {"status": "success", "damages": [
                pred([0, 0, 0.2, 0.2], 0.9),                 # true, right type
                pred([0.5, 0.5, 0.2, 0.2], 0.8, "scratch"),  # true, wrong type (crack)
                pred([0.8, 0.8, 0.1, 0.1], 0.3)]},           # false positive, low confidence
            "2": {"status": "success", "damages": [pred([0.1, 0.1, 0.3, 0.3], 0.95, "lamp_broken")]}}}
        records, images, total = selective.collect(gt_two_images(), preds)
        rows = {r["threshold"]: r for r in selective.curve(records, images, total, [0.0, 0.5])}
        self.assertAlmostEqual(rows[0.0]["precision"], 3 / 4)
        self.assertAlmostEqual(rows[0.5]["precision"], 1.0)
        self.assertAlmostEqual(rows[0.5]["coverage"], 3 / 4)
        self.assertAlmostEqual(rows[0.5]["type_accuracy"], 2 / 3)
        self.assertAlmostEqual(rows[0.5]["recall"], 1.0)
        # image 2 is complete and correct; image 1 has a wrong type
        self.assertEqual(rows[0.5]["images_fully_correct_and_accepted"], 1)
        self.assertEqual(rows[0.0]["images_fully_correct_and_accepted"], 1)

    def test_failed_image_counts_its_damages_as_missed(self):
        preds = {"predictions": {"1": {"status": "error", "damages": []},
                                 "2": {"status": "success", "damages": [pred([0.1, 0.1, 0.3, 0.3], 0.9, "lamp_broken")]}}}
        records, images, total = selective.collect(gt_two_images(), preds)
        row = selective.curve(records, images, total, [0.0])[0]
        self.assertAlmostEqual(row["recall"], 1 / 3)


if __name__ == "__main__":
    unittest.main()
