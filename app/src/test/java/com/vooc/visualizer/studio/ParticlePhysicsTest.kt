package com.vooc.visualizer.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class ParticlePhysicsTest {
    @Test fun particleReflectsBackIntoSceneAfterRightEdgeImpact() {
        val result = reflectParticleAxis(110f, 20f, 100f, 0.5f)
        assertEquals(90f, result.position, 0.0001f)
        assertEquals(-10f, result.velocity, 0.0001f)
    }

    @Test fun particleReflectsBackIntoSceneAfterLeftEdgeImpact() {
        val result = reflectParticleAxis(-10f, -20f, 100f, 0.5f)
        assertEquals(10f, result.position, 0.0001f)
        assertEquals(10f, result.velocity, 0.0001f)
    }

    @Test fun multipleImpactsFoldPositionAndApplyBounceDamping() {
        val result = reflectParticleAxis(250f, 8f, 100f, 0.5f)
        assertEquals(50f, result.position, 0.0001f)
        assertEquals(2f, result.velocity, 0.0001f)
    }

    @Test fun particlesInsideBoundsAreUnchanged() {
        val result = reflectParticleAxis(45f, -7f, 100f, 0.25f)
        assertEquals(45f, result.position, 0.0001f)
        assertEquals(-7f, result.velocity, 0.0001f)
    }
}
