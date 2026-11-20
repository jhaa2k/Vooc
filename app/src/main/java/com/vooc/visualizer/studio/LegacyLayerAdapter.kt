package com.vooc.visualizer.studio

/**
 * Compatibility bridge from the original VisualLayer model to the specification-driven
 * ElementDocument model. Conversion is pure and deterministic: it never mutates or deletes
 * legacy project data, so older projects remain recoverable during the staged migration.
 */
object LegacyLayerAdapter {
    fun toElement(layer: VisualLayer): ElementDocument {
        val type = when (layer.kind) {
            LayerKind.PARTICLES -> StudioElementType.PARTICLES
            LayerKind.SHAPE -> StudioElementType.SHAPE
            LayerKind.TEXT -> StudioElementType.TEXT
            LayerKind.IMAGE -> StudioElementType.IMAGE
            LayerKind.BACKGROUND -> StudioElementType.BACKGROUND
            else -> StudioElementType.BARS_SEGMENTS
        }
        val base = ElementPropertyCatalog.create(
            type = type,
            id = "legacy-layer-${layer.id}",
            tag = layer.name.ifBlank { layer.kind.title }
        ).copy(visible = layer.enabled, locked = layer.locked)

        val values = linkedMapOf<String, ElementValue>()
        fun text(name: String, value: String) { values[name] = ElementValue.Text(value) }
        fun number(name: String, value: Float) {
            if (value.isFinite()) values[name] = ElementValue.Number(value.toDouble())
        }
        fun integer(name: String, value: Int) { values[name] = ElementValue.Integer(value.toLong()) }

        text("Tag", layer.name.ifBlank { layer.kind.title })
        values["Visible"] = ElementValue.Bool(layer.enabled)

        when (type) {
            StudioElementType.BARS_SEGMENTS -> {
                integer("Bar Count", layer.barCount.coerceIn(1, 512))
                number("Bar Width", layer.thickness.coerceIn(0.5f, 256f))
                number("Spacing / Gap", layer.spacing.coerceIn(0f, 256f))
                number("Amplitude Scale", (layer.sensitivity / 100f).coerceIn(0f, 16f))
                number("Smoothing / Damping", (layer.smoothing / 100f).coerceIn(0f, 1f))
                text("Frequency Scale", if (layer.spectrumScale == "LINEAR") "Linear" else "Logarithmic")
                text("Bar Color A", colorHex(layer.color))
                text("Bar Color B", colorHex(layer.gradientColor))
                when (layer.kind) {
                    LayerKind.BARSH -> values["Mirror"] = ElementValue.Bool(true)
                    LayerKind.CIRCLE, LayerKind.MULTIRING, LayerKind.CONCENTRIC,
                    LayerKind.RING, LayerKind.PULSE, LayerKind.DOTS ->
                        text("Bar Design", "Circle / Dot")
                    LayerKind.WAVE, LayerKind.WAVEC -> text("Bar Design", "Wave")
                    LayerKind.SCOPE, LayerKind.LINES -> text("Bar Design", "Line")
                    else -> Unit
                }
            }
            StudioElementType.PARTICLES -> integer("Particle Count", layer.particleCount.coerceIn(1, 4096))
            StudioElementType.TEXT -> text("Text", layer.textContent)
            StudioElementType.IMAGE -> text("Image Source / File", layer.imageUri)
            StudioElementType.BACKGROUND -> text("Image File / Source", layer.imageUri)
            else -> Unit
        }
        number("Rotation", layer.rotation)
        number("Opacity", layer.opacity.coerceIn(0f, 100f))
        return values.entries.fold(base) { element, (name, value) ->
            element.withValue(name, value)
        }.copy(visible = layer.enabled, locked = layer.locked).normalized()
    }

    fun toElements(layers: List<VisualLayer>): List<ElementDocument> =
        layers.map(::toElement)

    private fun colorHex(color: Long): String = "#${color.toString(16).uppercase().padStart(8, '0').takeLast(8)}"
}
