package com.vooc.visualizer.studio

object StudioPresets {
    val names = listOf(
        "Spectrum", "Neon Ring", "Dual Wave", "Particles", "Concentric", "Minimal Scope",
        "Mirrored Bars", "Radial Pulse", "Orbit Dots", "Block Matrix", "Oscilloscope", "Kinetic Lines",
        "Aurora Field", "Electric Skyline", "Pulse Tunnel", "Spectrum Bloom", "Retro Equalizer",
        "Prism Wave", "Bass Reactor", "Particle Storm", "Dual Mirrored Wave", "Neon Grid"
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
            "Kinetic Lines" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.LINES, barCount = 80, thickness = 2.5f, color = 0xFFFFB454))
            "Aurora Field" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.WAVE, color = 0xFF55D6FF, thickness = 3f), VisualLayer(3, LayerKind.PARTICLES, particleCount = 64, color = 0xFFB58CFF))
            "Electric Skyline" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.BLOCKS, barCount = 72, color = 0xFF55D6FF), VisualLayer(3, LayerKind.LINES, barCount = 72, thickness = 1.5f, color = 0xFFFFB454))
            "Pulse Tunnel" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.MULTIRING, radius = 22f, spacing = 5f), VisualLayer(3, LayerKind.PULSE, radius = 12f, color = 0xFF55D6FF))
            "Spectrum Bloom" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.CIRCLE, barCount = 128, radius = 22f, color = 0xFFB58CFF), VisualLayer(3, LayerKind.DOTS, barCount = 96, color = 0xFF55D6FF))
            "Retro Equalizer" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.BLOCKS, barCount = 48, thickness = 4f, spacing = 3f, color = 0xFFFFB454))
            "Prism Wave" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.WAVE, color = 0xFF55D6FF, gradientColor = 0xFFFF5F7A, thickness = 2.5f), VisualLayer(3, LayerKind.WAVEC, y = 14f, color = 0xFFB58CFF))
            "Bass Reactor" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.RING, radius = 28f, measureWhat = "Bass", thickness = 4f), VisualLayer(3, LayerKind.PULSE, radius = 18f, measureWhat = "Beat"))
            "Particle Storm" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.PARTICLES, particleCount = 128, sensitivity = 160f), VisualLayer(3, LayerKind.DOTS, barCount = 96, color = 0xFF55D6FF))
            "Dual Mirrored Wave" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.WAVE, color = 0xFF55D6FF), VisualLayer(3, LayerKind.WAVE, y = 12f, color = 0xFFFF5F7A, thickness = 1.5f))
            "Neon Grid" -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.GRID, barCount = 80, color = 0xFF55D6FF), VisualLayer(3, LayerKind.LINES, barCount = 40, color = 0xFFFFB454))
            else -> listOf(VisualLayer(1, LayerKind.BACKGROUND), VisualLayer(2, LayerKind.BARS))
        }
        return StudioProject(name = "$name Preset", layers = layers)
    }
}