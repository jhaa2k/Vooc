"""Vooc - Avee-style audio visualizer (Kivy).

Architecture (see docs/ARCHITECTURE.md):

    Model       vooc/model.py           ProjectModel / LayerModel / ProjectStore (JSON)
    Schema      vooc/property_schema.py every module, category and property
    ViewModel   vooc/viewmodel.py       LayerViewModel / ProjectViewModel (observable, autosave)
    Audio       vooc/audio/             providers (File / Mic / System) -> analysis -> AudioHub
    Engine      vooc/engine/            GPU renderer: layers, effects, shapes, shaders
    UI          vooc/ui/                Main Menu grid + Details accordion inspector
"""
import os

from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.floatlayout import FloatLayout
from kivy.uix.screenmanager import FadeTransition, ScreenManager

from vooc.audio.hub import AudioHub
from vooc.engine.scene import SceneView
from vooc.model import ProjectStore
from vooc.platform import android as platform
from vooc.ui.screens import DetailsScreen, MainMenu
from vooc.ui.theme import FS_BODY, T
from vooc.ui.widgets import Dialog, IconButton, Pressable, Snackbar, label, TextButton
from vooc.viewmodel import ProjectViewModel

AUDIO_EXT = (".mp3", ".wav", ".ogg", ".flac", ".m4a", ".aac", ".opus")
IMAGE_EXT = (".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif")
FONT_EXT = (".ttf", ".otf")


class VoocApp(App):
    title = "Vooc"

    # ------------------------------------------------------------ lifecycle
    def build(self):
        self.store = ProjectStore(self.user_data_dir)
        self.project = self.store.load()
        T.dark = bool(self.project.ui.get("dark", True))
        self.pvm = ProjectViewModel(self.project, self.store,
                                    schedule=lambda cb, d: Clock.schedule_once(cb, d))
        self.hub = AudioHub(self.project.layer("AUDIOPROVIDER"),
                            on_error=lambda msg: Clock.schedule_once(lambda dt: self.toast(msg), 0),
                            on_complete=lambda: Clock.schedule_once(lambda dt: self.next_track(auto=True), 0))
        self.scene = SceneView(self.project, self.hub)
        self.scene.listeners.append(self._on_frame)
        self.root_layout = FloatLayout()
        self.snackbar = Snackbar()
        self._build_screens()
        Window.bind(on_keyboard=self._on_key)
        return self.root_layout

    def _build_screens(self):
        Window.clearcolor = T.bg
        self.root_layout.clear_widgets()
        self.sm = ScreenManager(transition=FadeTransition(duration=.15))
        self.menu = MainMenu(self)
        self.sm.add_widget(self.menu)
        self.root_layout.add_widget(self.sm)
        self.snackbar = Snackbar()
        self.root_layout.add_widget(self.snackbar)
        self.menu.on_pre_enter()

    def on_start(self):
        # Visualizer needs RECORD_AUDIO even for our own MediaPlayer session
        platform.ensure_permissions(["RECORD_AUDIO", "READ_MEDIA_AUDIO", "READ_EXTERNAL_STORAGE"],
                                    self._permissions_done)
        idx = self.project.track_index
        if 0 <= idx < len(self.project.playlist):
            self.play_index(idx, autoplay=False)

    def _permissions_done(self, ok):
        if ok:
            self.hub.restart()
        else:
            self.toast("Allow microphone permission - Android requires it to read audio for the visualizer.")

    def on_pause(self):
        self.pvm.save_now()
        return True

    def on_stop(self):
        self.pvm.save_now()
        self.hub.release()

    def _on_key(self, window, key, *args):
        if key == 27 and self.sm.current == "details":
            self.go_menu()
            return True
        return False

    def _on_frame(self, frame):
        if self.sm.current == "menu":
            self.menu.player.update(frame)

    # ------------------------------------------------------------ navigation
    def open_module(self, module):
        old = self.sm.get_screen("details") if self.sm.has_screen("details") else None
        if old is not None:
            old.inspector.release()
            self.sm.remove_widget(old)
        self.sm.add_widget(DetailsScreen(self, module))
        self.sm.current = "details"

    def go_menu(self):
        self.sm.current = "menu"

    def toggle_theme(self):
        T.dark = not T.dark
        self.project.ui["dark"] = T.dark
        self.pvm.request_save()
        self._build_screens()

    def toast(self, text):
        self.snackbar.show(text)

    # ------------------------------------------------------------ actions from the inspector
    def run_action(self, vm, path):
        if path.endswith("Compile / Apply"):
            err = self.scene.compile_shader(vm.layer)
            if err:
                self.toast("Shader error: " + err)
            elif not vm.get("General/Layer/Visible"):
                self.toast("Shader compiled. Turn on General > Layer > Visible to see it.")
            else:
                self.toast("Shader compiled and applied.")
        elif path.endswith("Add Variable"):
            err = vm.add_variable()
            self.toast(err or "Variable added - it is now a uniform in your shader.")

    def pick_file(self, kind, callback):
        """kind: 'image' | 'font'. callback(local_path or None)."""
        directory = os.path.join(self.user_data_dir, "assets")
        if platform.IS_ANDROID:
            mime = "image/*" if kind == "image" else "*/*"

            def picked(uris):
                if not uris:
                    return

                def work():
                    try:
                        path = platform.copy_to_file(uris[0], directory)
                    except Exception as exc:
                        path = None
                        Clock.schedule_once(lambda dt: self.toast("Could not open file: %s" % exc), 0)
                    Clock.schedule_once(lambda dt: callback(path), 0)
                platform.run_async(work)
            platform.pick_documents(mime, picked)
        else:
            exts = IMAGE_EXT if kind == "image" else FONT_EXT
            self._desktop_chooser(exts, lambda paths: callback(paths[0] if paths else None))

    def _desktop_chooser(self, exts, callback, multiple=False):
        from kivy.uix.filechooser import FileChooserListView
        chooser = FileChooserListView(path=os.path.expanduser("~"), multiselect=multiple,
                                      filters=[lambda folder, name: name.lower().endswith(exts)])
        body = BoxLayout(orientation="vertical")
        body.add_widget(chooser)
        Dialog("Choose file", body, [("Cancel", None, False),
                                     ("Open", lambda: callback(list(chooser.selection)), True)],
               size_hint=(.96, .9)).open()

    # ------------------------------------------------------------ playlist / transport
    def open_tracks(self):
        if platform.IS_ANDROID:
            platform.pick_documents("audio/*", self._add_tracks, multiple=True)
        else:
            self._desktop_chooser(AUDIO_EXT, self._add_tracks, multiple=True)

    def _add_tracks(self, uris):
        if not uris:
            return
        first = len(self.project.playlist)
        for uri in uris:
            name = platform.display_name(uri) if platform.IS_ANDROID else os.path.basename(uri)
            self.project.playlist.append({"uri": uri, "title": os.path.splitext(name)[0], "artist": ""})
        self.pvm.request_save()
        if self.hub.kind != "File":
            self.project.layer("AUDIOPROVIDER").set("General/Routing/Audio Source", "File")
        self.toast("%d track(s) added" % len(uris))
        self.play_index(first)

    def play_index(self, index, autoplay=True):
        playlist = self.project.playlist
        if not playlist:
            return
        index %= len(playlist)
        entry = playlist[index]
        self.project.track_index = index
        self.pvm.request_save()
        meta = {"title": entry.get("title", ""), "artist": entry.get("artist", ""), "album": "", "art": ""}
        ok = self.hub.load(entry["uri"], meta, autoplay=autoplay)
        if not ok:
            self.toast("Could not play %s" % (entry.get("title") or entry["uri"]))
        if platform.IS_ANDROID:
            art_dir = os.path.join(self.user_data_dir, "art")

            def work():
                data = platform.read_metadata(entry["uri"], art_dir)

                def apply(dt):
                    if self.hub.current_uri == entry["uri"]:
                        self.hub.metadata = dict(self.hub.metadata, **{k: v for k, v in data.items() if v})
                        entry.update(title=data.get("title") or entry.get("title"),
                                     artist=data.get("artist") or "")
                Clock.schedule_once(apply, 0)
            platform.run_async(work)

    def toggle_play(self):
        if self.hub.kind != "File":
            self.toast("Audio Source is %s. Switch AUDIOPROVIDER to File to play tracks." % self.hub.kind)
            return
        if not self.hub.current_uri:
            self.open_tracks()
            return
        self.hub.toggle()

    def next_track(self, auto=False):
        if self.project.playlist:
            self.play_index(self.project.track_index + 1)

    def prev_track(self):
        if self.project.playlist:
            if self.hub.position() > 3:
                self.hub.seek(0)
            else:
                self.play_index(self.project.track_index - 1)

    def seek_fraction(self, fraction):
        dur = self.hub.duration()
        if dur > 0:
            self.hub.seek(fraction * dur)

    def show_queue(self):
        from kivy.uix.scrollview import ScrollView
        scroll = ScrollView(do_scroll_x=False)
        box = BoxLayout(orientation="vertical", size_hint_y=None, spacing=dp(4))
        box.bind(minimum_height=box.setter("height"))
        dlg = Dialog("Queue (%d)" % len(self.project.playlist), scroll,
                     [("Clear", self._clear_queue, False), ("Close", None, True)], size_hint=(.92, .75))
        if not self.project.playlist:
            box.add_widget(label("Queue is empty. Tap the folder to add music.", FS_BODY, color=T.muted,
                                 size_hint_y=None, height=dp(40)))
        for i, entry in enumerate(self.project.playlist):
            current = i == self.project.track_index
            row = Pressable(bg=T.primary_soft if current else T.surface_hi, size_hint_y=None,
                            height=dp(50), padding=(dp(12), 0), radius=dp(12))
            row.add_widget(label(entry.get("title") or entry["uri"], FS_BODY,
                                 color=T.primary if current else T.on_surface, bold=current))

            def remove(i=i):
                self.project.playlist.pop(i)
                if self.project.track_index >= i:
                    self.project.track_index -= 1
                self.pvm.request_save()
                dlg.dismiss()
                self.show_queue()
            row.add_widget(IconButton("close", remove, tint=T.muted, size=(dp(36), dp(36)),
                                      pos_hint={"center_y": .5}))
            row.bind(on_release=lambda *_, i=i: (dlg.dismiss(), self.play_index(i)))
            box.add_widget(row)
        scroll.add_widget(box)
        dlg.open()

    def _clear_queue(self):
        self.project.playlist = []
        self.project.track_index = -1
        self.pvm.request_save()


if __name__ == "__main__":
    VoocApp().run()
