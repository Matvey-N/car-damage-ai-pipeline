import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import agreement as ag
import make_labeling_page as mlp


def sheet(values):
    return [{"image_id": "1", "file_name": "a.png", "annotation_id": str(i), "damage_type": "crack",
             "bbox_x": "0", "bbox_y": "0", "bbox_w": "1", "bbox_h": "1",
             "part": "door", "severity": sev, "action": "repair", "comment": ""} for i, sev in enumerate(values)]


class FleissTest(unittest.TestCase):
    def test_perfect_agreement_is_one(self):
        self.assertAlmostEqual(ag.fleiss_kappa([["a", "a", "a"], ["b", "b", "b"]]), 1.0)

    def test_known_value(self):
        # 4 items, 3 raters, by hand: P_i = 1/3 for every item, P_e = 0.5 -> kappa = -1/3
        ratings = [["a", "a", "b"], ["a", "b", "b"], ["a", "a", "b"], ["a", "b", "b"]]
        self.assertAlmostEqual(ag.fleiss_kappa(ratings), (1 / 3 - 0.5) / 0.5)

    def test_two_raters_matches_scale(self):
        k = ag.fleiss_kappa([["a", "a"], ["b", "b"], ["a", "b"], ["b", "b"]])
        self.assertTrue(-1 <= k <= 1)


class AgreementTest(unittest.TestCase):
    def test_three_annotators_and_majority(self):
        a = sheet(["minor", "moderate", "severe"])
        b = sheet(["minor", "moderate", "moderate"])
        c = sheet(["minor", "severe", "minor"])
        report = ag.agreement([a, b, c])
        self.assertAlmostEqual(report["severity"]["all_agree"], 1 / 3)
        self.assertEqual(set(report["severity"]["cohen_kappa_pairs"]), {"1-2", "1-3", "2-3"})
        self.assertEqual(report["part"]["fleiss_kappa"], None, "everyone used one label: undefined")
        rows, unresolved = ag.majority([a, b, c])
        self.assertEqual([r["severity"] for r in rows], ["minor", "moderate", ""])
        self.assertEqual(len(unresolved), 1)

    def test_two_annotators_tie_is_left_empty(self):
        rows, unresolved = ag.majority([sheet(["minor"]), sheet(["severe"])])
        self.assertEqual(rows[0]["severity"], "")
        self.assertEqual(unresolved[0][1], "severity")

    def test_different_damages_are_refused(self):
        b = sheet(["minor"])
        b[0]["annotation_id"] = "99"
        with self.assertRaises(ValueError):
            ag.agreement([sheet(["minor"]), b])

    def test_empty_field_is_refused(self):
        b = sheet(["minor"])
        b[0]["severity"] = ""
        with self.assertRaises(ValueError):
            ag.agreement([sheet(["minor"]), b])


class RulesPageTest(unittest.TestCase):
    def test_v2_rules_are_shown_on_request(self):
        page = mlp.build_page(sheet(["minor"]), "s.csv", "img/", "v2")
        self.assertIn("Шкала v2", page)
        self.assertIn('lang="ru"', page)
        self.assertNotIn("Шкала v2", mlp.build_page(sheet(["minor"]), "s.csv", "img/"))


if __name__ == "__main__":
    unittest.main()
