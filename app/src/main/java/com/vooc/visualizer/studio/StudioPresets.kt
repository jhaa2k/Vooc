package com.vooc.visualizer.studio

object StudioPresets {
    val names = listOf(
        "Spectrum", "Neon Ring", "Dual Wave", "Particles", "Concentric", "Minimal Scope",
        "Mirrored Bars", "Radial Pulse", "Orbit Dots", "Block Matrix", "Oscilloscope", "Kinetic Lines"
    )

    fun create(name: String): StudioProject {
        val layers = when (name) {
            "Neon Ring" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.MULTIRING), VisualLayer(3, LayerKind.PARTICLES))
            "Dual Wave" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.WAVE), VisualLayer(3, LayerKind.WAVEC, y = 18f, color = 0xFF55D6FF))
            "Particles" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.PARTICLES, sensitivity = 180f), VisualLayer(3, LayerKind.RING))
            "Concentric" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.CONCENTRIC))
            "Minimal Scope" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.SCOPE, color = 0xFFFFFFFF))
            "Mirrored Bars" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.BARSH, barCount = 96, thickness = 3f))
            "Radial Pulse" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.CIRCLE, radius = 20f, measureWhat = "Spectrum"), VisualLayer(3, LayerKind.PULSE, radius = 12f))
            "Orbit Dots" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.DOTS, particleCount = 72), VisualLayer(3, LayerKind.PARTICLES, color = 0xFF55D6FF))
            "Block Matrix" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.BLOCKS, barCount = 48, color = 0xFF55D6FF))
            "Oscilloscope" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.WAVEC, color = 0xFFFFFFFF, thickness = 3f))
            "Kinetic Lines" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.LINES, barCount = 80, color = 0xFFFFB454))
            else -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.BARS))
        }
        return StudioProject(name = "$name Preset", layers = layers)
    }
}
