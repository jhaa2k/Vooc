package com.vooc.visualizer.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class ParticlePhysicsTest {
    @Test fun particleTrailGeometryScalesVelocityByConfiguredLength() {
        val result = resolveParticleTrailVector(120f, -40f, 25f)
        assertEquals(3f, result.x, 0.0001f)
        assertEquals(-1f, result.y, 0.0001f)
    }

    @Test fun particleTrailGeometryReturnsZeroForInvalidInputs() {
        val result = resolveParticleTrailVector(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN)
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(0f, result.y, 0.0001f)
    }

    @Test fun particleTrailGeometryPreventsOverflowFromExtremeVelocity() {
        val result = resolveParticleTrailVector(Float.MAX_VALUE, -Float.MAX_VALUE, 256f)
        assertEquals(true, result.x.isFinite() && result.y.isFinite())
        assertEquals(true, result.x > 0f && result.y < 0f)
        assertEquals(true, result.x < Float.MAX_VALUE && result.y > -Float.MAX_VALUE)
    }
    @Test fun particleTrailVectorReflectsAcrossEachMirrorAxis() {
        val horizontal = resolveParticleMirrorVector(12f, -7f, true, false)
        assertEquals(-12f, horizontal.x, 0.0001f)
        assertEquals(-7f, horizontal.y, 0.0001f)

        val vertical = resolveParticleMirrorVector(12f, -7f, false, true)
        assertEquals(12f, vertical.x, 0.0001f)
        assertEquals(7f, vertical.y, 0.0001f)

        val both = resolveParticleMirrorVector(12f, -7f, true, true)
        assertEquals(-12f, both.x, 0.0001f)
        assertEquals(7f, both.y, 0.0001f)
    }

    @Test fun particleTrailMirrorVectorSanitizesNonFiniteComponents() {
        val result = resolveParticleMirrorVector(Float.NaN, Float.POSITIVE_INFINITY, true, true)
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(0f, result.y, 0.0001f)
    }

    @Test fun particleAnchorAndAlignmentResolveNormalizedOffsets() {
        val result = resolveParticleAnchorOffset("start", "end", 0.25f, 0.75f)
        assertEquals(-0.25f, result.x, 0.0001f)
        assertEquals(0.25f, result.y, 0.0001f)
    }

    @Test fun unsetParticleAnchorsKeepDefaultEmitterCenter() {
        val result = resolveParticleAnchorOffset("unset", "unset", 0.2f, 0.8f)
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(0f, result.y, 0.0001f)
    }

    @Test fun particleAnchorAlignmentSanitizesInvalidValues() {
        val result = resolveParticleAnchorOffset("end", "start", Float.NaN, Float.POSITIVE_INFINITY)
        assertEquals(0.5f, result.x, 0.0001f)
        assertEquals(-0.5f, result.y, 0.0001f)
    }

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

    @Test fun exactBoundaryImpactsReverseOnlyOutwardVelocity() {
        assertEquals(-5f, reflectParticleAxis(100f, 10f, 100f, 0.5f).velocity, 0.0001f)
        assertEquals(-10f, reflectParticleAxis(100f, -10f, 100f, 0.5f).velocity, 0.0001f)
        assertEquals(5f, reflectParticleAxis(0f, -10f, 100f, 0.5f).velocity, 0.0001f)
        assertEquals(10f, reflectParticleAxis(0f, 10f, 100f, 0.5f).velocity, 0.0001f)
        assertEquals(2.5f, reflectParticleAxis(200f, 10f, 100f, 0.5f).velocity, 0.0001f)
        assertEquals(2.5f, reflectParticleAxis(-100f, 10f, 100f, 0.5f).velocity, 0.0001f)
        assertEquals(-1.25f, reflectParticleAxis(-200f, 10f, 100f, 0.5f).velocity, 0.0001f)
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

    @Test fun boundaryCollisionHandlesExtremeFiniteCoordinatesWithoutIntegerOverflow() {
        val positive = reflectParticleAxis(Float.MAX_VALUE, 12f, 100f, 0.5f)
        val negative = reflectParticleAxis(-Float.MAX_VALUE, -12f, 100f, 0.5f)
        assertEquals(true, positive.position.isFinite() && positive.position in 0f..100f)
        assertEquals(true, negative.position.isFinite() && negative.position in 0f..100f)
        assertEquals(true, positive.velocity.isFinite())
        assertEquals(true, negative.velocity.isFinite())
        assertEquals(0f, positive.velocity, 0.0001f)
        assertEquals(0f, negative.velocity, 0.0001f)
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

    @Test fun directionalForceUsesConfiguredDirectionAndAge() {
        val result = particleForceDelta("Directional", 20f, -10f, 3f, 2f, 0.6f, -0.8f)
        assertEquals(3.6f, result.x, 0.0001f)
        assertEquals(-4.8f, result.y, 0.0001f)
    }

    @Test fun zeroDistanceRadialForceDoesNotCreateAnUndefinedDirection() {
        val result = particleForceDelta("Attract", 0f, 0f, 10f, 5f, 1f, 0f)
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(0f, result.y, 0.0001f)
    }

    @Test fun unknownForceFieldModeDoesNotAlterParticleVelocity() {
        val result = particleForceDelta("Unknown", 10f, 5f, 4f, 3f, 1f, 0f)
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(0f, result.y, 0.0001f)
    }

    @Test fun emitterRadiiClampInnerRadiusToOuterRadius() {
        val result = resolveParticleEmitterRadii(80f, 120f)
        assertEquals(80f, result.outer, 0.0001f)
        assertEquals(80f, result.inner, 0.0001f)
    }

    @Test fun emitterRadiiSanitizeNegativeAndNonFiniteValues() {
        val negative = resolveParticleEmitterRadii(-10f, -5f)
        assertEquals(0f, negative.outer, 0.0001f)
        assertEquals(0f, negative.inner, 0.0001f)
        val invalid = resolveParticleEmitterRadii(Float.NaN, Float.POSITIVE_INFINITY)
        assertEquals(100f, invalid.outer, 0.0001f)
        // Invalid inner radius falls back to zero, then remains within the outer radius.
        assertEquals(0f, invalid.inner, 0.0001f)
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

    @Test fun ringEmitterUsesUniformAreaSamplingBetweenInnerAndOuterRadius() {
        val middle = particleSpawnOffset("Ring", 1f, 0.5f, 100f, 80f, 60f, 20f, 0f, (Math.PI / 2.0).toFloat())
        val inner = particleSpawnOffset("Ring", 1f, 0f, 100f, 80f, 60f, 20f, 0f, (Math.PI / 2.0).toFloat())
        val outer = particleSpawnOffset("Ring", 1f, 1f, 100f, 80f, 60f, 20f, 0f, (Math.PI / 2.0).toFloat())
        assertEquals(0f, middle.x, 0.0001f)
        assertEquals(kotlin.math.sqrt(2000f), middle.y, 0.0001f)
        assertEquals(20f, inner.y, 0.0001f)
        assertEquals(60f, outer.y, 0.0001f)
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


    @Test fun emitterArcStartAndPositiveSweepPlaceParticlesOnConfiguredArc() {
        val result = particleSpawnOffset(
            "Circle", 0.5f, 1f, 100f, 80f, 60f, 0f,
            (Math.PI / 2.0).toFloat(), (Math.PI / 2.0).toFloat()
        )
        assertEquals(-42.426407f, result.x, 0.0001f)
        assertEquals(42.426407f, result.y, 0.0001f)
    }

    @Test fun emitterNegativeSweepRunsClockwiseFromArcStart() {
        val result = particleSpawnOffset(
            "Circle", 0.5f, 1f, 100f, 80f, 60f, 0f,
            0f, (-Math.PI / 2.0).toFloat()
        )
        assertEquals(42.426407f, result.x, 0.0001f)
        assertEquals(-42.426407f, result.y, 0.0001f)
    }

    @Test fun zeroSweepKeepsAllParticlesAtArcStart() {
        val result = particleSpawnOffset("Ring", 0.9f, 1f, 100f, 80f, 60f, 20f, 0f, 0f)
        assertEquals(60f, result.x, 0.0001f)
        assertEquals(0f, result.y, 0.0001f)
    }

    @Test fun emitterArcSweepIsBoundedToOneFullRevolution() {
        val full = particleSpawnOffset("Circle", 0.25f, 1f, 100f, 80f, 60f, 0f, 0f, (Math.PI * 4.0).toFloat())
        val oneTurn = particleSpawnOffset("Circle", 0.25f, 1f, 100f, 80f, 60f, 0f, 0f, (Math.PI * 2.0).toFloat())
        assertEquals(oneTurn.x, full.x, 0.0001f)
        assertEquals(oneTurn.y, full.y, 0.0001f)
    }


    @Test fun emitterRotationKeepsOffsetAtZeroDegrees() {
        val result = rotateParticleEmitterOffset(12f, -8f, 0f)
        assertEquals(12f, result.x, 0.0001f)
        assertEquals(-8f, result.y, 0.0001f)
    }

    @Test fun emitterRotationTurnsOffsetNinetyDegrees() {
        val result = rotateParticleEmitterOffset(12f, 0f, (Math.PI / 2.0).toFloat())
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(12f, result.y, 0.0001f)
    }

    @Test fun emitterRotationSanitizesNonFiniteInputs() {
        val result = rotateParticleEmitterOffset(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN)
        assertEquals(0f, result.x, 0.0001f)
        assertEquals(0f, result.y, 0.0001f)
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

    @Test fun particleOpacityCombinesLifeAndColorFactors() {
        val result = resolveParticleOpacity(
            0.8f, 0.5f, 0.6f, 0.9f, 0.75f, 0.5f, 0.8f, 0.5f
        )
        assertEquals(0.0324f, result, 0.0001f)
    }

    @Test fun particleOpacityClampsOutOfRangeFactors() {
        val result = resolveParticleOpacity(
            2f, 1.5f, -1f, 1f, 1f, 1f, 1f, 1f
        )
        assertEquals(0f, result, 0.0001f)
    }

    @Test fun particleOpacitySanitizesNonFiniteFactors() {
        val result = resolveParticleOpacity(
            Float.NaN, 1f, 1f, 1f, 1f, 1f, 1f, 1f
        )
        assertEquals(0f, result, 0.0001f)
        val valid = resolveParticleOpacity(
            1f, 1f, 1f, 1f, Float.POSITIVE_INFINITY, 1f, 1f, 1f
        )
        assertEquals(0f, valid, 0.0001f)
    }
}


class ParticleEmissionTimingTest {
    @org.junit.Test fun continuousEmissionUsesDeterministicStaggeredAges() {
        val first = resolveParticleEmissionTiming(1f, 0, 0.2f, 3f, 0, 1f, true)
        val next = resolveParticleEmissionTiming(1f, 1, 0.2f, 3f, 0, 1f, true)
        org.junit.Assert.assertEquals(1f, first.age, 0.0001f)
        org.junit.Assert.assertEquals(1.2f, next.age, 0.0001f)
        org.junit.Assert.assertTrue(first.isAlive && next.isAlive)
    }

    @org.junit.Test fun burstWaitsForItsScheduledSpawnTime() {
        val timing = resolveParticleEmissionTiming(0.1f, 2, 0.2f, 3f, 5, 1f, false)
        org.junit.Assert.assertFalse(timing.isAlive)
    }

    @org.junit.Test fun loopingBurstWrapsAtConfiguredInterval() {
        val timing = resolveParticleEmissionTiming(2.5f, 0, 0.1f, 3f, 4, 1f, true)
        org.junit.Assert.assertEquals(0.5f, timing.age, 0.0001f)
        org.junit.Assert.assertTrue(timing.isAlive)
    }

    @org.junit.Test fun nonLoopingBurstExpiresAfterLifetime() {
        val timing = resolveParticleEmissionTiming(4f, 0, 0f, 2f, 4, 1f, false)
        org.junit.Assert.assertFalse(timing.isAlive)
    }

    @org.junit.Test fun burstCountAndInvalidInputsAreBounded() {
        val missing = resolveParticleEmissionTiming(1f, 4, 0f, 2f, 4, 1f, true)
        val invalid = resolveParticleEmissionTiming(Float.NaN, 0, Float.NaN, Float.NaN, 0, Float.NaN, false)
        org.junit.Assert.assertFalse(missing.isAlive)
        org.junit.Assert.assertEquals(0f, invalid.age, 0f)
        org.junit.Assert.assertTrue(invalid.isAlive)
    }
}
