"""Reusable hierarchical property editor for Vooc's advanced visualizer settings."""
import json, os
from copy import deepcopy
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.scrollview import ScrollView
from vooc.material import (
    MaterialLabel as Label, MaterialButton as Button, MaterialSlider as Slider,
    MaterialSpinner as Spinner, MaterialTextInput as TextInput,
    MaterialCheckBox as CheckBox, MaterialPopup as Popup,
    SURFACE_HIGH, TEXT
)
from kivy.uix.accordion import Accordion, AccordionItem
from .property_schema import SCHEMA

class AdvancedProperties(BoxLayout):
    """Two-column category home -> accordion inspector; values live in state.properties."""
    def __init__(self, state, on_change=None, on_save=None, **kwargs):
        super().__init__(orientation="vertical", **kwargs)
        self.state, self.on_change, self.on_save = state, on_change, on_save
        if not hasattr(state, "properties"): state.properties = {}
        self.category = None
        self._home()

    def _value(self, category, section, group, name, spec):
        path = category+"."+section+"."+group+"."+name
        if path not in self.state.properties:
            self.state.properties[path] = deepcopy(spec.get("default"))
        return path, self.state.properties[path]

    def _changed(self, path, value):
        self.state.properties[path] = value
        if self.on_change: self.on_change()
        if self.on_save: self.on_save()

    def _button(self, label, callback):
        b=Button(text=label,size_hint_y=None,height=dp(44),font_size="12sp",variant="tonal")
        b.bind(on_release=lambda *_:callback()); return b

    def _home(self):
        self.clear_widgets(); self.category=None
        head=BoxLayout(size_hint_y=None,height=dp(48))
        head.add_widget(Label(text="ADVANCED PROPERTIES",bold=True))
        self.add_widget(head)
        scroll=ScrollView(); grid=GridLayout(cols=2,spacing=dp(7),padding=dp(7),size_hint_y=None)
        grid.bind(minimum_height=grid.setter("height"))
        for name in SCHEMA:
            grid.add_widget(self._button(name,lambda n=name:self._details(n)))
        scroll.add_widget(grid); self.add_widget(scroll)

    def _details(self, category):
        self.category=category; self.clear_widgets()
        top=BoxLayout(size_hint_y=None,height=dp(46),spacing=dp(5))
        top.add_widget(self._button("‹ BACK",self._home))
        top.add_widget(Label(text=category,bold=True))
        top.add_widget(self._button("RESET",lambda:self._reset(category)))
        top.add_widget(self._button("INFO",lambda:self._info(category)))
        self.add_widget(top)
        acc=Accordion(orientation="vertical",min_space=dp(42),size_hint_y=1)
        for section, groups in SCHEMA[category].items():
            item=AccordionItem(title=section,collapse=True)
            item.background_normal=""
            item.background_selected=""
            item.background_color=SURFACE_HIGH
            item.color=TEXT
            sc=ScrollView(); body=BoxLayout(orientation="vertical",size_hint_y=None,spacing=dp(7),padding=dp(8))
            body.bind(minimum_height=body.setter("height"))
            for group, fields in groups.items():
                body.add_widget(Label(text="[b]"+group+"[/b]",markup=True,size_hint_y=None,height=dp(28),halign="left"))
                for name,spec in fields.items(): body.add_widget(self._control(category,section,group,name,spec))
            sc.add_widget(body); item.add_widget(sc); acc.add_widget(item)
        self.add_widget(acc)

    def _control(self, category, section, group, name, spec):
        path,value=self._value(category,section,group,name,spec)
        kind=spec["type"]; row=BoxLayout(size_hint_y=None,height=dp(48),spacing=dp(5))
        label=Label(text=name,size_hint_x=.36,font_size="10sp",halign="left",valign="middle")
        label.bind(size=lambda obj,size:setattr(obj,"text_size",size))
        row.add_widget(label)
        if kind=="checkbox":
            control=CheckBox(active=bool(value),size_hint_x=.2)
            control.bind(active=lambda _,v:self._changed(path,bool(v)))
            row.add_widget(control)
        elif kind=="dropdown":
            options=[str(v) for v in spec.get("options",[])]
            control=Spinner(text=str(value),values=options,size_hint_x=.62,font_size="10sp")
            control.bind(text=lambda _,v:self._changed(path,v)); row.add_widget(control)
        elif kind=="slider":
            lo=float(spec.get("min",0)); hi=float(spec.get("max",1))
            control=Slider(min=lo,max=hi,value=max(lo,min(hi,float(value or 0))),size_hint_x=.48)
            val=Label(text="{:.3f}".format(float(value or 0)),size_hint_x=.18,font_size="10sp")
            def update(_,v):
                rounded=round(float(v),3); val.text="{:.3f}".format(rounded); self._changed(path,rounded)
            control.bind(value=update); row.add_widget(control); row.add_widget(val)
        elif kind in ("text","number","color","image","font","code"):
            control=TextInput(text=str(value if value is not None else ""),multiline=(kind=="code"),size_hint_x=.64,font_size="10sp",hint_text=kind.upper())
            control.bind(text=lambda _,v:self._changed(path,int(v) if kind=="number" and v.lstrip("-").isdigit() else (v if kind!="number" else self._number(v))))
            row.add_widget(control)
        elif kind=="action":
            row.add_widget(self._button(str(value),lambda:self._action(path,category)))
        return row

    def _number(self,value):
        try:return float(value)
        except (TypeError,ValueError):return 0

    def _action(self,path,category):
        if path.endswith(".Compile / Apply"):
            self._changed("runtime.shader_compile_requested",True)
        elif path.endswith(".Add Variable"):
            self._changed("runtime.shader_variable_add_requested",True)

    def _reset(self,category):
        prefix=category+"."
        for path in list(self.state.properties):
            if path.startswith(prefix): del self.state.properties[path]
        self._details(category)

    def _info(self,category):
        Popup(title=category,content=Label(text="Settings are stored per property in the project JSON. Engine-dependent options are configuration-ready; rendering support is added by the corresponding engine module.")).open()
