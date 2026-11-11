from kivy.app import App
from kivy.clock import Clock
from kivy.metrics import dp
from kivy.core.window import Window
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.scrollview import ScrollView
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.slider import Slider
from kivy.uix.spinner import Spinner
from kivy.uix.textinput import TextInput
from kivy.uix.popup import Popup
from kivy.core.audio import SoundLoader
from pathlib import Path
import json, random
from vooc.state import ProjectState, THEMES
from vooc.visualizer import Spectrum
from vooc.pcm_pipeline import PCMAnalysisPipeline
from vooc.android_audio import AndroidMediaPlayerSource
from vooc.android_media import choose_audio

class B(Button):
    def __init__(self, **kw):
        kw.setdefault("size_hint_y", None); kw.setdefault("height", dp(40))
        kw.setdefault("font_size", "12sp")
        super().__init__(**kw); self.background_normal=""; self.background_color=(.17,.17,.17,1)

class VoocRoot(BoxLayout):
    tabs=["Media","Layers","Presets","Audio","Scene","Export"]
    def __init__(self, **kw):
        super().__init__(orientation="vertical",padding=dp(7),spacing=dp(5),**kw)
        self.s=ProjectState(); self.sound=None; self.playing=False; self.track=""; self._paused_at=0.0
        self.native_mode=False; self._native_requested=False
        self.native_audio=AndroidMediaPlayerSource(
            self.feed_pcm_samples, self._native_error, self._native_completed,
            self._native_focus_changed
        )
        top=BoxLayout(size_hint_y=None,height=dp(40))
        top.add_widget(Label(text="[b]AUDIOVIS PRO[/b]",markup=True,halign="left"))
        for name,fn in [("SAVE",self.save),("LOAD",self.load)]:
            b=B(text=name,size_hint_x=None,width=dp(58)); b.bind(on_release=lambda _,f=fn:f()); top.add_widget(b)
        self.add_widget(top)
        self.preview=Spectrum(self.s,size_hint_y=.38); self.add_widget(self.preview)
        self.pcm_pipeline=PCMAnalysisPipeline(self._on_audio_analysis, scheduler=lambda callback: Clock.schedule_once(lambda _dt: callback(), 0), sample_rate=44100, fft_size=1024, band_count=64, min_interval=1/30)
        self.track_label=Label(text="No track loaded",size_hint_y=None,height=dp(25),font_size="11sp")
        self.add_widget(self.track_label)
        transport=BoxLayout(size_hint_y=None,height=dp(42),spacing=dp(5))
        for text,fn in [("PREV",self.previous),("IMPORT",self.import_audio),("PLAY / PAUSE",self.toggle),("STOP",self.stop),("NEXT",self.next)]:
            b=B(text=text,font_size="10sp"); b.bind(on_release=lambda _,f=fn:f()); transport.add_widget(b)
        self.add_widget(transport)
        self.seek=Slider(min=0,max=100,value=0,size_hint_y=None,height=dp(22))
        self._seek_dragging=False
        self.seek.bind(on_touch_down=self.seek_touch_down,on_touch_up=self.seek_audio); self.add_widget(self.seek)
        self._last_track_pos=0; self._end_handled=False
        self.body=BoxLayout()
        self.add_widget(self.body)
        nav=GridLayout(cols=6,size_hint_y=None,height=dp(50),spacing=dp(2))
        for tab in self.tabs:
            b=B(text=tab,font_size="9sp"); b.bind(on_release=lambda _,t=tab:self.show(t)); nav.add_widget(b)
        self.add_widget(nav)
        self.show("Media"); Clock.schedule_interval(self.tick,1/20)

    def feed_pcm_samples(self, samples, timestamp=None):
        return self.pcm_pipeline.submit(samples, timestamp)
    def _on_audio_analysis(self, result):
        self.preview.set_spectrum(result["bands"], result["beat"])
    def _native_error(self, message):
        Clock.schedule_once(lambda _dt: self.popup(message), 0)
    def _native_completed(self):
        Clock.schedule_once(lambda _dt: self._handle_native_completion(), 0)
    def _native_focus_changed(self, state):
        def apply(_dt):
            if state in ("interrupted", "focus_lost", "paused", "stopped"):
                self.playing=False
            elif state in ("playing", "resumed"):
                self.playing=True
            elif state == "denied":
                self.playing=False
                self.popup("Android audio focus was not granted. Playback did not start.")
        Clock.schedule_once(apply, 0)
    def _handle_native_completion(self):
        if not self.native_mode or self._end_handled:
            return
        self._end_handled=True; self.playing=False
        if self.s.repeat=="one":
            self.load_track(self.track)
            self.toggle()
        else:
            self.next()
    def on_stop(self):
        if hasattr(self, "pcm_pipeline"):
            self.pcm_pipeline.close()
        if hasattr(self, "native_audio"):
            self.native_audio.release()
    def popup(self,msg):
        Popup(title="Vooc",content=Label(text=msg),size_hint=(.85,None),height=dp(140)).open()
    def show(self,tab):
        self.tab=tab; self.body.clear_widgets()
        scroll=ScrollView(); col=BoxLayout(orientation="vertical",size_hint_y=None,spacing=dp(5),padding=dp(4))
        col.bind(minimum_height=col.setter("height")); scroll.add_widget(col); self.body.add_widget(scroll)
        def heading(t): col.add_widget(Label(text="[b]"+t+"[/b]",markup=True,size_hint_y=None,height=dp(28),halign="left"))
        def action(t,f):
            b=B(text=t); b.bind(on_release=lambda *_:f()); col.add_widget(b)
        def slider(label,key,lo,hi,step=1,suffix=""):
            r=BoxLayout(size_hint_y=None,height=dp(42),spacing=dp(5))
            r.add_widget(Label(text=label,size_hint_x=.3,font_size="10sp"))
            val=Label(text=str(self.s.audio.get(key,lo))+suffix,size_hint_x=.16,font_size="10sp")
            sl=Slider(min=lo,max=hi,step=step,value=self.s.audio.get(key,lo))
            def changed(_,v):
                self.s.audio[key]=round(v,2); val.text=str(self.s.audio[key])+suffix; self.preview.refresh()
            sl.bind(value=changed); r.add_widget(sl); r.add_widget(val); col.add_widget(r)
        if tab=="Media":
            heading("MEDIA LIBRARY"); action("＋ IMPORT AUDIO",self.import_audio)
            action("USE ANDROID NATIVE PLAYER",self.enable_native_audio)
            action("PLAY / PAUSE",self.toggle); action("STOP",self.stop)
            action("SHUFFLE PLAYLIST",lambda:random.shuffle(self.s.playlist))
            action("REPEAT: "+self.s.repeat.upper(),self.repeat)
            for i,path in enumerate(self.s.playlist):
                action(("▶ " if path==self.track else "♫ ")+Path(path).name,lambda p=path:self.load_track(p))
            if not self.s.playlist: col.add_widget(Label(text="Your imported tracks will appear here.",size_hint_y=None,height=dp(36),font_size="11sp"))
        elif tab=="Layers":
            heading("LAYER STACK")
            for i,l in enumerate(self.s.layers):
                action(("● " if l["visible"] else "○ ")+l["name"],lambda ix=i:self.select_layer(ix))
            action("＋ ADD SPECTRUM",lambda:self.add_layer("Spectrum"))
            action("＋ ADD TEXT",lambda:self.add_layer("Text"))
            action("＋ ADD IMAGE",lambda:self.add_layer("Image"))
            action("DUPLICATE SELECTED",self.duplicate)
            action("TOGGLE VISIBILITY",self.visibility)
            action("DELETE SELECTED",self.delete_layer)
            if self.s.layers:
                l=self.s.layers[self.s.selected]
                ti=TextInput(text=l["name"],multiline=False,size_hint_y=None,height=dp(40))
                ti.bind(text=lambda _,v:l.__setitem__("name",v)); col.add_widget(ti)
        elif tab=="Presets":
            heading("VISUALIZER PRESETS")
            for n in self.s.presets: action("APPLY "+n,lambda name=n:self.apply_preset(name))
            action("SAVE CURRENT PRESET",self.save_preset)
            action("DELETE LAST PRESET",self.delete_preset)
        elif tab=="Audio":
            heading("AUDIO PROCESSING CONTROLS")
            for label,key,lo,hi,step,suf in [
                ("Volume","volume",0,150,1,"%"),("Pre gain","pre",-24,24,1," dB"),
                ("Bass","bass",-12,12,.5," dB"),("Balance","balance",-100,100,1,""),
                ("Speed","speed",50,200,1,"%"),("Reverb","reverb",0,100,1,"%"),
                ("Delay","delay",0,100,1,"%"),("Distortion","distortion",0,100,1,"%")]:
                slider(label,key,lo,hi,step,suf)
            heading("10-BAND EQ")
            for i,f in enumerate(self.s.eq_freqs): slider(str(f)+" Hz","eq_"+str(i),-12,12,.5," dB")
        elif tab=="Scene":
            heading("SCENE / CANVAS")
            self.spinner(col,"Aspect ratio",["16:9","9:16","1:1","4:5","4:3"],"ratio")
            self.spinner(col,"Resolution",["720p","1080p","1440p","2160p"],"resolution")
            self.spinner(col,"Frame rate",["24","30","60"],"fps")
            heading("THEME")
            self.spinner(col,"Theme",list(THEMES),"theme")
        elif tab=="Export":
            heading("VIDEO EXPORT SETTINGS")
            self.spinner(col,"Format",["MP4","WEBM","GIF"],"format")
            self.spinner(col,"Resolution",["720p","1080p","1440p","2160p"],"resolution")
            self.spinner(col,"FPS",["24","30","60"],"fps")
            self.spinner(col,"Quality",["Draft","Standard","High","Maximum"],"quality")
            action("RENDER VIDEO",lambda:self.popup("Video encoder adapter is not yet connected. See docs/PORTING_PLAN.md."))
            action("SAVE PROJECT JSON",self.save)
    def spinner(self,col,label,values,key):
        r=BoxLayout(size_hint_y=None,height=dp(42)); r.add_widget(Label(text=label,size_hint_x=.35,font_size="11sp"))
        sp=Spinner(text=self.s.scene.get(key,values[0]),values=values)
        sp.bind(text=lambda _,v:self.s.scene.__setitem__(key,v)); r.add_widget(sp); col.add_widget(r)
    def import_audio(self):
        choose_audio(self.load_track)
    def enable_native_audio(self):
        if not AndroidMediaPlayerSource.is_available():
            self.popup("Android native audio is available only in the Android build.")
            return
        self._native_requested=True
        if not self.track:
            self.import_audio()
            return
        self._activate_native_track(self.track, autoplay=True)
    def _activate_native_track(self, path, autoplay=False):
        if not self.native_audio.load(path, autoplay=autoplay):
            self._native_requested=False
            return False
        self.native_mode=True; self._native_requested=True; self.sound=None
        self.track=path; self.s.current_track=path
        if path not in self.s.playlist:self.s.playlist.append(path)
        self.track_label.text=Path(path).name
        self.seek.max=max(1,self.native_audio.duration_ms()/1000.0)
        self.seek.value=0; self._paused_at=0.0; self._end_handled=False
        self.playing=self.native_audio.is_playing()
        self.preview.refresh(); self.show("Media")
        return True
    def load_track(self,path):
        if not path:return
        try:
            if self._native_requested:
                self._activate_native_track(path, autoplay=False)
                return
            if self.native_mode:
                self.native_audio.release(); self.native_mode=False
            if self.sound:self.sound.stop()
            self.sound=SoundLoader.load(path)
            if not self.sound:self.popup("This audio format is not supported by the installed Android audio provider.");return
            duration=float(self.sound.length or 1)
            self.track=path; self.s.current_track=path
            if path not in self.s.playlist:self.s.playlist.append(path)
            self.track_label.text=Path(path).name; self.seek.max=max(1,duration)
            self.playing=False; self._paused_at=0.0; self._last_track_pos=0; self._end_handled=False; self.seek.value=0
            self.preview.refresh(); self.show("Media")
        except Exception as e:self.popup(str(e))
    def toggle(self):
        if self.native_mode:
            if not self.track:self.import_audio();return
            if self.playing:self.native_audio.pause();self.playing=False
            else:self.playing=self.native_audio.play()
            return
        if not self.sound:self.import_audio();return
        if self.playing:
            try:self._paused_at=float(self.sound.get_pos() or self.seek.value)
            except Exception:self._paused_at=float(self.seek.value)
            self.sound.stop();self.playing=False
        else:
            self.sound.play()
            if self._paused_at>0:
                try:self.sound.seek(self._paused_at)
                except Exception:pass
            self.playing=True
    def stop(self):
        if self.native_mode:self.native_audio.stop()
        if self.sound:self.sound.stop()
        self.playing=False;self._paused_at=0.0;self._end_handled=False;self.seek.value=0
    def seek_touch_down(self, slider, touch):
        if slider.collide_point(*touch.pos): self._seek_dragging=True
    def seek_audio(self, slider, touch):
        if not slider.collide_point(*touch.pos): return
        self._seek_dragging=False
        if self.native_mode:
            self.native_audio.seek(float(slider.value)*1000.0); self._last_track_pos=float(slider.value); self._end_handled=False
            return
        if not self.sound:return
        try:
            self.sound.seek(max(0,min(float(slider.value),float(self.sound.length or slider.value))))
            self._last_track_pos=float(slider.value); self._end_handled=False
        except Exception: pass
    def previous(self):
        if not self.s.playlist:return
        try:i=self.s.playlist.index(self.track)
        except ValueError:i=0
        if self.native_mode and self.native_audio.position_ms()>3000:
            self.native_audio.seek(0);return
        if self.sound and self.sound.get_pos()>3:
            self.sound.seek(0);return
        self.load_track(self.s.playlist[(i-1)%len(self.s.playlist)]);self.toggle()
    def next(self):
        if not self.s.playlist:return
        try:i=self.s.playlist.index(self.track)
        except ValueError:i=-1
        if self.s.repeat=="one" and self.track:
            self.load_track(self.track);self.toggle();return
        if i+1<len(self.s.playlist): self.load_track(self.s.playlist[i+1]);self.toggle()
        elif self.s.repeat=="all": self.load_track(self.s.playlist[0]);self.toggle()
        else:self.stop()
    def tick(self,dt):
        if self.native_mode and self.playing:
            pos=self.native_audio.position_ms()/1000.0; duration=self.native_audio.duration_ms()/1000.0
            if not self._seek_dragging:self.seek.value=pos
            if duration>0 and pos>=duration-.25 and not self._end_handled:
                self._handle_native_completion()
        if self.sound and self.playing:
            try:
                pos=float(self.sound.get_pos() or 0); duration=float(self.sound.length or 0)
                if not self._seek_dragging: self.seek.value=pos
                if duration>0 and pos>=duration-.25 and not self._end_handled:
                    self._end_handled=True; self.playing=False
                    if self.s.repeat=="one": self.load_track(self.track);self.toggle()
                    else:self.next()
            except Exception:pass
        self.preview.refresh()
    def add_layer(self,k):
        self.s.layers.append({"type":k.lower(),"name":k+" "+str(len(self.s.layers)+1),"visible":True,"locked":False,"x":50,"y":50})
        self.s.selected=len(self.s.layers)-1;self.show("Layers")
    def select_layer(self,i):self.s.selected=i;self.show("Layers")
    def duplicate(self):
        if self.s.layers:
            x=dict(self.s.layers[self.s.selected]);x["name"]+=" copy";self.s.layers.append(x);self.s.selected=len(self.s.layers)-1;self.show("Layers")
    def visibility(self):
        if self.s.layers:self.s.layers[self.s.selected]["visible"]=not self.s.layers[self.s.selected]["visible"];self.preview.refresh();self.show("Layers")
    def delete_layer(self):
        if self.s.layers:self.s.layers.pop(self.s.selected);self.s.selected=max(0,min(self.s.selected,len(self.s.layers)-1));self.show("Layers")
    def repeat(self):self.s.repeat={"off":"all","all":"one","one":"off"}[self.s.repeat];self.show("Media")
    def save_preset(self):self.s.presets["Custom "+str(len(self.s.presets)+1)]=dict(self.s.audio);self.show("Presets")
    def apply_preset(self,n):self.s.audio.update(self.s.presets[n]);self.show("Audio")
    def delete_preset(self):
        if self.s.presets:self.s.presets.pop(list(self.s.presets)[-1]);self.show("Presets")
    def save(self):
        p=Path(App.get_running_app().user_data_dir)/"vooc_project.json"
        p.write_text(json.dumps(self.s.data(),indent=2));self.popup("Project saved: "+str(p))
    def load(self):
        p=Path(App.get_running_app().user_data_dir)/"vooc_project.json"
        if p.exists():self.s=ProjectState.from_data(json.loads(p.read_text()));self.show(self.tab);self.popup("Project loaded")
        else:self.popup("No saved project found.")

class VoocApp(App):
    def build(self):
        self.title="Vooc";Window.clearcolor=(.08,.08,.08,1);return VoocRoot()
if __name__=="__main__":VoocApp().run()
