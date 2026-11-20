package com.vooc.visualizer.analyzer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnalyzerTest {
    private val sr = 44100

    private fun sine(freq: Double, n: Int, amp: Float = 1f, sampleRate: Int = sr) =
        FloatArray(n) { (amp * sin(2.0 * PI * freq * it / sampleRate)).toFloat() }

    @Test
    fun fftPeakAtExpectedBinForEveryWindow() {
        val size = 2048
        val bin = 47
        val freq = bin * sr.toDouble() / size
        for (w in WindowFunction.entries) {
            val fft = FFTProcessor(size, w)
            val out = FftResult(fft.bins)
            fft.process(sine(freq, size), 0, out)
            var peakBin = 0
            for (k in 0 until fft.bins) if (out.raw[k] > out.raw[peakBin]) peakBin = k
            assertEquals("window $w", bin, peakBin)
            assertEquals("window $w amplitude", 1.0, out.raw[bin].toDouble(), 0.03)
        }
    }

    @Test
    fun binWidthMatchesFormula() {
        assertEquals(21.533, FFTProcessor(2048).binWidthHz(44100).toDouble(), 0.01)
    }

    @Test
    fun normalizationPeakScalesToOne() {
        val fft = FFTProcessor(1024)
        val out = FftResult(fft.bins)
        fft.process(sine(1000.0, 1024, 0.25f), 0, out, Normalization.PEAK)
        assertEquals(1.0, out.magnitude.max().toDouble(), 1e-4)
    }

    @Test
    fun windowShapes() {
        val hann = Windows.create(WindowFunction.HANN, 64)
        assertEquals(0.0, hann[0].toDouble(), 1e-6)
        assertTrue(hann[32] > 0.99f)
        assertTrue(Windows.create(WindowFunction.RECTANGULAR, 16).all { it == 1f })
    }

    @Test
    fun smootherAttackIsFasterThanRelease() {
        val s = SpectrumSmoother(1, SmoothingConfig(attackMs = 10f, releaseMs = 500f, asymmetric = true))
        val out = FloatArray(1)
        s.update(floatArrayOf(0f), 0.016f, out)
        s.update(floatArrayOf(1f), 0.016f, out)
        val afterAttack = out[0]
        s.update(floatArrayOf(0f), 0.016f, out)
        val afterRelease = out[0]
        assertTrue(afterAttack > 0.7f)
        assertTrue(afterRelease > afterAttack * 0.9f)
    }

    @Test
    fun bandSplitterSeparatesBassMidTreble() {
        val cases = listOf(100.0 to 0, 1000.0 to 1, 8000.0 to 2)
        for ((freq, expected) in cases) {
            val analyzer = AudioAnalyzer(sr)
            val sig = sine(freq, 2048, 0.5f)
            var frame = analyzer.analyzeWindow(sig, sig, 0.0)
            for (i in 1..5) frame = analyzer.analyzeWindow(sig, sig, i / 60.0)
            val levels = frame.bands
            val best = levels.indices.maxByOrNull { levels[it] }!!
            assertEquals("tone $freq Hz", expected, best)
        }
    }

    @Test
    fun crossoverSplitsLowAndHigh() {
        val xo = LinkwitzRiley(sr, 1000f, CrossoverOrder.LR24)
        val out = FloatArray(2)
        var lowMax = 0f
        var highMax = 0f
        val low = sine(100.0, 20000)
        for (i in low.indices) {
            xo.process(low[i], out)
            if (i > 10000) { lowMax = maxOf(lowMax, abs(out[0])); highMax = maxOf(highMax, abs(out[1])) }
        }
        assertTrue(lowMax > 0.9f)
        assertTrue(highMax < 0.05f)
    }

    @Test
    fun ringBufferOverrunAndUnderrun() {
        val ring = FloatRingBuffer(8)
        ring.write(FloatArray(20) { it.toFloat() }, 0, 20)
        assertTrue(ring.overrunDrops > 0)
        val dst = FloatArray(8)
        assertEquals(8, ring.copyWindowEnding(20, dst, 8))
        assertEquals(12f, dst[0], 0f)
        assertEquals(19f, dst[7], 0f)
        val big = FloatArray(16)
        assertEquals(8, ring.copyWindowEnding(20, big, 16))
        assertEquals(0f, big[0], 0f)
        assertTrue(ring.underrunFills > 0)
    }

    @Test
    fun beatDetectorFindsKickTrackTempo() {
        val n = sr * 14
        val kick = FloatArray(n)
        val period = (sr * 0.5).toInt()
        var start = 0
        while (start < n) {
            for (i in 0 until (sr * 0.15).toInt()) {
                if (start + i < n) kick[start + i] = (0.9 * exp(-i / (sr * 0.03)) * sin(2.0 * PI * 60.0 * i / sr)).toFloat()
            }
            start += period
        }
        val analyzer = AudioAnalyzer(sr)
        var beats = 0
        var bpm = 0f
        var t = 0.0
        while (t < 14.0) {
            val frame = analyzer.analyzeOffline(kick, kick, (t * sr).toInt(), t)
            if (t > 2.0 && frame.beat.beat) beats++
            bpm = frame.beat.bpm
            t += 1.0 / 60.0
        }
        assertTrue("beats=$beats", beats in 18..26)
        assertTrue("bpm=$bpm", bpm in 114f..126f)
    }

    @Test
    fun tapTempoAndManualOverride() {
        val tracker = BeatTracker(sr, 2048, BeatConfig())
        tracker.tapTempo(0.0)
        tracker.tapTempo(0.5)
        val bpm = tracker.tapTempo(1.0)
        assertEquals(120.0, bpm.toDouble(), 0.5)
        tracker.setManualBpm(500f)
        val state = tracker.process(FloatArray(1024), 2.0)
        assertEquals(240.0, state.bpm.toDouble(), 0.01)
        assertTrue(state.bpmIsManual)
    }

    @Test
    fun pcmConversionRoundTrip16Bit() {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(16384).putShort(-16384).putShort(0).putShort(32767)
        buf.flip()
        val out = FloatArray(4)
        assertEquals(4, PcmConverter.toFloat(buf, PcmFormat.PCM_16, out))
        assertEquals(0.5f, out[0], 1e-4f)
        assertEquals(-0.5f, out[1], 1e-4f)
        assertEquals(1f, out[3], 1e-3f)
        assertEquals(8, buf.remaining())
    }

    @Test
    fun spectrumMapperLogSpacing() {
        val spectrum = FloatArray(1024) { 1f }
        val out = FloatArray(32)
        SpectrumMapper.map(spectrum, sr, 2048, 32, 20f, 20000f, FrequencyScale.LOGARITHMIC, Interpolation.LINEAR, out)
        assertTrue(out.all { it > 0.99f })
    }

    @Test
    fun spectrumFrequencyCropSelectsExpectedBinsAndKeepsOriginalMagnitudes() {
        val spectrum = FloatArray(100) { it / 100f }
        val cropped = SpectrumMapper.cropFrequencyRange(spectrum, 10000, 1000f, 2000f)
        assertEquals(20, cropped.size)
        assertEquals(spectrum[20], cropped.first(), 0f)
        assertEquals(spectrum[39], cropped.last(), 0f)
    }

    @Test
    fun spectrumFrequencyCropClampsToNyquistAndHandlesEmptyInput() {
        val spectrum = FloatArray(100) { it.toFloat() }
        val cropped = SpectrumMapper.cropFrequencyRange(spectrum, 10000, 4900f, 24000f)
        assertEquals(2, cropped.size)
        assertEquals(98f, cropped.first(), 0f)
        assertEquals(99f, cropped.last(), 0f)
        assertTrue(SpectrumMapper.cropFrequencyRange(FloatArray(0), 10000, 100f, 1000f).isEmpty())
    }

    @Test
    fun invalidConfigIsRejected() {
        try {
            BandConfig(bands = BandConfig.logSpaced(32) + BandSpec("x", 20f, 30f))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(BandConfig.logSpaced(32).isEmpty())
    }

    @Test
    fun stereoSplitProducesPerChannelAnalysis() {
        val cfg = AnalyzerConfig(channelMode = ChannelMode.STEREO_SPLIT)
        val analyzer = AudioAnalyzer(sr, cfg)
        val l = sine(200.0, 2048, 0.8f)
        val r = FloatArray(2048)
        val frame = analyzer.analyzeWindow(l, r, 0.0)
        assertTrue(frame.channel(ChannelSelect.LEFT).rms > 0.5f)
        assertTrue(frame.channel(ChannelSelect.RIGHT).rms < 0.01f)
    }
}
