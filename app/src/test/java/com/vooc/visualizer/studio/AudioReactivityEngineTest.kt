package com.vooc.visualizer.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioReactivityEngineTest {
    @Test fun linearMappingHonorsInputAndOutputRanges() {
        val config = MeasurementMapping(inputMin = 0.2f, inputMax = 0.8f, outputMin = 2f, outputMax = 4f)
        assertEquals(3f, AudioReactivityEngine.map(0.5f, config), 0.0001f)
    }

    @Test fun curvesAndInvertChangeTheResponse() {
        val config = MeasurementMapping()
        assertEquals(0.25f, AudioReactivityEngine.map(0.5f, config, "Square"), 0.0001f)
        assertEquals(0.5f, AudioReactivityEngine.map(0.5f, config.copy(invert = true)), 0.0001f)
        assertNotEquals(AudioReactivityEngine.map(0.2f, config), AudioReactivityEngine.map(0.2f, config, "Logarithmic"))
    }

    @Test fun thresholdAndClampAreApplied() {
        val config = MeasurementMapping(outputMin = -2f, outputMax = 2f, multiplier = 10f)
        assertEquals(-2f, AudioReactivityEngine.map(0.2f, config, threshold = 0.3f), 0.0001f)
        assertEquals(2f, AudioReactivityEngine.map(1f, config), 0.0001f)
        assertTrue(AudioReactivityEngine.map(1f, config, clampOutput = false) > 2f)
    }

    @Test fun disabledLayerDoesNotReact() {
        assertNull(AudioReactivityEngine.applyTo(emptyMap(), AudioReactivitySources(overall = 1f)))
    }

    @Test fun sourceSelectionUsesRequestedAudioMeasurement() {
        val sources = AudioReactivitySources(bass = 0.2f, treble = 0.9f, beat = 0.7f)
        assertEquals(0.2f, sources.value("Bass"), 0.0001f)
        assertEquals(0.9f, sources.value("Treble"), 0.0001f)
        assertEquals(0.7f, sources.value("Beat"), 0.0001f)
    }

    @Test fun envelopeUsesFastAttackAndControlledRelease() {
        val envelope = AudioReactivityEnvelope()
        assertEquals(0f, envelope.update(0f, 1f / 60f, 0.1f, 0.5f), 0.0001f)
        val attacked = envelope.update(1f, 1f / 60f, 0.1f, 0.5f)
        assertTrue(attacked > 0f)
        val released = envelope.update(0f, 1f / 60f, 0.1f, 0.5f)
        assertTrue(released > 0f && released < attacked)
    }
    @Test fun spectrumSourcesAreNormalizedAndSplitIntoBands() {
        val sources = audioReactivitySources(
            samples = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f),
            overall = 0.4f,
            beat = 0.75f,
            waveform = 0.31f,
            left = 0.22f,
            right = 0.67f
        )
        assertEquals(0.4f, sources.overall, 0.0001f)
        assertEquals(0.75f, sources.beat, 0.0001f)
        assertEquals(0.15f, sources.bass, 0.0001f)
        assertEquals(0.95f, sources.treble, 0.0001f)
        assertEquals(0.31f, sources.value("Waveform"), 0.0001f)
        assertEquals(0.22f, sources.value("Left Channel"), 0.0001f)
        assertEquals(0.67f, sources.value("Right Channel"), 0.0001f)
    }

    @Test fun runtimeHonorsAttackAndReleaseAndResetsWhenDisabled() {
        val runtime = AudioReactivityRuntime()
        val properties = mapOf(
            "Audio Reactivity Enabled" to ElementValue.Bool(true),
            "Audio Reactivity Source" to ElementValue.Text("Beat"),
            "Audio Reactivity Attack" to ElementValue.Number(0.1),
            "Audio Reactivity Release" to ElementValue.Number(0.5)
        )
        val first = runtime.update(properties, AudioReactivitySources(beat = 0f), 1f / 60f)!!
        val attacked = runtime.update(properties, AudioReactivitySources(beat = 1f), 1f / 60f)!!
        assertEquals(0f, first, 0.0001f)
        assertTrue(attacked > 0f && attacked < 1f)
        val disabled = runtime.update(emptyMap(), AudioReactivitySources(beat = 1f), 1f / 60f)
        assertNull(disabled)
        assertEquals(1f, runtime.update(properties, AudioReactivitySources(beat = 1f), 1f / 60f)!!, 0.0001f)
    }

    @Test fun applyToRoutesMappedSignalToSelectedTransform() {
        val properties = mapOf("Audio Reactivity Apply To" to ElementValue.Text("Rotation"))
        val rotation = audioReactivityTransform(properties, 12f)
        assertEquals(12f, rotation.rotationOffset, 0.0001f)
        assertEquals(1f, rotation.scaleMultiplier, 0.0001f)
        val scale = audioReactivityTransform(
            mapOf("Audio Reactivity Apply To" to ElementValue.Text("Scale")), 0.5f
        )
        assertEquals(1.5f, scale.scaleMultiplier, 0.0001f)
    }

    @Test fun applyToRoutesColorAndEffectStrengthIndependently() {
        val color = audioReactivityTransform(
            mapOf("Audio Reactivity Apply To" to ElementValue.Text("Color")), 0.5f
        )
        assertEquals(0.625f, color.colorMultiplier, 0.0001f)
        assertEquals(1f, color.effectStrengthMultiplier, 0.0001f)

        val effect = audioReactivityTransform(
            mapOf("Audio Reactivity Apply To" to ElementValue.Text("Effect Strength")), 0.75f
        )
        assertEquals(1f, effect.colorMultiplier, 0.0001f)
        assertEquals(0.75f, effect.effectStrengthMultiplier, 0.0001f)
    }

    @Test fun audioReactiveColorScalesRgbAndPreservesAlpha() {
        assertEquals(0x80402010.toInt(), audioReactivityColor(0x80FF8040.toInt(), 0.25f))
        assertEquals(0x80FF8040.toInt(), audioReactivityColor(0x80FF8040.toInt(), 1f))
    }

    @Test fun barSpectrumProcessorAppliesThresholdOffsetAndAmplitudeModes() {
        val normal = processBarSpectrum(floatArrayOf(0.25f, 0.5f, 1f))
        assertEquals(0.2f, normal[0], 0.0001f)
        assertEquals(1f / 3f, normal[1], 0.0001f)
        assertEquals(0.5f, normal[2], 0.0001f)

        val gated = processBarSpectrum(
            floatArrayOf(0.25f, 0.5f, 1f), threshold = 0.3f, offset = 0.1f
        )
        assertEquals(0.1f, gated[0], 0.0001f)
        assertEquals(1f / 3f + 0.1f, gated[1], 0.0001f)
        val power = processBarSpectrum(floatArrayOf(0.5f), amplitudeMode = "Power")
        assertEquals(0.2f, power[0], 0.0001f)

        val rms = processBarSpectrum(floatArrayOf(0.25f, 0.5f, 1f), amplitudeMode = "RMS")
        assertEquals(0.398f, rms[1], 0.002f)
        val peak = processBarSpectrum(floatArrayOf(0.25f, 0.5f, 1f), amplitudeMode = "Peak")
        assertEquals(0.5f, peak[1], 0.0001f)
    }

    @Test fun barSpectrumProcessorMapsFrequencyBinsAndHandlesInvalidSamples() {
        val shifted = processBarSpectrum(floatArrayOf(0.25f, 0.5f, 1f), bandOffset = 1)
        assertEquals(1f / 3f, shifted[0], 0.0001f)
        assertEquals(0.5f, shifted[1], 0.0001f)
        assertEquals(0.2f, shifted[2], 0.0001f)
        val safe = processBarSpectrum(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(0f, safe[0], 0.0001f)
        assertEquals(0f, safe[1], 0.0001f)
    }

}
