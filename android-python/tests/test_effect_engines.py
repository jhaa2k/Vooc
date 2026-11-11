import unittest
from vooc.measure_engine import MeasureEngine
from vooc.particle_system import ParticleSystem
class MeasureTests(unittest.TestCase):
 def test_nothing_static(self):
  e=MeasureEngine(); e.update(.1,beat=True,rms=.8); self.assertEqual(e.evaluate("Nothing",4,9),4)
 def test_beat_pulse(self):
  e=MeasureEngine(); e.update(.01,beat=True); self.assertGreater(e.evaluate("BeatPulseScale",amount=2),0)
 def test_volume(self):
  e=MeasureEngine(); e.update(.01,rms=.5); self.assertAlmostEqual(e.evaluate("VolumeLevelResponsive",amount=2),1)
 def test_track_progress(self):
  e=MeasureEngine(); e.update(.1,track_position=25,track_duration=100); self.assertAlmostEqual(e.evaluate("TrackProgressPercentage"),.25)
class ParticleTests(unittest.TestCase):
 def test_count_limit(self):
  p=ParticleSystem()
  for _ in range(40):items=p.update(.05,{"count":8,"spawn_time":.005},300,200)
  self.assertLessEqual(len(items),8)
 def test_lifetime(self):
  p=ParticleSystem(); p.update(.05,{"count":4,"spawn_time":.005,"lifetime":.1},300,200)
  for _ in range(10):items=p.update(.05,{"count":4,"spawn_time":10,"lifetime":.1},300,200)
  self.assertEqual(len(items),0)
if __name__=="__main__":unittest.main()
