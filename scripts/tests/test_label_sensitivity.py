import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import label_sensitivity as ls


def row(ann, severity, action="repair", part="door"):
    return {"annotation_id": str(ann), "part": part, "severity": severity, "action": action}


class LabelSensitivityTest(unittest.TestCase):
    def test_accuracy_against_each_annotator_and_on_agreed_pairs(self):
        gt = {"images": [{"image_id": "1", "width": 100, "height": 100, "damages": [
            {"annotation_id": 1, "bbox": [0, 0, 20, 20]},
            {"annotation_id": 2, "bbox": [50, 50, 20, 20]},
            {"annotation_id": 3, "bbox": [80, 0, 10, 10]}]}]}          # not found by the model
        pred = {"predictions": {"1": {"status": "success", "damages": [
            {"bounding_box": [0, 0, 0.2, 0.2], "confidence": 0.9, "part": "door", "severity": "severe", "action": "repair"},
            {"bounding_box": [0.5, 0.5, 0.2, 0.2], "confidence": 0.8, "part": "door", "severity": "minor", "action": "repair"}]}}}
        a = [row(1, "severe"), row(2, "moderate"), row(3, "minor")]
        b = [row(1, "severe"), row(2, "minor"), row(3, "severe")]

        r = ls.sensitivity(gt, pred, a, b)
        sev = r["severity"]
        self.assertEqual(sev["pairs"], 2, "only matched pairs count; the missed damage is ignored")
        self.assertAlmostEqual(sev["accuracy_vs_a"], 0.5)
        self.assertAlmostEqual(sev["accuracy_vs_b"], 1.0)
        self.assertEqual(sev["a_b_agree"], 1)
        self.assertAlmostEqual(sev["accuracy_where_agree"], 1.0)
        self.assertAlmostEqual(r["part"]["accuracy_vs_a"], 1.0)

    def test_failed_images_are_skipped(self):
        gt = {"images": [{"image_id": "1", "width": 10, "height": 10,
                          "damages": [{"annotation_id": 1, "bbox": [0, 0, 5, 5]}]}]}
        r = ls.sensitivity(gt, {"predictions": {"1": {"status": "error", "damages": []}}},
                           [row(1, "minor")], [row(1, "minor")])
        self.assertEqual(r["severity"]["pairs"], 0)
        self.assertIsNone(r["severity"]["accuracy_vs_a"])


if __name__ == "__main__":
    unittest.main()
