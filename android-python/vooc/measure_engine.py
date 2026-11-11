"""Frame-rate independent Measure Engine for audio-reactive scene properties."""
import math
class MeasureEngine:
    def __init__(self):
        self.time=0.0; self.beat_pulse=0.0; self.last_beat_time=-1.0
        self.track_position=0.0; self.track_duration=0.0; self.rms=0.0; self.peak=0.0; self.dominant_hz=0.0
    def update(self,dt,beat=False,rms=0.0,peak=0.0,track_position=None,track_duration=None,dominant_hz=0.0):
        dt=max(0.0,min(.25,float(dt))); self.time+=dt
        self.beat_pulse=max(0.0,self.beat_pulse-dt*3.5)
        if beat: self.beat_pulse=1.0; self.last_beat_time=self.time
        self.rms=max(0.0,min(1.0,float(rms))); self.peak=max(0.0,min(1.0,float(peak)))
        self.dominant_hz=max(0.0,float(dominant_hz))
        if track_position is not None:self.track_position=max(0.0,float(track_position))
        if track_duration is not None:self.track_duration=max(0.0,float(track_duration))
    def evaluate(self,mode,base=0.0,amount=1.0,speed=1.0,now=None):
        t=self.time if now is None else float(now); a=max(-1000.0,min(1000.0,float(amount))); b=max(-100.0,min(100.0,float(speed))); p=self.beat_pulse
        if mode in (None,"","Nothing"): d=0.0
        elif mode=="Beat": d=a*p
        elif mode in ("TotalTime","TotalTimeWhenPlaying"): d=a*math.sin(t*b)
        elif mode=="TotalTimeBackward": d=a*math.sin(-t*b)
        elif mode=="TotalTimeAndBeat": d=a*(math.sin(t*b)+p)
        elif mode=="TrackPosition": d=a*self.track_position
        elif mode in ("BeatRandomShake","BeatCamShakeOnly","BeatCamShakeRotMore","BeatCamShakeRotLess","ConstantShakeRotMore","ConstantShakeRotLess"):
            sign=-1.0 if mode.endswith("Less") else 1.0
            gate=p if mode.startswith("Beat") else 1.0
            d=sign*a*gate*math.sin(t*(17.0+abs(b)*13.0))
        elif mode in ("BeatTriggerAnim","BeatPulseScale","BeatFlashColor","BeatGlowIntensity","BeatHorizontalStretch","BeatVerticalBounce"): d=a*p
        elif mode=="ConstantSlowRotate": d=a*((t*b)%360.0)/360.0
        elif mode=="ConstantOrbitMove": d=a*math.sin(t*b)
        elif mode=="ConstantLinearScroll": d=a*((t*b)%1.0)
        elif mode=="TrackProgressPercentage": d=a*(self.track_position/self.track_duration if self.track_duration>0 else 0.0)
        elif mode=="TrackRemainingTime": d=a*max(0.0,self.track_duration-self.track_position)
        elif mode=="TrackIntroOutroTrigger":
            q=self.track_position/self.track_duration if self.track_duration>0 else 0.0; d=a if q<.08 or q>.92 else 0.0
        elif mode=="VolumeLevelResponsive": d=a*self.rms
        elif mode=="PitchFrequencyResponsive": d=a*min(1.0,math.log1p(self.dominant_hz)/math.log1p(20000.0))
        else:d=0.0
        result=float(base)+d
        return result if math.isfinite(result) else float(base)
