package com.vooc.visualizer.studio

import kotlin.math.max

internal fun resolveMotionBlurLength(
    baseLength: Float,
    shutterAngle: Float,
    velocityScale: Float,
    velocity: Float
): Float {
    val safeBase = baseLength.takeIf { it.isFinite() } ?: 0f
    val safeShutter = shutterAngle.takeIf { it.isFinite() }?.coerceIn(0f, 360f) ?: 180f
    val safeScale = velocityScale.takeIf { it.isFinite() }?.coerceIn(0f, 32f) ?: 1f
    val safeVelocity = velocity.takeIf { it.isFinite() }?.coerceIn(0f, 32f) ?: 1f
    return (safeBase * (safeShutter / 180f) * safeScale * safeVelocity).coerceIn(0f, 100000f)
}

internal fun resolveMotionBlurSampleProgress(index: Int, sampleCount: Int, jitter: Float): Float {
    val count = sampleCount.coerceAtLeast(1)
    val safeJitter = jitter.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val base = index.coerceIn(0, count) / count.toFloat()
    val direction = if (index % 2 == 0) -1f else 1f
    return (base + direction * safeJitter * 0.5f / count).coerceIn(0f, 1f)
}

internal fun resolveMotionBlurExposureAlpha(alpha: Float, exposure: Float): Float {
    val safeAlpha = alpha.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val safeExposure = exposure.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
    return (safeAlpha * safeExposure * 2f).coerceIn(0f, 1f)
}
