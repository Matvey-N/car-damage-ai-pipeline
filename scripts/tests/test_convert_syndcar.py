import os
import struct
import sys
import tempfile
import unittest
import zlib

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, ".."))
import convert_syndcar as cs  # noqa: E402
import make_labeling_sheet as mls  # noqa: E402


def png_bytes(width, height):
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IEND", b"")


def jpeg_bytes(width, height, orientation=None):
    data = b"\xff\xd8"
    if orientation is not None:
        tiff = b"MM" + struct.pack(">HI", 42, 8) + struct.pack(">H", 1) \
            + struct.pack(">HHIHH", 0x0112, 3, 1, orientation, 0) + struct.pack(">I", 0)
        app1 = b"Exif\x00\x00" + tiff
        data += b"\xff\xe1" + struct.pack(">H", len(app1) + 2) + app1
    sof = struct.pack(">BHHB", 8, height, width, 3) + b"\x01\x11\x00\x02\x11\x00\x03\x11\x00"
    data += b"\xff\xc0" + struct.pack(">H", len(sof) + 2) + sof + b"\xff\xd9"
    return data


def make_syndcar(root, damage_yaml, n_images=8):
    for d in ("images", "labels_damage", "labels_parts"):
        os.makedirs(os.path.join(root, d), exist_ok=True)
    with open(os.path.join(root, "data_damage.yaml"), "w") as f:
        f.write(damage_yaml)
    with open(os.path.join(root, "data_parts.yaml"), "w") as f:
        f.write("path: .\nnames:\n  0: left front quarter panel\n  1: front lights\n  2: roof\n"
                "  3: left rocker panel\n  4: front panel\n  5: left door\n")
    for i in range(n_images):
        name = f"D{i % 3}_img{i:03d}"
        if i == 0:
            with open(os.path.join(root, "images", name + ".jpg"), "wb") as f:
                f.write(jpeg_bytes(1000, 800, orientation=6))
        else:
            with open(os.path.join(root, "images", name + ".png"), "wb") as f:
                f.write(png_bytes(1000, 800))
        with open(os.path.join(root, "labels_damage", name + ".txt"), "w") as f:
            f.write(f"{i % 4} 0.5 0.5 0.2 0.1\n")          # box [400, 360, 200, 80] px
            f.write("3 0.9 0.1 0.05 0.05\n")               # small scratch top right, no part there
        with open(os.path.join(root, "labels_parts", name + ".txt"), "w") as f:
            # left door polygon covers the left half of the central box
            f.write("5 0.3 0.3 0.5 0.3 0.5 0.7 0.3 0.7\n")
            # front lights polygon covers only a sliver of it
            f.write("1 0.59 0.4 0.62 0.4 0.62 0.6 0.59 0.6\n")


YAML_BLOCK_LIST = "nc: 4\nnames:\n- broken glass\n- broken lights\n- cracks\n- scratches\n"


class NamesAndMappingTest(unittest.TestCase):
    def write(self, text):
        path = os.path.join(tempfile.mkdtemp(), "d.yaml")
        with open(path, "w") as f:
            f.write(text)
        return path

    def test_yaml_forms(self):
        expected = {0: "broken glass", 1: "broken lights", 2: "cracks", 3: "scratches"}
        self.assertEqual(cs.read_yaml_names(self.write(YAML_BLOCK_LIST)), expected)
        self.assertEqual(cs.read_yaml_names(self.write(
            "names: ['broken glass', 'broken lights', 'cracks', 'scratches']\n")), expected)
        self.assertEqual(cs.read_yaml_names(self.write(
            "names:\n  0: broken glass\n  1: broken lights\n  2: cracks\n  3: scratches\nnc: 4\n")), expected)

    def test_damage_mapping(self):
        self.assertEqual([cs.map_name(n, cs.DAMAGE_RULES) for n in
                          ["broken glass", "Broken_Lights", "cracks", "scratches"]],
                         ["glass_shatter", "lamp_broken", "crack", "scratch"])

    def test_part_mapping(self):
        cases = {"left front quarter panel": "fender", "rear lights": "light", "roof": None,
                 "left rocker panel": "other", "front panel": "bumper", "front windshield": "windshield",
                 "left rear side window": "window", "right mirror": "mirror", "left front wheel": "wheel",
                 "right door": "door", "license plate": None}
        for name, target in cases.items():
            self.assertEqual(cs.map_name(name, cs.PART_RULES), target, name)


class GeometryAndHeadersTest(unittest.TestCase):
    def test_coverage(self):
        square = [(0, 0), (10, 0), (10, 10), (0, 10)]
        self.assertAlmostEqual(cs.coverage(square, (5, 0, 15, 10)), 0.5)
        self.assertEqual(cs.coverage(square, (20, 20, 30, 30)), 0.0)
        self.assertAlmostEqual(cs.coverage(square, (2, 2, 8, 8)), 1.0)

    def test_image_headers(self):
        tmp = tempfile.mkdtemp()
        p1, p2 = os.path.join(tmp, "a.png"), os.path.join(tmp, "b.jpg")
        with open(p1, "wb") as f:
            f.write(png_bytes(640, 480))
        with open(p2, "wb") as f:
            f.write(jpeg_bytes(4032, 3024, orientation=6))
        self.assertEqual(cs.image_info(p1), (640, 480, None))
        self.assertEqual(cs.image_info(p2), (4032, 3024, 6))


class ConvertTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        make_syndcar(self.root, YAML_BLOCK_LIST)

    def test_boxes_classes_and_auto_part(self):
        dev, test, report = cs.convert(self.root)
        anns = dev["annotations"] + test["annotations"]
        self.assertEqual(report["images"], 8)
        self.assertEqual(report["damage_boxes"], 16)
        central = [a for a in anns if a["bbox"] == [400.0, 360.0, 200.0, 80.0]]
        self.assertEqual(len(central), 8)
        self.assertTrue(all(a["auto_part"] == "door" for a in central))
        self.assertTrue(all(a["auto_part_source"] == "left door" for a in central))
        self.assertAlmostEqual(central[0]["auto_part_coverage"], 0.5, places=2)
        corner = [a for a in anns if a not in central]
        self.assertTrue(all(a["auto_part"] == "" for a in corner), "no part there -> annotator fills in")
        self.assertEqual(report["no_auto_part"], 8)
        self.assertEqual([c["name"] for c in dev["categories"]], ["glass_shatter", "lamp_broken", "crack", "scratch"])

    def test_report_flags_exif_rotation(self):
        _, _, report = cs.convert(self.root)
        self.assertEqual(len(report["images_with_exif_rotation"]), 1)

    def test_split_is_disjoint_complete_and_reproducible(self):
        dev1, test1, _ = cs.convert(self.root, seed=42)
        dev2, test2, _ = cs.convert(self.root, seed=42)
        ids_dev = {i["id"] for i in dev1["images"]}
        ids_test = {i["id"] for i in test1["images"]}
        self.assertEqual(ids_dev & ids_test, set())
        self.assertEqual(len(ids_dev | ids_test), 8)
        self.assertEqual(dev1, dev2)
        self.assertEqual(test1, test2)

    def test_unknown_damage_class_is_an_error(self):
        root = tempfile.mkdtemp()
        make_syndcar(root, "names:\n- broken glass\n- rust\n- cracks\n- scratches\n")
        with self.assertRaises(ValueError):
            cs.convert(root)

    def test_main_writes_pools_and_sheet_prefills_part(self):
        out = os.path.join(tempfile.mkdtemp(), "coco")
        self.assertEqual(cs.main(["--syndcar", self.root, "--out-dir", out]), 0)
        for name in ("dev_pool.json", "test_pool.json", "conversion_report.json"):
            self.assertTrue(os.path.exists(os.path.join(out, name)))
        import json
        with open(os.path.join(out, "dev_pool.json")) as f:
            dev = json.load(f)
        examples = [{"image_id": img["id"], "file_name": img["file_name"], "class_selected_for": "crack"}
                    for img in dev["images"]]
        rows = mls.build_rows(dev, examples)
        prefilled = [r for r in rows if r["part"]]
        self.assertTrue(prefilled)
        self.assertTrue(all("left door" in r["comment"] for r in prefilled))
        self.assertTrue(any(r["part"] == "" for r in rows))


if __name__ == "__main__":
    unittest.main()


class RealSyndcarNamesTest(unittest.TestCase):
    """Class names exactly as in SYNDCAR v1 (data_damage.yaml, data_parts.yaml)."""

    DAMAGE = {"Broken_Glass": "glass_shatter", "Cracks": "crack",
              "Scratches": "scratch", "Broken_Lights": "lamp_broken"}
    PARTS = {
        "left_side_window": "window", "right_side_window": "window", "left_door": "door",
        "right_door": "door", "right_rear_quarter_panel": "fender", "left_rear_quarter_panel": "fender",
        "right_front_quarter_panel": "fender", "left_rear_side_window": "window",
        "right_rear_side_window": "window", "left_front_side_window": "window",
        "right_front_side_window": "window", "roof": "other", "left_rear_wheel": "wheel",
        "right_rear_wheel": "wheel", "left_front_wheel": "wheel", "right_front_wheel": "wheel",
        "front_windshield": "windshield", "rear_windshield": "windshield", "left_mirror": "mirror",
        "right_mirror": "mirror", "license_plate": "other", "rear_lights": "light",
        "front_lights": "light", "front_panel": "bumper", "rear_panel": "bumper",
        "left_front_quarter_panel": "fender", "left_rocker_panel": "other", "right_rocker_panel": "other",
    }

    def test_damage_classes(self):
        for name, target in self.DAMAGE.items():
            self.assertEqual(cs.map_name(name, cs.DAMAGE_RULES), target, name)

    def test_all_28_parts(self):
        self.assertEqual(len(self.PARTS), 28)
        for name, target in self.PARTS.items():
            self.assertEqual(cs.map_name(name, cs.PART_RULES) or "other", target, name)

    def test_yaml_with_numbered_names(self):
        path = os.path.join(tempfile.mkdtemp(), "data_damage.yaml")
        with open(path, "w") as f:
            f.write("path: .\ntrain: ./images/\n\nnc: 4\nnames:\n  0: Broken_Glass\n  1: Cracks\n"
                    "  2: Scratches\n  3: Broken_Lights")
        self.assertEqual(cs.read_yaml_names(path),
                         {0: "Broken_Glass", 1: "Cracks", 2: "Scratches", 3: "Broken_Lights"})


class SessionSplitTest(unittest.TestCase):
    def images(self, names):
        return [{"id": i + 1, "file_name": n} for i, n in enumerate(names)]

    def test_session_key(self):
        self.assertEqual(cs.session_key("ID1_20240917_150956.png", "date"), "date:20240917")
        self.assertEqual(cs.session_key("ID3_20240917_160000.jpg", "date"), "date:20240917")
        self.assertEqual(cs.session_key("photo.png", "date"), "file:photo")
        # Unix time in milliseconds (device ID5): 1726736225270 -> 2024-09-19 UTC
        self.assertEqual(cs.session_key("ID5_1726736225270.png", "date"), "date:20240919")
        self.assertEqual(cs.session_key("ID5_1726736227317.png", "date"), "date:20240919")
        self.assertEqual(cs.session_key("ID1_20240917_150956.png", "none"), "file:ID1_20240917_150956")

    def test_same_day_never_split_across_pools_even_across_devices(self):
        names = []
        for day in ["20240901", "20240902", "20240903", "20240904", "20240905", "20240906"]:
            for device in (1, 2, 3):
                for t in ("100000", "100005", "100010"):
                    names.append(f"ID{device}_{day}_{t}.png")
        imgs = self.images(names)
        dev, groups = cs.split_groups(imgs, "date", seed=42)
        for key, ids in groups.items():
            inside = {i in dev for i in ids}
            self.assertEqual(len(inside), 1, f"group {key} was split between pools")
        self.assertEqual(len(dev), 27)  # 6 days x 9 images, balanced to half

    def test_split_is_reproducible(self):
        imgs = self.images([f"ID1_202409{d:02d}_120000.png" for d in range(1, 21)])
        self.assertEqual(cs.split_groups(imgs, "date", 7)[0], cs.split_groups(imgs, "date", 7)[0])

    def test_convert_report_lists_groups(self):
        root = tempfile.mkdtemp()
        make_syndcar(root, YAML_BLOCK_LIST)
        _, _, report = cs.convert(root)
        self.assertEqual(report["group_by"], "date")
        self.assertTrue(all(k.startswith("file:") for k in report["groups"]))  # synthetic names: no date pattern


class Id5BurstTest(unittest.TestCase):
    def test_burst_of_epoch_named_shots_stays_in_one_pool(self):
        names = [f"ID1_20240917_15{m:02d}00.png" for m in range(30)] \
              + [f"ID2_20240918_10{m:02d}00.png" for m in range(10)] \
              + [f"ID5_17267362{n:05d}.png" for n in range(25270, 27400, 160)]
        imgs = [{"id": i + 1, "file_name": n} for i, n in enumerate(names)]
        dev, groups = cs.split_groups(imgs, "date", seed=42)
        self.assertEqual(sorted(groups), ["date:20240917", "date:20240918", "date:20240919"])
        burst = groups["date:20240919"]
        self.assertEqual(len({i in dev for i in burst}), 1)
