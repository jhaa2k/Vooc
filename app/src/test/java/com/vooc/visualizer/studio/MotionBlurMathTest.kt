package com.vooc.visualizer.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionBlurMathTest {
    @Test fun shutterAndVelocityScaleControlBlurLength() {
        assertEquals(24f, resolveMotionBlurLength(24f, 180f, 1f, 1f), 0.0001f)
        assertEquals(12f, resolveMotionBlurLength(24f, 90f, 1f, 1f), 0.0001f)
        assertEquals(48f, resolveMotionBlurLength(24f, 180f, 2f, 1f), 0.0001f)
    }

    @Test fun sampleJitterRemainsBoundedAndZeroJitterIsStable() {
        assertEquals(0.5f, resolveMotionBlurSampleProgress(4, 8, 0f), 0.0001f)
        val jittered = resolveMotionBlurSampleProgress(4, 8, 1f)
        assertEquals(true, jittered in 0f..1f)
        assertEquals(0f, resolveMotionBlurSampleProgress(-5, 8, Float.NaN), 0.0001f)
    }

    @Test fun exposureScalesAlphaWithoutProducingInvalidValues() {
        assertEquals(0.5f, resolveMotionBlurExposureAlpha(0.5f, 0.5f), 0.0001f)
        assertEquals(1f, resolveMotionBlurExposureAlpha(0.8f, 1f), 0.0001f)
        assertEquals(0f, resolveMotionBlurExposureAlpha(Float.NaN, 0.5f), 0.0001f)
    }
}
