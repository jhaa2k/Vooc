package com.vooc.visualizer.studio

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow


internal data class ParticleEmissionTiming(val age: Float, val isAlive: Boolean)

/** Resolves deterministic particle age for continuous emission and timed burst emitters. */
internal fun resolveParticleEmissionTiming(
    playbackSeconds: Float,
    particleIndex: Int,
    spawnInterval: Float,
    particleLifetime: Float,
    burstCount: Int,
    burstInterval: Float,
    loopBurst: Boolean
): ParticleEmissionTiming {
    val time = playbackSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val interval = spawnInterval.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val lifetime = particleLifetime.takeIf { it.isFinite() }?.coerceAtLeast(0.01f) ?: 0.01f
    val index = particleIndex.coerceAtLeast(0)
    if (burstCount > 0) {
        if (index >= burstCount) return ParticleEmissionTiming(0f, false)
        val elapsed = time - index * interval
        if (elapsed < 0f) return ParticleEmissionTiming(0f, false)
        val age = if (loopBurst) {
            val cycle = burstInterval.takeIf { it.isFinite() }?.coerceAtLeast(0.001f) ?: 0.001f
            elapsed % cycle
        } else elapsed
        return ParticleEmissionTiming(age.coerceAtLeast(0f), age <= lifetime)
    }
    val age = (time + index * interval) % lifetime
    return ParticleEmissionTiming(age.coerceIn(0f, lifetime), true)
}

internal data class ParticleAxisState(val position: Float, val velocity: Float)

/** Folds an unconstrained particle coordinate back into the scene after boundary impacts. */
internal fun reflectParticleAxis(position: Float, velocity: Float, extent: Float, bounce: Float): ParticleAxisState {
    val safePosition = position.takeIf { it.isFinite() } ?: 0f
    val safeVelocity = velocity.takeIf { it.isFinite() } ?: 0f
    val safeExtent = extent.takeIf { it.isFinite() && it > 0f } ?: 0f
    val safeBounce = bounce.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
    if (safeExtent <= 0f) return ParticleAxisState(0f, safeVelocity)
    if (safePosition in 0f..safeExtent) {
        // An exact edge contact only collides when velocity points out of the scene.
        val movingOutAtLeft = safePosition == 0f && safeVelocity < 0f
        val movingOutAtRight = safePosition == safeExtent && safeVelocity > 0f
        if (movingOutAtLeft || movingOutAtRight) {
            return ParticleAxisState(safePosition, -safeVelocity * safeBounce)
        }
        return ParticleAxisState(safePosition, safeVelocity)
    }
    // Use Double for the fold arithmetic: converting an enormous collision count
    // directly to Int can overflow and reverse the particle in the wrong direction.
    val extent = safeExtent.toDouble()
    val coordinate = safePosition.toDouble()
    val period = extent * 2.0
    val wrapped = ((coordinate % period) + period) % period
    val folded = if (wrapped > extent) period - wrapped else wrapped
    val hitCount = if (coordinate < 0.0) kotlin.math.floor(-coordinate / extent) + 1.0
        else kotlin.math.floor((coordinate - extent) / extent) + 1.0
    val safeHitCount = hitCount.coerceAtLeast(1.0)
    // More than 64 impacts already makes practical bounce energy negligible;
    // cap damping while preserving the full impact-count parity.
    val direction = if (safeHitCount % 2.0 == 1.0) -1f else 1f
    val damping = safeBounce.toDouble().pow(safeHitCount.coerceAtMost(64.0)).toFloat()
    return ParticleAxisState(folded.toFloat().coerceIn(0f, safeExtent), safeVelocity * direction * damping)
}

/** Converts normalized particle layer position (0..1) into a center-relative canvas offset. */
internal fun particlePositionOffset(normalizedPosition: Float): Float {
    val safePosition = normalizedPosition.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
    return safePosition - 0.5f
}

internal data class ParticleVector(val x: Float, val y: Float)

/** Resolves a finite trail displacement from particle velocity and the configured trail length. */
internal fun resolveParticleTrailVector(velocityX: Float, velocityY: Float, trailLength: Float): ParticleVector {
    val safeVelocityX = velocityX.takeIf { it.isFinite() } ?: 0f
    val safeVelocityY = velocityY.takeIf { it.isFinite() } ?: 0f
    val safeLength = trailLength.takeIf { it.isFinite() }?.coerceIn(0f, 100000f) ?: 0f
    val scale = safeLength * 0.001f
    val x = safeVelocityX * scale
    val y = safeVelocityY * scale
    return ParticleVector(x.takeIf { it.isFinite() } ?: 0f, y.takeIf { it.isFinite() } ?: 0f)
}

/** Reflects a particle's directional vector across the requested canvas axes. */
internal fun resolveParticleMirrorVector(x: Float, y: Float, mirrorX: Boolean, mirrorY: Boolean): ParticleVector {
    val safeX = x.takeIf { it.isFinite() } ?: 0f
    val safeY = y.takeIf { it.isFinite() } ?: 0f
    return ParticleVector(if (mirrorX) -safeX else safeX, if (mirrorY) -safeY else safeY)
}

internal data class ParticleAnchorOffset(val x: Float, val y: Float)

/** Resolves particle anchor controls as a normalized offset from the selected alignment point. */
internal fun resolveParticleAnchorOffset(
    anchorX: String,
    anchorY: String,
    alignmentX: Float,
    alignmentY: Float
): ParticleAnchorOffset {
    fun axis(anchor: String, alignment: Float): Float {
        val anchorPoint = when (anchor.lowercase()) {
            "start" -> 0f
            "center" -> 0.5f
            "end" -> 1f
            else -> return 0f
        }
        val safeAlignment = alignment.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
        return anchorPoint - safeAlignment
    }
    return ParticleAnchorOffset(axis(anchorX, alignmentX), axis(anchorY, alignmentY))
}

internal data class ParticleLayerScale(val x: Float, val y: Float)

/** Applies uniform/non-uniform layer scale consistently to preview and export particle sizes. */
internal fun resolveParticleLayerScale(x: Float, y: Float, uniform: Boolean, particleScale: Float): ParticleLayerScale {
    val safeX = x.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 1f
    val safeY = y.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 1f
    val safeScale = particleScale.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 1f
    return ParticleLayerScale(
        (safeX * safeScale).coerceIn(0f, 10f),
        ((if (uniform) safeX else safeY) * safeScale).coerceIn(0f, 10f)
    )
}

internal data class ParticleForcePosition(val x: Float, val y: Float)

/** Resolves normalized force-field coordinates in full-canvas pixel space. */
internal fun particleForcePosition(x: Float, y: Float, canvasWidth: Float, canvasHeight: Float): ParticleForcePosition {
    val safeX = x.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
    val safeY = y.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
    val safeWidth = canvasWidth.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeHeight = canvasHeight.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    return ParticleForcePosition(safeX * safeWidth, safeY * safeHeight)
}

internal data class ParticleForceDelta(val x: Float, val y: Float)

/** Returns the velocity delta applied by a configured particle force field. */
internal fun particleForceDelta(
    mode: String,
    dx: Float,
    dy: Float,
    strength: Float,
    age: Float,
    directionX: Float,
    directionY: Float
): ParticleForceDelta {
    val safeDx = dx.takeIf { it.isFinite() } ?: 0f
    val safeDy = dy.takeIf { it.isFinite() } ?: 0f
    val safeStrength = strength.takeIf { it.isFinite() } ?: 0f
    val safeAge = age.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeDirectionX = directionX.takeIf { it.isFinite() } ?: 0f
    val safeDirectionY = directionY.takeIf { it.isFinite() } ?: 0f
    val scale = maxOf(kotlin.math.abs(safeDx), kotlin.math.abs(safeDy))
    val scaledX = if (scale > 0f) safeDx / scale else 0f
    val scaledY = if (scale > 0f) safeDy / scale else 0f
    val scaledLength = kotlin.math.sqrt(scaledX.pow(2) + scaledY.pow(2))
    val distanceIsLarge = scale > 0f && scale >= 1f / scaledLength
    val radialX = if (distanceIsLarge) scaledX / scaledLength else safeDx
    val radialY = if (distanceIsLarge) scaledY / scaledLength else safeDy
    val amount = (safeStrength * safeAge).takeIf { it.isFinite() } ?: 0f
    return when (mode) {
        "Attract", "Radial" -> ParticleForceDelta(radialX * amount, radialY * amount)
        "Repel" -> ParticleForceDelta(-radialX * amount, -radialY * amount)
        "Directional" -> ParticleForceDelta(safeDirectionX * amount, safeDirectionY * amount)
        "Vortex" -> ParticleForceDelta(-radialY * amount, radialX * amount)
        else -> ParticleForceDelta(0f, 0f)
    }
}

internal data class ParticleEmitterRadii(val outer: Float, val inner: Float)

/** Sanitizes serialized emitter radii and guarantees inner <= outer. */
internal fun resolveParticleEmitterRadii(outer: Float, inner: Float): ParticleEmitterRadii {
    val safeOuter = outer.takeIf { it.isFinite() }?.coerceIn(0f, 10000f) ?: 100f
    val safeInner = inner.takeIf { it.isFinite() }?.coerceIn(0f, 10000f) ?: 0f
    return ParticleEmitterRadii(safeOuter, safeInner.coerceAtMost(safeOuter))
}

internal data class ParticleSpawnOffset(val x: Float, val y: Float)

/** Deterministic emitter sampling shared by preview and export. randomA/randomB are in [0, 1]. */
internal fun particleSpawnOffset(
    shape: String,
    randomA: Float,
    randomB: Float,
    width: Float,
    height: Float,
    radius: Float,
    innerRadius: Float,
    arcStart: Float,
    arcSweep: Float
): ParticleSpawnOffset {
    // Invalid serialized values must never inject NaN/Infinity into the render loop.
    val a = randomA.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val b = randomB.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val safeWidth = width.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeHeight = height.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeInnerRadius = innerRadius.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val safeRadius = (radius.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f)
        .coerceAtLeast(safeInnerRadius)
    val safeArcStart = arcStart.takeIf { it.isFinite() } ?: 0f
    val safeArcSweep = (arcSweep.takeIf { it.isFinite() } ?: 0f).coerceIn(-2f * Math.PI.toFloat(), 2f * Math.PI.toFloat())
    val angle = safeArcStart + a * safeArcSweep
    val radialDistance = safeInnerRadius + (safeRadius - safeInnerRadius) * b
    return when (shape) {
        "Rectangle" -> ParticleSpawnOffset((a * 2f - 1f) * safeWidth, (b * 2f - 1f) * safeHeight)
        "Ellipse" -> {
            // Uniform-area sampling keeps particles evenly distributed across the ellipse.
            val radialFactor = kotlin.math.sqrt(b)
            ParticleSpawnOffset(
                kotlin.math.cos(angle) * safeWidth * radialFactor,
                kotlin.math.sin(angle) * safeHeight * radialFactor
            )
        }
        "Circle" -> {
            val radialFactor = kotlin.math.sqrt(b)
            val circleRadius = safeRadius * radialFactor
            ParticleSpawnOffset(kotlin.math.cos(angle) * circleRadius, kotlin.math.sin(angle) * circleRadius)
        }
        "Ring" -> {
            // Uniform-area sampling avoids over-populating the inner edge of an annulus.
            val innerSquared = safeInnerRadius * safeInnerRadius
            val outerSquared = safeRadius * safeRadius
            val ringRadius = kotlin.math.sqrt(innerSquared + (outerSquared - innerSquared) * b)
            ParticleSpawnOffset(kotlin.math.cos(angle) * ringRadius, kotlin.math.sin(angle) * ringRadius)
        }
        "Line", "HorizontalLine" -> ParticleSpawnOffset((a * 2f - 1f) * safeWidth, 0f)
        "Edge" -> {
            // Sample uniformly along the rectangle perimeter so long sides receive
            // proportionally more particles than short sides.
            val perimeter = 4f * (safeWidth + safeHeight)
            if (perimeter <= 0f) {
                ParticleSpawnOffset(0f, 0f)
            } else {
                val distance = a * perimeter
                val leftRightLength = 2f * safeHeight
                val topBottomLength = 2f * safeWidth
                when {
                    distance < leftRightLength ->
                        ParticleSpawnOffset(-safeWidth, (b * 2f - 1f) * safeHeight)
                    distance < leftRightLength * 2f ->
                        ParticleSpawnOffset(safeWidth, (b * 2f - 1f) * safeHeight)
                    distance < leftRightLength * 2f + topBottomLength ->
                        ParticleSpawnOffset((b * 2f - 1f) * safeWidth, -safeHeight)
                    else ->
                        ParticleSpawnOffset((b * 2f - 1f) * safeWidth, safeHeight)
                }
            }
        }
        else -> ParticleSpawnOffset(0f, 0f)
    }
}
