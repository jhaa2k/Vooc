THEMES={"Acid Night":"#070A05","Pure Black":"#000000","Graphite":"#141614","Slate":"#141B24","Midnight Blue":"#060B1F","Deep Ocean":"#03141A","Neon Purple":"#0D0617","Crimson":"#140407","Emerald":"#03120B","Amber":"#140D03","Claude Dark":"#262624","CapCut":"#000000","Claude Light":"#FAF9F5","Paper":"#F2F4EC"}
AUDIO_DEFAULTS={"volume":100,"pre":0,"bass":0,"balance":0,"speed":100,"pitch":100,"virtualizer":0}
VISUALIZER_DEFAULTS={"style":"Bars","palette":"Acid","bar_count":48,"sensitivity":1.0,"smoothing":0.55,
    "height":1.0,"gap":0.35,"glow":0.5,"peak_fall":1.0,"peaks":True,"beat_pulse":True,"particles":False,
    "reflection":True,"spin":False}
EQ_PRESETS={"Flat":[0]*10,"Bass boost":[7,6,4,2,0,0,0,0,0,0],"Vocal":[-2,-2,-1,1,3,4,3,1,0,-1],
    "Rock":[5,4,2,-1,-2,-1,2,3,4,4],"Electronic":[6,5,1,0,-2,2,1,2,5,6],"Treble":[0,0,0,0,0,1,3,5,6,7],
    "Night":[3,2,1,0,-1,-2,-3,-4,-5,-6]}
class ProjectState:
    def __init__(self):
        self.layers=[{"type":"spectrum","name":"Spectrum","visible":True,"locked":False,"x":50,"y":50,"scale":1.0,"opacity":1.0,"rotation":0},
                     {"type":"text","name":"Track title","visible":True,"locked":False,"x":50,"y":12,"scale":1.0,"opacity":0.9,"rotation":0}]
        self.selected=0;self.playlist=[];self.current_track="";self.repeat="off";self.shuffle=False
        self.audio=dict(AUDIO_DEFAULTS)
        self.eq_freqs=[32,64,125,250,500,1000,2000,4000,8000,16000]
        for i in range(10):self.audio["eq_"+str(i)]=0
        self.scene={"ratio":"16:9","resolution":"1080p","fps":"30","theme":"Acid Night","ui_theme":"dark"}
        self.visualizer=dict(VISUALIZER_DEFAULTS)
        self.properties={}
        self.engine=None
        self.export={"resolution":"FHD 1080p","fps":"30","aspect":"16:9","codec":"H.264 (AVC)","quality":"Standard","audio":True}
        self.perf={"preview_fps":60,"render_scale":1.0,"heavy_blur":True,"max_bars":256,"max_particles":2000}
        self.palettes={}
        self.look={"template":"Circle Classic","palette":"Acid","auto_palette":True}
        self.presets={"Classic Bars":dict(self.audio),"Deep Bass":dict(self.audio,bass=8,eq_0=6,eq_1=4),"Wide":dict(self.audio,virtualizer=70)}
    def data(self):
        return {k:getattr(self,k) for k in ["layers","selected","playlist","current_track","repeat","shuffle","audio","scene","presets","visualizer","properties","engine","export","perf","palettes","look"]}
    @classmethod
    def from_data(cls,d):
        s=cls()
        for k,v in (d or {}).items():
            if not hasattr(s,k):continue
            if k in ("audio","scene","visualizer","export","perf","look") and isinstance(v,dict):
                getattr(s,k).update(v)
            else:
                setattr(s,k,v)
        for key in ("reverb","delay","distortion"):s.audio.pop(key,None)
        return s
