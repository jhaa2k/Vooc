'use strict';

/* ================= HELPERS ================= */
const $=s=>document.querySelector(s),$$=s=>[...document.querySelectorAll(s)];
const clamp=(v,a,b)=>Math.max(a,Math.min(b,v)),TAU=Math.PI*2;
const fmt=s=>isFinite(s)?Math.floor(s/60)+':'+String(Math.floor(s%60)).padStart(2,'0'):'0:00';
const esc=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const cv=$('#cv'),ctx=cv.getContext('2d',{alpha:false});
const ls={
  get(k,d){try{const v=localStorage.getItem(k);return v==null?d:JSON.parse(v)}catch(e){return d}},
  set(k,v){try{localStorage.setItem(k,JSON.stringify(v))}catch(e){}},
  del(k){try{localStorage.removeItem(k)}catch(e){}}
};
let tT;
function toast(m,k){const t=$('#toast');t.textContent=m;t.className='show '+(k||'');clearTimeout(tT);tT=setTimeout(()=>t.className='',2600)}

/* ================= THEMES ================= */
const TH={
  'Claude Dark':['#262624','#30302E','#454440','#FAF9F5','#B0AEA5','#D97757'],
  'CapCut':['#000000','#141416','#2a2a2e','#ffffff','#8e8e93','#00e5c0'],
  'Claude Light':['#FAF9F5','#FFFFFF','#E8E6DC','#141413','#6B6A63','#C15F3C'],
  'Pure Black':['#000000','#0e0e10','#252529','#f2f2f5','#a8a8b2','#00e6a8'],
  'Graphite':['#1a1a1c','#232326','#3a3a3e','#f0f0f0','#8e8e93','#f0f0f0'],
  'Slate':['#1e2530','#262e3b','#3b4657','#e8edf4','#8794a8','#cfd8e6'],
  'Midnight Blue':['#060b1f','#0c1330','#223063','#e6ecff','#7a89c2','#6d8cff'],
  'Electric Cyan':['#031215','#08202a','#0f4a57','#e0fbff','#5fa3b0','#00e5ff'],
  'Neon Purple':['#0d0617','#170a29','#3d1d6b','#f3e8ff','#9a7bc4','#b44dff'],
  'Crimson':['#120406','#20080c','#5a1620','#ffe8ea','#b07078','#ff2d4a'],
  'Emerald':['#03120b','#08201a','#134a36','#e3fff1','#6aa88a','#18e08a'],
  'Amber':['#120c03','#201607','#5a3f12','#fff3dc','#b09260','#ffb020']
};
const lum=h=>{const n=parseInt(h.slice(1),16);return(.299*(n>>16&255)+.587*(n>>8&255)+.114*(n&255))/255};
let TC={'--ac':'#D97757','--dm':'#B0AEA5'};
function applyTheme(n){
  const t=TH[n]||TH['Claude Dark'],r=document.documentElement.style;
  ['--bg','--pn','--ln','--tx','--dm','--ac'].forEach((k,i)=>r.setProperty(k,t[i]));
  r.setProperty('--on',lum(t[5])>.5?'#000':'#fff');
  TC={'--ac':t[5],'--dm':t[4]};S.theme=n;$('#theme').value=n;save();
}

/* ================= SETTINGS ================= */
const SD={theme:'Claude Dark',rot:'auto',pq:75,fps:60,showFps:0,guides:0,
  fft:2048,smooth:50,floor:8,beat:130,bmode:'auto',bsrc:'bass',bmin:30,bcool:180,bdec:90,boff:0,bpm:120,lfo1:.25,lfo2:.5,auto:1,vol:100,pre:0,bass:0,bal:0,speed:100,norm:0,
  rev:0,dly:0,dist:0,eq:[0,0,0,0,0,0,0,0,0,0],sh:0,rp:'off',ratio:'16:9',res:720,
  cw:1280,ch:720,xfps:30,mbps:8,abr:192,fmt:0,t0:0,t1:0};
let S={...SD,...ls.get('av.set',{})};
if(S.cv!==4){S.theme='Claude Dark';S.cv=4;S.cc=1}
let svT;const save=()=>{clearTimeout(svT);svT=setTimeout(()=>ls.set('av.set',S),250)};
addEventListener('pagehide',()=>ls.set('av.set',S));
$('#theme').innerHTML=Object.keys(TH).map(k=>`<option>${k}</option>`).join('');

/* ================= AUDIO ENGINE ================= */
const EQF=[32,64,125,250,500,1000,2000,4000,8000,16000];
let ac,an,vg,dest,pre,bb,pan,fil=[],sD,sY,sR,shp,dly,fb,rvb,aL,aR,
    fd=new Uint8Array(1024),td=new Uint8Array(2048).fill(128),
    tL=new Uint8Array(512).fill(128),tR=new Uint8Array(512).fill(128);
let files=[],cur=-1,lt=0,rec=null,sleepT=null;

const au={
  buf:null,off:0,t0:0,rate:1,node:null,paused:true,norm:1,
  onplay(){},onpause(){},onended(){},
  get duration(){return this.buf?this.buf.duration:NaN},
  get currentTime(){
    if(!this.buf||!ac)return 0;
    const t=this.paused?this.off:this.off+(ac.currentTime-this.t0)*this.rate;
    return clamp(t,0,this.buf.duration);
  },
  set currentTime(v){
    if(!this.buf)return;
    const w=!this.paused;this.stop();
    this.off=clamp(v,0,this.buf.duration-.05);
    if(w)this.play();
  },
  setRate(r){
    if(!this.paused&&ac){this.off=this.currentTime;this.t0=ac.currentTime}
    this.rate=r;if(this.node)this.node.playbackRate.value=r;
  },
  play(){
    if(!this.buf||!this.paused||!ac)return;
    ac.resume();
    const n=ac.createBufferSource();
    n.buffer=this.buf;n.playbackRate.value=this.rate;n.connect(pre);
    n.onended=()=>{
      if(au.node===n){au.stop();au.off=0;au.onpause();au.onended()}
    };
    n.start(0,this.off);this.node=n;this.t0=ac.currentTime;this.paused=false;this.onplay();
  },
  pause(){
    if(this.paused)return;
    this.off=this.currentTime;this.stop();this.onpause();
  },
  stop(){
    const n=this.node;this.node=null;this.paused=true;
    if(n){n.onended=null;try{n.stop()}catch(e){}n.disconnect()}
  }
};

function initAudio(){
  if(ac)return true;
  const C=window.AudioContext||window.webkitAudioContext;
  if(!C){toast('Web Audio is not supported here','err');return false}
  ac=new C();pre=ac.createGain();let n=pre;
  fil=EQF.map((f,i)=>{
    const b=ac.createBiquadFilter();
    b.type=i==0?'lowshelf':i==9?'highshelf':'peaking';
    b.frequency.value=f;b.Q.value=1;n.connect(b);n=b;return b;
  });
  bb=ac.createBiquadFilter();bb.type='lowshelf';bb.frequency.value=120;n.connect(bb);
  const st=(i,p)=>{const o=ac.createGain(),d=ac.createGain(),w=ac.createGain();
    i.connect(d);d.connect(o);i.connect(p);p.connect(w);w.connect(o);w.gain.value=0;return{o,w,d}};
  shp=ac.createWaveShaper();sD=st(bb,shp);
  dly=ac.createDelay(2);dly.delayTime.value=.32;fb=ac.createGain();fb.gain.value=.4;
  dly.connect(fb);fb.connect(dly);sY=st(sD.o,dly);
  rvb=ac.createConvolver();
  const len=ac.sampleRate*2,ir=ac.createBuffer(2,len,ac.sampleRate);
  for(let c=0;c<2;c++){const d=ir.getChannelData(c);
    for(let i=0;i<len;i++)d[i]=(Math.random()*2-1)*Math.pow(1-i/len,2.5)}
  rvb.buffer=ir;sR=st(sY.o,rvb);
  pan=ac.createStereoPanner?ac.createStereoPanner():ac.createGain();sR.o.connect(pan);
  an=ac.createAnalyser();vg=ac.createGain();dest=ac.createMediaStreamDestination();
  pan.connect(an);an.connect(vg);vg.connect(ac.destination);pan.connect(dest);
  const sp=ac.createChannelSplitter(2);
  aL=ac.createAnalyser();aR=ac.createAnalyser();aL.fftSize=aR.fftSize=512;
  pan.connect(sp);sp.connect(aL,0);sp.connect(aR,1);
  applyAudio();return true;
}
let lastDist=-1;
const sp=(p,v)=>p.setTargetAtTime(v,ac.currentTime,.02);
function applyAudio(){
  if(!ac)return;
  sp(pre.gain,Math.pow(10,S.pre/20)*au.norm);
  sp(bb.gain,S.bass);
  if(pan.pan)pan.pan.value=S.bal/100;
  sp(vg.gain,S.vol/100);
  sD.w.gain.value=S.dist/100;sD.d.gain.value=1-S.dist/200;
  if(S.dist!==lastDist){
    lastDist=S.dist;const k=1+S.dist*.8,c=new Float32Array(2048);
    for(let i=0;i<2048;i++){const x=i/1024-1;c[i]=Math.tanh(k*x)/Math.tanh(k)}
    shp.curve=c;
  }
  sY.w.gain.value=S.dly/100;sY.d.gain.value=1;
  sR.w.gain.value=S.rev/100;
  fil.forEach((f,i)=>sp(f.gain,S.eq[i]));
  au.setRate(S.speed/100);
  if(an.fftSize!==+S.fft){an.fftSize=+S.fft;fd=new Uint8Array(an.frequencyBinCount);td=new Uint8Array(an.fftSize)}
  an.smoothingTimeConstant=S.smooth/100;
}

/* ================= ANALYSIS ================= */
const BH={sub:[20,60],bass:[60,250],lowmid:[250,500],mid:[500,2000],upmid:[2000,4000],presence:[4000,6000],brill:[6000,20000]};
const SRCS=['bass','sub','lowmid','mid','upmid','presence','brill','volume','peak','beat','kick','snare','flux','left','right','phase','lfo1','lfo2','rnd'];
let SV={beat:0,phase:0,lfo1:0,lfo2:0,rnd:0},PV={},EN={},hist=[],beatNow=false,lastB=0,
    BT={},bGap=500,rndV=0,mapI=0,mapT=-9,gridK=-1,tapPend=false,aT=0,taps=[],mapSrc=null,mapSR=44100,mapTm;
const BTK=['global','kick','snare','hat','onset','tap','off'];
const bt=e=>e.bsel==='off'?false:(!e.bsel||e.bsel==='global')?beatNow:!!BT[e.bsel];
function bandAvg(a,b){
  const sr=ac?ac.sampleRate:44100,hz=sr/2/fd.length;
  let i=Math.max(0,Math.floor(a/hz)),j=Math.max(i+1,Math.min(fd.length,Math.ceil(b/hz))),s=0;
  for(let k=i;k<j;k++)s+=fd[k];
  return s/(j-i)/255;
}
const rms=a=>{let r=0;for(let i=0;i<a.length;i++){const x=(a[i]-128)/128;r+=x*x}return clamp(Math.sqrt(r/a.length)*2.2,0,1)};
const RS={},pkS={};
const edge=(k,v,th)=>{const r=v>=th&&!pkS[k];pkS[k]=v>=th;return r};
function mapBeat(t){
  if(Math.abs(t-mapT)>.4){
    let lo=0,hi=beatT.length;
    while(lo<hi){const m=(lo+hi)>>1;if(beatT[m]<=t)lo=m+1;else hi=m}
    mapI=lo;mapT=t;return false;
  }
  let hit=false;
  while(mapI<beatT.length&&beatT[mapI]<=t){hit=true;mapI++}
  mapT=t;return hit;
}
function analyse(now){
  const dt=Math.min(.1,Math.max(.001,(now-(aT||now-16))/1000));aT=now;
  if(an){an.getByteFrequencyData(fd);an.getByteTimeDomainData(td);aL.getByteTimeDomainData(tL);aR.getByteTimeDomainData(tR)}
  const fl=S.floor/100,g=v=>clamp((v-fl)/(1-fl),0,1);let fx=0;
  for(const k in BH){const v=g(bandAvg(...BH[k])),d=Math.max(0,v-(PV[k]||0));fx+=d;RS[k]=d;PV[k]=v;SV[k]=v}
  SV.volume=rms(td);SV.left=rms(tL);SV.right=rms(tR);
  let pk=0;for(let i=0;i<fd.length;i++)if(fd[i]>pk)pk=fd[i];
  SV.peak=pk/255;
  for(const k of ['bass','upmid']){
    const f=Math.max(0,SV[k]-(EN['p'+k]||0));EN['p'+k]=SV[k];
    EN[k]=Math.max(f*5,(EN[k]||0)*.88);
  }
  SV.kick=Math.min(1,EN.bass||0);SV.snare=Math.min(1,EN.upmid||0);
  SV.flux=Math.min(1,(EN.f||0)*.9+fx*2);EN.f=SV.flux;
  /* beat engine: auto (live), map (pre-scanned, zero latency), bpm grid, off */
  const playing=!!an&&!au.paused,ct=au.currentTime,off=S.boff/1000,mode=S.bmode;
  beatNow=false;
  if(playing&&mode==='auto'){
    const x=SV[S.bsrc]||0,m=hist.length?hist.reduce((a,b)=>a+b,0)/hist.length:0;
    beatNow=x>S.bmin/100&&x>m*S.beat/100&&now-lastB>S.bcool;
    hist.push(x);const cap=Math.max(12,Math.round(S.fps*.7));
    while(hist.length>cap)hist.shift();
  }else if(playing&&mode==='map'&&beatT.length){
    beatNow=mapBeat(ct+off)&&now-lastB>S.bcool*.5;
  }else if(playing&&mode==='bpm'){
    const k=Math.floor((ct+off)*S.bpm/60);
    if(gridK<0||Math.abs(k-gridK)>1)gridK=k;
    else if(k!==gridK){gridK=k;beatNow=true}
  }
  BT.tap=false;
  if(tapPend){tapPend=false;beatNow=true;BT.tap=true}
  if(beatNow){
    const gp=now-lastB;if(gp>200&&gp<2000)bGap+=(gp-bGap)*.3;
    lastB=now;rndV=Math.random();
    if(SC.hueB)SC._h=((SC._h||0)+SC.hueB)%360;
  }
  BT.global=beatNow;
  BT.kick=edge('kick',SV.kick,.55);BT.snare=edge('snare',SV.snare,.5);
  BT.hat=edge('hat',clamp(((RS.brill||0)+(RS.presence||0))*5,0,1),.35);
  BT.onset=edge('onset',SV.flux,.5);
  SV.beat=beatNow?1:SV.beat*Math.pow(S.bdec/100,dt*60);
  SV.phase=mode==='bpm'?((((ct+off)*S.bpm/60)%1)+1)%1:clamp((now-lastB)/bGap,0,1);
  const sec=now/1000,q=(sec*S.lfo2)%1;
  SV.lfo1=(Math.sin(sec*S.lfo1*TAU)+1)/2;SV.lfo2=q<.5?q*2:2-q*2;
  SV.rnd+=(rndV-SV.rnd)*Math.min(1,dt*9);
}

/* ================= LAYER MODEL ================= */
const COMMON={
  type:'bars',name:'',vis:1,lock:0,
  x:50,y:50,sx:100,sy:100,uni:0,rot:0,spin:0,
  op:100,blend:'source-over',
  band:'all',f0:30,f1:16000,fsc:'log',sens:100,att:60,rel:15,
  color:'#ffffff',color2:'#6a6a75',color3:'#00e6a8',three:0,
  grad:'linear',gAng:90,gAnim:0,
  glow:0,shOff:0,blur:0,hue:0,bright:100,contr:100,sat:100,
  anim:'none',as:1,aa:30,
  rep:1,repang:0,repox:0,repoy:0,repsc:0,repfade:0,
  rmode:'continuous',rmin:-30,rmax:30,rspd:.5,rstep:45,rease:'smooth',
  rbeat:0,rpx:0,rpy:0,flx:0,fly:0,raud:0,rsrc:'bass',rph:0,
  mm:'none',mspd:.3,mrx:10,mry:8,mph:0,mbp:0,bk:0,bkd:10,bsel:'global',skx:0,sky:0,bop:0,bgl:0
};
const TYPE_DEFAULTS={
  bars:{n:48,thick:70,round:0,maxH:100,dir:'up',mir:'none',rvar:0,
    flipInput:0,heightScale:100,minHeightScale:0,maxHeightScale:100,
    colorBlendOffset:0,barsShapePath:'line',
    seg1ColorFrom:'#ffffff',seg1ColorTo:'#000000',seg1Fixed:0,seg1Mul:100,seg1Mirror:0,seg1Flip:0,
    seg2ColorFrom:'#ffffff',seg2ColorTo:'#000000',seg2Fixed:0,seg2Mul:100,seg2Mirror:0,seg2Flip:0,
    softness:0,reactDelay:0,reactAcc:0},
  barsh:{n:40,thick:70,round:0,maxH:100,mir:'none',rvar:0,dir:'up'},
  circle:{n:96,thick:50,r0:60,a0:-90,a1:270,maxH:100,odir:'out',mir:'none'},
  multiring:{n:48,thick:40,r0:50,rings:3,a0:-90,a1:270,maxH:100,odir:'out'},
  concentric:{rings:6,thick:20,maxH:100},
  ring:{thick:30,r0:60,sens:120},
  pulse:{thick:20,r0:40},
  wave:{n:100,thick:30,maxH:100,ws:'line',wfill:0,mir:'none'},
  wavec:{thick:25,r0:70,maxH:100},
  scope:{n:100,thick:20,ws:'line'},
  dots:{n:36,thick:70,maxH:100},
  lines:{n:48,thick:70,maxH:100},
  blocks:{n:28,seg:12,thick:80,maxH:100},
  grid:{n:48,seg:24,maxH:100},
  particles:{n:120,countLimit:200,thick:40,
    mirrorX:0,mirrorY:0,perspectiveDepth:0,perspectiveFov:60,spawnArea:100,
    vectorRelCenter:0,useRandomVector:0,vectorAngle:0,vectorAngleZ:0,forceField:0,
    particleScale:100,trailLength:0,colorFrom:'#ffffff',colorTo:'#000000',
    fadeInOutTime:0,velocityAngle:0,sizeAspect:1,startSize:2,fadeInSize:0,
    endSize:4,endSizeRandom:0,endSizeZFactor:0,nearCamFadeOut:0,
    spawnTime:1,measureSpeed:'none',speedAX:0,speedBY:0,speed:2,speedRandom:0,
    lifetime:2,gravity:0,
    sideSineFreq:0,sideSineFreqRandom:0,sideSineMag:0,sideSineMagRandom:0},
  shape:{shape:'circle',sides:5,maxH:100,sw:0,sc:'#ffffff'},
  text:{text:'AUDIOVIS',font:'Archivo',fz:12,fw:800,fi:0,ls:0,up:0,al:'center',
    tlh:120,tbeat:35,tty:0,sw:0,sc:'#ffffff',
    tbox:0,tbc:'#000000',tbo:60,tpad:14,trad:10},
  image:{maxH:100,imgMask:'none',imgKeepAspect:1,imgColorKeyEnabled:0,
    imgColorKey:'#00ff00',imgTransparencyStrength:100,imgBlurEnabled:0,imgBlurRadius:4,
    imgSaturation:100,imgColor:'#ffffff',imgColorTo:'#000000'},
  bg:{color:'#000000',color2:'#000000',three:0,grad:'linear',gAng:90}
};
const D={...COMMON};
for(const k in TYPE_DEFAULTS)Object.assign(D,TYPE_DEFAULTS[k]);
const mk=(type,o={})=>({...COMMON,...(TYPE_DEFAULTS[type]||{}),type,name:type,rules:[],...o});
const B=(a,b,o={})=>mk('bg',{color:a,color2:b,...o});
const SCD={bg:'#000000',cam:0,vig:0,scan:0,grain:0,flash:0,shake:0,hueB:0};

/* ================= PRESETS ================= */
const PRE=[
['Minimal','Thin Lines',[B('#000','#0a0a0a'),mk('bars',{n:90,thick:18,y:78,color:'#fff',color2:'#fff'})]],
['Minimal','Dot Spectrum',[B('#000','#0a0a0a'),mk('dots',{n:36,y:70})]],
['Minimal','Mono Bars',[B('#000','#111'),mk('bars',{n:48,y:80,color2:'#555'})]],
['Classic','Classic Vertical',[B('#000','#0b1018'),mk('bars',{n:64,y:78,color:'#00e6a8',color2:'#6d8cff'})]],
['Classic','Classic Horizontal',[B('#000','#0b1018'),mk('barsh',{n:40,x:12,y:50})]],
['Classic','Rounded Bars',[B('#000','#111'),mk('bars',{n:32,y:78,round:50,thick:60})]],
['Mirror','Mirrored Bars',[B('#000','#0d0d0d'),mk('bars',{n:72,y:58,mir:'mirror',color2:'#888'})]],
['Mirror','Symmetrical',[B('#000','#0d0d0d'),mk('bars',{n:80,y:50,mir:'sym',dir:'both'})]],
['Circular','Circular Pulse',[B('#000','#0a0a14'),mk('circle',{n:90,thick:50,glow:8})]],
['Circular','Radial Burst',[B('#000','#0a0a0a'),mk('circle',{n:150,thick:30,r0:30})]],
['Circular','Dual Rings',[B('#000','#0a0a1e'),mk('circle',{n:80,r0:40,maxH:60}),mk('circle',{n:120,r0:80,band:'upmid',f0:2000,f1:16000,color:'#ff6b9d',color2:'#ff6b9d'})]],
['Circular','Multi-Ring',[B('#000','#0a0a1e'),mk('multiring',{n:40,rings:3,thick:60})]],
['Circular','Concentric',[B('#000','#0a0a1e'),mk('concentric',{rings:8,thick:30})]],
['Circular','Audio Ring',[B('#000','#0a0a14'),mk('ring',{thick:30,glow:12})]],
['Waveform','Line Wave',[B('#000','#0a0a14'),mk('wave',{n:100,thick:30,color:'#00e6a8',color2:'#00e6a8'})]],
['Waveform','Filled Wave',[B('#000','#0a0a14'),mk('wave',{n:100,wfill:1,thick:20,color:'#6d8cff',color2:'#6d8cff'})]],
['Waveform','Circular Wave',[B('#050010','#0a0018'),mk('wavec',{thick:25,color:'#00f0ff',color2:'#00f0ff'})]],
['Waveform','Oscilloscope',[B('#001a08','#000'),mk('scope',{thick:20,glow:14,color:'#37ff7a',color2:'#37ff7a'})]],
['Neon','Neon Bars',[B('#05010f','#12002a'),mk('bars',{n:56,y:78,glow:20,color:'#00f0ff',color2:'#ff2bd6'})]],
['Neon','Neon Wave',[B('#05010f','#12002a'),mk('wave',{n:100,glow:20,thick:30,color:'#00f0ff',color2:'#00f0ff'}),mk('wave',{n:100,y:56,sy:80,glow:20,thick:30,color:'#ff2bd6',color2:'#ff2bd6'})]],
['Retro','Retro Sunset',[B('#1a0033','#ff6a00'),mk('blocks',{n:24,seg:10,y:85,color:'#ffd000',color2:'#ff2d95'})]],
['Retro','Equalizer Blocks',[B('#000','#0a0a0a'),mk('blocks',{n:28,seg:12,y:80,thick:80,color:'#37ff7a',color2:'#ffdd00'})]],
['Particles','Particle Burst',[B('#000','#1a001a'),mk('particles',{n:120,color:'#ff00ff',color2:'#ff00ff'})]],
['Particles','Bass Particles',[B('#000','#1a0505'),mk('particles',{n:80,band:'bass',f0:60,f1:250,color:'#ff3b30',color2:'#ff3b30'}),mk('circle',{n:48,maxH:50,band:'bass',f0:60,f1:250})]],
['Geometry','Triangle Pulse',[B('#000','#0a0a1a'),mk('shape',{shape:'triangle',color:'#00e6a8',color2:'#6d8cff',band:'bass',f0:60,f1:250})]],
['Geometry','Star Reactor',[B('#000','#0a0a1a'),mk('shape',{shape:'star',sides:6,glow:16,color:'#ffd000',color2:'#ff2d95'})]],
['Geometry','Frequency Grid',[B('#000','#0a0a14'),mk('grid',{n:48,seg:24,y:50,color:'#6d8cff'})]],
['Cinematic','Cinematic Glow',[B('#0a0e14','#1b2430'),mk('wave',{n:100,glow:30,thick:18}),mk('circle',{band:'upmid',f0:2000,f1:16000,glow:22,color:'#99ccff',color2:'#99ccff',sx:130,sy:130,n:120,thick:30})],{vig:60,grain:18}],
['Typography','Beat Title',[B('#111','#000'),mk('text',{band:'bass',f0:60,f1:250,sens:150,y:45,fz:16})]],
['Typography','Title + Spectrum',[B('#000','#111'),mk('text',{y:30}),mk('bars',{n:48,y:88,thick:55})]],
['Bass','Bass Reactor',[B('#000','#1a0505'),mk('circle',{band:'bass',f0:60,f1:250,n:64,glow:15,color:'#ff2d4a',color2:'#ff2d4a'}),mk('shape',{band:'bass',f0:60,f1:250,color:'#ff2d4a',color2:'#ff2d4a',maxH:20})]],
['Claude','Ember Rings',[B('#262624','#30302E'),mk('circle',{n:96,thick:40,color:'#d97757',color2:'#f0b9a0',glow:10}),mk('concentric',{rings:5,thick:20,color:'#faf9f5',color2:'#b0aea5',op:50})]],
['Claude','Paper Wave',[B('#faf9f5','#e8e6dc'),mk('wave',{n:120,thick:30,color:'#c15f3c',color2:'#c15f3c'}),mk('wave',{n:120,y:58,sy:60,thick:20,color:'#141413',color2:'#141413',op:50})]],
['Claude','Terracotta Bars',[B('#262624','#1f1e1d'),mk('bars',{n:48,y:80,round:20,color:'#d97757',color2:'#faf9f5'})]],
['Pro','Aurora',[B('#05080f','#0b1a2a'),mk('bars',{n:72,y:78,glow:18,three:1,color:'#00e6a8',color2:'#6d8cff',color3:'#ff6bd6',mir:'mirror'})]],
['Pro','Vinyl',[B('#0c0c0c','#1a1a1a'),mk('ring',{r0:95,thick:12,color:'#d97757',color2:'#d97757'}),mk('shape',{shape:'circle',maxH:30,color:'#d97757',color2:'#d97757',sw:3})]],
['Pro','Pro Equaliser',[B('#111','#000'),mk('blocks',{n:32,seg:14,y:82,thick:80,color:'#37ff7a',color2:'#ff4d5e'})]],
['Typography','Serif Title',[B('#faf9f5','#e8e6dc'),mk('text',{text:'Now Playing',fz:14,y:42,font:'Playfair Display',color:'#141413',color2:'#141413',band:'bass',f0:60,f1:250}),mk('bars',{n:60,y:88,thick:30,color:'#c15f3c',color2:'#c15f3c'})]],
['Particles','Galaxy',[B('#02030a','#0a0a24'),mk('particles',{n:140,color:'#9fb7ff',color2:'#9fb7ff'}),mk('multiring',{n:48,rings:3,thick:30,spin:6,glow:10,color:'#d97757',color2:'#6d8cff'})]],
['Bass','Sub Drop',[B('#000','#0a0a14'),mk('shape',{band:'sub',f0:20,f1:60,sens:200,color:'#6d8cff',color2:'#00e6a8',glow:20})],{cam:6,flash:12}]
];

const PL=[['Sunset','#ff7a18','#ff2d95','#ffd000','#1a0533','#2b0a1f'],['Ocean','#00c6ff','#0072ff','#00ffc8','#001224','#02273d'],['Forest','#37ff7a','#00b36b','#d4ff3a','#02130a','#0a2416'],['Candy','#ff6bd6','#6d8cff','#fff06b','#1a0a24','#250a35'],['Ice','#e0f7ff','#7fd1ff','#ffffff','#08121c','#11263a'],['Lava','#ff3d00','#ffb300','#fff176','#150402','#2a0a05'],['Violet','#b44dff','#6d8cff','#ff6bd6','#0d0617','#1d0b36'],['Mint','#18e08a','#00e5ff','#ffffff','#021310','#06262a'],['Gold','#ffd000','#ff9500','#fff3b0','#120c03','#241606'],['Rose','#ff5d8f','#ff99ac','#ffd6e0','#1a0610','#2e0b1c'],['Cyber','#00f0ff','#ff2bd6','#f5ff00','#05010f','#12002a'],['Slate','#cfd8e6','#8794a8','#ffffff','#10151c','#1e2530']];
const FN=['Orbitron','Bebas Neue','Playfair Display','Montserrat','Righteous','Anton','Poppins','Audiowide','Oswald','Raleway','Bungee','Cinzel'];
const LY=[
['Spectrum','Bars',(p,i)=>[B(p[4],p[5]),mk('bars',{n:56,y:78,glow:14,three:1,color:p[1],color2:p[2],color3:p[3]})]],
['Mirror','Mirror',(p,i)=>[B(p[4],p[5]),mk('bars',{n:72,y:55,mir:'mirror',glow:10,color:p[1],color2:p[2]})]],
['Radial','Ring',(p,i)=>[B(p[4],p[5]),mk('circle',{n:96,thick:45,glow:12,color:p[1],color2:p[2]}),mk('concentric',{rings:5,thick:20,op:55,color:p[3],color2:p[2]})]],
['Waveform','Wave',(p,i)=>[B(p[4],p[5]),mk('wave',{n:100,glow:16,thick:30,color:p[1],color2:p[1]}),mk('wave',{n:100,y:58,sy:70,thick:20,op:60,color:p[2],color2:p[2]})]],
['Retro','Blocks',(p,i)=>[B(p[4],p[5]),mk('blocks',{n:24,seg:12,y:82,thick:80,color:p[1],color2:p[3]})]],
['Particles','Orbit',(p,i)=>[B(p[4],p[5]),mk('multiring',{n:44,rings:3,thick:35,spin:8,glow:10,color:p[1],color2:p[2]}),mk('particles',{n:100,color:p[3],color2:p[3]})]],
['Typography','Title',(p,i)=>[B(p[4],p[5]),mk('text',{text:p[0].toUpperCase(),font:FN[i%FN.length],fz:16,y:38,band:'bass',f0:60,f1:250,sens:150,glow:10,color:p[1],color2:p[3]}),mk('bars',{n:48,y:88,thick:55,color:p[2],color2:p[3]})],{vig:45}],
['Geometry','Shape',(p,i)=>[B(p[4],p[5]),mk('shape',{shape:['star','triangle','poly','circle'][i%4],sides:5+i%3,glow:16,band:'bass',f0:60,f1:250,color:p[1],color2:p[2]}),mk('ring',{thick:25,glow:10,color:p[3],color2:p[3]})],{cam:5}]
];
PL.forEach((p,i)=>LY.forEach(y=>PRE.push([y[0],p[0]+' '+y[1],y[2](p,i),y[3]])));

let L=[],SC={...SCD},user=ls.get('av.presets',[]),favs=ls.get('av.favs',[]),
    sel=1,tab='lib',open=false,copyBuf=null;
const clone=o=>JSON.parse(JSON.stringify(o,(k,v)=>k[0]==='_'?undefined:v));
const RD={on:1,src:'bass',prop:'sy',i0:0,i1:1,o0:100,o1:130,cv:'linear',inv:0,att:60,rel:15};
const norm=l=>({...D,...l,rules:(l.rules||[]).map(r=>({...RD,...r}))});
function setProject(layers,sc){L=layers.map(norm);SC={...SCD,...(sc||{})};sel=Math.min(L.length-1,1)}
{const p=ls.get('av.proj',null);
 if(p&&Array.isArray(p.layers)&&p.layers.length)setProject(p.layers,p.scene);
 else if(Array.isArray(p)&&p.length)setProject(p);
 else setProject(PRE[3][2])}
let hu=[],hr=[],lastH=0,sT;
const snap=()=>JSON.stringify({L:clone(L),SC});
function touch(){
  const n=performance.now();
  if(n-lastH>900){hu.push(snap());if(hu.length>50)hu.shift();hr=[]}
  lastH=n;clearTimeout(sT);sT=setTimeout(()=>ls.set('av.proj',{v:4,layers:clone(L),scene:SC}),400);
}
function restore(j){
  const o=JSON.parse(j);L=o.L.map(norm);SC=o.SC;
  sel=clamp(sel,0,L.length-1);renderAll();
  ls.set('av.proj',{v:4,layers:clone(L),scene:SC});
}
const undo=()=>{if(hu.length){hr.push(snap());restore(hu.pop());lastH=0}};
const redo=()=>{if(hr.length){hu.push(snap());restore(hr.pop());lastH=0}};

/* ================= DRAW ================= */
const CURV={linear:x=>x,ease_in:x=>x*x,ease_out:x=>1-(1-x)*(1-x),smooth:x=>x*x*(3-2*x),sqrt:Math.sqrt,step:x=>x>.5?1:0};
function eff(l,t){
  l._s=l._s||[];l._p=l._p||[];l._rv=l._rv||[];l._h=l._h||[];
  const e=l._e||(l._e={});
  Object.assign(e,l);
  for(const r of l.rules){
    if(!r.on)continue;
    let x=SV[r.src]||0;x=clamp((x-r.i0)/Math.max(.001,r.i1-r.i0),0,1);
    x=CURV[r.cv](x);if(r.inv)x=1-x;
    const o=r._v||0;x=o+(x-o)*(x>o?r.att/100:r.rel/100);r._v=x;
    e[r.prop]=r.o0+(r.o1-r.o0)*x;
  }
  const a=e.aa/100,s=t*e.as;
  if(e.anim==='pulse'){const k=1+Math.sin(s*TAU)*a*.3;e.sx*=k;e.sy*=k}
  else if(e.anim==='bounce')e.y-=Math.abs(Math.sin(s*Math.PI))*a*12;
  else if(e.anim==='sway')e.x+=Math.sin(s*TAU)*a*10;
  else if(e.anim==='fade')e.op*=1-a*(1+Math.sin(s*TAU))/2;
  return e;
}
function fpos(l,t){
  const a=Math.max(20,l.f0),b=Math.max(a+1,l.f1);
  if(l.fsc==='linear')return a+(b-a)*t;
  if(l.fsc==='mel'){const m=x=>2595*Math.log10(1+x/700);return 700*(Math.pow(10,(m(a)+(m(b)-m(a))*t)/2595)-1)}
  return a*Math.pow(b/a,t);
}
function bv(l,i,n){
  let v=bandAvg(fpos(l,i/n),fpos(l,(i+1)/n));
  const fl=S.floor/100;v=clamp((v-fl)/(1-fl),0,1);
  const o=l._s[i]||0;v=o+(v-o)*(v>o?l.att/100:l.rel/100);
  l._s[i]=v;return clamp(v*l.sens/100,0,1.5);
}
function fillFor(l,bx,by,bw,bh,t){
  if(l.grad==='solid')return l.color;
  const cx=bx+bw/2,cy=by+bh/2,a=(l.gAng+l.gAnim*t)*Math.PI/180,d=Math.hypot(bw,bh)/2;let g;
  if(l.grad==='radial')g=ctx.createRadialGradient(cx,cy,0,cx,cy,d);
  else if(l.grad==='conic'&&ctx.createConicGradient)g=ctx.createConicGradient(a,cx,cy);
  else g=ctx.createLinearGradient(cx-Math.cos(a)*d,cy-Math.sin(a)*d,cx+Math.cos(a)*d,cy+Math.sin(a)*d);
  const cs=l.three?[l.color,l.color2,l.color3]:[l.color,l.color2];
  cs.forEach((c,i)=>g.addColorStop(i/(cs.length-1),c));return g;
}
function rr(x,y,w,h,r){
  if(r>0&&ctx.roundRect){ctx.beginPath();ctx.roundRect(x,y,w,h,Math.min(r,w/2,h/2));ctx.fill()}
  else ctx.fillRect(x,y,w,h);
}
function pr(p,x,y,w,h,r){
  if(r>0&&p.roundRect)p.roundRect(x,y,w,h,Math.min(r,w/2,h/2));else p.rect(x,y,w,h);
}
function bars(e,W,H,t,hz){
  const span=(hz?H:W)*.8,len=(hz?W:H)*.4*e.maxH/100*e.heightScale/100;
  const n=e.n,sl=span/n,bw=Math.max(1,sl*e.thick/100);
  if(hz)ctx.rotate(Math.PI/2);
  const both=e.dir==='both',dn=e.dir==='down',mirr=e.mir==='mirror';
  ctx.fillStyle=fillFor(e,-span/2,dn?0:-len,span,both?len*2:len,t);
  const nh=Math.ceil(n/2),a0=ctx.globalAlpha,mp=mirr?new Path2D():null;
  ctx.beginPath();
  for(let i=0;i<n;i++){
    let v;
    if(e.mir==='sym')v=bv(e,Math.min(nh-1,Math.floor(Math.abs(i-(n-1)/2))),nh);
    else v=bv(e,e.flipInput?(n-1-i):i,n);
    let h=Math.max(1,v*len*(1-e.rvar/100*(e._rv[i]??(e._rv[i]=Math.random()))));
    h=clamp(h,len*e.minHeightScale/100,len*e.maxHeightScale/100);
    const x=-span/2+i*sl+(sl-bw)/2,r=e.round*bw/100;
    if(!dn)pr(ctx,x,-h,bw,h,r);
    if(dn||both)pr(ctx,x,0,bw,h,r);
    if(mirr)pr(mp,x,0,bw,h*.6,r);
  }
  ctx.fill();
  if(mirr){ctx.globalAlpha=a0*.35;ctx.fill(mp);ctx.globalAlpha=a0}
}
function circ(e,W,H,t,k,K){
  const R=Math.min(W,H)*.22,n=e.n,r0=R*e.r0/100*(1+k*.55);
  const len=R*e.maxH/100*(K>1?.5:1);
  const lw=Math.max(1,TAU*Math.max(r0,10)/n*e.thick/100),nh=Math.ceil(n/2);
  ctx.lineWidth=lw;ctx.strokeStyle=fillFor(e,-R*2,-R*2,R*4,R*4,t);
  ctx.beginPath();
  for(let i=0;i<n;i++){
    const v=e.mir!=='none'?bv(e,i<nh?i:n-1-i,nh):bv(e,i+k*n,n*K);
    const a=(e.a0+(e.a1-e.a0)*i/n)*Math.PI/180;
    const c=Math.cos(a),s=Math.sin(a),l=v*len;
    let p=r0,q=r0+l;
    if(e.odir==='in')q=Math.max(0,r0-l);
    else if(e.odir==='both'){p=Math.max(0,r0-l/2);q=r0+l/2}
    ctx.moveTo(c*p,s*p);ctx.lineTo(c*q,s*q);
  }
  ctx.stroke();
}
function trace(e,W,H,t,circle){
  const R=Math.min(W,H)*.22,w=W*.8,p=Math.max(16,Math.min(td.length,e.n*4));
  const A=(circle?R*.4:H*.2)*e.sens/100*e.maxH/100;
  ctx.lineJoin=ctx.lineCap='round';ctx.lineWidth=e.thick/10+1;
  ctx.strokeStyle=ctx.fillStyle=fillFor(e,-w/2,-H*.2,w,H*.4,t);
  const pt=(i,m)=>{
    const k=Math.floor(i/p*(td.length-1)),v=(td[k]-128)/128*A*m;
    if(circle){const a=i/p*TAU-1.571;return[Math.cos(a)*(R*e.r0/100+v),Math.sin(a)*(R*e.r0/100+v)]}
    return[-w/2+i/p*w,v];
  };
  if(e.type==='scope'){
    const a0=ctx.globalAlpha;ctx.globalAlpha=a0*.2;ctx.lineWidth=1;
    for(let i=0;i<=8;i++){ctx.beginPath();ctx.moveTo(-w/2+w*i/8,-H*.25);ctx.lineTo(-w/2+w*i/8,H*.25);ctx.stroke()}
    for(let i=0;i<=4;i++){ctx.beginPath();ctx.moveTo(-w/2,-H*.25+H*.5*i/4);ctx.lineTo(w/2,-H*.25+H*.5*i/4);ctx.stroke()}
    ctx.globalAlpha=a0;ctx.lineWidth=e.thick/10+1;
  }
  for(const m of (e.mir!=='none'&&!circle)?[1,-1]:[1]){
    ctx.beginPath();
    for(let i=0;i<=p;i++){
      const[x,y]=pt(i,m);
      if(e.ws==='spiky'&&!circle){ctx.moveTo(x,0);ctx.lineTo(x,y)}
      else i?ctx.lineTo(x,y):ctx.moveTo(x,y);
    }
    if(circle)ctx.closePath();
    if(e.wfill&&!circle){
      const a0=ctx.globalAlpha;ctx.lineTo(w/2,0);ctx.lineTo(-w/2,0);
      ctx.globalAlpha=a0*.35;ctx.fill();ctx.globalAlpha=a0;
    }
    ctx.stroke();
  }
}
function poly(n,r,star){
  ctx.beginPath();const m=star?n*2:n;
  for(let i=0;i<m;i++){
    const a=i/m*TAU-1.571,q=star&&i%2?r*.45:r;
    i?ctx.lineTo(Math.cos(a)*q,Math.sin(a)*q):ctx.moveTo(Math.cos(a)*q,Math.sin(a)*q);
  }
  ctx.closePath();
}
function rotA(l,e,t){
  let a=e.rot+e.rph;const m=e.rmode;
  const hr=k=>{const x=Math.sin(k*127.1+311.7)*43758.5453;return x-Math.floor(x)};
  if(m==='continuous')a+=e.spin*t;
  else if(m==='pendulum')a+=e.rmin+(e.rmax-e.rmin)*CURV[e.rease]((Math.sin(t*e.rspd*TAU)+1)/2);
  else if(m==='steps'){const k=Math.floor(t*e.rspd),f=t*e.rspd-k;a+=(k+CURV.smooth(Math.min(1,f*3)))*e.rstep}
  else if(m==='random'){
    const k=Math.floor(t*e.rspd),f=t*e.rspd-k;
    const A=Math.max(Math.abs(e.rmin),Math.abs(e.rmax));
    const r1=(hr(k)*2-1)*A,r0=(hr(k-1)*2-1)*A;
    a+=r0+(r1-r0)*CURV.smooth(Math.min(1,f*3));
  }
  a+=l._rd||0;if(e.raud)a+=(SV[e.rsrc]||0)*e.raud;
  return a;
}

function layerDraw(l,W,H,t,ro){
  if(!ro&&l.rep>1&&l.type!=='bg'){
    for(let r=0;r<l.rep;r++)layerDraw(l,W,H,t,{r});
    return;
  }
  const e=eff(l,t),u=H/720;
  if(SC._h&&e.type!=='bg')e.hue=(e.hue||0)+SC._h;
  ctx.save();
  ctx.globalAlpha=clamp(e.op/100,0,1);
  ctx.globalCompositeOperation=e.blend;
  if(e.blur||e.hue||e.bright!==100||e.contr!==100||e.sat!==100)
    ctx.filter=`hue-rotate(${e.hue}deg) brightness(${e.bright}%) contrast(${e.contr}%) saturate(${e.sat}%) blur(${e.blur*u}px)`;

  if(e.type==='bg'){ctx.fillStyle=fillFor(e,0,0,W,H,t);ctx.fillRect(0,0,W,H);ctx.restore();return}

  if(!ro||!ro.r){
    if(bt(e)){l._rb=(l._rb||0)+e.rbeat;l._k=1}
    l._rd=(l._rd||0)+((l._rb||0)-(l._rd||0))*.2;
    l._k=(l._k||0)*Math.max(0,1-e.bkd/40);
  }
  {
    const ts=t*e.mspd*TAU+e.mph*Math.PI/180,mm=e.mm;
    let dx=0,dy=0;
    if(mm==='drift'){dx=Math.sin(ts)*e.mrx;dy=Math.cos(ts*.7)*e.mry}
    else if(mm==='orbit'){dx=Math.cos(ts)*e.mrx;dy=Math.sin(ts)*e.mry}
    else if(mm==='figure8'){dx=Math.sin(ts)*e.mrx;dy=Math.sin(ts*2)*e.mry}
    else if(mm==='bounce')dy=-Math.abs(Math.sin(ts/2))*e.mry*2;
    else if(mm==='shake'){dx=(Math.random()-.5)*e.mrx*e.mspd;dy=(Math.random()-.5)*e.mry*e.mspd}
    const kk=1+(l._k||0)*e.bk/100;
    if(e.bop)ctx.globalAlpha=clamp(ctx.globalAlpha+(l._k||0)*e.bop/100,0,1);
    if(e.bgl)e.glow+=(l._k||0)*e.bgl;
    const rs=ro?1+ro.r*e.repsc/100:1;
    let X=e.x+dx+(ro?ro.r*e.repox:0);
    let Y=e.y+dy-(l._k||0)*e.mbp+(ro?ro.r*e.repoy:0);
    if(ro)ctx.globalAlpha*=Math.max(0,1-ro.r*e.repfade/100);
    ctx.translate(X/100*W,Y/100*H);
    const pvx=e.rpx/100*W,pvy=e.rpy/100*H;
    ctx.translate(pvx,pvy);
    ctx.rotate((rotA(l,e,t)+(ro?ro.r*e.repang:0))*Math.PI/180);
    ctx.translate(-pvx,-pvy);
    ctx.scale(e.sx*kk*rs/100*(e.flx?-1:1),e.sy*kk*rs/100*(e.fly?-1:1));
    if(e.skx||e.sky)ctx.transform(1,Math.tan(e.sky*Math.PI/180),Math.tan(e.skx*Math.PI/180),1,0,0);
  }
  if(e.shOff){ctx.shadowColor='#000';ctx.shadowOffsetY=e.shOff*u;ctx.shadowBlur=Math.max(e.glow,6)*u}
  else if(e.glow){ctx.shadowColor=e.color;ctx.shadowBlur=e.glow*u}

  const R=Math.min(W,H)*.22,ty=e.type;

  if(ty==='bars')bars(e,W,H,t,0);
  else if(ty==='barsh')bars(e,W,H,t,1);
  else if(ty==='circle')circ(e,W,H,t,0,1);
  else if(ty==='multiring'){for(let k=0;k<e.rings;k++)circ(e,W,H,t,k,e.rings)}
  else if(ty==='concentric'){
    ctx.strokeStyle=fillFor(e,-R*2,-R*2,R*4,R*4,t);
    for(let k=0;k<e.rings;k++){
      const v=bv(e,k,e.rings);
      ctx.lineWidth=Math.max(1,u*e.thick/10*(1+v*2));
      ctx.beginPath();
      ctx.arc(0,0,R*(.25+.75*(k+1)/e.rings)*(1+v*.15*e.maxH/100),0,TAU);
      ctx.stroke();
    }
  }
  else if(ty==='ring'){
    const v=bv(e,0,1),p=180;
    ctx.lineWidth=u*e.thick/6*(1+v);
    ctx.strokeStyle=fillFor(e,-R*2,-R*2,R*4,R*4,t);
    ctx.beginPath();
    for(let i=0;i<=p;i++){
      const k=Math.floor(i/p*(td.length-1));
      const r=R*(e.r0/100+v*.25)+(td[k]-128)/128*R*.3*e.sens/100;
      const a=i/p*TAU;
      i?ctx.lineTo(Math.cos(a)*r,Math.sin(a)*r):ctx.moveTo(Math.cos(a)*r,Math.sin(a)*r);
    }
    ctx.closePath();ctx.stroke();
  }
  else if(ty==='pulse'){
    const P=e._p;
    if(bt(e))P.push({r:R*e.r0/100,a:1});
    ctx.strokeStyle=fillFor(e,-R*3,-R*3,R*6,R*6,t);
    ctx.lineWidth=Math.max(1,u*e.thick/8);
    for(let i=P.length-1;i>=0;i--){
      const p=P[i];p.r+=R*.025*(1+SV.bass*2);p.a-=.018;
      if(p.a<=0){P.splice(i,1);continue}
      ctx.globalAlpha=e.op/100*p.a;
      ctx.beginPath();ctx.arc(0,0,p.r,0,TAU);ctx.stroke();
    }
  }
  else if(ty==='wave'||ty==='scope')trace(e,W,H,t,0);
  else if(ty==='wavec')trace(e,W,H,t,1);
  else if(ty==='dots'||ty==='lines'||ty==='blocks'){
    const span=W*.8,len=H*.35*e.maxH/100,n=e.n,sl=span/n;
    ctx.fillStyle=ctx.strokeStyle=fillFor(e,-span/2,-len,span,len,t);
    ctx.lineWidth=Math.max(1,sl*e.thick/100*.4);
    for(let i=0;i<n;i++){
      const v=bv(e,i,n),x=-span/2+i*sl+sl/2;
      if(ty==='dots'){
        ctx.beginPath();
        ctx.arc(x,-v*len,Math.max(2,sl*.35*e.thick/70),0,TAU);
        ctx.fill();
      }else if(ty==='lines'){
        ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,-v*len);ctx.stroke();
        ctx.beginPath();ctx.arc(x,-v*len,ctx.lineWidth,0,TAU);ctx.fill();
      }else{
        const bh=len/e.seg,lit=Math.floor(v*e.seg);
        for(let b=0;b<lit;b++)ctx.fillRect(x-sl*e.thick/200,-(b+1)*bh,sl*e.thick/100,bh*.8);
      }
    }
  }
  else if(ty==='grid'){
    const n=e.n,span=W*.8,cw=span/n,ch=H*.5/e.seg,H2=e._h,row=[];
    for(let i=0;i<n;i++)row.push(bv(e,i,n));
    H2.unshift(row);if(H2.length>e.seg)H2.length=e.seg;
    ctx.fillStyle=fillFor(e,-span/2,-H*.25,span,H*.5,t);
    const a0=ctx.globalAlpha;
    H2.forEach((r,y)=>r.forEach((v,x)=>{
      ctx.globalAlpha=a0*clamp(v,0,1);
      ctx.fillRect(-span/2+x*cw,-H*.25+y*ch,cw*.9,ch*.9);
    }));
    ctx.globalAlpha=a0;
  }
  else if(ty==='particles'){
    const P=e._p,v=bv(e,0,1);
    const cap=e.countLimit||400;
    const spawnN=Math.max(1,Math.floor((e.n||10)*(0.5+v)));
    if((bt(e)||(SV.volume>.35&&Math.random()<.3))&&P.length<cap){
      for(let i=0;i<Math.min(spawnN,cap-P.length);i++){
        const a=(e.velocityAngle||Math.random()*360)*Math.PI/180+(e.useRandomVector?Math.random()*TAU:0);
        const sp=(e.speed||2)*(0.5+Math.random()*(1+(e.speedRandom||0)))*u;
        P.push({x:0,y:0,vx:Math.cos(a)*sp,vy:Math.sin(a)*sp,
          l:1,life:Math.max(.3,e.lifetime||2),
          size:(e.startSize||2)*(e.particleScale||100)/100});
      }
    }
    const a0=ctx.globalAlpha;
    const g=e.gravity||0;
    const z=(e.thick||40)/10*u;
    for(let i=P.length-1;i>=0;i--){
      const p=P[i];
      p.x+=p.vx;p.y+=p.vy;p.vy+=g*u*.03;
      p.l-=.012/p.life;
      if(p.l<=0){P.splice(i,1);continue}
      ctx.globalAlpha=a0*p.l;
      ctx.fillStyle=e.colorFrom||e.color;
      const zz=Math.max(1,z*p.size);
      ctx.fillRect(p.x-zz/2,p.y-zz/2,zz,zz);
    }
    ctx.globalAlpha=a0;
  }
  else if(ty==='shape'){
    const v=bv(e,0,1),r=R*e.maxH/100*(.6+v*.6);
    ctx.fillStyle=fillFor(e,-r,-r,r*2,r*2,t);
    ctx.strokeStyle=e.sc;ctx.lineWidth=e.sw*u;
    if(e.shape==='circle'){ctx.beginPath();ctx.arc(0,0,r,0,TAU)}
    else if(e.shape==='rect'){ctx.beginPath();ctx.rect(-r,-r,r*2,r*2)}
    else if(e.shape==='capsule'){ctx.beginPath();ctx.ellipse(0,0,r*1.6,r*.6,0,0,TAU)}
    else poly(e.shape==='triangle'?3:e.sides,r,e.shape==='star');
    ctx.fill();if(e.sw)ctx.stroke();
  }
  else if(ty==='text'){
    const v=bv(e,0,1),px=H*e.fz/100*(1+v*e.tbeat/100);
    let tx=String(e.text);if(e.up)tx=tx.toUpperCase();
    if(e.tty>0){const nn=tx.length+14;tx=tx.slice(0,Math.min(Math.floor(t*e.tty)%nn,tx.length))}
    const ln=tx.split('|'),lh=px*e.tlh/100;
    ctx.font=`${e.fi?'italic ':''}${e.fw} ${px}px "${e.font}",sans-serif`;
    if('letterSpacing' in ctx)ctx.letterSpacing=e.ls*u+'px';
    ctx.textAlign=e.al;ctx.textBaseline='middle';
    const y0=-(ln.length-1)*lh/2;
    if(e.tbox&&tx){
      const bw=Math.max(...ln.map(q=>ctx.measureText(q).width)),pd=e.tpad*u,bh=ln.length*lh;
      const bx=e.al==='left'?0:e.al==='right'?-bw:-bw/2;
      ctx.save();ctx.globalAlpha*=e.tbo/100;
      ctx.fillStyle=e.tbc;ctx.shadowBlur=0;ctx.shadowOffsetY=0;
      ctx.beginPath();
      if(ctx.roundRect)ctx.roundRect(bx-pd,-bh/2-pd/2,bw+pd*2,bh+pd,e.trad*u);
      else ctx.rect(bx-pd,-bh/2-pd/2,bw+pd*2,bh+pd);
      ctx.fill();ctx.restore();
    }
    ctx.fillStyle=fillFor(e,-W*.3,-H*.1,W*.6,H*.2,t);
    ln.forEach((q,k)=>{
      ctx.fillText(q,0,y0+k*lh);
      if(e.sw){ctx.strokeStyle=e.sc;ctx.lineWidth=e.sw*u;ctx.strokeText(q,0,y0+k*lh)}
    });
  }
  else if(ty==='image'&&e._img&&e._img.width){
    const m=e._img,w=W*.5*e.maxH/100,h=w*m.height/m.width;
    ctx.save();
    if(e.imgBlurEnabled)ctx.filter=(ctx.filter==='none'?'':ctx.filter+' ')+`blur(${e.imgBlurRadius}px)`;
    if(e.imgMask==='circle'){ctx.beginPath();ctx.arc(0,0,Math.min(w,h)/2,0,TAU);ctx.clip()}
    if(e.imgSaturation!==100)ctx.filter=(ctx.filter==='none'?'':ctx.filter+' ')+`saturate(${e.imgSaturation}%)`;
    ctx.drawImage(m,-w/2,-h/2,w,h);
    ctx.restore();
  }
  ctx.restore();
}

function render(t){
  const W=cv.width,H=cv.height,u=H/720;
  ctx.globalAlpha=1;ctx.globalCompositeOperation='source-over';ctx.filter='none';
  ctx.fillStyle=SC.bg;ctx.fillRect(0,0,W,H);
  ctx.save();
  const z=1+SV.bass*SC.cam/100;
  ctx.translate(W/2+(SC.shake?(Math.random()-.5)*SC.shake*SV.beat*u*2:0),H/2+(SC.shake?(Math.random()-.5)*SC.shake*SV.beat*u*2:0));
  ctx.scale(z,z);ctx.translate(-W/2,-H/2);
  L.forEach(l=>{if(l.vis)layerDraw(l,W,H,t)});
  ctx.restore();
  if(SC.vig){
    const g=ctx.createRadialGradient(W/2,H/2,Math.min(W,H)*.3,W/2,H/2,Math.hypot(W,H)/2);
    g.addColorStop(0,'rgba(0,0,0,0)');g.addColorStop(1,`rgba(0,0,0,${SC.vig/100})`);
    ctx.fillStyle=g;ctx.fillRect(0,0,W,H);
  }
  if(SC.scan){
    ctx.fillStyle=`rgba(0,0,0,${SC.scan/100*.5})`;
    ctx.beginPath();for(let y=0;y<H;y+=4*u)ctx.rect(0,y,W,u);ctx.fill();
  }
  if(SC.grain){
    for(let c=0;c<2;c++){
      ctx.fillStyle=c?'rgba(255,255,255,.12)':'rgba(0,0,0,.2)';ctx.beginPath();
      for(let i=0;i<SC.grain*4;i++)ctx.rect(Math.random()*W,Math.random()*H,2*u,2*u);
      ctx.fill();
    }
  }
  if(SC.flash&&SV.beat>.02){
    ctx.fillStyle=`rgba(255,255,255,${SV.beat*SC.flash/100*.5})`;
    ctx.fillRect(0,0,W,H);
  }
  if(!rec){
    ctx.lineWidth=u;
    if(S.guides){
      ctx.strokeStyle='rgba(255,255,255,.3)';
      ctx.beginPath();
      ctx.moveTo(W/2,0);ctx.lineTo(W/2,H);ctx.moveTo(0,H/2);ctx.lineTo(W,H/2);
      for(const f of [1/3,2/3]){ctx.moveTo(W*f,0);ctx.lineTo(W*f,H);ctx.moveTo(0,H*f);ctx.lineTo(W,H*f)}
      ctx.strokeRect(W*.05,H*.05,W*.9,H*.9);ctx.stroke();
    }
    const l=L[sel];
    if(l&&l.type!=='bg'){
      const x=l.x/100*W,y=l.y/100*H,s=10*u;
      ctx.strokeStyle='#00e6a8';
      ctx.beginPath();
      ctx.moveTo(x-s,y);ctx.lineTo(x+s,y);ctx.moveTo(x,y-s);ctx.lineTo(x,y+s);
      ctx.stroke();
    }
  }
}

/* ================= DIMENSIONS ================= */
function dims(){
  let w,h;
  if(S.ratio==='custom'){w=S.cw;h=S.ch}
  else{
    const[a,b]=S.ratio.split(':').map(Number);
    if(a>=b){h=S.res;w=h*a/b}else{w=S.res;h=w*b/a}
  }
  return[Math.round(w/2)*2,Math.round(h/2)*2];
}
function size(full){
  const[w,h]=dims(),q=full?1:S.pq/100*aq;
  cv.width=Math.max(2,Math.round(w*q/2)*2);
  cv.height=Math.max(2,Math.round(h*q/2)*2);
  $('#res').textContent=w+'×'+h;
  const i=$('#xinfo');
  if(i){
    const d=(S.t1>0?Math.min(S.t1,au.duration||0):au.duration||0)-S.t0;
    i.textContent=`Output ${w}×${h} at ${S.xfps} fps`+(d>0?`, ${fmt(d)}, about ${(d*(S.mbps+S.abr/1000)/8).toFixed(1)} MB`:'');
  }
}

/* ================= WAVEFORM ================= */
let peaks=[],beatT=[];
function analyseBuf(b){
  const d=b.getChannelData(0),N=500,st=Math.max(1,Math.floor(d.length/N));
  peaks=[];
  for(let i=0;i<N;i++){
    let m=0;
    for(let j=i*st;j<(i+1)*st&&j<d.length;j+=8)m=Math.max(m,Math.abs(d[j]));
    peaks.push(m);
  }
  mapSrc=d;mapSR=b.sampleRate;buildMap(d,b.sampleRate);
}
function buildMap(d,sr){
  const w=Math.max(1,Math.floor(sr*.01)),n=Math.floor(d.length/w);beatT=[];mapT=-9;
  if(n<20)return;
  const a=Math.exp(-TAU*180/sr),e=new Float32Array(n);let y=0,mx=0;
  for(let i=0;i<n;i++){
    let s=0;for(let j=0,o=i*w;j<w;j++){y=a*y+(1-a)*d[o+j];s+=y*y}
    e[i]=Math.sqrt(s/w);if(e[i]>mx)mx=e[i];
  }
  const f=new Float32Array(n),ps=new Float64Array(n+1);
  for(let i=1;i<n;i++)f[i]=Math.max(0,e[i]-e[i-1]);
  for(let i=0;i<n;i++)ps[i+1]=ps[i]+f[i];
  const R=50,gm=Math.max(10,Math.round(S.bcool/10)),k=S.beat/100*1.6;let last=-99;
  for(let i=2;i<n-2;i++){
    const a0=Math.max(0,i-R),b0=Math.min(n,i+R),m=(ps[b0]-ps[a0])/(b0-a0);
    if(f[i]>m*k&&e[i]>mx*.06&&f[i]>=f[i-1]&&f[i]>=f[i+1]&&i-last>=gm){beatT.push(i*.01);last=i}
  }
}
function rescan(){if(mapSrc&&au.buf){buildMap(mapSrc,mapSR);drawWf()}}
function rescanSoon(){clearTimeout(mapTm);mapTm=setTimeout(rescan,350)}
function estBPM(){
  if(beatT.length<8)return 0;
  const h=new Float32Array(400);
  for(let i=1;i<beatT.length;i++)for(let k=1;k<=3&&i-k>=0;k++){
    let b=60*k/(beatT[i]-beatT[i-k]);while(b<70)b*=2;while(b>=180)b/=2;
    h[Math.round(b*2)]+=1/k;
  }
  let best=0,bi=0;
  for(let i=1;i<399;i++){const s=h[i-1]+h[i]*2+h[i+1];if(s>best){best=s;bi=i}}
  return bi/2;
}
function drawWf(){
  const c=$('#wf'),r=c.getBoundingClientRect(),dpr=devicePixelRatio||1;
  c.width=Math.max(10,r.width*dpr);c.height=Math.max(10,r.height*dpr);
  const x=c.getContext('2d'),W=c.width,H=c.height,cs=getComputedStyle(document.documentElement);
  x.clearRect(0,0,W,H);
  if(!peaks.length)return;
  x.fillStyle=cs.getPropertyValue('--dm');
  peaks.forEach((p,i)=>{
    const h=Math.max(1,p*H*.9);
    x.fillRect(i/peaks.length*W,(H-h)/2,Math.max(1,W/peaks.length-1),h);
  });
  x.fillStyle=cs.getPropertyValue('--ac');
  const d=au.duration;
  beatT.forEach(t=>x.fillRect(t/d*W,0,1,H*.2));
}

/* ================= LIBRARY ================= */
const AX=/\.(mp3|wav|flac|aac|ogg|oga|opus|m4a|mp4|weba|webm|aif|aiff)$/i;
function addFiles(arr,bufs){
  const f=[];
  arr.forEach((x,i)=>{
    if(/^(audio|video)/.test(x.type||'')||AX.test(x.name)){x._ab=bufs[i];f.push(x)}
  });
  if(!f.length){toast('No supported audio files in that selection','err');return}
  files.push(...f);renderQ();
  if(cur<0)load(0);else toast('Added '+f.length+' track(s)','ok');
}
async function load(i,go){
  const f=files[i];if(!f)return false;
  if(!initAudio())return false;
  cur=i;renderQ();const t=++lt;
  $('#tt').textContent='Decoding '+f.name+'...';
  try{
    await ac.resume();
    let ab=await f._ab;if(!ab)ab=await f.arrayBuffer();
    const buf=await new Promise((res,rej)=>{
      const p=ac.decodeAudioData(ab.slice(0),res,rej);
      if(p&&p.then)p.then(res,rej);
    });
    if(t!==lt)return false;
    au.stop();au.buf=buf;au.off=0;
    let pk=0;const d=buf.getChannelData(0);
    for(let j=0;j<d.length;j+=16)pk=Math.max(pk,Math.abs(d[j]));
    au.norm=S.norm&&pk>0?.9/pk:1;
    applyAudio();analyseBuf(buf);
    $('#tt').textContent=f.name;drawWf();size();
    if(go!==false)au.play();
    return true;
  }catch(e){
    if(t===lt){
      $('#tt').textContent='Cannot play '+f.name;
      toast('This device could not decode that file. Try MP3, WAV, OGG or M4A.','err');
    }
    return false;
  }
}
function renderQ(){
  const q=$('#queue');if(!q)return;
  const s=($('#qs')?.value||'').toLowerCase();
  q.innerHTML=files.length
    ? files.map((f,i)=>f.name.toLowerCase().includes(s)
        ? `<div class="it ${i==cur?'sl':''}"><button data-act="qsel" data-i="${i}">${esc(f.name)}</button><button data-act="qrm" data-i="${i}"><svg class="i" style="width:16px;height:16px"><use href="#i-trash"/></svg></button></div>`
        : '').join('')
    : '<div class="dm">No tracks yet. Choose audio files above.</div>';
}
function next(dir){
  if(!files.length)return;
  let i;
  if(S.sh&&dir>0)i=Math.floor(Math.random()*files.length);
  else i=cur+dir;
  if(i>=files.length){if(S.rp==='all')i=0;else return}
  if(i<0)i=0;
  load(i);
}
au.onplay=()=>$$('#pl,#pl2').forEach(b=>b.innerHTML=ico('pause'));
au.onpause=()=>$$('#pl,#pl2').forEach(b=>b.innerHTML=ico('play'));
au.onended=()=>{
  if(rec)return finishRec();
  if(S.rp==='one'){au.off=0;au.play()}else next(1);
};

/* ================= PRESETS UI ================= */
let pf='All';
function allPre(){
  return[...PRE.map(p=>({cat:p[0],name:p[1],layers:p[2],scene:p[3]})),
    ...user.map((p,i)=>({cat:'Mine',name:p.name,layers:p.layers,scene:p.scene,u:i}))];
}
function renderPre(){
  const el=$('#plist');if(!el)return;
  const s=($('#ps')?.value||'').toLowerCase();
  const a=allPre().map((p,i)=>({...p,i}))
    .filter(p=>p.name.toLowerCase().includes(s)&&(pf==='All'||(pf==='Favorites'?favs.includes(p.name):pf===p.cat)))
    .sort((x,y)=>favs.includes(y.name)-favs.includes(x.name));
  el.innerHTML=a.map(p=>`<div class="it"><button data-act="apply" data-i="${p.i}">${esc(p.name)} <span class="dm" style="font-weight:500;margin-left:4px">${p.cat}</span></button><button data-act="fav" data-i="${p.i}"><svg class="i" style="width:18px;height:18px;${favs.includes(p.name)?'fill:var(--ac);stroke:var(--ac)':''}"><use href="#i-pre"/></svg></button>${p.u!=null?`<button class="dg" data-act="delp" data-i="${p.u}"><svg class="i" style="width:16px;height:16px"><use href="#i-trash"/></svg></button>`:''}</div>`).join('')
    ||'<div class="dm">No presets match.</div>';
}

/* ================= INSPECTOR ================= */
const BL=['source-over','lighter','screen','multiply','overlay','darken','lighten','color-dodge','difference','exclusion','hard-light','soft-light'];
const FONTS=['Archivo','Inter','Roboto','Montserrat','Oswald','Bebas Neue','Anton','Poppins','Playfair Display','Lobster','Pacifico','Orbitron','Audiowide','Righteous','Press Start 2P','Bungee','Permanent Marker','Caveat','Dancing Script','Abril Fatface','Russo One','Teko','Space Mono','Cinzel','Raleway'];
const ico=n=>`<svg class="i"><use href="#i-${n}"/></svg>`;
const SEL={
  font:FONTS,al:['left','center','right'],blend:BL,
  dir:['up','down','both'],odir:['out','in','both'],
  mir:['none','mirror','sym'],ws:['line','spiky'],
  shape:['circle','rect','triangle','poly','star','capsule'],
  imgMask:['none','circle'],
  band:['custom','all',...Object.keys(BH)],
  fsc:['log','linear','mel'],
  grad:['solid','linear','radial','conic'],
  anim:['none','pulse','bounce','sway','fade'],
  rmode:['continuous','pendulum','steps','random'],
  rease:['linear','smooth','ease_in','ease_out'],
  rsrc:SRCS,bsel:BTK,
  mm:['none','drift','orbit','figure8','bounce','shake'],
  barsShapePath:['line','curve','step','diamond'],
  measureSpeed:['none','bass','mid','treble','volume','beat']
};

const FD_BASIC=[
  ['Transform',[['x','X %','r',0,100,.1],['y','Y %','r',0,100,.1],['sx','Scale X','r',5,400,1],['sy','Scale Y','r',5,400,1],['skx','Skew X °','r',-60,60,.5],['sky','Skew Y °','r',-60,60,.5],['uni','Uniform','k',0,0,0],['op','Opacity','r',0,100,1],['blend','Blend','s',0,0,0]]],
  ['Rotate',[['rmode','Mode','s',0,0,0],['rot','Angle °','r',-360,360,1],['spin','Spin °/s','r',-720,720,1],['rbeat','Beat kick °','r',-180,180,1],['raud','Audio twist','r',-360,360,1],['rpx','Pivot X %','r',-50,50,.5],['rpy','Pivot Y %','r',-50,50,.5],['flx','Flip H','k',0,0,0],['fly','Flip V','k',0,0,0]]],
  ['Audio',[['band','Range','s',0,0,0],['f0','Min Hz','r',20,20000,1],['f1','Max Hz','r',20,20000,1],['fsc','Scale','s',0,0,0],['sens','Sensitivity','r',0,400,1],['att','Attack','r',1,100,1],['rel','Release','r',1,100,1]]],
  ['Colour',[['color','Colour','c',0,0,0],['color2','Colour 2','c',0,0,0],['three','Three stops','k',0,0,0],['color3','Colour 3','c',0,0,0],['grad','Gradient','s',0,0,0],['gAng','Angle','r',0,360,1],['gAnim','Rotate °/s','r',-180,180,1]]],
  ['Effects',[['glow','Glow','r',0,80,1],['shOff','Drop shadow','r',0,40,1],['blur','Blur','r',0,30,.5],['hue','Hue','r',-180,180,1],['bright','Brightness','r',0,200,1],['contr','Contrast','r',0,200,1],['sat','Saturation','r',0,200,1]]],
  ['Repeat',[['rep','Copies','r',1,16,1],['repang','Angle step °','r',-180,180,1],['repox','Offset X %','r',-30,30,.5],['repoy','Offset Y %','r',-30,30,.5],['repsc','Scale step %','r',-50,50,1],['repfade','Fade per copy %','r',0,50,1]]],
  ['Motion',[['mm','Path','s',0,0,0],['mspd','Speed','r',0,5,.05],['mrx','Range X %','r',0,50,.5],['mry','Range Y %','r',0,50,.5],['mbp','Beat push %','r',0,30,.5],['bsel','Beat trigger','s',0,0,0],['bk','Beat scale %','r',0,100,1],['bop','Beat opacity %','r',0,100,1],['bgl','Beat glow','r',0,80,1],['bkd','Kick decay','r',1,30,1]]],
  ['Animation',[['anim','Animation','s',0,0,0],['as','Speed','r',.1,8,.1],['aa','Amount','r',0,100,1]]]
];

const FD_TYPE={
  bars:[
    ['Bars',[['n','Count','r',2,256,1],['thick','Thickness','r',5,100,1],['round','Corner %','r',0,50,1],['maxH','Length %','r',5,200,1],['dir','Direction','s',0,0,0],['mir','Mirror','s',0,0,0],['rvar','Random variation','r',0,100,1],['flipInput','Flip input','k',0,0,0],['heightScale','Height scale','r',0,200,1],['minHeightScale','Min height','r',0,200,1],['maxHeightScale','Max height','r',0,200,1],['colorBlendOffset','Color blend offset','r',0,100,1],['barsShapePath','Shape path','s',0,0,0]]],
    ['Segments',[['seg1ColorFrom','Seg1 from','c',0,0,0],['seg1ColorTo','Seg1 to','c',0,0,0],['seg1Fixed','Seg1 fixed h','r',0,200,1],['seg1Mul','Seg1 multiplier','r',0,200,1],['seg1Mirror','Seg1 mirror','k',0,0,0],['seg1Flip','Seg1 flip every other','k',0,0,0],['seg2ColorFrom','Seg2 from','c',0,0,0],['seg2ColorTo','Seg2 to','c',0,0,0],['seg2Fixed','Seg2 fixed h','r',0,200,1],['seg2Mul','Seg2 multiplier','r',0,200,1],['seg2Mirror','Seg2 mirror','k',0,0,0],['seg2Flip','Seg2 flip every other','k',0,0,0]]],
    ['Behaviour',[['softness','Softness','r',0,100,1],['reactDelay','Reaction delay','r',0,100,1],['reactAcc','Reaction accumulated delay','r',0,100,1]]]
  ],
  barsh:[['Bars',[['n','Count','r',2,256,1],['thick','Thickness','r',5,100,1],['round','Corner %','r',0,50,1],['maxH','Length %','r',5,200,1],['mir','Mirror','s',0,0,0],['rvar','Random variation','r',0,100,1]]]],
  circle:[['Radial',[['n','Count','r',2,256,1],['thick','Thickness','r',5,100,1],['r0','Inner radius %','r',0,95,1],['maxH','Length %','r',5,200,1],['a0','Start angle','r',-180,360,1],['a1','End angle','r',-180,540,1],['odir','Growth','s',0,0,0],['mir','Mirror','s',0,0,0]]]],
  multiring:[['Rings',[['n','Bars per ring','r',2,256,1],['thick','Thickness','r',5,100,1],['rings','Ring count','r',1,8,1],['r0','Inner radius %','r',0,95,1],['maxH','Length %','r',5,200,1],['a0','Start','r',-180,360,1],['a1','End','r',-180,540,1],['odir','Growth','s',0,0,0]]]],
  concentric:[['Rings',[['rings','Rings','r',1,8,1],['thick','Thickness','r',5,100,1],['maxH','Length %','r',5,200,1]]]],
  ring:[['Ring',[['thick','Thickness','r',5,100,1],['r0','Inner radius %','r',0,95,1]]]],
  pulse:[['Pulse',[['thick','Thickness','r',5,100,1],['r0','Inner radius %','r',0,95,1]]]],
  wave:[['Wave',[['n','Resolution','r',2,256,1],['thick','Thickness','r',5,100,1],['maxH','Amplitude %','r',5,200,1],['ws','Style','s',0,0,0],['wfill','Fill','k',0,0,0],['mir','Mirror','s',0,0,0]]]],
  wavec:[['Circular Wave',[['thick','Thickness','r',5,100,1],['r0','Radius %','r',0,95,1],['maxH','Amplitude %','r',5,200,1]]]],
  scope:[['Scope',[['n','Resolution','r',2,256,1],['thick','Thickness','r',5,100,1],['ws','Style','s',0,0,0]]]],
  dots:[['Dots',[['n','Count','r',2,256,1],['thick','Size %','r',5,100,1],['maxH','Length %','r',5,200,1]]]],
  lines:[['Lines',[['n','Count','r',2,256,1],['thick','Thickness','r',5,100,1],['maxH','Length %','r',5,200,1]]]],
  blocks:[['Blocks',[['n','Count','r',2,256,1],['seg','Segments','r',2,32,1],['thick','Thickness','r',5,100,1],['maxH','Length %','r',5,200,1]]]],
  grid:[['Grid',[['n','Columns','r',2,256,1],['seg','Rows','r',2,32,1],['maxH','Length %','r',5,200,1]]]],
  particles:[
    ['Emission',[['n','Spawn per beat','r',1,100,1],['countLimit','Count limit','r',1,1000,1],['spawnTime','Spawn time','r',0,10,.1],['lifetime','Lifetime s','r',0,20,.1],['spawnArea','Spawn area','r',0,200,1]]],
    ['Motion',[['speed','Speed','r',0,20,.1],['speedRandom','Speed random','r',0,10,.1],['velocityAngle','Velocity angle','r',0,360,1],['gravity','Gravity','r',-20,20,.1],['forceField','Force field','r',-100,100,1]]],
    ['Appearance',[['thick','Size','r',5,100,1],['particleScale','Particle scale','r',0,200,1],['startSize','Start size','r',0,50,.5],['endSize','End size','r',0,50,.5],['fadeInOutTime','Fade in/out','r',0,10,.1],['trailLength','Trail length','r',0,100,1],['colorFrom','Color from','c',0,0,0],['colorTo','Color to','c',0,0,0]]],
    ['Wave Motion',[['sideSineFreq','Sine freq','r',0,20,.1],['sideSineMag','Sine magnitude','r',0,50,.5],['sideSineFreqRandom','Freq random','r',0,20,.1],['sideSineMagRandom','Mag random','r',0,50,.5]]],
    ['3D',[['perspectiveDepth','Perspective depth','r',0,100,1],['perspectiveFov','Perspective FOV','r',10,170,1],['vectorAngleZ','Vector angle Z','r',0,360,1]]]
  ],
  shape:[['Shape',[['shape','Shape','s',0,0,0],['sides','Sides','r',3,12,1],['maxH','Size %','r',5,200,1],['sw','Outline','r',0,20,.5],['sc','Outline colour','c',0,0,0]]]],
  text:[
    ['Text',[['text','Text (| = newline)','t',0,0,0],['font','Font','s',0,0,0],['fz','Font size','r',2,60,.5],['fw','Weight','r',100,900,100],['fi','Italic','k',0,0,0],['up','Uppercase','k',0,0,0],['ls','Letter spacing','r',-5,40,.5],['tlh','Line height %','r',60,250,1],['al','Align','s',0,0,0]]],
    ['Text Effects',[['tbeat','Beat size pulse %','r',0,150,1],['tty','Typewriter chars/s','r',0,30,.5],['sw','Outline','r',0,20,.5],['sc','Outline colour','c',0,0,0]]],
    ['Text Box',[['tbox','Background box','k',0,0,0],['tbc','Box colour','c',0,0,0],['tbo','Box opacity','r',0,100,1],['tpad','Box padding','r',0,60,1],['trad','Box corners','r',0,60,1]]]
  ],
  image:[
    ['Image',[['maxH','Size %','r',5,200,1],['imgMask','Mask','s',0,0,0],['imgKeepAspect','Keep aspect','k',0,0,0]]],
    ['Image Colour',[['imgColorKeyEnabled','Colour key','k',0,0,0],['imgColorKey','Colour key','c',0,0,0],['imgTransparencyStrength','Transparency strength','r',0,100,1],['imgSaturation','Saturation','r',0,200,1],['imgColor','Tint colour','c',0,0,0],['imgColorTo','Tint to','c',0,0,0]]],
    ['Image Blur',[['imgBlurEnabled','Blur enabled','k',0,0,0],['imgBlurRadius','Blur radius','r',0,40,1]]],
    ['Image Source',[['img','Load image','f',0,0,0]]]
  ],
  bg:[['Background',[['color','Colour','c',0,0,0],['color2','Colour 2','c',0,0,0],['three','Three stops','k',0,0,0],['color3','Colour 3','c',0,0,0],['grad','Gradient','s',0,0,0],['gAng','Angle','r',0,360,1],['gAnim','Rotate °/s','r',-180,180,1]]]]
};

/* Sections the user can collapse — remembered per session */
const openSec={Transform:1,Rotate:0,Audio:0,Colour:0,Effects:0,Repeat:0,Motion:0,Animation:0,Bars:0,Segments:0,Behaviour:0,Radial:0,Rings:0,Ring:0,Pulse:0,Wave:0,'Circular Wave':0,Scope:0,Dots:0,Lines:0,Blocks:0,Grid:0,Emission:0,Appearance:0,'Wave Motion':0,'3D':0,Shape:0,Text:0,'Text Effects':0,'Text Box':0,Image:0,'Image Colour':0,'Image Blur':0,'Image Source':0,Background:0,Reactive:0};

/* Filter basic sections for a given layer type */
function sectionsForType(ty){
  const isBg=ty==='bg';
  const needsAudio=!(isBg||ty==='image'||ty==='text');
  const out=[];
  for(const[sec,fields]of FD_BASIC){
    if(sec==='Audio'&&!needsAudio)continue;
    if(sec==='Colour'&&ty==='image')continue;
    out.push([sec,fields]);
  }
  return [...out,...(FD_TYPE[ty]||[])];
}

const so=(a,v)=>a.map(o=>`<option ${o===v?'selected':''}>${o}</option>`).join('');
const rg=(lab,at,mn,mx,st,v)=>`<div class="rg"><span>${lab}</span><input type="range" ${at} min="${mn}" max="${mx}" step="${st}" value="${v}"><input type="number" class="nm" ${at} min="${mn}" max="${mx}" step="${st}" value="${v}"></div>`;
const fieldHtml=(l,f)=>{
  const[k,lab,kd,mn,mx,st]=f,v=l[k],at=`data-k="${k}"`;
  if(kd==='r')return rg(lab,at,mn,mx,st,v);
  if(kd==='k')return`<label class="f"><span>${lab}</span><input type="checkbox" ${at} ${v?'checked':''}></label>`;
  if(kd==='s')return`<label class="f"><span>${lab}</span><select ${at}>${so(SEL[k]||[],v)}</select></label>`;
  if(kd==='c')return`<div class="cr"><span>${lab}</span><input type="color" ${at} value="${v}"><input class="hx" ${at} value="${v}" maxlength="7"></div>`;
  if(kd==='t')return`<label class="f"><span>${lab}</span><input type="text" ${at} value="${esc(v)}"></label>`;
  return`<label class="f"><span>${lab}</span><input type="file" accept="image/*" data-img="1"></label>`;
};
function ruleHtml(r,i){
  const a=`data-r="${i}"`,n=(k,lab,mn,mx,st)=>`<label class="dm">${lab}<input type="number" class="nm" ${a} data-rk="${k}" min="${mn}" max="${mx}" step="${st}" value="${r[k]}"></label>`;
  return`<div class="rl"><div class="row"><label class="dm"><input type="checkbox" ${a} data-rk="on" ${r.on?'checked':''}> On</label><select ${a} data-rk="src">${so(SRCS,r.src)}</select><select ${a} data-rk="prop">${so(Object.keys(PR),r.prop)}</select><button class="dg" data-act="delrule" data-i="${i}"><svg class="i" style="width:16px;height:16px"><use href="#i-trash"/></svg></button></div><div class="row">${n('i0','In min',0,1,.05)}${n('i1','In max',0,1,.05)}${n('o0','Out min',-400,540,1)}${n('o1','Out max',-400,540,1)}</div><div class="row"><select ${a} data-rk="cv">${so(Object.keys(CURV),r.cv)}</select><label class="dm"><input type="checkbox" ${a} data-rk="inv" ${r.inv?'checked':''}> Invert</label>${n('att','Attack',1,100,1)}${n('rel','Release',1,100,1)}</div></div>`;
}

function inspHtml(){
  const l=L[sel];
  if(!l)return'<div class="dm">No layer selected.</div>';
  let h=`<div class="${l.lock?'lk':''}"><div class="it" style="margin-bottom:8px"><button style="pointer-events:none;color:var(--dm);font-weight:500;font-size:11px">Name</button><input type="text" data-k="name" value="${esc(l.name)}" style="flex:2;min-height:40px;background:transparent;padding:0 14px;font-weight:600;font-size:13px;border-radius:0"></div>`;
  const secs=sectionsForType(l.type);
  for(const[sec,fs]of secs){
    if(!fs.length)continue;
    h+=`<details data-sec="${sec}" ${openSec[sec]?'open':''}><summary>${sec}<button data-act="rsts" data-sec="${sec}" style="min-height:0;height:32px;width:32px;padding:0;background:transparent;border-radius:50%;color:var(--dm);display:flex;align-items:center;justify-content:center" aria-label="Reset"><svg class="i" style="width:18px;height:18px"><use href="#i-reset"/></svg></button></summary><div>${fs.map(f=>fieldHtml(l,f)).join('')}</div></details>`;
  }
  h+=`<details data-sec="Reactive" ${openSec.Reactive?'open':''}><summary>Audio reactive mapping<button data-act="addrule" style="min-height:0;height:32px;width:32px;padding:0;background:transparent;border-radius:50%;color:var(--dm);display:flex;align-items:center;justify-content:center" aria-label="Add"><svg class="i" style="width:18px;height:18px"><use href="#i-plus"/></svg></button></summary><div>${l.rules.map((r,i)=>ruleHtml(r,i)).join('')||'<div class="dm">Map any audio source to any property. Output replaces the property while the rule is on.</div>'}</div></details></div>`;
  return h;
}

const PR={x:[0,100],y:[0,100],sx:[5,400],sy:[5,400],rot:[-180,180],op:[0,100],thick:[5,100],maxH:[5,200],n:[2,256],glow:[0,80],blur:[0,30],hue:[-180,180],bright:[0,200],contr:[0,200],sat:[0,200],sens:[0,400],r0:[0,95],fz:[2,60],spin:[-180,180],rbeat:[0,180],raud:[0,360],mrx:[0,50],mry:[0,50],bk:[0,100],mbp:[0,30],skx:[-60,60],sky:[-60,60],bop:[0,100],bgl:[0,80]};

function renderLayers(){
  $$('[data-act=lup],[data-act=ldn],[data-act=ldel]').forEach(b=>{b.style.opacity=L[sel]&&L[sel].lock?.4:''});
  const el=$('#llist');
  if(el)el.innerHTML=L.map((l,i)=>i).reverse().map(i=>`<div class="it ${i==sel?'sl':''} ${L[i].lock?'lkd':''}"><button data-act="lsel" data-i="${i}">${esc(L[i].name||L[i].type)} <span class="dm">${L[i].type}</span></button><button data-act="lvis" data-i="${i}" aria-label="${L[i].vis?'Hide':'Show'}"><svg class="i" style="width:18px;height:18px"><use href="#i-${L[i].vis?'eye':'eye-off'}"/></svg></button><button data-act="llock" data-i="${i}" aria-label="${L[i].lock?'Unlock':'Lock'}"><svg class="i" style="width:18px;height:18px"><use href="#i-${L[i].lock?'lock':'unlock'}"/></svg></button></div>`).join('')
    ||'<div class="dm">No layers. Add one above.</div>';
}
function renderInsp(){
  const el=$('#insp');
  if(el)el.innerHTML=inspHtml();
}

/* ================= TABS ================= */
const TN={lib:'Media',lay:'Layers',pre:'Presets',aud:'Audio',scn:'Scene',exp:'Export'};
const rs=(lab,k,mn,mx,st)=>rg(lab,`data-s="${k}"`,mn,mx,st,S[k]);
const sc=(lab,k,mn,mx,st)=>rg(lab,`data-c="${k}"`,mn,mx,st,SC[k]);
const ss=(lab,k,opts,n)=>`<label class="f"><span>${lab}</span><select data-s="${k}" ${n?'data-n="1"':''}>${opts.map(o=>`<option ${String(S[k])===String(o)?'selected':''}>${o}</option>`).join('')}</select></label>`;
const gsel=(key,lab)=>`<label class="f"><span>${lab}</span><select data-spg="${key}"><option value="">Custom</option>${Object.keys(SPG[key]).map(k=>`<option>${k}</option>`).join('')}</select></label>`;
const SPG={
  '@scene':{'Clean':{cam:0,vig:0,scan:0,grain:0,flash:0,shake:0,hueB:0},'Cinematic':{cam:4,vig:55,grain:15,scan:0,flash:0,shake:0,hueB:0},'Retro CRT':{cam:0,vig:40,scan:60,grain:30,flash:0,shake:0,hueB:0},'Hype':{cam:12,vig:20,flash:40,shake:10,hueB:20},'Dreamy':{cam:3,vig:30,hueB:8,flash:0,shake:0},'Glitch':{cam:0,scan:30,grain:40,flash:25,shake:20,hueB:45}},
  '@audio':{'Balanced':{fft:2048,smooth:50,floor:8,beat:130},'Punchy':{fft:1024,smooth:30,floor:10,beat:120},'Smooth':{fft:4096,smooth:80,floor:6,beat:150},'Sensitive':{fft:2048,smooth:55,floor:2,beat:115},'Heavy bass':{fft:4096,smooth:60,floor:12,beat:140}}
};
function fmts(){
  const c=[['video/mp4;codecs=avc1,mp4a.40.2','MP4 H.264'],['video/mp4;codecs=hvc1,mp4a.40.2','MP4 H.265'],['video/webm;codecs=vp9,opus','WebM VP9'],['video/webm;codecs=vp8,opus','WebM VP8'],['video/webm;codecs=av1,opus','WebM AV1']];
  return window.MediaRecorder?c.filter(x=>MediaRecorder.isTypeSupported(x[0])):[];
}
const TABS={
  lib:()=>`<div class="sec">Import</div><label class="btn" for="fi">Choose audio files</label><input id="fi" class="hid" type="file" accept="audio/*,video/mp4,.mp3,.wav,.flac,.aac,.ogg,.oga,.opus,.m4a,.aif,.aiff,.weba" multiple><label class="btn" for="fdr">Choose a folder (desktop browsers)</label><input id="fdr" class="hid" type="file" webkitdirectory multiple>
    <div class="sec">Queue</div><input id="qs" placeholder="Search tracks"><div id="queue"></div>
    <div class="sec">Sleep timer</div><select id="slp"><option value="0">Off</option><option value="15">15 min</option><option value="30">30 min</option><option value="45">45 min</option><option value="60">60 min</option></select>
    <div class="dm">Audio is decoded in memory, so very long files need RAM. Files stay on this device.</div>`,
  lay:()=>`<div class="row" style="margin-bottom:8px"><select id="ltype">${['bars','barsh','circle','multiring','concentric','ring','pulse','wave','wavec','scope','dots','lines','blocks','grid','particles','shape','text','image','bg'].map(t=>`<option>${t}</option>`).join('')}</select><button class="pri" data-act="addlayer">Add layer</button></div>
    <div class="sec" style="margin-top:0">Add text</div><input id="tnew" placeholder="Type text, use | for a new line" style="width:100%;margin-bottom:8px">
    <div class="row" style="margin-bottom:8px"><select id="tfont">${FONTS.map(f=>`<option>${f}</option>`).join('')}</select><button class="pri" data-act="addtext">Add text layer</button></div>
    <div id="llist"></div>
    <div class="lay-tools"><button data-act="lup" aria-label="Move up"><svg class="i"><use href="#i-up"/></svg></button><button data-act="ldn" aria-label="Move down"><svg class="i"><use href="#i-down"/></svg></button><button data-act="ldup" aria-label="Duplicate"><svg class="i"><use href="#i-dup"/></svg></button><button class="dg" data-act="ldel" aria-label="Delete"><svg class="i"><use href="#i-trash"/></svg></button></div>
    <div class="lay-tools"><button data-act="cpy" aria-label="Copy props"><svg class="i"><use href="#i-copy"/></svg></button><button data-act="pst" aria-label="Paste props"><svg class="i"><use href="#i-paste"/></svg></button><button data-act="rstl" aria-label="Reset layer"><svg class="i"><use href="#i-reset"/></svg></button></div>
    <div id="insp"></div>`,
  pre:()=>`<input id="ps" placeholder="Search presets" style="margin-bottom:8px"><select id="pc" style="margin-bottom:8px">${['All','Favorites','Mine',...new Set(PRE.map(p=>p[0]))].map(c=>`<option ${c===pf?'selected':''}>${c}</option>`).join('')}</select><div id="plist"></div><button data-act="rand" style="margin-top:8px">Controlled random (from presets)</button>
    <div class="sec">Save current as</div><div class="row"><input id="pn" placeholder="Preset name"><button class="pri" data-act="savep">Save</button></div>
    <div class="sec">Project JSON</div><textarea id="js" spellcheck="false"></textarea><div class="row"><button data-act="jshow">Show</button><button data-act="jload">Load</button><button data-act="jsave">Save .viz file</button></div>
    <label class="btn" for="vzi">Import .viz / .json file</label><input id="vzi" class="hid" type="file" accept="*/*">
    <div class="dm">The .viz format here is AUDIOVIS's own JSON template. Avee .viz and After Effects .aep files cannot be read.</div>`,
  aud:()=>`<div class="sec">Playback</div><div style="background:var(--p2);border-radius:var(--r);padding:8px 14px;margin-bottom:8px">${rs('Volume %','vol',0,100,1)}${rs('Preamp dB','pre',-12,12,.5)}${rs('Bass boost dB','bass',0,15,.5)}${rs('Balance','bal',-100,100,1)}${rs('Speed %','speed',50,150,1)}<label class="f"><span>Normalise</span><input type="checkbox" data-s="norm"></label></div>
    <div class="sec">Equalizer</div><div style="background:var(--p2);border-radius:var(--r);padding:8px 14px;margin-bottom:8px">${EQF.map((f,i)=>rg(f>=1000?f/1000+'k':f,`data-eq="${i}"`,-12,12,.5,S.eq[i])).join('')}</div>
    <div class="row"><select id="eqp"><option value="">EQ preset</option>${Object.keys(EQP).map(k=>`<option>${k}</option>`).join('')}</select><button data-act="eqrst">Flat</button></div>
    <div class="sec">Effects</div><div style="background:var(--p2);border-radius:var(--r);padding:8px 14px;margin-bottom:8px">${rs('Reverb','rev',0,100,1)}${rs('Delay','dly',0,100,1)}${rs('Distortion','dist',0,100,1)}</div>
    <div class="sec">Analysis</div>${gsel('@audio','Analysis preset')}<div style="background:var(--p2);border-radius:var(--r);padding:8px 14px;margin-bottom:8px">${ss('FFT size','fft',[1024,2048,4096],1)}${rs('Smoothing','smooth',0,95,1)}${rs('Noise floor','floor',0,60,1)}${rs('Beat level','beat',110,250,1)}</div>
    <div class="sec">Beat engine</div><div style="background:var(--p2);border-radius:var(--r);padding:8px 14px;margin-bottom:8px"><label class="f"><span>Mode</span><select data-s="bmode">${[['auto','Auto (live)'],['map','Beat map (scanned)'],['bpm','Manual BPM grid'],['off','Off']].map(o=>`<option value="${o[0]}" ${S.bmode===o[0]?'selected':''}>${o[1]}</option>`).join('')}</select></label>
    <label class="f"><span>Detect from</span><select data-s="bsrc">${['bass','sub','lowmid','mid','upmid','presence','brill','volume','kick','snare','flux'].map(o=>`<option ${S.bsrc===o?'selected':''}>${o}</option>`).join('')}</select></label>
    ${rs('Min level %','bmin',5,90,1)}${rs('Min gap ms','bcool',60,800,10)}${rs('Beat decay %','bdec',60,97,1)}${rs('Offset ms','boff',-300,300,5)}${rs('BPM','bpm',40,220,.5)}
    <div class="row"><button data-act="tap">Tap tempo</button><button data-act="detbpm">Detect BPM</button></div><div class="row" style="margin-top:8px"><button data-act="trig">Trigger beat</button><button data-act="rescan">Re-scan beats</button></div></div>
    <div class="sec">Modulators</div><div style="background:var(--p2);border-radius:var(--r);padding:8px 14px;margin-bottom:8px">${rs('LFO 1 Hz','lfo1',.05,4,.05)}${rs('LFO 2 Hz','lfo2',.05,4,.05)}</div>
    <div class="dm">Auto reacts live. Beat map uses a scan of the whole track for zero-latency hits. Manual BPM follows a fixed grid. Sources phase, lfo1, lfo2 and rnd can drive any layer through reactive mapping, and each layer can pick its own beat trigger under Motion.</div>
    <canvas id="mon" height="90" style="width:100%;height:90px;background:var(--bg);border:1px solid var(--ln);border-radius:var(--r);margin:4px 0"></canvas>
    <div class="dm">Monitor: live spectrum and the analysis sources used by reactive mapping.</div><button data-act="rsta">Reset audio</button>`,
  scn:()=>`${gsel('@scene','Scene range preset')}<div style="background:var(--p2);border-radius:var(--r);padding:8px 14px;margin-bottom:8px"><label class="cr"><span>Background</span><input type="color" data-c="bg" value="${SC.bg}"><span></span></label>${sc('Camera zoom','cam',0,30,.5)}${sc('Vignette','vig',0,100,1)}${sc('Scanlines','scan',0,100,1)}${sc('Film grain','grain',0,100,1)}${sc('Beat flash','flash',0,100,1)}${sc('Beat shake','shake',0,40,1)}${sc('Hue jump per beat','hueB',0,90,1)}</div><button data-act="rstsc">Reset scene</button><div class="dm">Camera zoom follows bass. These effects are part of the project and of the exported video.</div>`,
  exp:()=>{
    const f=fmts();
    return`<div class="x-grid"><label>Aspect<select data-s="ratio">${['16:9','9:16','1:1','4:5','4:3','21:9','custom'].map(o=>`<option ${o===S.ratio?'selected':''}>${o}</option>`).join('')}</select></label><label>Short side<select data-s="res" data-n="1">${[360,480,720,1080,1440,2160].map(o=>`<option ${o==S.res?'selected':''}>${o}</option>`).join('')}</select></label></div>
      ${S.ratio==='custom'?`<div class="row" style="margin-bottom:8px">${rs('Width','cw',64,4096,2)}${rs('Height','ch',64,4096,2)}</div>`:''}
      <div class="x-grid"><label>FPS<select data-s="xfps" data-n="1">${[24,25,30,50,60].map(x=>`<option ${x==S.xfps?'selected':''}>${x}</option>`).join('')}</select></label><label>Format<select data-s="fmt" data-n="1">${f.map((x,i)=>`<option value="${i}" ${i==S.fmt?'selected':''}>${x[1]}</option>`).join('')||'<option>Unsupported</option>'}</select></label></div>
      <div style="background:var(--p2);border-radius:var(--r);padding:8px 14px;margin-bottom:8px">${rs('Video Mbps','mbps',1,40,1)}${rs('Audio kbps','abr',64,320,32)}${rs('Start s','t0',0,3600,1)}${rs('End s (0=end)','t1',0,3600,1)}</div><div class="dm" id="xinfo"></div>
      <div class="row" style="margin-top:8px"><button class="pri" data-act="rec">Export</button><button data-act="rpause">Pause</button><button class="dg" data-act="rcancel">Cancel</button></div>
      <button data-act="still">Save still PNG</button><progress id="xp" value="0" max="1"></progress><div class="dm" id="xs">Export runs in real time with the real audio, so a 3 minute track takes 3 minutes. Keep this tab open. 4K needs a strong device.</div><div id="xout"></div>`;
  }
};
const EQP={Bass:[6,5,4,2,0,0,0,0,0,0],Treble:[0,0,0,0,0,0,2,4,5,6],Vocal:[-2,-1,0,2,4,4,2,0,-1,-2],Rock:[4,3,2,0,-1,0,2,4,5,4],Pop:[2,1,0,2,3,3,2,0,-1,-2],Electronic:[5,4,3,1,0,-1,1,3,4,5],Classical:[4,3,2,1,0,0,0,1,2,3],Loud:[4,4,3,3,2,2,3,3,4,4]};

function renderAll(){renderQ();renderLayers();renderInsp();renderPre()}
function showTab(t){
  tab=t;$('#st').textContent=TN[t];$('#sb').innerHTML=TABS[t]();
  $$('#nav button').forEach(b=>b.classList.toggle('on',open&&b.dataset.t===t));
  if(t==='exp')size();
  renderAll();
}
function setOpen(o){
  open=o;$('#side').classList.toggle('open',o);
  if(o)showTab(tab);else $$('#nav button').forEach(b=>b.classList.remove('on'));
  setTimeout(()=>{drawWf()},50);
}

/* ================= EXPORT ================= */
let chunks=[],cancelled=false,outBlob=null,outName='',rpaused=false,wl=null,drops=0,lastRecT=0;
async function startRec(){
  if(rec)return;
  if(!au.buf){toast('Load audio first','err');return}
  const f=fmts();
  if(!f.length){toast('This browser cannot record video','err');return}
  const fm=f[S.fmt]||f[0];
  initAudio();
  size(true);
  await (document.fonts&&document.fonts.ready);
  await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
  try{wl=await navigator.wakeLock.request('screen')}catch(e){}
  drops=0;lastRecT=0;
  let stream;
  try{stream=cv.captureStream(S.xfps)}catch(e){toast('Capture not supported','err');size();return}
  dest.stream.getAudioTracks().forEach(t=>stream.addTrack(t));
  chunks=[];cancelled=false;rpaused=false;
  try{rec=new MediaRecorder(stream,{mimeType:fm[0],videoBitsPerSecond:S.mbps*1e6,audioBitsPerSecond:S.abr*1000})}
  catch(e){rec=null;size();toast('Recorder failed: '+e.message,'err');return}
  const r=rec;
  r.ondataavailable=e=>e.data.size&&chunks.push(e.data);
  r.onerror=()=>{cancelled=true;toast('Recording error. Try a lower resolution.','err')};
  r.onstop=()=>{
    rec=null;if(wl){try{wl.release()}catch(e){}wl=null}
    $('#bdg').textContent='PREVIEW';$('#bdg').classList.remove('rec');size();
    const xs=$('#xs');
    if(cancelled){chunks=[];if(xs)xs.textContent='Cancelled. Partial recording discarded.';return}
    const bl=new Blob(chunks,{type:fm[0].split(';')[0]});chunks=[];
    if(!bl.size){if(xs)xs.textContent='Export produced no data.';return}
    outBlob=bl;outName='audiovis.'+(fm[0].includes('mp4')?'mp4':'webm');
    const u=URL.createObjectURL(bl),xo=$('#xout');
    if(xo){
      xo.innerHTML=`<video controls src="${u}"></video><div class="dm" id="vf">Checking file...</div><button class="pri" data-act="saveout">Save ${outName} (${(bl.size/1048576).toFixed(1)} MB)</button>`;
      const v=xo.querySelector('video');
      v.onloadedmetadata=()=>{const q=$('#vf');if(q)q.textContent=`Verified: ${v.videoWidth}×${v.videoHeight}, playable. ${drops?`The preview stalled ${drops} time(s) while recording, so short freezes are possible. Re-record at a lower resolution or frame rate.`:'No stalled frames were detected.'}`};
      v.onerror=()=>{const q=$('#vf');if(q)q.textContent='This browser cannot play the file back. Try another format.'};
    }
    if(xs)xs.textContent='Done.';toast('Export finished','ok');
  };
  au.pause();au.currentTime=S.t0;ac.resume();r.start(1000);au.play();
  $('#bdg').textContent='EXPORT';$('#bdg').classList.add('rec');
  $('#xs').textContent='Exporting '+fm[1]+'...';
}
function finishRec(){if(rec&&rec.state!=='inactive'){au.pause();rec.stop()}}
function cancelRec(){if(rec){cancelled=true;au.pause();try{rec.stop()}catch(e){}}}
async function saveBlob(blob,name){
  try{
    const C=window.Capacitor,P=C&&C.Plugins;
    if(C&&C.isNativePlatform&&C.isNativePlatform()&&P&&P.Filesystem){
      const b64=await new Promise((res,rej)=>{const r=new FileReader();r.onload=()=>res(String(r.result).split(',')[1]);r.onerror=rej;r.readAsDataURL(blob)});
      const w=await P.Filesystem.writeFile({path:name,data:b64,directory:'CACHE'});
      if(P.Share)await P.Share.share({title:name,url:w.uri,dialogTitle:'Save or share '+name});
      toast('Ready','ok');return;
    }
    const d=window.claude&&await claude.use('downloads');
    if(d){await d.save({filename:name,data:blob});toast('Saved','ok');return}
    const u=URL.createObjectURL(blob),a=document.createElement('a');
    a.href=u;a.download=name;document.body.appendChild(a);a.click();a.remove();
    setTimeout(()=>URL.revokeObjectURL(u),4000);toast('Saved '+name,'ok');
  }catch(e){toast(e&&e.code==='declined'?'Save cancelled':'Save failed: '+((e&&e.message)||e),'err')}
}

/* ================= IMPORT ================= */
let ovI=1;
async function unzip(b){
  const dv=new DataView(b.buffer,b.byteOffset,b.length);let e=-1;
  for(let i=b.length-22;i>=Math.max(0,b.length-65557);i--)if(dv.getUint32(i,true)===0x06054b50){e=i;break}
  if(e<0)throw Error('Not a ZIP');
  const n=dv.getUint16(e+10,true);let p=dv.getUint32(e+16,true);const out={};
  for(let i=0;i<n;i++){
    if(dv.getUint32(p,true)!==0x02014b50)break;
    const m=dv.getUint16(p+10,true),cs=dv.getUint32(p+20,true),nl=dv.getUint16(p+28,true),xl=dv.getUint16(p+30,true),cl=dv.getUint16(p+32,true),lo=dv.getUint32(p+42,true);
    const name=new TextDecoder().decode(b.slice(p+46,p+46+nl));
    p+=46+nl+xl+cl;
    const st=lo+30+dv.getUint16(lo+26,true)+dv.getUint16(lo+28,true),raw=b.slice(st,st+cs);
    out[name]=m===0?raw:m===8?new Uint8Array(await new Response(new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).arrayBuffer()):null;
  }
  return out;
}
function zipNames(b){
  const dv=new DataView(b.buffer,b.byteOffset,b.length);let e=-1;
  for(let i=b.length-22;i>=Math.max(0,b.length-65557);i--)if(dv.getUint32(i,true)===0x06054b50){e=i;break}
  if(e<0)return[];
  const n=dv.getUint16(e+10,true);let p=dv.getUint32(e+16,true);const out=[];
  for(let i=0;i<n&&p+46<=b.length;i++){
    const nl=dv.getUint16(p+28,true),xl=dv.getUint16(p+30,true),cl=dv.getUint16(p+32,true);
    out.push(new TextDecoder().decode(b.slice(p+46,p+46+nl)));
    p+=46+nl+xl+cl;
  }
  return out;
}
async function inspectViz(f){
  try{
    const b=new Uint8Array(await f.arrayBuffer());
    const sig=[...b.slice(0,4)].map(x=>x.toString(16).padStart(2,'0')).join(' ');
    let txt=null;
    try{txt=new TextDecoder('utf-8',{fatal:true}).decode(b)}catch(e){}
    if(txt){
      txt=txt.replace(/^\uFEFF/,'').trim();
      if(txt[0]==='{'||txt[0]==='['){
        try{
          let o=JSON.parse(txt);
          if(Array.isArray(o))o={layers:o};
          const ly=o.layers||(o.project&&o.project.layers);
          if(Array.isArray(ly)&&ly.length&&ly.every(l=>l&&typeof l.type==='string')){
            o={app:'AUDIOVIS',version:4,layers:ly,scene:o.scene||(o.project&&o.project.scene)||{}};
            $('#js').value=JSON.stringify(o,null,1);
            return ACT.jload();
          }
        }catch(e){}
      }
    }
    let info='Unrecognised file '+f.name+' ('+b.length+' bytes, header '+sig+').';
    if(b[0]===0x50&&b[1]===0x4b){const nm=zipNames(b);info+=' ZIP container, '+nm.length+' entries: '+nm.slice(0,8).join(', ')}
    else if(txt)info+=' Text starting: '+txt.slice(0,60).replace(/\s+/g,' ');
    $('#js').value=info+'\n\nThe .viz format used by Avee is not publicly documented, so this app cannot convert it.';
    toast('Not an AUDIOVIS preset. Details are in the box.','err');
  }catch(e){toast('Could not read file','err')}
}

/* ================= ACTIONS ================= */
const ACT={
  osz:()=>{ovI=(ovI+1)%3;$('#side').style.setProperty('--oh',[36,58,88][ovI]+'%')},
  undo,redo,
  trig:()=>{tapPend=true},
  tap:()=>{
    const n=performance.now();if(taps.length&&n-taps[taps.length-1]>2200)taps.length=0;
    taps.push(n);tapPend=true;
    if(taps.length>=3){
      const iv=(taps[taps.length-1]-taps[0])/(taps.length-1);
      S.bpm=Math.round(clamp(60000/iv,40,220)*2)/2;save();syncSet();
    }
  },
  detbpm:()=>{
    const b=estBPM();if(!b){toast('Load a track with clear beats first','err');return}
    S.bpm=b;save();syncSet();toast('Detected about '+b+' BPM','ok');
  },
  rescan:()=>{rescan();toast(beatT.length+' beats found','ok')},
  panel:()=>setOpen(!open),
  close:()=>setOpen(false),
  set:()=>$('#set').hidden=false,
  setx:()=>$('#set').hidden=true,
  tab:b=>{
    if(open&&tab===b.dataset.t&&!document.body.classList.contains('wide'))setOpen(false);
    else{tab=b.dataset.t;setOpen(true);showTab(tab)}
  },
  play:async()=>{
    if(!files.length){toast('Add an audio file in Media','err');setOpen(true);showTab('lib');return}
    if(!initAudio())return;
    await ac.resume();
    if(au.buf)au.paused?au.play():au.pause();
    else load(Math.max(cur,0));
  },
  prev:()=>next(-1),next:()=>next(1),
  shuf:()=>{S.sh=S.sh?0:1;save();syncBtns()},
  rpt:()=>{S.rp={off:'all',all:'one',one:'off'}[S.rp];save();syncBtns()},
  qsel:b=>load(+b.dataset.i),
  qrm:b=>{
    const i=+b.dataset.i;files.splice(i,1);
    if(i===cur){au.stop();au.buf=null;cur=-1;peaks=[];drawWf()}
    else if(i<cur)cur--;
    renderQ();
  },
  addtext:()=>{
    touch();
    const q=$('#tnew'),tf=$('#tfont');
    const l=mk('text',{text:(q&&q.value.trim())||'AUDIOVIS',font:tf?tf.value:'Archivo',name:'Text',y:40,band:'bass',f0:60,f1:250,sens:150});
    if(document.fonts)document.fonts.load('20px "'+l.font+'"');
    L.push(l);sel=L.length-1;
    if(q)q.value='';
    renderLayers();renderInsp();
    toast('Text added. Customise it below','ok');
  },
  addlayer:()=>{touch();L.push(mk($('#ltype').value));sel=L.length-1;renderLayers();renderInsp()},
  ldel:()=>{
    if(L[sel]&&L[sel].lock)return toast('Layer is locked. Unlock to delete','err');
    if(L[sel]){touch();L.splice(sel,1);sel=clamp(sel,0,L.length-1);renderLayers();renderInsp()}
  },
  ldup:()=>{if(L[sel]){touch();const c=norm(clone(L[sel]));c.name+=' copy';L.splice(sel+1,0,c);sel++;renderLayers();renderInsp()}},
  lup:()=>{if(L[sel]&&L[sel].lock)return toast('Layer is locked. Unlock to move','err');if(sel<L.length-1){touch();[L[sel],L[sel+1]]=[L[sel+1],L[sel]];sel++;renderLayers()}},
  ldn:()=>{if(L[sel]&&L[sel].lock)return toast('Layer is locked. Unlock to move','err');if(sel>0){touch();[L[sel],L[sel-1]]=[L[sel-1],L[sel]];sel--;renderLayers()}},
  lsel:b=>{sel=+b.dataset.i;renderLayers();renderInsp()},
  lvis:b=>{touch();L[+b.dataset.i].vis^=1;renderLayers()},
  llock:b=>{touch();L[+b.dataset.i].lock^=1;renderLayers();renderInsp()},
  cpy:()=>{copyBuf=clone(L[sel]);toast('Properties copied','ok')},
  pst:()=>{
    if(!copyBuf||!L[sel]||L[sel].lock)return;
    touch();const t=L[sel];
    L[sel]=norm({...copyBuf,type:t.type,name:t.name});
    renderInsp();
  },
  rstl:()=>{const l=L[sel];if(!l||l.lock)return;touch();L[sel]=mk(l.type,{name:l.name});renderInsp()},
  rsts:b=>{
    const l=L[sel];if(!l||l.lock)return;
    touch();
    const section=b.dataset.sec;
    const all=[...FD_BASIC,...(FD_TYPE[l.type]||[])];
    const found=all.find(s=>s[0]===section);
    if(found)found[1].forEach(f=>{if(f[0]!=='img')l[f[0]]=D[f[0]]});
    renderInsp();
  },
  addrule:()=>{
    const l=L[sel];if(!l||l.lock)return;
    touch();
    const mn=PR.sy[0],mx=PR.sy[1],o0=l.sy;
    l.rules.push({...RD,o0,o1:clamp(o0+(mx-mn)*.2,mn,mx)});
    openSec.Reactive=1;renderInsp();
  },
  delrule:b=>{if(L[sel]&&L[sel].lock)return;touch();L[sel].rules.splice(+b.dataset.i,1);renderInsp()},
  apply:b=>{const p=allPre()[+b.dataset.i];touch();setProject(clone(p.layers),p.scene);renderAll();toast('Applied '+p.name,'ok')},
  fav:b=>{const n=allPre()[+b.dataset.i].name;favs=favs.includes(n)?favs.filter(x=>x!==n):[...favs,n];ls.set('av.favs',favs);renderPre()},
  delp:b=>{user.splice(+b.dataset.i,1);ls.set('av.presets',user);renderPre()},
  rand:()=>{
    const a=allPre(),p=a[Math.floor(Math.random()*a.length)];
    touch();setProject(clone(p.layers),p.scene);
    L.forEach(l=>{if(l.type!=='bg'&&l.color!=='#ffffff')l.hue=Math.round(Math.random()*120-60)});
    renderAll();toast('Random: '+p.name,'ok');
  },
  savep:()=>{
    const n=$('#pn').value.trim();
    if(!n){$('#pn').focus();return}
    user.push({name:n,layers:clone(L),scene:{...SC}});
    ls.set('av.presets',user);$('#pn').value='';renderPre();toast('Saved preset','ok');
  },
  jshow:()=>{$('#js').value=JSON.stringify({app:'AUDIOVIS',version:4,layers:clone(L),scene:SC},null,1)},
  jload:()=>{
    try{
      const o=JSON.parse($('#js').value);
      if(o.app!=='AUDIOVIS'||!Array.isArray(o.layers))throw Error('Not an AUDIOVIS project');
      if(o.version>4)throw Error('Made by a newer version');
      if(o.layers.length>60)throw Error('Too many layers');
      o.layers.forEach(l=>{if(typeof l.type!=='string')throw Error('Bad layer')});
      touch();setProject(o.layers,o.scene);renderAll();toast('Loaded','ok');
    }catch(e){toast('Import failed: '+e.message,'err')}
  },
  jsave:()=>saveBlob(new Blob([JSON.stringify({app:'AUDIOVIS',version:4,layers:clone(L),scene:SC})],{type:'application/json'}),'audiovis-template.viz'),
  eqrst:()=>{S.eq=SD.eq.slice();save();applyAudio();showTab('aud')},
  rsta:()=>{
    ['vol','pre','bass','bal','speed','norm','rev','dly','dist','fft','smooth','floor','beat','bmode','bsrc','bmin','bcool','bdec','boff','bpm','lfo1','lfo2'].forEach(k=>S[k]=SD[k]);
    S.eq=SD.eq.slice();save();applyAudio();showTab('aud');
  },
  rstsc:()=>{touch();SC={...SCD};showTab('scn')},
  rec:startRec,
  rcancel:cancelRec,
  rpause:b=>{if(!rec)return;rpaused=!rpaused;if(rpaused){rec.pause();au.pause();b.textContent='Resume'}else{rec.resume();au.play();b.textContent='Pause'}},
  still:()=>cv.toBlob(b=>b&&saveBlob(b,'audiovis-still.png'),'image/png'),
  saveout:()=>outBlob&&saveBlob(outBlob,outName),
  rstset:()=>{
    Object.assign(S,{...SD,eq:SD.eq.slice()});
    save();applyAudio();applyTheme(S.theme);syncSet();layout();size();
    toast('Settings reset','ok');
  },
  clr:()=>{
    ls.del('av.proj');ls.del('av.presets');
    user=[];hu=[];hr=[];
    setProject(PRE[3][2]);renderAll();toast('Saved projects cleared','ok');
  }
};

$('#app').addEventListener('click',e=>{
  const b=e.target.closest('[data-act]');
  if(!b)return;e.preventDefault();
  const f=ACT[b.dataset.act];if(f)f(b);
});

function setProp(l,k,v){
  l[k]=v;
  if(k==='font'&&document.fonts)document.fonts.load('20px "'+v+'"');
  if(k==='uni'||(k==='sx'&&l.uni)){if(l.uni)l.sy=l.sx}
  if(k==='band'&&v!=='custom'){const r=v==='all'?[30,16000]:BH[v];l.f0=r[0];l.f1=r[1]}
}

$('#app').addEventListener('input',e=>{
  const t=e.target;
  let v=t.type==='checkbox'?t.checked:(t.type==='range'||t.type==='number')?+t.value:t.value;
  if(t.type==='number'&&t.min!==''){if(!isFinite(v))return;v=clamp(v,+t.min,+t.max)}
  const row=t.closest('.rg');
  if(t.dataset.k!==undefined){
    const l=L[sel];if(!l||l.lock)return;
    if(t.classList.contains('hx')){if(!/^#[0-9a-f]{6}$/i.test(v))return;t.closest('.cr').querySelector('input[type=color]').value=v}
    else if(t.type==='color'){const h=t.closest('.cr').querySelector('.hx');if(h)h.value=v}
    touch();
    const k=t.dataset.k;
    setProp(l,k,t.type==='checkbox'?(v?1:0):v);
    if(row)row.querySelectorAll('input').forEach(i=>{if(i!==t)i.value=v});
    if(k==='name')renderLayers();
    if(k==='sx'&&l.uni)$$('#insp [data-k="sy"]').forEach(i=>i.value=l.sy);
    if(k==='uni'||k==='band'||k==='grad')renderInsp();
  }
  else if(t.dataset.r!==undefined){
    const l=L[sel];if(!l||l.lock)return;
    touch();
    const r=l.rules[+t.dataset.r],k=t.dataset.rk;
    r[k]=t.type==='checkbox'?(v?1:0):v;
    if(k==='prop'){const[mn,mx]=PR[v],o0=l[v];r.o0=o0;r.o1=clamp(o0+(mx-mn)*.2,mn,mx);renderInsp()}
  }
  else if(t.dataset.s!==undefined){
    const k=t.dataset.s;S[k]=t.dataset.n?+t.value:v;
    if(row)row.querySelectorAll('input').forEach(i=>{if(i!==t)i.value=v});
    save();applyAudio();
    if(k==='rot')layout();
    if(k==='pq')aq=1;
    if((k==='beat'||k==='bcool')&&S.bmode==='map')rescanSoon();
    if(['pq','res','ratio','cw','ch','xfps','mbps','abr','t0','t1'].includes(k)){size();if(k==='ratio')showTab('exp')}
    if(k==='ratio'&&tab!=='exp')size();
  }
  else if(t.dataset.eq!==undefined){
    const i=+t.dataset.eq;S.eq[i]=v;
    if(row)row.querySelectorAll('input').forEach(x=>{if(x!==t)x.value=v});
    save();if(fil[i])fil[i].gain.value=v;
  }
  else if(t.dataset.c!==undefined){
    touch();SC[t.dataset.c]=t.type==='color'?v:v;
    if(row)row.querySelectorAll('input').forEach(i=>{if(i!==t)i.value=v});
  }
  else if(t.id==='qs')renderQ();
  else if(t.id==='ps')renderPre();
  else if(t.id==='pc'){pf=t.value;renderPre()}
  else if(t.id==='slp'){
    clearTimeout(sleepT);
    const m=+t.value;
    if(m)sleepT=setTimeout(()=>{au.pause();toast('Sleep timer: paused')},m*60000);
  }
  else if(t.id==='eqp'&&EQP[t.value]){S.eq=EQP[t.value].slice();save();applyAudio();showTab('aud')}
});

$('#app').addEventListener('change',e=>{
  const t=e.target;
  if(t.id==='fi'||t.id==='fdr'){
    const a=[...t.files];
    const b=a.map(f=>f.arrayBuffer().catch(()=>null));
    t.value='';
    initAudio();if(ac)ac.resume();
    addFiles(a,b);
  }
  else if(t.id==='vzi'){const f=t.files[0];t.value='';if(f)inspectViz(f)}
  else if(t.dataset.img){
    const f=t.files[0],l=L[sel];
    if(!f||!l)return;
    const im=new Image();
    im.onload=()=>{l._img=im;toast('Image loaded','ok')};
    im.onerror=()=>toast('Could not read that image','err');
    im.src=URL.createObjectURL(f);
    if(t.type==='file')t.value='';
  }
});

$('#app').addEventListener('change',e=>{
  const t=e.target,key=t.dataset&&t.dataset.spg;
  if(!key||!t.value)return;
  const o=SPG[key][t.value];
  if(!o)return;
  if(key==='@scene'){touch();Object.assign(SC,o);showTab('scn')}
  else{Object.assign(S,o);save();applyAudio();showTab('aud')}
  toast('Applied '+t.value,'ok');
});

function syncSet(){
  $$('[data-s]').forEach(el=>{
    const v=S[el.dataset.s];
    if(el.type==='checkbox')el.checked=!!v;
    else if(el.type==='range'||el.type==='number')el.value=v;
    else el.value=v;
  });
  $('#theme').value=S.theme;
}
$('#set').addEventListener('change',e=>{
  const t=e.target;
  if(t.id==='theme')applyTheme(t.value);
  else if(t.dataset.s){
    S[t.dataset.s]=t.type==='checkbox'?(t.checked?1:0):t.dataset.n?+t.value:t.value;
    save();
    if(t.dataset.s==='rot')layout();
    if(t.dataset.s==='pq'){aq=1;size()}
  }
});
function syncBtns(){
  $('#bsh').classList.toggle('on',!!S.sh);
  $('#brp').classList.toggle('on',S.rp!=='off');
  $('#brp').innerHTML=ico('repeat')+(S.rp==='one'?'1':'');
}
$('#app').addEventListener('toggle',e=>{
  const d=e.target;
  if(d.dataset&&d.dataset.sec)openSec[d.dataset.sec]=d.open;
},true);

/* canvas drag + seek */
let drag=null;
cv.addEventListener('pointerdown',e=>{
  const l=L[sel];
  if(rec||!l||l.lock||l.type==='bg')return;
  const r=cv.getBoundingClientRect(),s=Math.min(r.width/cv.width,r.height/cv.height);
  drag={ox:r.left+(r.width-cv.width*s)/2,oy:r.top+(r.height-cv.height*s)/2,w:cv.width*s,h:cv.height*s,sx:e.clientX,sy:e.clientY,x:l.x,y:l.y};
  cv.setPointerCapture(e.pointerId);touch();
});
cv.addEventListener('pointermove',e=>{
  if(!drag)return;
  const l=L[sel];
  l.x=clamp(drag.x+(e.clientX-drag.sx)/drag.w*100,0,100);
  l.y=clamp(drag.y+(e.clientY-drag.sy)/drag.h*100,0,100);
  touch();
});
const endDrag=()=>{if(drag){drag=null;renderInsp()}};
cv.addEventListener('pointerup',endDrag);
cv.addEventListener('pointercancel',endDrag);

let sk=false;
const seekTo=e=>{const r=$('#wfw').getBoundingClientRect();if(au.duration)au.currentTime=clamp((e.clientX-r.left)/r.width,0,1)*au.duration};
$('#wfw').addEventListener('pointerdown',e=>{sk=true;$('#wfw').setPointerCapture(e.pointerId);seekTo(e)});
$('#wfw').addEventListener('pointermove',e=>{if(sk)seekTo(e)});
$('#wfw').addEventListener('pointerup',()=>sk=false);

addEventListener('keydown',e=>{
  const n=e.target.tagName;
  if(n==='INPUT'||n==='TEXTAREA'||n==='SELECT')return;
  const c=e.ctrlKey||e.metaKey;
  if(e.code==='Space'){e.preventDefault();ACT.play()}
  else if(e.key==='ArrowLeft'&&au.duration)au.currentTime-=5;
  else if(e.key==='ArrowRight'&&au.duration)au.currentTime+=5;
  else if(c&&e.key==='z'){e.preventDefault();undo()}
  else if(c&&e.key==='y'){e.preventDefault();redo()}
  else if(c&&e.key==='d'){e.preventDefault();ACT.ldup()}
  else if(e.key==='Delete')ACT.ldel();
  else if(!c&&(e.key==='b'||e.key==='B'))ACT.trig();
  else if(!c&&(e.key==='t'||e.key==='T'))ACT.tap();
});

/* ================= LAYOUT ================= */
function layout(){
  const pw=innerWidth,ph=innerHeight,por=ph>pw;
  let r=0;
  if(S.rot==='portrait'&&!por)r=-90;
  if(S.rot==='landscape'&&por)r=90;
  const a=$('#app');
  a.style.cssText=r==90
    ?`inset:auto;top:0;left:${pw}px;width:${ph}px;height:${pw}px;transform:rotate(90deg);transform-origin:0 0`
    :r==-90
    ?`inset:auto;top:${ph}px;left:0;width:${ph}px;height:${pw}px;transform:rotate(-90deg);transform-origin:0 0`
    :'';
  const w=r?ph:pw,was=document.body.classList.contains('wide'),wide=w>=820;
  document.body.classList.toggle('wide',wide);
  if(wide&&!was&&!open)setOpen(true);
  wfwW=0;setTimeout(drawWf,60);
}
addEventListener('resize',layout);
addEventListener('orientationchange',layout);
document.addEventListener('visibilitychange',()=>{
  if(document.hidden&&rec&&!rpaused){
    rpaused=true;rec.pause();au.pause();
    const b=$('[data-act="rpause"]');if(b)b.textContent='Resume';
    toast('Recording paused: keep this tab visible, then press Resume.','err');
  }
});

/* ================= MAIN LOOP ================= */
let lastF=0,fc=0,ft=0,aq=1,slow=0,tcS='',phX=-1,wfwW=0,mf=0,bdK=-1,monC=null,monX=null;
const elBD=$('#beatDot'),elPH=$('#ph'),elTC=$('#tc'),elFPS=$('#fps'),elWF=$('#wfw'),MF={f0:30,f1:16000,fsc:'log'};
function drawMon(){
  const m=$('#mon');if(!m)return;
  if(m!==monC){monC=m;monX=m.getContext('2d')}
  const w=m.clientWidth;if(m.width!==w)m.width=w;if(m.height!==90)m.height=90;
  const x=monX,W=m.width;x.clearRect(0,0,W,90);x.fillStyle=TC['--ac'];
  const N=64;
  for(let i=0;i<N;i++){const v=bandAvg(fpos(MF,i/N),fpos(MF,(i+1)/N));x.fillRect(i*W/N,50-v*50,W/N-1,v*50)}
  x.fillStyle=TC['--dm'];
  SRCS.forEach((k,i)=>{const v=SV[k]||0;x.fillRect(i*W/SRCS.length,90-v*34,W/SRCS.length-2,v*34)});
}
function frame(now){
  requestAnimationFrame(frame);
  const iv=1000/S.fps;
  if(!rec&&now-lastF<iv-2)return;
  if(!rec&&S.auto&&lastF&&!document.hidden){
    const g=now-lastF;
    if(g>iv*1.5&&g<2000)slow++;else if(slow>0)slow--;
    if(slow>40&&aq>.5){aq=Math.max(.5,aq*.8);slow=0;size();toast('Preview resolution lowered to keep playback smooth')}
  }
  lastF=now;
  if(rec&&!rpaused){if(lastRecT&&now-lastRecT>2500/S.xfps)drops++;lastRecT=now}
  else lastRecT=0;
  analyse(now);
  render(now/1000);
  const k=(SV.beat>.4?1:0)+(SV.kick>.6?2:0);
  if(k!==bdK&&elBD){bdK=k;elBD.classList.toggle('on',!!(k&1));elBD.classList.toggle('kick',!!(k&2))}
  if(rec&&S.t1>0&&au.currentTime>=Math.min(S.t1,au.duration))finishRec();
  const d=au.duration;
  if(d){
    const ct=au.currentTime,s=fmt(ct)+' / '+fmt(d);
    if(s!==tcS){elTC.textContent=s;tcS=s}
    if(!wfwW)wfwW=elWF.clientWidth;
    const px=Math.round(ct/d*wfwW*2)/2;
    if(px!==phX){elPH.style.transform='translateX('+px+'px)';phX=px}
    if(rec){
      const e0=S.t0,e1=S.t1>0?Math.min(S.t1,d):d;
      const pr=clamp((ct-e0)/Math.max(1,e1-e0),0,1),xp=$('#xp');
      if(xp)xp.value=pr;
      const xs=$('#xs');
      if(xs&&!rpaused)xs.textContent=`Recording ${(pr*100).toFixed(0)}%, about ${fmt((e1-ct)/au.rate)} left`;
    }
  }
  fc++;
  if(now-ft>500){
    elFPS.textContent=S.showFps?Math.round(fc*1000/(now-ft))+' FPS':'';
    fc=0;ft=now;
  }
  if(tab==='aud'&&open&&(mf^=1))drawMon();
}

/* ================= INIT ================= */
applyTheme(S.theme);
syncSet();
syncBtns();
layout();
size();
showTab('lib');
requestAnimationFrame(frame);
const applyCC=()=>document.body.classList.toggle('cc',S.cc===undefined||!!S.cc);
applyCC();
$('#set').addEventListener('change',e=>{if(e.target.dataset&&e.target.dataset.s==='cc')applyCC()});
applyTheme(S.theme);