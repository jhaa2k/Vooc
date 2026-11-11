"""Bounded deterministic particle simulation for the Kivy preview."""
import math,random
class ParticleSystem:
    def __init__(self,seed=9173):
        self.random=random.Random(seed); self.particles=[]; self.spawn_accumulator=0.0; self.elapsed=0.0
    def update(self,dt,settings,width,height,audio_level=0.0,beat=False):
        dt=max(0.0,min(.05,float(dt))); self.elapsed+=dt
        limit=max(0,min(5000,int(settings.get("count",120))))
        life=max(.05,min(120.0,float(settings.get("lifetime",2.0))))
        interval=max(.005,min(10.0,float(settings.get("spawn_time",.035))))
        speed=max(0.0,min(3000.0,float(settings.get("speed",65.0))))
        random_speed=max(0.0,min(1.0,float(settings.get("speed_random",.35))))
        gx=max(-3000.0,min(3000.0,float(settings.get("gravity_x",0.0))))
        gy=max(-3000.0,min(3000.0,float(settings.get("gravity_y",-20.0))))
        area=settings.get("spawn_area","Rectangle"); angle=math.radians(float(settings.get("angle",90.0))); force=settings.get("force_field","None")
        self.particles=[p for p in self.particles if p["age"]<p["life"]][:limit]
        multiplier=1.0+(max(0.0,min(1.0,audio_level)) if settings.get("audio_reactive",True) else 0.0)+(0.5 if beat else 0.0)
        self.spawn_accumulator+=dt*multiplier; spawned=0
        while self.spawn_accumulator>=interval and len(self.particles)<limit and spawned<100:
            self.spawn_accumulator-=interval
            if area=="Circle":
                a=self.random.random()*math.tau; r=math.sqrt(self.random.random())*min(width,height)*.25
                x,y=width*.5+math.cos(a)*r,height*.5+math.sin(a)*r
            elif area=="Line":x,y=self.random.random()*width,height*.5
            elif area=="Point":x,y=width*.5,height*.5
            else:x,y=self.random.random()*width,self.random.random()*height
            theta=angle+self.random.uniform(-.65,.65); v=speed*(1+self.random.uniform(-random_speed,random_speed))
            self.particles.append({"x":x,"y":y,"vx":math.cos(theta)*v,"vy":math.sin(theta)*v,"age":0.0,"life":life*self.random.uniform(.7,1.3),"size":max(.5,float(settings.get("size",2.5)))})
            spawned+=1
        for p in self.particles:
            p["age"]+=dt; ax,ay=gx,gy
            if force=="Wind":ax+=25.0
            elif force=="Gravity":ay-=80.0
            elif force=="Vortex":
                dx,dy=width*.5-p["x"],height*.5-p["y"]; ax-=dy*.12; ay+=dx*.12
            elif force in ("Attractor","Repeller"):
                dx,dy=width*.5-p["x"],height*.5-p["y"]; sign=1.0 if force=="Attractor" else -1.0; ax+=dx*sign*.35; ay+=dy*sign*.35
            p["vx"]+=ax*dt; p["vy"]+=ay*dt; p["x"]+=p["vx"]*dt; p["y"]+=p["vy"]*dt
        return self.particles
