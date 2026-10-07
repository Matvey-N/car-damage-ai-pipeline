import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.dirname(__file__))
import build_ground_truth as bgt  # noqa: E402
import compare_labels as cmp  # noqa: E402
import make_labeling_sheet as mls  # noqa: E402
from helpers import fake_coco  # noqa: E402
from labels_io import read_sheet, write_sheet  # noqa: E402


def examples_for(ids):
    return [{"image_id": i, "file_name": f"{i:06d}.jpg", "class_selected_for": "crack"} for i in ids]


def filled(rows, part="door", severity="moderate", action="repair"):
    out = []
    for r in rows:
        r = dict(r)
        r.update(part=part, severity=severity, action=action)
        out.append(r)
    return out


class LabelingSheetTest(unittest.TestCase):
    def setUp(self):
        self.coco = fake_coco(10, 1)
        self.examples = examples_for([2, 5, 7])

    def test_one_row_per_annotation_of_selected_images(self):
        rows = mls.build_rows(self.coco, self.examples)
        expected = [a for a in self.coco["annotations"] if a["image_id"] in (2, 5, 7)]
        self.assertEqual(len(rows), len(expected))
        self.assertTrue(all(not r.get("part") for r in rows), "label columns must start empty")

    def test_unknown_image_is_an_error(self):
        with self.assertRaises(ValueError):
            mls.build_rows(self.coco, examples_for([999]))

    def test_sheet_round_trip_through_csv(self):
        tmp = os.path.join(tempfile.mkdtemp(), "sheet.csv")
        write_sheet(tmp, mls.build_rows(self.coco, self.examples))
        rows = read_sheet(tmp)
        self.assertEqual(rows[0]["damage_type"] in [c["name"] for c in self.coco["categories"]], True)

    def test_comma_separated_sheet_is_also_read(self):
        tmp = os.path.join(tempfile.mkdtemp(), "sheet.csv")
        with open(tmp, "w", encoding="utf-8") as f:
            f.write("image_id,annotation_id,part\n1,7,door\n")
        self.assertEqual(read_sheet(tmp)[0]["part"], "door")


class BuildGroundTruthTest(unittest.TestCase):
    def setUp(self):
        self.coco = fake_coco(10, 1)
        self.examples = examples_for([2, 5])
        tmp = os.path.join(tempfile.mkdtemp(), "s.csv")
        write_sheet(tmp, mls.build_rows(self.coco, self.examples))
        self.rows = read_sheet(tmp)

    def test_complete_sheet_builds_ground_truth(self):
        gt, errors = bgt.build(self.coco, self.examples, filled(self.rows))
        self.assertEqual(errors, [])
        self.assertEqual([img["image_id"] for img in gt["images"]], ["2", "5"])
        self.assertEqual(gt["images"][0]["width"], 1000)
        self.assertEqual(gt["images"][0]["damages"][0]["part"], "door")

    def test_missing_label_is_rejected(self):
        _, errors = bgt.build(self.coco, self.examples, filled(self.rows)[1:])
        self.assertTrue(any("has no row" in e for e in errors))

    def test_invalid_value_is_rejected(self):
        rows = filled(self.rows)
        rows[0]["severity"] = "very bad"
        _, errors = bgt.build(self.coco, self.examples, rows)
        self.assertTrue(any("severity" in e for e in errors))

    def test_empty_value_is_rejected(self):
        _, errors = bgt.build(self.coco, self.examples, self.rows)  # nothing filled in
        self.assertTrue(errors)

    def test_edited_damage_type_is_rejected(self):
        rows = filled(self.rows)
        rows[0]["damage_type"] = "scratch" if rows[0]["damage_type"] != "scratch" else "crack"
        _, errors = bgt.build(self.coco, self.examples, rows)
        self.assertTrue(any("must not be edited" in e for e in errors))

    def test_row_from_another_image_is_rejected(self):
        rows = filled(self.rows)
        extra = dict(rows[0])
        other = next(a for a in self.coco["annotations"] if a["image_id"] == 9)
        extra["annotation_id"] = str(other["id"])
        _, errors = bgt.build(self.coco, self.examples, rows + [extra])
        self.assertTrue(any("does not belong" in e for e in errors))


class CompareLabelsTest(unittest.TestCase):
    def test_full_agreement(self):
        rows = [{"image_id": "1", "file_name": "x", "annotation_id": str(i), "damage_type": "crack",
                 "part": p, "severity": "minor", "action": "repair"}
                for i, p in enumerate(["door", "hood", "door", "bumper"])]
        stats, dis, _, _ = cmp.compare(rows, [dict(r) for r in rows])
        self.assertEqual(stats["part"]["agreement"], 1.0)
        self.assertEqual(stats["part"]["kappa"], 1.0)
        self.assertEqual(dis, [])

    def test_disagreement_is_listed(self):
        a = [{"image_id": "1", "file_name": "x", "annotation_id": "1", "damage_type": "crack",
              "part": "door", "severity": "minor", "action": "repair"}]
        b = [dict(a[0], severity="severe")]
        stats, dis, _, _ = cmp.compare(a, b)
        self.assertEqual(stats["severity"]["agreement"], 0.0)
        self.assertEqual(len(dis), 1)
        self.assertEqual((dis[0]["annotator_a"], dis[0]["annotator_b"]), ("minor", "severe"))

    def test_kappa_known_value(self):
        # 10 items: A says yes 5/no 5, B yes 5/no 5, agree on 8 -> po=0.8, pe=0.5, kappa=0.6
        pairs = [("y", "y")] * 4 + [("n", "n")] * 4 + [("y", "n"), ("n", "y")]
        self.assertAlmostEqual(cmp.cohens_kappa(pairs), 0.6)


if __name__ == "__main__":
    unittest.main()


class LabelingPageTest(unittest.TestCase):
    def setUp(self):
        import make_labeling_page as mlp
        self.mlp = mlp
        self.tmp = tempfile.mkdtemp()
        coco = fake_coco(10, 1)
        self.sheet = os.path.join(self.tmp, "labels_dev_A.csv")
        write_sheet(self.sheet, mls.build_rows(coco, examples_for([2, 5])))
        self.images = os.path.join(self.tmp, "data", "images")
        os.makedirs(self.images)
        for i in (2, 5):
            open(os.path.join(self.images, f"{i:06d}.jpg"), "wb").close()
        self.out_dir = os.path.join(self.tmp, "benchmark")
        os.makedirs(self.out_dir)

    def test_page_contains_rows_options_and_relative_image_path(self):
        out = os.path.join(self.out_dir, "page.html")
        self.assertEqual(self.mlp.main(["--sheet", self.sheet, "--images-dir", self.images, "--out", out]), 0)
        html = open(out, encoding="utf-8").read()
        self.assertIn('"image_base": "../data/images/"', html)
        self.assertIn('"sheet": "labels_dev_A.csv"', html)
        self.assertIn('"severity": ["minor", "moderate", "severe"]', html)
        self.assertNotIn("__DATA__", html)

    def test_missing_image_is_an_error(self):
        os.remove(os.path.join(self.images, "000005.jpg"))
        out = os.path.join(self.out_dir, "page.html")
        self.assertEqual(self.mlp.main(["--sheet", self.sheet, "--images-dir", self.images, "--out", out]), 1)

    def test_script_tag_cannot_be_closed_by_data(self):
        rows = read_sheet(self.sheet)
        rows[0]["comment"] = "</script><b>x"
        html = self.mlp.build_page(rows, "s.csv", "img/")
        self.assertEqual(html.count("</script>"), 1)
