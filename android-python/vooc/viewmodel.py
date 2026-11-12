"""View-models: the only path through which the UI changes data.

    UI widget --(user edit)--> LayerViewModel.set(path, value)
                                   │  validates via the model (_coerce)
                                   ├─ notifies observers  -> other widgets / renderer refresh
                                   └─ schedules autosave  -> ProjectStore.save (debounced)

    Renderer  --(every frame)--> LayerModel.values  (read-only, no copies)

The view-models are free of Kivy imports so they are unit-testable; the app
injects a `schedule(callback, delay)` function (Kivy Clock on device).
"""
from .property_schema import MODULES, MODULE_INFO, field_spec, iter_fields, SCHEMA


class Observable:
    def __init__(self):
        self._observers = []

    def subscribe(self, callback):
        self._observers.append(callback)
        return lambda: self._observers.remove(callback) if callback in self._observers else None

    def emit(self, *args):
        for callback in list(self._observers):
            callback(*args)


class LayerViewModel(Observable):
    def __init__(self, project_vm, layer):
        super().__init__()
        self.project_vm = project_vm
        self.layer = layer

    @property
    def module(self):
        return self.layer.module

    @property
    def title(self):
        return self.module

    @property
    def info(self):
        return MODULE_INFO.get(self.module, "")

    def categories(self):
        """Structure for the accordion: [(category, [(sub, [(path, name, spec)])])]."""
        out = []
        for category, subs in SCHEMA[self.module].items():
            sub_list = []
            for sub, props in subs.items():
                sub_list.append((sub, [("/".join((category, sub, name)), name, spec)
                                       for name, spec in props.items()]))
            out.append((category, sub_list))
        return out

    def get(self, path):
        return self.layer.get(path)

    def spec(self, path):
        return field_spec(self.module, path)

    def set(self, path, value):
        if self.layer.set(path, value):
            self.emit(path, self.layer.get(path))
            self.project_vm.changed(self.layer, path)
            # Uniform scale: keep Y locked to X
            if path.endswith("/Scale X") and self.layer.get("General/Scale & Rotation/Scale Is Uniform"):
                self.set(path.replace("Scale X", "Scale Y"), self.layer.get(path))

    def reset(self, category=None):
        self.layer.reset(category)
        self.emit(None, None)
        self.project_vm.changed(self.layer, None)

    # ---- custom shader variables ------------------------------------------
    VAR_PATH = "Variables/Variable Mappings/Mappings"

    def variables(self):
        return list(self.layer.get(self.VAR_PATH)) if self.module == "CUSTOMSHADER" else []

    def add_variable(self):
        kind = self.layer.get("Variables/Declare/New Variable")
        name = "".join(c for c in self.layer.get("Variables/Declare/New Variable Name")
                       if c.isalnum() or c == "_")
        if kind == "None" or not name or name[0].isdigit():
            return "Pick a type and a valid GLSL name (letters, digits, _)."
        current = self.variables()
        if any(v["name"] == name for v in current):
            return "A variable named %s already exists." % name
        default = {"Float": 0.5, "Vector2": [0.5, 0.5], "Vector3": [0.5, 0.5, 0.5],
                   "Color": "#FFFFFFFF"}[kind]
        current.append({"name": name, "type": kind, "value": default})
        self.set(self.VAR_PATH, current)
        return None

    def update_variable(self, name, value):
        current = self.variables()
        for var in current:
            if var["name"] == name:
                var["value"] = value
        self.set(self.VAR_PATH, current)

    def remove_variable(self, name):
        self.set(self.VAR_PATH, [v for v in self.variables() if v["name"] != name])


class ProjectViewModel(Observable):
    """Owns the ProjectModel, layer view-models and autosave."""

    def __init__(self, project, store=None, schedule=None, save_delay=0.6):
        super().__init__()
        self.project = project
        self.store = store
        self.schedule = schedule
        self.save_delay = save_delay
        self._save_pending = False
        self._layer_vms = {}
        self.revision = 0            # bumps on every change; renderer can cache on it

    def layer_vm(self, module):
        layer = self.project.layer(module)
        vm = self._layer_vms.get(layer.id)
        if vm is None:
            vm = self._layer_vms[layer.id] = LayerViewModel(self, layer)
        return vm

    def menu_items(self):
        return [(m, self.project.layer(m)) for m in MODULES]

    def changed(self, layer, path):
        self.revision += 1
        self.emit(layer, path)
        self.request_save()

    def request_save(self):
        if self.store is None:
            return
        if self.schedule is None:
            self.save_now()
            return
        if not self._save_pending:
            self._save_pending = True
            self.schedule(lambda *_: self.save_now(), self.save_delay)

    def save_now(self):
        self._save_pending = False
        if self.store is not None:
            try:
                self.store.save(self.project)
            except OSError:
                pass

    def all_paths(self, module):
        return [p for p, _ in iter_fields(module)]
