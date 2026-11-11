from kivy.uix.widget import Widget
from kivy.clock import Clock
from kivy.graphics import Color,Rectangle
from kivy.metrics import dp
import math
class Spectrum(Widget):
    def __init__(self,state,**kw):
        super().__init__(**kw);self.state=state;self.phase=0
        self.bind(pos=self.refresh,size=self.refresh);Clock.schedule_interval(self.tick,1/30)
    def tick(self,dt):self.phase+=dt*3;self.refresh()
    def refresh(self,*_):
        self.canvas.clear()
        with self.canvas:
            Color(.005,.005,.008,1);Rectangle(pos=self.pos,size=self.size)
            n=64;gap=dp(2);bw=max(1,(self.width-gap*(n+1))/n)
            for i in range(n):
                h=self.height*(.08+.72*abs(math.sin(i*.19+self.phase))*abs(math.sin(i*.073-self.phase)))
                Color(.85,.38,.25,1);Rectangle(pos=(self.x+gap+i*(bw+gap),self.y+dp(8)),size=(bw,max(dp(2),h)))
