"""Live audio effects for the Android MediaPlayer session.

Every setter is applied immediately while the user drags a slider:
volume / balance / pre-gain (MediaPlayer volume + LoudnessEnhancer),
10-band EQ (mapped onto the device Equalizer bands), bass boost, 3D virtualizer
and playback speed / pitch (PlaybackParams). Every effect is optional: devices
that lack one simply skip it.
"""
import math


def _clamp(v, lo, hi):
    return max(lo, min(hi, v))


def eq_gain_at(freq_hz, freqs, gains):
    """Interpolate our 10-band EQ (dB) at an arbitrary frequency (log scale)."""
    if not freqs:
        return 0.0
    if freq_hz <= freqs[0]:
        return float(gains[0])
    if freq_hz >= freqs[-1]:
        return float(gains[-1])
    lf = math.log(freq_hz)
    for i in range(len(freqs) - 1):
        a, b = freqs[i], freqs[i + 1]
        if a <= freq_hz <= b:
            t = (lf - math.log(a)) / (math.log(b) - math.log(a))
            return float(gains[i]) * (1 - t) + float(gains[i + 1]) * t
    return 0.0


class AndroidAudioFx:
    def __init__(self):
        self.player = None
        self.eq = None
        self.bass = None
        self.loud = None
        self.virt = None
        self.settings = {}
        self.eq_freqs = []
        self.available = []

    # ------------------------------------------------------------ lifecycle
    def attach(self, player, settings, eq_freqs):
        self.release()
        self.player = player
        self.eq_freqs = list(eq_freqs)
        self.settings = dict(settings)
        self.available = []
        try:
            from jnius import autoclass
            session = int(player.getAudioSessionId())
        except Exception:
            return
        for attr, cls in (("eq", "android.media.audiofx.Equalizer"),
                          ("bass", "android.media.audiofx.BassBoost"),
                          ("virt", "android.media.audiofx.Virtualizer")):
            try:
                fx = autoclass(cls)(0, session)
                setattr(self, attr, fx)
                self.available.append(attr)
            except Exception as exc:
                print("Vooc fx: %s unavailable: %s" % (cls, exc))
        try:
            self.loud = autoclass("android.media.audiofx.LoudnessEnhancer")(session)
            self.available.append("loud")
        except Exception as exc:
            print("Vooc fx: LoudnessEnhancer unavailable: %s" % exc)
        self.apply_all(self.settings)

    def release(self):
        for attr in ("eq", "bass", "loud", "virt"):
            fx = getattr(self, attr)
            if fx is not None:
                try:
                    fx.setEnabled(False)
                    fx.release()
                except Exception:
                    pass
            setattr(self, attr, None)
        self.player = None

    # ------------------------------------------------------------ setters
    def apply_all(self, settings):
        self.settings.update(settings)
        self.apply_volume()
        self.apply_eq()
        self.apply_bass()
        self.apply_virtualizer()

    def set(self, key, value):
        self.settings[key] = value
        if key in ("volume", "balance", "pre"):
            self.apply_volume()
        elif key.startswith("eq_"):
            self.apply_eq()
        elif key == "bass":
            self.apply_bass()
        elif key == "virtualizer":
            self.apply_virtualizer()
        elif key in ("speed", "pitch"):
            self.apply_speed()

    def apply_volume(self):
        if self.player is None:
            return
        s = self.settings
        vol = _clamp(float(s.get("volume", 100)) / 100.0, 0.0, 1.5)
        pre_db = float(s.get("pre", 0))
        balance = _clamp(float(s.get("balance", 0)) / 100.0, -1.0, 1.0)
        base = min(1.0, vol) * (10 ** (min(0.0, pre_db) / 20.0))
        left = base * (1.0 - max(0.0, balance))
        right = base * (1.0 + min(0.0, balance))
        try:
            self.player.setVolume(float(left), float(right))
        except Exception:
            pass
        boost_db = max(0.0, pre_db) + (20 * math.log10(vol) if vol > 1.0 else 0.0)
        if self.loud is not None:
            try:
                self.loud.setTargetGain(int(boost_db * 100))
                self.loud.setEnabled(boost_db > 0.05)
            except Exception:
                pass

    def apply_eq(self):
        if self.eq is None:
            return
        try:
            gains = [float(self.settings.get("eq_%d" % i, 0)) for i in range(len(self.eq_freqs))]
            rng = self.eq.getBandLevelRange()
            lo, hi = int(rng[0]), int(rng[1])
            for band in range(int(self.eq.getNumberOfBands())):
                center = float(self.eq.getCenterFreq(band)) / 1000.0
                level = int(_clamp(eq_gain_at(center, self.eq_freqs, gains) * 100, lo, hi))
                self.eq.setBandLevel(band, level)
            self.eq.setEnabled(any(abs(g) > 0.01 for g in gains))
        except Exception as exc:
            print("Vooc fx: EQ failed: %s" % exc)

    def apply_bass(self):
        if self.bass is None:
            return
        try:
            # bass slider is 0..12 dB of boost (negative values = no boost)
            amount = _clamp(float(self.settings.get("bass", 0)) / 12.0, 0.0, 1.0)
            self.bass.setStrength(int(amount * 1000))
            self.bass.setEnabled(amount > 0.01)
        except Exception as exc:
            print("Vooc fx: bass failed: %s" % exc)

    def apply_virtualizer(self):
        if self.virt is None:
            return
        try:
            amount = _clamp(float(self.settings.get("virtualizer", 0)) / 100.0, 0.0, 1.0)
            self.virt.setStrength(int(amount * 1000))
            self.virt.setEnabled(amount > 0.01)
        except Exception as exc:
            print("Vooc fx: virtualizer failed: %s" % exc)

    def apply_speed(self, playing=None):
        """PlaybackParams resumes a paused player, so only apply while playing."""
        if self.player is None:
            return
        try:
            if playing is None:
                playing = bool(self.player.isPlaying())
            if not playing:
                return
            speed = _clamp(float(self.settings.get("speed", 100)) / 100.0, 0.5, 2.0)
            pitch = _clamp(float(self.settings.get("pitch", 100)) / 100.0, 0.5, 2.0)
            params = self.player.getPlaybackParams().setSpeed(float(speed)).setPitch(float(pitch))
            self.player.setPlaybackParams(params)
        except Exception as exc:
            print("Vooc fx: speed failed: %s" % exc)
