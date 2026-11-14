import json
import os
import tempfile
import unittest
from pathlib import Path

from vooc import templates as TP
from vooc.model import ProjectModel


class TemplateV2Test(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.store = TP.TemplateStore(os.path.join(self.tmp, "tpl"))

    def test_migrate_v1(self):
        p = ProjectModel()
        v1 = {"format": "vooc-viz", "version": 1, "name": "Old", "layers": [l.to_dict() for l in p.layers]}
        f = os.path.join(self.tmp, "old.viz")
        Path(f).write_text(json.dumps(v1))
        name = self.store.import_file(f)
        d = self.store.load(name)
        self.assertEqual(d["version"], 2)
        self.assertIn("tags", d["meta"])

    def test_bundle_roundtrip_assets(self):
        p = ProjectModel()
        img = os.path.join(self.tmp, "art.png")
        Path(img).write_bytes(TP.png_bytes(bytes([255, 0, 0, 255] * 4), 2, 2))
        p.layer("IMAGE").set("Image Source/Source/Pick Image", img)
        self.store.save(p, "Mine", meta={"description": "red", "tags": ["test"]},
                        thumbnail=TP.make_thumbnail(bytes(16 * 9 * 4), 16, 9))
        out = self.store.export_bundle("Mine", self.tmp)
        os.remove(img)
        name = self.store.import_file(str(out))
        self.assertEqual(name, "Mine 2")
        d = self.store.load(name)
        self.assertTrue(d["thumbnail"])
        layer = [l for l in d["layers"] if l["module"] == "IMAGE"][0]
        path = layer["values"]["Image Source/Source/Pick Image"]
        self.assertTrue(os.path.isfile(path) and path.startswith(self.store.dir.as_posix()))
        self.assertEqual(d["meta"]["description"], "red")

    def test_library_ops(self):
        p = ProjectModel()
        self.store.save(p, "A")
        self.assertTrue(self.store.toggle_favorite("A"))
        self.assertEqual(self.store.rename("A", "B"), "B")
        self.assertEqual(self.store.favorites(), ["B"])
        self.assertEqual(self.store.duplicate("B"), "B copy")
        self.store.delete("B")
        self.assertEqual(self.store.favorites(), [])
        self.assertEqual(set(self.store.names()), {"B copy"})
        ok, problems = TP.check({"layers": []})
        self.assertFalse(ok)
        with self.assertRaises(ValueError):
            TP.unpack_bundle(b"nope", self.tmp)


if __name__ == "__main__":
    unittest.main()
