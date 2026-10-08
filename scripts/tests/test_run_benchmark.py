import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.dirname(__file__))
import run_benchmark as rb  # noqa: E402
from helpers import FakeService, fake_coco, write, write_fake_images  # noqa: E402


class RunBenchmarkTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.coco = fake_coco(5, 3)
        self.images = os.path.join(self.tmp, "images")
        write_fake_images(self.images, self.coco)
        self.examples = os.path.join(self.tmp, "examples.json")
        write(self.examples, [{"image_id": i, "file_name": f"{i:06d}.jpg", "class_selected_for": "crack"}
                              for i in range(5)])
        self.out = os.path.join(self.tmp, "pred.json")
        self.service = None

    def tearDown(self):
        if self.service:
            self.service.stop()

    def run_main(self, *extra):
        return rb.main(["--examples", self.examples, "--images-dir", self.images,
                        "--out", self.out, "--api", self.service.url, *extra])

    def test_all_images_are_sent_and_saved(self):
        self.service = FakeService()
        self.assertEqual(self.run_main(), 0)
        with open(self.out) as f:
            doc = json.load(f)
        self.assertEqual(sorted(doc["predictions"]), ["0", "1", "2", "3", "4"])
        self.assertEqual(doc["meta"]["model"], "claude-opus-5-5")
        self.assertEqual(doc["meta"]["prompt_version"], "v1")
        self.assertEqual(self.service.analyze_calls, 5)

    def test_tiled_mode_is_requested_and_recorded(self):
        self.service = FakeService()
        self.assertEqual(self.run_main("--mode", "tiled"), 0)
        self.assertEqual(set(self.service.modes), {"tiled"})
        with open(self.out) as f:
            meta = json.load(f)["meta"]
        self.assertEqual(meta["mode"], "tiled")
        self.assertIn("2x2", meta["tiled_mode"])

    def test_relook_mode_records_its_prompt_version(self):
        self.service = FakeService()
        self.assertEqual(self.run_main("--mode", "relook"), 0)
        self.assertEqual(set(self.service.modes), {"relook"})
        with open(self.out) as f:
            meta = json.load(f)["meta"]
        self.assertEqual(meta["mode"], "relook")
        self.assertEqual(meta["relook_prompt_version"], "rl1")

    def test_parallel_workers_send_every_image_once(self):
        self.service = FakeService()
        self.assertEqual(self.run_main("--workers", "3"), 0)
        with open(self.out) as f:
            doc = json.load(f)
        self.assertEqual(sorted(doc["predictions"]), ["0", "1", "2", "3", "4"])
        self.assertEqual(self.service.analyze_calls, 5)

    def test_continuing_a_file_in_another_mode_is_refused(self):
        self.service = FakeService()
        self.assertEqual(self.run_main(), 0)
        self.assertEqual(self.run_main("--mode", "tiled"), 1)

    def test_stub_service_is_refused(self):
        self.service = FakeService(model_client="stub")
        self.assertEqual(self.run_main(), 1)
        self.assertEqual(self.service.analyze_calls, 0)
        self.assertFalse(os.path.exists(self.out))

    def test_stub_allowed_for_dry_run(self):
        self.service = FakeService(model_client="stub")
        self.assertEqual(self.run_main("--allow-stub"), 0)

    def test_error_answers_are_saved_as_errors(self):
        self.service = FakeService(fail_after=3)
        self.assertEqual(self.run_main(), 0)
        with open(self.out) as f:
            doc = json.load(f)
        statuses = [doc["predictions"][k]["status"] for k in ["0", "1", "2", "3", "4"]]
        self.assertEqual(statuses.count("error"), 2)

    def test_rerun_continues_and_skips_done_images(self):
        self.service = FakeService()
        self.run_main()
        self.run_main()
        self.assertEqual(self.service.analyze_calls, 5, "second run must not resend anything")

    def test_missing_file_stops_before_sending(self):
        os.remove(os.path.join(self.images, "000003.jpg"))
        self.service = FakeService()
        self.assertEqual(self.run_main(), 1)
        self.assertEqual(self.service.analyze_calls, 0)

    def test_oversized_file_stops_before_sending(self):
        with open(os.path.join(self.images, "000002.jpg"), "wb") as f:
            f.write(b"\0" * (rb.MAX_IMAGE_BYTES + 1))
        self.service = FakeService()
        self.assertEqual(self.run_main(), 1)
        self.assertEqual(self.service.analyze_calls, 0)

    def test_unreachable_service(self):
        self.service = FakeService()
        url = self.service.url
        self.service.stop()
        self.service = None
        code = rb.main(["--examples", self.examples, "--images-dir", self.images,
                        "--out", self.out, "--api", url])
        self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main()
