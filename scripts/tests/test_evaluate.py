import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import evaluate as ev  # noqa: E402


def gt_image(image_id, damages, width=100, height=100):
    return {"image_id": image_id, "width": width, "height": height, "damages": damages}


def gt_damage(bbox_px, damage_type="crack", part="door", severity="moderate", action="repair"):
    return {"bbox": bbox_px, "damage_type": damage_type, "part": part, "severity": severity, "action": action}


def pred(box, conf=0.9, damage_type="crack", part="door", severity="moderate", action="repair"):
    return {"bounding_box": box, "confidence": conf, "damage_type": damage_type,
            "part": part, "severity": severity, "action": action}


def ok(damages):
    return {"status": "success", "damages": damages, "overall_score": 10}


class IoUTest(unittest.TestCase):
    def test_identical_boxes(self):
        self.assertAlmostEqual(ev.iou([0, 0, 1, 1], [0, 0, 1, 1]), 1.0)

    def test_disjoint_boxes(self):
        self.assertEqual(ev.iou([0, 0, 0.1, 0.1], [0.5, 0.5, 0.1, 0.1]), 0.0)

    def test_partial_overlap(self):
        # 0.5 x 1 overlap, union = 1 + 1 - 0.5 = 1.5
        self.assertAlmostEqual(ev.iou([0, 0, 1, 1], [0.5, 0, 1, 1]), 0.5 / 1.5)

    def test_pixel_box_is_normalized(self):
        self.assertEqual(ev.normalize_box([10, 20, 30, 40], 100, 200), [0.1, 0.1, 0.3, 0.2])


class MatchingTest(unittest.TestCase):
    def test_one_prediction_one_gt(self):
        pairs, ugt, upred = ev.match_image([[0.1, 0.1, 0.2, 0.2]], [pred([0.1, 0.1, 0.2, 0.2])])
        self.assertEqual(len(pairs), 1)
        self.assertEqual((ugt, upred), ([], []))

    def test_duplicate_prediction_is_false_positive(self):
        gt = [[0.1, 0.1, 0.2, 0.2]]
        preds = [pred([0.1, 0.1, 0.2, 0.2], conf=0.6), pred([0.11, 0.1, 0.2, 0.2], conf=0.9)]
        pairs, ugt, upred = ev.match_image(gt, preds)
        self.assertEqual(len(pairs), 1)
        self.assertEqual(pairs[0][1], 1, "the higher-confidence prediction gets the match")
        self.assertEqual(upred, [0], "the second prediction on the same damage is a false positive")

    def test_one_prediction_cannot_match_two_gt(self):
        gt = [[0.1, 0.1, 0.2, 0.2], [0.12, 0.1, 0.2, 0.2]]
        pairs, ugt, upred = ev.match_image(gt, [pred([0.1, 0.1, 0.2, 0.2])])
        self.assertEqual(len(pairs), 1)
        self.assertEqual(len(ugt), 1)

    def test_iou_exactly_at_threshold_matches(self):
        # overlap 0.5x... construct IoU = 0.5: box A [0,0,1,1], box B [0,0,0.5,1] -> 0.5/1.0
        pairs, _, _ = ev.match_image([[0, 0, 1, 1]], [pred([0, 0, 0.5, 1])])
        self.assertEqual(len(pairs), 1)

    def test_iou_below_threshold_does_not_match(self):
        pairs, ugt, upred = ev.match_image([[0, 0, 1, 1]], [pred([0, 0, 0.49, 1])])
        self.assertEqual((len(pairs), ugt, upred), (0, [0], [0]))

    def test_prediction_takes_best_iou_gt(self):
        gt = [[0.0, 0.0, 0.2, 0.2], [0.5, 0.5, 0.2, 0.2]]
        pairs, _, _ = ev.match_image(gt, [pred([0.5, 0.5, 0.2, 0.2])])
        self.assertEqual(pairs[0][0], 1)


class EvaluateTest(unittest.TestCase):
    def test_perfect_prediction(self):
        gt = {"images": [gt_image("a", [gt_damage([10, 10, 20, 20])])]}
        pr = {"predictions": {"a": ok([pred([0.1, 0.1, 0.2, 0.2], conf=1.0)])}}
        r = ev.evaluate(gt, pr)
        self.assertEqual(r["detection"]["recall"], 1.0)
        self.assertEqual(r["detection"]["precision"], 1.0)
        self.assertEqual(r["damage_type"]["macro_f1"], 1.0)
        self.assertEqual(r["matched_pairs"]["severity_accuracy_exact"], 1.0)
        self.assertEqual(r["confidence"]["brier_score"], 0.0)

    def test_wrong_type_is_detection_tp_but_type_error(self):
        gt = {"images": [gt_image("a", [gt_damage([10, 10, 20, 20], damage_type="crack")])]}
        pr = {"predictions": {"a": ok([pred([0.1, 0.1, 0.2, 0.2], damage_type="scratch", conf=0.8)])}}
        r = ev.evaluate(gt, pr)
        self.assertEqual(r["detection"]["tp"], 1)
        self.assertEqual(r["damage_type"]["per_class"]["crack"]["fn"], 1)
        self.assertEqual(r["damage_type"]["per_class"]["scratch"]["fp"], 1)
        self.assertEqual(r["matched_pairs"]["damage_type_accuracy"], 0.0)
        self.assertAlmostEqual(r["confidence"]["brier_score"], 0.64)  # (0.8 - 0)^2

    def test_severity_is_exact_match_only(self):
        gt = {"images": [gt_image("a", [gt_damage([10, 10, 20, 20], severity="moderate")])]}
        pr = {"predictions": {"a": ok([pred([0.1, 0.1, 0.2, 0.2], severity="severe")])}}
        r = ev.evaluate(gt, pr)
        self.assertEqual(r["matched_pairs"]["severity_accuracy_exact"], 0.0)
        self.assertEqual(r["matched_pairs"]["severity_confusion_gt_rows_pred_cols"]["moderate"]["severe"], 1)

    def test_unmatched_predictions_do_not_affect_severity_or_confidence(self):
        gt = {"images": [gt_image("a", [gt_damage([10, 10, 20, 20])])]}
        pr = {"predictions": {"a": ok([pred([0.1, 0.1, 0.2, 0.2], conf=1.0),
                                       pred([0.7, 0.7, 0.2, 0.2], conf=0.1, severity="minor")])}}
        r = ev.evaluate(gt, pr)
        self.assertEqual(r["detection"]["fp"], 1)
        self.assertEqual(r["matched_pairs"]["count"], 1)
        self.assertEqual(r["matched_pairs"]["severity_accuracy_exact"], 1.0)
        self.assertEqual(r["confidence"]["brier_score"], 0.0)

    def test_pipeline_error_counts_all_gt_as_missed(self):
        gt = {"images": [gt_image("a", [gt_damage([10, 10, 20, 20]), gt_damage([50, 50, 10, 10])])]}
        pr = {"predictions": {"a": {"status": "error", "damages": [], "overall_score": 0}}}
        r = ev.evaluate(gt, pr)
        self.assertEqual(r["detection"]["fn"], 2)
        self.assertEqual(r["images_pipeline_error_or_missing"], 1)

    def test_missing_prediction_counts_as_error(self):
        gt = {"images": [gt_image("a", [gt_damage([10, 10, 20, 20])])]}
        r = ev.evaluate(gt, {"predictions": {}})
        self.assertEqual(r["per_image"][0]["status"], "missing")

    def test_no_matches_gives_none_not_crash(self):
        gt = {"images": [gt_image("a", [gt_damage([10, 10, 20, 20])])]}
        pr = {"predictions": {"a": ok([])}}
        r = ev.evaluate(gt, pr)
        self.assertEqual(r["detection"]["recall"], 0.0)
        self.assertIsNone(r["detection"]["precision"])
        self.assertIsNone(r["confidence"]["brier_score"])


if __name__ == "__main__":
    unittest.main()
