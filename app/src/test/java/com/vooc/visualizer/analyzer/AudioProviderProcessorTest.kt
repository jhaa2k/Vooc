package com.vooc.visualizer.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProviderProcessorTest {
    private fun frame(
        spectrum: FloatArray = FloatArray(1024) { i -> if (i in 10..20) 1f else 0.1f },
        beat: BeatState = BeatState.NONE,
        time: Double = 0.0,
        fftSize: Int = 2048,
        sampleRate: Int = 44100
    ) = AnalysisFrame(
        timeSec = time, sampleRate = sampleRate, fftSize = fftSize,
        binWidthHz = sampleRate.toFloat() / fftSize, spectrum = spectrum,
        rawMagnitude = spectrum, phase = FloatArray(spectrum.size), db = FloatArray(spectrum.size),
        bands = FloatArray(3), bandLabels = listOf("Bass", "Mid", "Treble"), beat = beat,
        rms = 0.2f, peak = 0.5f, channels = emptyMap(), waveform = FloatArray(256)
    )

    @Test fun outputCountAndGapAreApplied() {
        val result = AudioProviderProcessor().process(frame(), AudioProviderConfig(sampleOutCount = 64, startAndEndGap = 4))
        assertEquals(64, result.samples.size)
        assertEquals(0f, result.samples[0], 0f)
        assertEquals(0f, result.samples[3], 0f)
        assertTrue(result.samples[4] > 0f)
        assertEquals(0f, result.samples[63], 0f)
    }

    @Test fun mirrorProducesSymmetricVisualSamplesWithoutSummingEnergy() {
        val result = AudioProviderProcessor().process(frame(), AudioProviderConfig(sampleOutCount = 32, mirrorSamples = true, smooth = 0f))
        for (i in 0 until 16) assertEquals(result.samples[i], result.samples[31 - i], 1e-6f)
    }

    @Test fun outputMultiplierScalesSamples() {
        val quietFrame = frame(FloatArray(1024) { 0.01f })
        val base = AudioProviderProcessor().process(quietFrame, AudioProviderConfig(sampleOutCount = 32, smooth = 0f, outputMultiplier = 1f))
        val doubled = AudioProviderProcessor().process(quietFrame, AudioProviderConfig(sampleOutCount = 32, smooth = 0f, outputMultiplier = 2f))
        assertTrue(base.samples[8] > 0f)
        assertEquals(base.samples[8] * 2f, doubled.samples[8], 1e-4f)
    }

    @Test fun filterStrengthKeepsValuesBetweenOriginalAndNeighborhoodAverage() {
        val raw = FloatArray(1024) { 0f }.also { it[20] = 1f }
        val result = AudioProviderProcessor().process(frame(raw), AudioProviderConfig(sampleOutCount = 64, lowerHz = 100f, higherHz = 2000f, filterRadius = 2, filterStrength = 1f, smooth = 0f))
        assertTrue(result.samples.all { it.isFinite() && it >= 0f })
    }

    @Test fun validatedConfigClampsInvalidValues() {
        val config = AudioProviderConfig(sampleOutCount = 9999, smooth = Float.NaN, lowerHz = -5f, higherHz = 1f).validated(22050f)
        assertEquals(2048, config.sampleOutCount)
        assertEquals(0f, config.smooth, 0f)
        assertTrue(config.lowerHz >= 1f)
        assertTrue(config.higherHz > config.lowerHz)
    }

    @Test fun aWeightingUsesFrequencyDependentStandardCurve() {
        val raw = FloatArray(1024) { 1f }
        val result = AudioProviderProcessor().process(
            frame(raw), AudioProviderConfig(sampleOutCount = 2, lowerHz = 100f, higherHz = 10000f, aWeight = 1f, smooth = 0f)
        )
        assertTrue("100 Hz should be attenuated more than 10 kHz", result.samples[0] < result.samples[1])
    }

    @Test fun beatNeedsAnalyzerOnsetRatherThanSustainedLoudness() {
        val quiet = frame(FloatArray(1024) { 0.9f })
        val processor = AudioProviderProcessor()
        val noBeat = processor.process(quiet, AudioProviderConfig(sampleOutCount = 32, smooth = 0f))
        assertEquals(0f, noBeat.beatValue, 0f)
    } 
    @Test fun repeatedSamplesRepeatMappedOutputInsteadOfRawFftBins() {
        val result = AudioProviderProcessor().process(
            frame(), AudioProviderConfig(sampleOutCount = 2048, repeatSamples = true, smooth = 0f, filterRadius = 0)
        )
        assertEquals(result.samples[0], result.samples[1024], 1e-6f)
        assertEquals(result.samples[100], result.samples[1124], 1e-6f)
    }

    @Test fun smoothingIsTimeNormalizedAcrossAnalysisCadences() {
        val config = AudioProviderConfig(sampleOutCount = 32, smooth = 0.8f, filterRadius = 0, outputMultiplier = 1f)
        val raw = FloatArray(1024) { 0.75f }
        val at60Hz = AudioProviderProcessor()
        val at30Hz = AudioProviderProcessor()
        var result60 = 0f
        var result30 = 0f
        for (i in 0..60) {
            result60 = at60Hz.process(frame(raw, time = i / 60.0), config).samples[10]
        }
        for (i in 0..30) {
            result30 = at30Hz.process(frame(raw, time = i / 30.0), config).samples[10]
        }
        assertEquals(result60, result30, 1e-4f)
    }

}
