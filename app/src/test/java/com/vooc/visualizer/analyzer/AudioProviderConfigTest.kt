package com.vooc.visualizer.analyzer

import com.vooc.visualizer.studio.ElementPropertyCatalog
import com.vooc.visualizer.studio.ElementValue
import com.vooc.visualizer.studio.StudioElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProviderConfigTest {
    @Test
    fun persistedProviderControlsMapToRuntimeConfiguration() {
        val document = ElementPropertyCatalog.create(StudioElementType.AUDIO_PROVIDER, "provider")
            .withValue("Provider Enabled", ElementValue.Bool(false))
            .withValue("Analysis Mode", ElementValue.Text("Beat"))
            .withValue("Channel Mode", ElementValue.Text("Right"))
            .withValue("Window Function", ElementValue.Text("Blackman"))
            .withValue("Beat Detection Enabled", ElementValue.Bool(false))
            .withValue("Beat Sensitivity", ElementValue.Number(4.0))
            .withValue("Beat Threshold", ElementValue.Number(0.5))
            .withValue("Beat Release", ElementValue.Number(0.3))
            .withValue("Fft Size", ElementValue.Text("4096"))

        val config = document.toAudioProviderConfig()
        assertFalse(config.providerEnabled)
        assertEquals(ProviderAnalysisMode.BEAT, config.analysisMode)
        assertEquals(ProviderChannelMode.RIGHT, config.channelMode)
        assertEquals(WindowFunction.BLACKMAN, config.windowFunction)
        assertFalse(config.beatDetectionEnabled)
        assertEquals(4f, config.beatSensitivity, 0.0001f)
        assertEquals(0.5f, config.beatThreshold, 0.0001f)
        assertEquals(0.3f, config.beatRelease, 0.0001f)
        assertEquals(4096, config.fftSize)
    }

    @Test
    fun disabledStereoProviderDoesNotForceStereoAnalysis() {
        assertFalse(listOf(AudioProviderConfig(providerEnabled = false, channelMode = ProviderChannelMode.LEFT)).requiresStereoAnalysis())
        assertTrue(listOf(AudioProviderConfig(providerEnabled = true, channelMode = ProviderChannelMode.LEFT)).requiresStereoAnalysis())
        assertFalse(listOf(AudioProviderConfig(providerEnabled = true, channelMode = ProviderChannelMode.MONO_MIX)).requiresStereoAnalysis())
    }

    @Test
    fun firstEnabledProviderOwnsSharedAnalyzerSettings() {
        val disabled = AudioProviderConfig(providerEnabled = false, fftSize = 4096)
        val enabled = AudioProviderConfig(providerEnabled = true, fftSize = 1024)
        assertEquals(enabled, listOf(disabled, enabled).analyzerConfigSource())
        assertEquals(disabled, listOf(disabled).analyzerConfigSource())
        assertEquals(1, listOf(disabled, enabled).analyzerConfigSourceIndex())
        assertEquals(0, listOf(disabled).analyzerConfigSourceIndex())
    }

    @Test
    fun providerAppliesFftWindowChannelAndBeatSettingsToAnalyzer() {
        val provider = AudioProviderConfig(
            fftSize = 4096,
            windowFunction = WindowFunction.HAMMING,
            channelMode = ProviderChannelMode.LEFT,
            beatSensitivity = 4f,
            beatThreshold = 0.5f,
            beatRelease = 0.3f
        )
        val base = AnalyzerConfig()
        val applied = provider.applyTo(base)

        assertEquals(FftSize.S4096, applied.fftSize)
        assertEquals(WindowFunction.HAMMING, applied.window)
        assertEquals(ChannelMode.STEREO_SPLIT, applied.channelMode)
        assertEquals(0.5f, applied.beat.sensitivity, 0.0001f)
        assertEquals(3f, applied.beat.threshold, 0.0001f)
        assertEquals(300, applied.beat.cooldownMs)
        assertEquals(base.overlap, applied.overlap)
        assertEquals(base.smoothing, applied.smoothing)
    }

    @Test
    fun stereoAverageRequestsBothIndependentChannelSpectra() {
        val applied = AudioProviderConfig(channelMode = ProviderChannelMode.STEREO_AVERAGE)
            .applyTo(AnalyzerConfig())
        assertEquals(ChannelMode.STEREO_SPLIT, applied.channelMode)
    }

    @Test
    fun invalidProviderBeatValuesAreClampedBeforeAnalyzerConfiguration() {
        val applied = AudioProviderConfig(
            beatSensitivity = 99f,
            beatThreshold = -2f,
            beatRelease = 9f
        ).applyTo(AnalyzerConfig())

        assertEquals(1f, applied.beat.sensitivity, 0.0001f)
        assertEquals(1f, applied.beat.threshold, 0.0001f)
        assertEquals(500, applied.beat.cooldownMs)
        assertTrue(applied.beat.sensitivity in 0f..1f)
    }
    @Test fun beatReleaseIsClampedToTheSupportedHalfSecondRange() {
        val config = AudioProviderConfig(beatRelease = 5f).validated()
        assertEquals(0.5f, config.beatRelease, 1e-6f)
        val minimumConfig = AudioProviderConfig(beatRelease = 0f).validated()
        assertEquals(0.05f, minimumConfig.beatRelease, 1e-6f)
        assertEquals(50, minimumConfig.applyTo(AnalyzerConfig()).beat.cooldownMs)
    }


    @Test
    fun persistedSpectrumShapingControlsMapToRuntimeConfiguration() {
        val document = ElementPropertyCatalog.create(StudioElementType.AUDIO_PROVIDER, "provider")
            .withValue("Sample Out Count", ElementValue.Integer(64))
            .withValue("Mirror Samples", ElementValue.Bool(true))
            .withValue("Repeat Samples", ElementValue.Bool(true))
            .withValue("Star And End Gap", ElementValue.Integer(3))
            .withValue("Smooth", ElementValue.Number(0.7))
            .withValue("Pre Smooth", ElementValue.Number(0.2))
            .withValue("Filter Radius", ElementValue.Integer(4))
            .withValue("Filter Strength", ElementValue.Number(0.6))
            .withValue("AWeight", ElementValue.Number(0.4))
            .withValue("Output Multiplier", ElementValue.Number(2.5))
            .withValue("Lower Hz", ElementValue.Number(80.0))
            .withValue("Higher Hz", ElementValue.Number(12000.0))
            .withValue("Hz Linear Factor", ElementValue.Number(-0.3))
            .withValue("Freq Shift", ElementValue.Number(120.0))
            .withValue("Beat Smooth", ElementValue.Number(0.8))
            .withValue("Beat Range Bar First", ElementValue.Number(0.25))
            .withValue("Beat Range Bar Last", ElementValue.Number(0.75))
            .withValue("Beat Range Value Lower", ElementValue.Number(0.1))
            .withValue("Beat Range Value Higher", ElementValue.Number(0.9))

        val config = document.toAudioProviderConfig()
        assertEquals(64, config.sampleOutCount)
        assertTrue(config.mirrorSamples)
        assertTrue(config.repeatSamples)
        assertEquals(3, config.startAndEndGap)
        assertEquals(0.7f, config.smooth, 0.0001f)
        assertEquals(0.2f, config.preSmooth, 0.0001f)
        assertEquals(4, config.filterRadius)
        assertEquals(0.6f, config.filterStrength, 0.0001f)
        assertEquals(0.4f, config.aWeight, 0.0001f)
        assertEquals(2.5f, config.outputMultiplier, 0.0001f)
        assertEquals(80f, config.lowerHz, 0.0001f)
        assertEquals(12000f, config.higherHz, 0.0001f)
        assertEquals(-0.3f, config.hzLinearFactor, 0.0001f)
        assertEquals(120f, config.freqShift, 0.0001f)
        assertEquals(0.8f, config.beatSmooth, 0.0001f)
        assertEquals(15, config.beatRangeBarFirst)
        assertEquals(47, config.beatRangeBarLast)
        assertEquals(0.1f, config.beatRangeValueLower, 0.0001f)
        assertEquals(0.9f, config.beatRangeValueHigher, 0.0001f)
    }

}
