package com.vooc.visualizer.studio

object StudioPresets {
    val names = listOf("Spectrum", "Neon Ring", "Dual Wave", "Particles", "Concentric", "Minimal Scope")

    fun create(name: String): StudioProject {
        val layers = when (name) {
            "Neon Ring" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.MULTIRING), VisualLayer(3, LayerKind.PARTICLES))
            "Dual Wave" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.WAVE), VisualLayer(3, LayerKind.WAVEC, y = 18f, color = 0xFF55D6FF))
            "Particles" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.PARTICLES, sensitivity = 180f), VisualLayer(3, LayerKind.RING))
            "Concentric" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.CONCENTRIC))
            "Minimal Scope" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.SCOPE, color = 0xFFFFFFFF))
            else -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.BARS))
        }
        return StudioProject(name = "$name Preset", layers = layers)
    }
}
