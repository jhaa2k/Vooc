package com.vooc.visualizer.studio

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

internal data class ParticleAxisState(val position: Float, val velocity: Float)

/** Folds an unconstrained particle coordinate back into the scene after boundary impacts. */
internal fun reflectParticleAxis(position: Float, velocity: Float, extent: Float, bounce: Float): ParticleAxisState {
    if (!position.isFinite() || !velocity.isFinite() || !extent.isFinite() || extent <= 0f) {
        return ParticleAxisState(position.coerceIn(0f, extent.coerceAtLeast(0f)), velocity)
    }
    if (position in 0f..extent) return ParticleAxisState(position, velocity)
    val hits = if (position < 0f) ceil(-position / extent).toInt().coerceAtLeast(1)
        else (floor((position - extent) / extent).toInt() + 1).coerceAtLeast(1)
    val period = extent * 2f
    val wrapped = ((position % period) + period) % period
    val folded = if (wrapped > extent) period - wrapped else wrapped
    val direction = if (hits % 2 == 0) 1f else -1f
    return ParticleAxisState(folded.coerceIn(0f, extent), velocity * direction * bounce.coerceIn(0f, 1f).pow(hits))
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
internal fun particleForcePosition(x: Float, y: Float, canvasWidth: Float, canvasHeight: Float): ParticleForcePosition =
    ParticleForcePosition(x.coerceIn(0f, 1f) * canvasWidth.coerceAtLeast(0f),
        y.coerceIn(0f, 1f) * canvasHeight.coerceAtLeast(0f))

internal data class ParticleForceDelta(val x: Float, val y: Float)

/** Returns the velocity delta applied by an Avee-style force field at a particle position. */
internal fun particleForceDelta(
    mode: String,
    dx: Float,
    dy: Float,
    strength: Float,
    age: Float,
    directionX: Float,
    directionY: Float
): ParticleForceDelta {
    val distance = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    val amount = strength * age
    return when (mode) {
        "Attract", "Radial" -> ParticleForceDelta(dx / distance * amount, dy / distance * amount)
        "Repel" -> ParticleForceDelta(-dx / distance * amount, -dy / distance * amount)
        "Directional" -> ParticleForceDelta(directionX * amount, directionY * amount)
        "Vortex" -> ParticleForceDelta(-dy / distance * amount, dx / distance * amount)
        else -> ParticleForceDelta(0f, 0f)
    }
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
    val safeArcSweep = arcSweep.takeIf { it.isFinite() } ?: 0f
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
        "Ring" -> ParticleSpawnOffset(kotlin.math.cos(angle) * radialDistance, kotlin.math.sin(angle) * radialDistance)
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
