THEMES={"Claude Dark":"#262624","CapCut":"#000000","Claude Light":"#FAF9F5","Pure Black":"#000000","Graphite":"#1A1A1C","Slate":"#1E2530","Midnight Blue":"#060B1F","Electric Cyan":"#031215","Neon Purple":"#0D0617","Crimson":"#120406","Emerald":"#03120B","Amber":"#120C03"}
class ProjectState:
    def __init__(self):
        self.layers=[{"type":"spectrum","name":"Spectrum","visible":True,"locked":False,"x":50,"y":72},{"type":"text","name":"Track title","visible":True,"locked":False,"x":50,"y":18}]
        self.selected=0;self.playlist=[];self.current_track="";self.repeat="off"
        self.audio={"volume":100,"pre":0,"bass":0,"balance":0,"speed":100,"reverb":0,"delay":0,"distortion":0}
        self.eq_freqs=[32,64,125,250,500,1000,2000,4000,8000,16000]
        for i in range(10):self.audio["eq_"+str(i)]=0
        self.scene={"ratio":"16:9","resolution":"1080p","fps":"30","theme":"Claude Dark"}
        self.presets={"Classic Bars":dict(self.audio),"Deep Bass":dict(self.audio,bass=8),"Ambient":dict(self.audio,reverb=60,delay=25)}
    def data(self):
        return {k:getattr(self,k) for k in ["layers","selected","playlist","current_track","repeat","audio","scene","presets"]}
    @classmethod
    def from_data(cls,d):
        s=cls()
        for k,v in d.items():
            if hasattr(s,k):setattr(s,k,v)
        return s
