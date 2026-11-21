package com.vooc.visualizer.studio

/** Resolves the normalized opacity shared by the particle preview and video renderer. */
internal fun resolveParticleOpacity(
    opacity: Float,
    fadeIn: Float,
    fadeOut: Float,
    nearFade: Float,
    opacityRandomFactor: Float,
    alphaOverLife: Float,
    colorFade: Float,
    colorAlpha: Float
): Float {
    fun unit(value: Float): Float = value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val result = unit(opacity) * unit(fadeIn) * unit(fadeOut) * unit(nearFade) *
        unit(opacityRandomFactor) * unit(alphaOverLife) * unit(colorFade) * unit(colorAlpha)
    return result.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
}
