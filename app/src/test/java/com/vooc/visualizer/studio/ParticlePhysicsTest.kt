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

    @Test fun boundaryCollisionSanitizesNonFiniteInputs() {
        val invalidPosition = reflectParticleAxis(Float.NaN, 12f, 100f, 0.5f)
        assertEquals(0f, invalidPosition.position, 0.0001f)
        assertEquals(12f, invalidPosition.velocity, 0.0001f)

        val invalidVelocity = reflectParticleAxis(25f, Float.POSITIVE_INFINITY, 100f, 0.5f)
        assertEquals(25f, invalidVelocity.position, 0.0001f)
        assertEquals(0f, invalidVelocity.velocity, 0.0001f)

        val invalidExtent = reflectParticleAxis(75f, -8f, Float.NaN, 0.5f)
        assertEquals(0f, invalidExtent.position, 0.0001f)
        assertEquals(-8f, invalidExtent.velocity, 0.0001f)
    }

    @Test fun boundaryCollisionUsesSafeDefaultForNonFiniteBounce() {
        val result = reflectParticleAxis(110f, 20f, 100f, Float.NaN)
        assertEquals(90f, result.position, 0.0001f)
        assertEquals(-10f, result.velocity, 0.0001f)
    }

    @Test fun particlesInsideBoundsAreUnchanged() {
        val result = reflectParticleAxis(45f, -7f, 100f, 0.25f)
        assertEquals(45f, result.position, 0.0001f)
        assertEquals(-7f, result.velocity, 0.0001f)
    }

    @Test fun normalizedParticlePositionKeepsDefaultAtCanvasCenter() {
        assertEquals(0f, particlePositionOffset(0.5f), 0.0001f)
    }

    @Test fun normalizedParticlePositionMovesAcrossFullCanvasRange() {
        assertEquals(-0.5f, particlePositionOffset(0f), 0.0001f)
        assertEquals(0.5f, particlePositionOffset(1f), 0.0001f)
    }

    @Test fun normalizedParticlePositionSanitizesInvalidValuesToCenter() {
        assertEquals(0f, particlePositionOffset(Float.NaN), 0.0001f)
        assertEquals(0f, particlePositionOffset(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test fun particleLayerScalePreservesIndependentAxesWhenNonUniform() {
        val result = resolveParticleLayerScale(2f, 0.5f, false, 1.5f)
        assertEquals(3f, result.x, 0.0001f)
        assertEquals(0.75f, result.y, 0.0001f)
    }

    @Test fun particleLayerScaleUsesXForBothAxesWhenUniform() {
        val result = resolveParticleLayerScale(2f, 0.5f, true, 1.5f)
        assertEquals(3f, result.x, 0.0001f)
        assertEquals(3f, result.y, 0.0001f)
    }

    @Test fun particleLayerScaleClampsLargeValues() {
        val result = resolveParticleLayerScale(20f, 30f, false, 2f)
        assertEquals(10f, result.x, 0.0001f)
        assertEquals(10f, result.y, 0.0001f)
    }

    @Test fun forceFieldPositionUsesNormalizedFullCanvasCoordinates() {
        val result = particleForcePosition(0.25f, 0.75f, 1920f, 1080f)
        assertEquals(480f, result.x, 0.0001f)
        assertEquals(810f, result.y, 0.0001f)
    }

    @Test fun forceFieldPositionClampsOutOfRangeNormalizedValues() {
        val result = particleForcePosition(1.5f, -0.5f, 1920f, 1080f)
        assertEquals(1920f, result.x, 0.0001f)
        assertEquals(0f, result.y, 0.0001f)
    }

    @Test fun forceFieldPositionSanitizesNonFiniteCoordinatesAndCanvasSize() {
        val result = particleForcePosition(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, 1080f)
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(540f, result.y, 0.0001f)
    }

    @Test fun forceFieldDeltaSanitizesNonFiniteInputs() {
        val result = particleForceDelta(
            "Directional", Float.NaN, Float.POSITIVE_INFINITY,
            Float.NaN, 2f, Float.POSITIVE_INFINITY, -3f
        )
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(0f, result.y, 0.0001f)
        assertEquals(true, result.x.isFinite() && result.y.isFinite())
    }

    @Test fun radialForceFieldHandlesVeryLargeFiniteCoordinates() {
        val result = particleForceDelta("Attract", Float.MAX_VALUE, Float.MAX_VALUE, 2f, 1f, 0f, 0f)
        val expected = (2.0 / kotlin.math.sqrt(2.0)).toFloat()
        assertEquals(expected, result.x, 0.0001f)
        assertEquals(expected, result.y, 0.0001f)
        assertEquals(true, result.x.isFinite() && result.y.isFinite())
    }

    @Test fun vortexForceFieldAppliesTangentialAcceleration() {
        val result = particleForceDelta("Vortex", 10f, 0f, 4f, 2f, 1f, 0f)
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(8f, result.y, 0.0001f)
    }

    @Test fun attractAndRepelForceFieldsPointInOppositeDirections() {
        val attract = particleForceDelta("Attract", 3f, 4f, 5f, 2f, 0f, 0f)
        val repel = particleForceDelta("Repel", 3f, 4f, 5f, 2f, 0f, 0f)
        assertEquals(6f, attract.x, 0.0001f)
        assertEquals(8f, attract.y, 0.0001f)
        assertEquals(-6f, repel.x, 0.0001f)
        assertEquals(-8f, repel.y, 0.0001f)
    }

    @Test fun horizontalLineEmitterKeepsParticlesOnHorizontalAxis() {
        val result = particleSpawnOffset("HorizontalLine", 0.75f, 0.4f, 100f, 50f, 40f, 0f, 0f, 1f)
        assertEquals(50f, result.x, 0.0001f)
        assertEquals(0f, result.y, 0.0001f)
    }

    @Test fun rectangleEmitterSamplesBothAxes() {
        val result = particleSpawnOffset("Rectangle", 0.75f, 0.25f, 100f, 80f, 40f, 0f, 0f, 1f)
        assertEquals(50f, result.x, 0.0001f)
        assertEquals(-40f, result.y, 0.0001f)
    }

    @Test fun ellipseEmitterUsesConfiguredWidthAndHeightWithUniformAreaRadius() {
        val result = particleSpawnOffset("Ellipse", 1f, 0.25f, 100f, 40f, 60f, 0f, 0f, (Math.PI / 2.0).toFloat())
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(20f, result.y, 0.0001f)
    }

    @Test fun circleEmitterUsesConfiguredRadiusWithUniformAreaRadius() {
        val result = particleSpawnOffset("Circle", 1f, 0.25f, 100f, 40f, 80f, 0f, 0f, (Math.PI / 2.0).toFloat())
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(40f, result.y, 0.0001f)
    }

    @Test fun ringEmitterUsesConfiguredInnerAndOuterRadius() {
        val result = particleSpawnOffset("Ring", 1f, 0.5f, 100f, 80f, 60f, 20f, 0f, (Math.PI / 2.0).toFloat())
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(40f, result.y, 0.0001f)
    }

    @Test fun edgeEmitterSamplesLeftAndRightSides() {
        val left = particleSpawnOffset("Edge", 0.1f, 0.75f, 100f, 80f, 60f, 0f, 0f, 1f)
        val right = particleSpawnOffset("Edge", 0.4f, 0.75f, 100f, 80f, 60f, 0f, 0f, 1f)
        assertEquals(-100f, left.x, 0.0001f)
        assertEquals(40f, left.y, 0.0001f)
        assertEquals(100f, right.x, 0.0001f)
        assertEquals(40f, right.y, 0.0001f)
    }

    @Test fun edgeEmitterSamplesTopAndBottomSides() {
        val top = particleSpawnOffset("Edge", 0.6f, 0.25f, 100f, 80f, 60f, 0f, 0f, 1f)
        val bottom = particleSpawnOffset("Edge", 0.9f, 0.25f, 100f, 80f, 60f, 0f, 0f, 1f)
        assertEquals(-50f, top.x, 0.0001f)
        assertEquals(-80f, top.y, 0.0001f)
        assertEquals(-50f, bottom.x, 0.0001f)
        assertEquals(80f, bottom.y, 0.0001f)
    }

    @Test fun particleLayerScaleFallsBackForNonFiniteValues() {
        val result = resolveParticleLayerScale(Float.NaN, Float.POSITIVE_INFINITY, false, Float.NaN)
        assertEquals(1f, result.x, 0.0001f)
        assertEquals(1f, result.y, 0.0001f)
    }

    @Test fun emitterSamplingSanitizesNonFiniteAndNegativeInputs() {
        val result = particleSpawnOffset(
            "Rectangle", Float.NaN, Float.POSITIVE_INFINITY,
            -100f, Float.NaN, Float.NaN, Float.NEGATIVE_INFINITY,
            Float.NaN, Float.POSITIVE_INFINITY
        )
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(0f, result.y, 0.0001f)
        assertEquals(true, result.x.isFinite() && result.y.isFinite())
    }

    @Test fun edgeEmitterWeightsSidesByTheirPerimeterLength() {
        val tallSide = particleSpawnOffset("Edge", 0.20f, 0.5f, 100f, 80f, 60f, 0f, 0f, 1f)
        val wideSide = particleSpawnOffset("Edge", 0.60f, 0.5f, 100f, 80f, 60f, 0f, 0f, 1f)
        assertEquals(-100f, tallSide.x, 0.0001f)
        assertEquals(0f, tallSide.y, 0.0001f)
        assertEquals(0f, wideSide.x, 0.0001f)
        assertEquals(-80f, wideSide.y, 0.0001f)
    }

    @Test fun edgeEmitterWithZeroAreaFallsBackToCenter() {
        val result = particleSpawnOffset("Edge", 0.7f, 0.3f, 0f, 0f, 60f, 0f, 0f, 1f)
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(0f, result.y, 0.0001f)
    }

    @Test fun unknownEmitterFallsBackToPoint() {
        val result = particleSpawnOffset("Custom", 0.9f, 0.8f, 100f, 80f, 60f, 20f, 0f, 1f)
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(0f, result.y, 0.0001f)
    }
}
