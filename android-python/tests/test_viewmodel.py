import tempfile
import unittest

from vooc.model import ProjectModel, ProjectStore
from vooc.viewmodel import ProjectViewModel


class ViewModelTests(unittest.TestCase):
    def setUp(self):
        self.project = ProjectModel()
        self.scheduled = []
        self.pvm = ProjectViewModel(self.project, None,
                                    schedule=lambda cb, d: self.scheduled.append(cb))

    def test_set_notifies_observers(self):
        vm = self.pvm.layer_vm("BARS")
        seen = []
        vm.subscribe(lambda path, value: seen.append((path, value)))
        vm.set("Bars Setup/Bars/Bar Width", 0.4)
        self.assertEqual(seen, [("Bars Setup/Bars/Bar Width", 0.4)])
        self.assertEqual(self.project.layer("BARS").get("Bars Setup/Bars/Bar Width"), 0.4)
        self.assertEqual(self.pvm.revision, 1)

    def test_unchanged_value_does_not_notify(self):
        vm = self.pvm.layer_vm("BARS")
        seen = []
        vm.subscribe(lambda *a: seen.append(a))
        vm.set("Bars Setup/Bars/Bar Width", vm.get("Bars Setup/Bars/Bar Width"))
        self.assertEqual(seen, [])

    def test_uniform_scale_sync(self):
        vm = self.pvm.layer_vm("IMAGE")
        vm.set("General/Scale & Rotation/Scale X", 2.5)
        self.assertEqual(vm.get("General/Scale & Rotation/Scale Y"), 2.5)

    def test_autosave_is_debounced(self):
        with tempfile.TemporaryDirectory() as d:
            store = ProjectStore(d)
            pvm = ProjectViewModel(self.project, store, schedule=lambda cb, delay: self.scheduled.append(cb))
            vm = pvm.layer_vm("TEXT")
            vm.set("Content/Text/Text Input", "A")
            vm.set("Content/Text/Text Input", "B")
            self.assertEqual(len(self.scheduled), 1)
            self.scheduled[0]()
            self.assertEqual(store.load().layer("TEXT").get("Content/Text/Text Input"), "B")

    def test_reset_category(self):
        vm = self.pvm.layer_vm("BARS")
        vm.set("Bars Setup/Bars/Bar Width", 0.1)
        vm.set("General/Position/Position X", 0.1)
        vm.reset("Bars Setup")
        self.assertEqual(vm.get("Bars Setup/Bars/Bar Width"), 0.7)
        self.assertEqual(vm.get("General/Position/Position X"), 0.1)

    def test_shader_variables(self):
        vm = self.pvm.layer_vm("CUSTOMSHADER")
        vm.set("Variables/Declare/New Variable Name", "glow")
        vm.set("Variables/Declare/New Variable", "Color")
        self.assertIsNone(vm.add_variable())
        self.assertIsNotNone(vm.add_variable())          # duplicate rejected
        vm.update_variable("glow", "#FF000080")
        names = {v["name"]: v for v in vm.variables()}
        self.assertEqual(names["glow"]["value"], "#FF000080")
        vm.remove_variable("glow")
        self.assertNotIn("glow", [v["name"] for v in vm.variables()])

    def test_invalid_variable_name(self):
        vm = self.pvm.layer_vm("CUSTOMSHADER")
        vm.set("Variables/Declare/New Variable Name", "9bad")
        self.assertIsNotNone(vm.add_variable())


if __name__ == "__main__":
    unittest.main()
