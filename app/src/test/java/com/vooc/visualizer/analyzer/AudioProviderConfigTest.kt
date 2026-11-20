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
}
