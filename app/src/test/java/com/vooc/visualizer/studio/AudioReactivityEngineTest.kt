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
}
