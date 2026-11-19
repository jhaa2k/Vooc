package com.vooc.visualizer.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasureWhatEngineTest {
    @Test
    fun exposesEveryRequestedMeasureMode() {
        assertEquals(22, MeasureWhatEngine.options.size)
        assertTrue(MeasureWhatEngine.options.containsAll(listOf(
            "Nothing", "Beat", "Total Time", "Total Time Backward", "TotalTimeWhen Playing",
            "Total TimeAndBeat", "TrackPosition", "TrackPosition Backward", "TrackPosition Smooth",
            "BeatRandom Shake", "BeatCamShake RotMore", "BeatCamShakeRotLess", "BeatCamShake PosOnly",
            "BeatZoom Shake", "Constant Shake RotMore", "Constant Shake RotLess", "Constant Shake Smooth",
            "Beat Trigger Anim", "Beat Trigger Reverse", "Beat PingPong", "HighFreq React", "BassPulse Smooth"
        )))
    }

    @Test
    fun nothingModeDoesNotModifyLayerTransform() {
        assertEquals(MeasureMotion(), MeasureWhatEngine.evaluate(
            "Nothing", 2f, 2f, true, 1f, 1f, 1f, 120f, 7L
        ))
    }

    @Test
    fun beatAndFrequencyModesReactToTheirInputSignals() {
        val beat = MeasureWhatEngine.evaluate("Beat", 0f, 0f, true, 1f, 0f, 0f, 120f, 1L)
        val bass = MeasureWhatEngine.evaluate("BassPulse Smooth", 0f, 0f, true, 0f, 1f, 0f, 120f, 1L)
        val treble = MeasureWhatEngine.evaluate("HighFreq React", 0f, 0f, true, 0f, 0f, 1f, 120f, 1L)
        assertTrue(beat.scale > 1f)
        assertTrue(bass.scale > 1f)
        assertTrue(treble.scale > 1f)
    }

    @Test
    fun everyModeReturnsFiniteSafeTransforms() {
        MeasureWhatEngine.options.forEach { mode ->
            val result = MeasureWhatEngine.evaluate(mode, 10f, 3f, true, 0.8f, 0.6f, 0.7f, 128f, 99L)
            assertTrue("$mode offsetX", result.offsetX.isFinite())
            assertTrue("$mode offsetY", result.offsetY.isFinite())
            assertTrue("$mode scale", result.scale.isFinite() && result.scale > 0f)
            assertTrue("$mode rotation", result.rotation.isFinite())
        }
    }
}
