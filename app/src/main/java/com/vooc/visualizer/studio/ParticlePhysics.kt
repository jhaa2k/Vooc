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
    val a = randomA.coerceIn(0f, 1f)
    val b = randomB.coerceIn(0f, 1f)
    val angle = arcStart + a * arcSweep
    val radialDistance = innerRadius.coerceAtLeast(0f) +
        (radius.coerceAtLeast(innerRadius) - innerRadius.coerceAtLeast(0f)) * b
    return when (shape) {
        "Rectangle" -> ParticleSpawnOffset((a * 2f - 1f) * width, (b * 2f - 1f) * height)
        "Ellipse" -> {
            // Uniform-area sampling keeps particles evenly distributed across the ellipse.
            val radialFactor = kotlin.math.sqrt(b)
            ParticleSpawnOffset(
                kotlin.math.cos(angle) * width.coerceAtLeast(0f) * radialFactor,
                kotlin.math.sin(angle) * height.coerceAtLeast(0f) * radialFactor
            )
        }
        "Circle" -> {
            val radialFactor = kotlin.math.sqrt(b)
            val circleRadius = radius.coerceAtLeast(0f) * radialFactor
            ParticleSpawnOffset(kotlin.math.cos(angle) * circleRadius, kotlin.math.sin(angle) * circleRadius)
        }
        "Ring" -> ParticleSpawnOffset(kotlin.math.cos(angle) * radialDistance, kotlin.math.sin(angle) * radialDistance)
        "Line", "HorizontalLine" -> ParticleSpawnOffset((a * 2f - 1f) * width, 0f)
        "Edge" -> ParticleSpawnOffset(if (a < 0.5f) -width else width, (b * 2f - 1f) * height)
        else -> ParticleSpawnOffset(0f, 0f)
    }
}
