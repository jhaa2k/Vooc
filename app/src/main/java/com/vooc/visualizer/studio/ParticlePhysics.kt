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
