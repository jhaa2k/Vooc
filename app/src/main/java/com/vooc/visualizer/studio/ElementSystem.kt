package com.vooc.visualizer.studio

import org.json.JSONArray
import org.json.JSONObject

/**
 * Schema for the specification-driven element editor.
 *
 * The legacy VisualLayer model remains readable while the new element model is introduced
 * incrementally. Element properties are stored by their specification names so future
 * property additions do not invalidate existing project documents.
 */
enum class StudioElementType(val displayName: String) {
    AUDIO_PROVIDER("Audio Provider"),
    IMAGE("Image"),
    BACKGROUND("Background"),
    RGB_SPLIT("Rgb Split"),
    BLUR_EFFECT("Blur Effect"),
    BARS_SEGMENTS("Bars/Segments"),
    PARTICLES("Particles"),
    TEXT("Text"),
    MOTION_BLUR_EFFECT("Motion Blur Effect"),
    SHAPE("Shape")
}

enum class ElementValueType { BOOLEAN, NUMBER, INTEGER, TEXT, COLOR, ENUM, VECTOR2 }

sealed interface ElementValue {
    data class Bool(val value: Boolean) : ElementValue
    data class Number(val value: Double) : ElementValue
    data class Integer(val value: Long) : ElementValue
    data class Text(val value: String) : ElementValue
    data class Vector2(val x: Double, val y: Double) : ElementValue
}

data class ElementPropertySpec(
    val name: String,
    val category: String,
    val type: ElementValueType,
    val defaultValue: ElementValue,
    val min: Double? = null,
    val max: Double? = null,
    val options: List<String> = emptyList()
) {
    fun validate(value: ElementValue): ElementValue? {
        return when (type) {
            ElementValueType.BOOLEAN -> value as? ElementValue.Bool
            ElementValueType.TEXT, ElementValueType.ENUM -> {
                val text = value as? ElementValue.Text ?: return null
                if (type == ElementValueType.ENUM && options.isNotEmpty() && text.value !in options) null else text
            }
            ElementValueType.COLOR -> value as? ElementValue.Integer
            ElementValueType.VECTOR2 -> (value as? ElementValue.Vector2)?.takeIf { it.x.isFinite() && it.y.isFinite() }
            ElementValueType.INTEGER -> {
                val number = when (value) {
                    is ElementValue.Integer -> value.value.toDouble()
                    is ElementValue.Number -> value.value
                    else -> return null
                }
                val bounded = number.coerceIn(min ?: Long.MIN_VALUE.toDouble(), max ?: Long.MAX_VALUE.toDouble())
                ElementValue.Integer(bounded.toLong())
            }
            ElementValueType.NUMBER -> {
                val number = when (value) {
                    is ElementValue.Number -> value.value
                    is ElementValue.Integer -> value.value.toDouble()
                    else -> return null
                }
                if (!number.isFinite()) return null
                ElementValue.Number(number.coerceIn(min ?: -Double.MAX_VALUE, max ?: Double.MAX_VALUE))
            }
        }
    }
}

data class MeasurementMapping(
    val source: String = "Overall Audio Level",
    val inputMin: Float = 0f,
    val inputMax: Float = 1f,
    val outputMin: Float = 0f,
    val outputMax: Float = 1f,
    val multiplier: Float = 1f,
    val offset: Float = 0f,
    val smoothing: Float = 0f,
    val responseCurve: String = "LINEAR",
    val invert: Boolean = false,
    val enabled: Boolean = false
) {
    fun normalized(input: Float): Float {
        val range = inputMax - inputMin
        if (!input.isFinite() || !range.isFinite() || range <= 0f) return 0f
        val raw = ((input - inputMin) / range).coerceIn(0f, 1f)
        val direction = if (invert) 1f - raw else raw
        val curved = when (responseCurve) {
            "SQUARE" -> direction * direction
            "SQRT" -> kotlin.math.sqrt(direction)
            "CUBIC" -> direction * direction * direction
            else -> direction
        }
        return outputMin + curved * (outputMax - outputMin) * multiplier + offset
    }

    fun validated(): MeasurementMapping {
        val safeMin = inputMin.takeIf { it.isFinite() } ?: 0f
        val safeMax = inputMax.takeIf { it.isFinite() && it > safeMin } ?: (safeMin + 1f)
        return copy(
            inputMin = safeMin,
            inputMax = safeMax,
            outputMin = outputMin.takeIf { it.isFinite() } ?: 0f,
            outputMax = outputMax.takeIf { it.isFinite() } ?: 1f,
            multiplier = multiplier.takeIf { it.isFinite() } ?: 1f,
            offset = offset.takeIf { it.isFinite() } ?: 0f,
            smoothing = (smoothing.takeIf { it.isFinite() } ?: 0f).coerceIn(0f, 1f),
            responseCurve = responseCurve.takeIf { it in RESPONSE_CURVES } ?: "LINEAR"
        )
    }

    companion object {
        val RESPONSE_CURVES = setOf("LINEAR", "SQUARE", "SQRT", "CUBIC")
    }
}

data class ElementDocument(
    val id: String,
    val type: StudioElementType,
    val tag: String = type.displayName,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val properties: Map<String, ElementValue> = emptyMap(),
    val measurements: Map<String, MeasurementMapping> = emptyMap()
) {
    fun value(name: String): ElementValue? = properties[name]

    fun withValue(name: String, value: ElementValue): ElementDocument {
        val spec = ElementPropertyCatalog.forElement(type).firstOrNull { it.name == name }
            ?: return this
        val valid = spec.validate(value) ?: return this
        val updated = copy(properties = properties + (name to valid))
        return when (name) {
            "Tag" -> updated.copy(tag = (valid as ElementValue.Text).value)
            "Visible" -> updated.copy(visible = (valid as ElementValue.Bool).value)
            else -> updated
        }
    }

    fun normalized(): ElementDocument {
        val specs = ElementPropertyCatalog.forElement(type).associateBy { it.name }
        // Validate known properties, but retain unknown serialized values. This keeps projects
        // forward-compatible when a newer app version adds properties an older build cannot edit.
        val normalizedProperties = properties.mapNotNull { (name, value) ->
            val spec = specs[name] ?: return@mapNotNull (name to value)
            spec.validate(value)?.let { name to it }
        }.toMap()
        val defaults = specs.mapValues { (name, spec) ->
            normalizedProperties[name] ?: spec.defaultValue
        }.toMutableMap()
        val unknownProperties = normalizedProperties.filterKeys { it !in specs }
        defaults.putAll(unknownProperties)
        val safeTag = (defaults["Tag"] as? ElementValue.Text)?.value?.trim()?.take(80)
            ?.ifEmpty { type.displayName } ?: tag.trim().take(80).ifEmpty { type.displayName }
        val safeVisible = (defaults["Visible"] as? ElementValue.Bool)?.value ?: visible
        defaults["Tag"] = ElementValue.Text(safeTag)
        defaults["Visible"] = ElementValue.Bool(safeVisible)
        return copy(
            tag = safeTag,
            visible = safeVisible,
            properties = defaults,
            measurements = measurements.mapValues { it.value.validated() }
        )
    }
}

/**
 * Single source of truth for the Add Layer menu.
 * Legacy LayerKind remains available only for spectrum-style variants; common layer
 * types are created through StudioElementType so Image/Text/Shape/Background/Particles
 * cannot appear twice.
 */
object LayerAddMenuCatalog {
    val spectrumStyles: List<LayerKind> = LayerKind.entries.filter { kind ->
        kind !in setOf(
            LayerKind.BARS, LayerKind.PARTICLES, LayerKind.SHAPE,
            LayerKind.TEXT, LayerKind.IMAGE, LayerKind.BACKGROUND
        )
    }
    val elementTypes: List<StudioElementType> =
        StudioElementType.entries.filter { it != StudioElementType.AUDIO_PROVIDER }
}

object ElementPropertyCatalog {
    private fun text(name: String, category: String, value: String, options: List<String> = emptyList()) =
        ElementPropertySpec(name, category, if (options.isEmpty()) ElementValueType.TEXT else ElementValueType.ENUM,
            ElementValue.Text(value), options = options)
    private fun bool(name: String, category: String, value: Boolean) =
        ElementPropertySpec(name, category, ElementValueType.BOOLEAN, ElementValue.Bool(value))
    private fun number(name: String, category: String, value: Double, min: Double? = null, max: Double? = null) =
        ElementPropertySpec(name, category, ElementValueType.NUMBER, ElementValue.Number(value), min, max)
    private fun integer(name: String, category: String, value: Long, min: Double? = null, max: Double? = null) =
        ElementPropertySpec(name, category, ElementValueType.INTEGER, ElementValue.Integer(value), min, max)

    private val sharedGeneral = listOf(
        text("Tag", "General", "Element"),
        bool("Visible", "General", true),
        text("Blend Mode", "General", "Normal", listOf("Normal", "Add", "Multiply", "Screen", "Overlay", "Soft Light", "Hard Light", "Difference", "Alpha Blend")),
        ElementPropertySpec("Position", "General", ElementValueType.VECTOR2, ElementValue.Vector2(0.0, 0.0)),
        text("Measure Pos (Measure What)", "General", "Nothing", listOf("Nothing", "None") + MeasureWhatEngine.options),
        text("Anchor X", "General", "Center", listOf("Left", "Center", "Right")),
        text("Anchor Y", "General", "Middle", listOf("Top", "Middle", "Bottom")),
        text("Alignment Position", "General", "Center", listOf("Left", "Center", "Right", "Top", "Middle", "Bottom")),
        bool("Scale Is Uniform", "General", true),
        number("Scale", "General", 100.0, 0.0, 1000.0),
        text("Measure Scale (Measure What)", "General", "Nothing", listOf("Nothing", "None") + MeasureWhatEngine.options),
        number("Rotation", "General", 0.0, -3600.0, 3600.0),
        number("Opacity", "General", 100.0, 0.0, 100.0),
        text("Measure Rot (Measure What)", "General", "Nothing", listOf("Nothing", "None") + MeasureWhatEngine.options)
    )

    private val backgroundGeneral = listOf(
        text("Tag", "General", "Background"),
        bool("Visible", "General", true),
        text("Blend Mode", "General", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        ElementPropertySpec("Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        text("Anchor X", "General", "Center", listOf(
            "Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left",
            "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right"
        )),
        text("Anchor Y", "General", "Center", listOf(
            "Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left",
            "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right"
        )),
        text("Measure Pos (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        ElementPropertySpec("Alignment Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        bool("Scale Is Uniform", "General", true),
        ElementPropertySpec("Scale", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(1.0, 1.0), 0.0, 10.0),
        text("Measure Scale (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Rotation", "General", 0.0, -3600.0, 3600.0),
        text("Measure Rot (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Opacity", "General", 100.0, 0.0, 100.0),
        text("Measure Opacity (Measure What)", "General", "Nothing", MeasureWhatEngine.options)
    )

    private val backgroundAppearance = listOf(
        text("Blend Mode Content", "Appearance", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        text("Color", "Appearance", "#FFFFFFFF"),
        integer("Source Composition Index", "Appearance", 0, 0.0, 255.0),
        text("Mask Image", "Appearance", ""),
        bool("Show Unblurred Content", "Appearance", false),
        bool("Show Unblurred Content Under", "Appearance", false)
    )

    private val backgroundSpecific = listOf(
        text("Source Type", "Background", "Solid Color", listOf("Solid Color", "Image", "Video")),
        text("Image File / Source", "Background", ""),
        text("Fit Mode (Scale Type)", "Background", "Fill (Cover)", listOf(
            "Fit (Contain)", "Fill (Cover)", "Stretch"
        )),
        bool("Edge Blur (Image Fill)", "Background", false),
        number("Blur Radius", "Background", 24.0, 0.0, 256.0),
        number("Corner Radius", "Background", 0.0, 0.0, 512.0),
        text("Color Overlay", "Background", "#00000000"),
        number("Opacity / Alpha", "Background", 1.0, 0.0, 1.0)
    )


    private val shapeGeneral = listOf(
        text("Tag", "General", "Shape"),
        bool("Visible", "General", true),
        text("Blend Mode", "General", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        ElementPropertySpec("Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        text("Anchor X", "General", "Center", listOf(
            "Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left",
            "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right"
        )),
        text("Anchor Y", "General", "Center", listOf(
            "Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left",
            "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right"
        )),
        text("Measure Pos (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        ElementPropertySpec("Alignment Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        bool("Scale Is Uniform", "General", true),
        ElementPropertySpec("Scale", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(1.0, 1.0), 0.0, 10.0),
        text("Measure Scale (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Rotation", "General", 0.0, -3600.0, 3600.0),
        text("Measure Rot (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Opacity", "General", 100.0, 0.0, 100.0),
        text("Measure Opacity (Measure What)", "General", "Nothing", MeasureWhatEngine.options)
    )

    private val shapeAppearance = listOf(
        text("Blend Mode Content", "Appearance", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        text("Color", "Appearance", "#FFFFFFFF"),
        integer("Source Composition Index", "Appearance", 0, 0.0, 255.0),
        text("Mask Image", "Appearance", ""),
        bool("Show Unblurred Content", "Appearance", false),
        bool("Show Unblurred Content Under", "Appearance", false)
    )

    private val shapeSpecific = listOf(
        text("Shape Type", "Shape", "Rectangle", listOf(
            "Rectangle", "Circle/Oval", "Line", "Triangle", "Custom"
        )),
        number("Width", "Shape", 300.0, 0.0, 8192.0),
        number("Height", "Shape", 300.0, 0.0, 8192.0),
        number("Corner Radius", "Shape", 0.0, 0.0, 4096.0),
        text("Fill / Color", "Shape", "#FFFFFFFF"),
        number("Opacity / Alpha", "Shape", 1.0, 0.0, 1.0),
        number("Stroke / Border Width", "Shape", 0.0, 0.0, 512.0),
        text("Stroke Color", "Shape", "#FFFFFFFF"),
        text("Stroke Style", "Shape", "Solid", listOf("Solid", "Dashed", "Dotted")),
        number("Trim Paths / Progress", "Shape", 1.0, 0.0, 1.0),
        bool("Gradient Fill", "Shape", false),
        text("Gradient Type", "Shape", "Linear", listOf("Linear", "Radial")),
        text("Gradient Start Color", "Shape", "#FFFFFFFF"),
        text("Gradient End Color", "Shape", "#FF000000"),
        number("Gradient Angle", "Shape", 0.0, -360.0, 360.0)
    )

    private val provider = listOf(
        text("Tag", "General", "Audio Provider"),
        bool("Provider Enabled", "General", true),
        text("Sample Provider", "General", "Spectrum", listOf("Spectrum")),
        integer("Sample Out Count", "Spectrum", 201, 1.0, 2048.0),
        bool("Mirror Samples", "Spectrum", false),
        bool("Repeat Samples", "Spectrum", true),
        integer("Star And End Gap", "Spectrum", 0, 0.0, 2048.0),
        number("Smooth", "Spectrum", 0.595, 0.0, 1.0),
        number("Pre Smooth", "Spectrum", 1.0, 0.0, 1.0),
        integer("Filter Radius", "Spectrum", 1, 0.0, 64.0),
        number("Filter Strength", "Spectrum", 1.0, 0.0, 1.0),
        number("AWeight", "Spectrum", 0.2, 0.0, 1.0),
        number("Output Multiplier", "Spectrum", 7.0, 0.0, 32.0),
        number("Lower Hz", "Spectrum Hz", 20.0, 1.0, 96000.0),
        number("Higher Hz", "Spectrum Hz", 18000.0, 2.0, 96000.0),
        number("Hz Linear Factor", "Spectrum Hz", 0.06, -1.0, 1.0),
        number("Freq Shift", "Spectrum Hz", 0.0, -24000.0, 24000.0),
        number("Beat Smooth", "Beat", 0.685, 0.0, 1.0),
        number("Beat Range Bar First", "Beat", 0.0, 0.0, 1.0),
        number("Beat Range Bar Last", "Beat", 0.35, 0.0, 1.0),
        number("Beat Range Value Lower", "Beat", 0.0, 0.0, 100.0),
        number("Beat Range Value Higher", "Beat", 50.0, 0.0, 100.0),
        text("Analysis Mode", "Performance", "Spectrum + Beat", listOf("Spectrum", "Beat", "Spectrum + Beat")),
        text("Channel Mode", "Performance", "Mono Mix", listOf("Mono Mix", "Left", "Right", "Stereo Average")),
        text("Window Function", "Performance", "Hann", listOf("Rectangular", "Hann", "Hamming", "Blackman")),
        bool("Beat Detection Enabled", "Beat", true),
        number("Beat Sensitivity", "Beat", 1.0, 0.0, 8.0),
        number("Beat Threshold", "Beat", 0.65, 0.0, 1.0),
        number("Beat Release", "Beat", 0.25, 0.0, 5.0),
        text("Fft Size", "Performance", "2048", listOf("256", "512", "1024", "2048", "4096", "8192"))
    )

    private val imageGeneral = listOf(
        text("Tag", "General", "Image"),
        bool("Visible", "General", true),
        text("Blend Mode", "General", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        ElementPropertySpec("Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        text("Anchor X", "General", "Center", listOf(
            "Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left",
            "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right"
        )),
        text("Anchor Y", "General", "Center", listOf(
            "Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left",
            "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right"
        )),
        text("Measure Pos (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        ElementPropertySpec("Alignment Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        bool("Scale Is Uniform", "General", true),
        ElementPropertySpec("Scale", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(1.0, 1.0), 0.0, 10.0),
        text("Measure Scale (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Rotation", "General", 0.0, -3600.0, 3600.0),
        text("Measure Rot (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Opacity", "General", 100.0, 0.0, 100.0),
        text("Measure Opacity (Measure What)", "General", "Nothing", MeasureWhatEngine.options)
    )

    private val imageAppearance = listOf(
        text("Blend Mode Content", "Appearance", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        text("Color", "Appearance", "#FFFFFFFF"),
        integer("Source Composition Index", "Appearance", 0, 0.0, 255.0),
        text("Mask Image", "Appearance", ""),
        bool("Show Unblurred Content", "Appearance", false),
        bool("Show Unblurred Content Under", "Appearance", false)
    )

    private val imageSpecific = listOf(
        text("Custom Image", "Image", ""),
        text("Image Source / File", "Compatibility", ""),
        text("Fit Mode (Scale Type)", "Image", "Fit (Contain)", listOf(
            "Fit (Contain)", "Fill (Cover)", "Stretch"
        )),
        bool("Keep Aspect Ratio And Crop To Fit", "Image", false),
        bool("Keep Aspect Ratio", "Image", true),
        text("Shape", "Image", "Rectangle", listOf("Rectangle", "Rounded Rectangle", "Square", "Circle", "Ellipse", "Triangle", "Custom")),
        number("Crop Left", "Image", 0.0, 0.0, 1.0),
        number("Crop Top", "Image", 0.0, 0.0, 1.0),
        number("Crop Right", "Image", 0.0, 0.0, 1.0),
        number("Crop Bottom", "Image", 0.0, 0.0, 1.0),
        number("Corner Radius", "Image", 0.0, 0.0, 1000.0),
        text("Mask / Shape", "Image", "Rectangle", listOf(
            "Rectangle", "Circle", "Triangle", "Ellipse", "Custom"
        )),
        number("Edge Blur", "Image", 0.0, 0.0, 128.0),
        text("Color Overlay", "Image", "#00FFFFFF"),
        number("Opacity / Alpha", "Image", 1.0, 0.0, 1.0),
        number("Measure Animation Speed", "Image", 0.0, -20.0, 20.0),
        text("Measure Animation Speed (Measure What)", "Image", "Nothing", MeasureWhatEngine.options),
        number("Measure Animation Speed A (Speed)", "Image", 1.0, -100.0, 100.0),
        number("Measure Animation Speed B (Beat Amount)", "Image", 0.0, -100.0, 100.0),
        text("Measure Color Blend (Measure What)", "Color", "Nothing", MeasureWhatEngine.options),
        number("Measure Color Blend Min", "Color", 0.0, 0.0, 1.0),
        number("Measure Color Blend Max", "Color", 1.0, 0.0, 1.0),
        bool("Blurred Border", "Image", false),
        text("Mask Image", "Image", ""),
        text("Mask Mode", "Image", "Alpha Mask", listOf("Alpha Mask", "Luminance Mask", "Inverted Mask", "Normal Mask")),
        number("Mask Strength", "Image", 1.0, 0.0, 1.0),
        text("Color To", "Image", "#FFFFFFFF"),
        number("Measure Color Blend", "Image", 0.0, 0.0, 1.0),
        number("Saturation", "Image", 1.0, 0.0, 2.0),
        bool("Color Key Enabled", "Image", false),
        bool("Auto Detect Color Key", "Image", false),
        text("Color Key", "Image", "#FF000000"),
        number("Color Key Tolerance", "Image", 0.16, 0.0, 1.0),
        number("Transparency Strength", "Image", 1.0, 0.0, 1.0),
        number("Opacity Strength", "Image", 1.0, 0.0, 1.0),
        bool("Blur Enabled", "Image", false),
        number("Blur Divider", "Image", 2.0, 1.0, 32.0),
        number("Blur Radius", "Image", 8.0, 0.0, 128.0),
        number("Generated Album Art Hint Shift Hue", "Generated Album Art", 0.0, -360.0, 360.0),
        bool("Generated Album Art Hint No Text", "Generated Album Art", false),
        bool("Generated Album Art Hint Force Gen", "Generated Album Art", false),
        text("Generated Album Art Color", "Generated Album Art", "#FFFFFFFF")
    )

    private val rgbGeneral = listOf(
        text("Tag", "General", "RGB Split"),
        bool("Visible", "General", true),
        text("Blend Mode", "General", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        ElementPropertySpec("Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        text("Anchor X", "General", "Center", listOf(
            "Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left",
            "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right"
        )),
        text("Anchor Y", "General", "Center", listOf(
            "Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left",
            "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right"
        )),
        text("Measure Pos (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        ElementPropertySpec("Alignment Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        bool("Scale Is Uniform", "General", true),
        ElementPropertySpec("Scale", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(1.0, 1.0), 0.0, 10.0),
        text("Measure Scale (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Rotation", "General", 0.0, -3600.0, 3600.0),
        text("Measure Rot (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Opacity", "General", 100.0, 0.0, 100.0),
        text("Measure Opacity (Measure What)", "General", "Nothing", MeasureWhatEngine.options)
    )

    private val rgbAppearance = listOf(
        text("Blend Mode Content", "Appearance", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        text("Color", "Appearance", "#FFFFFFFF"),
        integer("Source Composition Index", "Appearance", 0, 0.0, 255.0),
        text("Mask Image", "Appearance", ""),
        bool("Show Unblurred Content", "Appearance", false),
        bool("Show Unblurred Content Under", "Appearance", false)
    )

    private val rgbSplit = listOf(
        text("Target Image", "RGB Split", ""),
        number("Split Amount", "RGB Split", 4.0, 0.0, 256.0),
        number("Red Offset X", "RGB Split", -4.0, -256.0, 256.0),
        number("Red Offset Y", "RGB Split", 0.0, -256.0, 256.0),
        number("Green Offset X", "RGB Split", 0.0, -256.0, 256.0),
        number("Green Offset Y", "RGB Split", 0.0, -256.0, 256.0),
        number("Blue Offset X", "RGB Split", 4.0, -256.0, 256.0),
        number("Blue Offset Y", "RGB Split", 0.0, -256.0, 256.0),
        number("Split Amount / Distance", "RGB Split", 4.0, 0.0, 256.0),
        number("Angle / Direction", "RGB Split", 0.0, -360.0, 360.0),
        ElementPropertySpec("Center / Focus Point", "RGB Split", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        number("Falloff / Radial Split", "RGB Split", 0.0, 0.0, 10.0),
        number("Speed / Frequency", "RGB Split", 0.0, 0.0, 100.0),
        number("Split Multiplier", "RGB Split", 1.0, 0.0, 32.0),
        text("Split Color 0", "RGB Split", "#FFFF0000"),
        text("Split Color 1", "RGB Split", "#FF00FF00"),
        text("Split Color 2", "RGB Split", "#FF0000FF")
    )

    private val blurGeneral = listOf(
        text("Tag", "General", "Blur Effect"),
        bool("Visible", "General", true),
        text("Blend Mode", "General", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        ElementPropertySpec("Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        text("Anchor X", "General", "Center", listOf("Left", "Center", "Right")),
        text("Anchor Y", "General", "Middle", listOf("Top", "Middle", "Bottom")),
        text("Measure Pos (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        ElementPropertySpec("Alignment Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        bool("Scale Is Uniform", "General", true),
        ElementPropertySpec("Scale", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(1.0, 1.0), 0.0, 10.0),
        text("Measure Scale (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Rotation", "General", 0.0, -3600.0, 3600.0),
        text("Measure Rot (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Opacity", "General", 100.0, 0.0, 100.0),
        text("Measure Opacity (Measure What)", "General", "Nothing", MeasureWhatEngine.options)
    )

    private val blurAppearance = listOf(
        text("Target Image", "Appearance", ""),
        text("Blend Mode Content", "Appearance", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        text("Color", "Appearance", "#FFFFFFFF"),
        integer("Source Composition Index", "Appearance", 2, 0.0, 255.0),
        text("Mask Image", "Appearance", ""),
        bool("Show Unblurred Content", "Appearance", false),
        bool("Show Unblurred Content Under", "Appearance", false)
    )

    private val blurEffect = listOf(
        number("Blur Radius", "Blur", 2.0, 0.0, 128.0),
        number("Blur Multiplier", "Blur", 4.1, 0.0, 64.0),
        ElementPropertySpec("1layer Scale", "Blur", ElementValueType.VECTOR2,
            ElementValue.Vector2(1.0, 1.0), 0.0, 10.0),
        ElementPropertySpec("2layer Scale", "Blur", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.0, 0.0), 0.0, 10.0),
        ElementPropertySpec("3layer Scale", "Blur", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.0, 0.0), 0.0, 10.0),
        text("1layer Color", "Blur", "#FFFFFFFF"),
        text("2layer Color", "Blur", "#FFFFFFFF"),
        text("3layer Color", "Blur", "#FFFFFFFF")
    )

    private val barsGeneral = listOf(
        text("Tag", "General", "Spectrum Bars"),
        bool("Visible", "General", true),
        text("Blend Mode", "General", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        ElementPropertySpec("Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        text("Anchor X", "General", "Center", listOf(
            "Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left",
            "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right"
        )),
        text("Anchor Y", "General", "Center", listOf(
            "Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left",
            "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right"
        )),
        text("Measure Pos (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        ElementPropertySpec("Alignment Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        bool("Scale Is Uniform", "General", true),
        ElementPropertySpec("Scale", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(1.0, 1.0), 0.0, 10.0),
        text("Measure Scale (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Rotation", "General", 0.0, -3600.0, 3600.0),
        text("Measure Rot (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Opacity", "General", 100.0, 0.0, 100.0),
        text("Measure Opacity (Measure What)", "General", "Nothing", MeasureWhatEngine.options)
    )

    private val barsAppearance = listOf(
        text("Blend Mode Content", "Appearance", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        text("Color", "Appearance", "#FFFFFFFF"),
        integer("Source Composition Index", "Appearance", 0, 0.0, 255.0),
        text("Mask Image", "Appearance", ""),
        bool("Show Unblurred Content", "Appearance", false),
        bool("Show Unblurred Content Under", "Appearance", false)
    )

    private val bars = listOf(
        integer("Audio Provider Index", "Bars", 0, 0.0, 31.0),
        integer("Bar Count", "Layout", 64, 8.0, 256.0),
        integer("Segments Per Bar", "Layout", 1, 1.0, 64.0),
        text("Orientation", "Layout", "Vertical", listOf("Horizontal", "Vertical", "Radial", "Mirror")),
        text("Shape Path", "Layout", "Horizontal Line", listOf("Horizontal Line", "Vertical Line", "Circle", "Custom Path")),
        text("Custom Path Points", "Layout", "0,0.5;0.25,0.25;0.5,0.75;0.75,0.25;1,0.5"),
        number("Min Height", "Layout", 0.0, 0.0, 10000.0),
        number("Max Height", "Layout", 300.0, 0.0, 10000.0),
        number("Height Scale", "Bars", 1.0, 0.0, 10.0),
        number("Min Height Scale", "Bars", 0.0, 0.0, 1.0),
        number("Max Height Scale", "Bars", 1.0, 0.0, 1.0),
        number("Color Blend Offset", "Bars", 0.0, -1.0, 1.0),
        text("Bar Design", "Design", "Rounded Rect", listOf(
            "Rectangle", "Rounded Rect", "Capsule / Pill", "Line", "Circle / Dot",
            "Triangle", "Diamond", "Hexagon", "Pentagon", "Octagon", "Trapezoid", "Parallelogram", "Star", "Chevron", "Arrow", "Cross", "Wave", "Lightning", "Custom Image"
        )),
        number("Bar Width", "Layout", 8.0, 0.5, 256.0),
        number("Corner Radius", "Design", 4.0, 0.0, 128.0),
        number("Bar Offset", "Layout", 0.0, -4096.0, 4096.0),
        number("Sensitivity", "Audio", 1.0, 0.0, 8.0),
        number("Amplitude Scale", "Audio", 1.0, 0.0, 16.0),
        number("Amplitude Gamma", "Audio", 1.0, 0.1, 4.0),
        number("Spacing / Gap", "Layout", 3.0, 0.0, 256.0),
        text("Alignment", "Layout", "Bottom", listOf("Bottom", "Top", "Center", "Stretch")),
        text("Frequency Scale", "Audio", "Logarithmic", listOf("Linear", "Logarithmic")),
        number("Smoothing / Damping", "Behavior", 0.72, 0.0, 1.0),
        number("Attack Smoothing", "Behavior", 0.12, 0.0, 1.0),
        number("Release Smoothing", "Behavior", 0.72, 0.0, 1.0),
        bool("Flip Input", "Audio", false),
        bool("Mirror", "Modifiers", false),
        text("Mirror Mode", "Modifiers", "Horizontal", listOf("Off", "Horizontal", "Vertical", "Both")),
        bool("Center Symmetry", "Modifiers", false),
        bool("Outline / Stroke", "Modifiers", false),
        number("Stroke Width", "Modifiers", 1.5, 0.0, 32.0),
        text("Stroke Color", "Modifiers", "#FFFFFFFF"),
        text("Stroke Style", "Modifiers", "Solid", listOf("Solid", "Dashed", "Dotted")),
        text("Bar Color A", "Color", "#FF56D8FF"),
        text("Bar Color B", "Color", "#FF9B5CFF"),
        text("Color Mode", "Color", "Alternating", listOf("Single", "Alternating", "Gradient", "By Amplitude", "Per Bar")),
        text("Per-Bar Color", "Color", "#FFFFFFFF"),
        text("Peak Color", "Color", "#FFFFFFFF"),
        text("Cap Color", "Color", "#FFFFFFFF"),
        text("Custom Bar Image", "Design", ""),
        number("Bar Rotation", "Design", 0.0, -180.0, 180.0),
        bool("3D / Extrusion", "Modifiers", false),
        number("Extrusion Depth", "Modifiers", 5.0, 0.0, 64.0),
        bool("Gradient Fill", "Modifiers", false),
        text("Gradient Start Color", "Modifiers", "#FF56D8FF"),
        text("Gradient End Color", "Modifiers", "#FF9B5CFF"),
        text("Gradient Direction", "Modifiers", "Vertical", listOf("Vertical", "Horizontal")),
        text("Top/Bottom Cap", "Modifiers", "None", listOf("None", "Circle", "Square", "Diamond")),
        text("Cap Placement", "Modifiers", "Top", listOf("Top", "Bottom", "Both")),
        number("Cap Thickness", "Modifiers", 3.0, 0.5, 64.0),
        number("Cap Size", "Modifiers", 4.0, 0.0, 64.0),
        bool("Glow / Shadow", "Modifiers", false),
        number("Glow Strength", "Modifiers", 0.35, 0.0, 1.0),
        bool("Peak / Cap", "Modifiers", false),
        text("Peak Indicator Style", "Modifiers", "Line", listOf("None", "Line", "Dot", "Bar")),
        text("Peak Indicator Color", "Modifiers", "#FFFFFFFF"),
        number("Peak Hold", "Modifiers", 0.65, 0.0, 1.0),
        number("Peak Fall Speed", "Modifiers", 0.8, 0.0, 1.0),
        number("Peak Width", "Modifiers", 2.0, 0.5, 64.0),
        number("Peak Hold Time", "Modifiers", 0.35, 0.0, 1.0),
        number("Reaction Delay", "Behavior", 0.0, 0.0, 5000.0),
        number("Accumulated Delay", "Behavior", 0.0, 0.0, 5000.0),
        number("Reaction Accumulated Delay", "Behavior", 0.0, 0.0, 5000.0),
        number("Softness", "Behavior", 0.0, 0.0, 1.0),
        text("Segment 1 Color From", "Segment 1", "#FF56D8FF"),
        text("Segment 1 Color To", "Segment 1", "#FF9B5CFF"),
        bool("Segment 1 Fixed Height", "Segment 1", false),
        number("Segment 1 Bar Height Multiplier", "Segment 1", 1.0, 0.0, 10.0),
        bool("Segment 1 Mirror", "Segment 1", false),
        bool("Segment 1 Flip Every Other", "Segment 1", false),
        text("Segment 2 Color From", "Segment 2", "#FF9B5CFF"),
        text("Segment 2 Color To", "Segment 2", "#FFFFFFFF"),
        bool("Segment 2 Fixed Height", "Segment 2", false),
        number("Segment 2 Bar Height Multiplier", "Segment 2", 1.0, 0.0, 10.0),
        bool("Segment 2 Mirror", "Segment 2", false),
        bool("Segment 2 Flip Every Other", "Segment 2", false)
    )

    private val particlesGeneral = listOf(
        text("Tag", "General", "Particles"),
        bool("Visible", "General", true),
        text("Blend Mode", "General", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        ElementPropertySpec("Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        text("Anchor X", "General", "Center", listOf("Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left", "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right")),
        text("Anchor Y", "General", "Center", listOf("Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left", "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right")),
        text("Measure Pos (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        ElementPropertySpec("Alignment Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        bool("Scale Is Uniform", "General", true),
        ElementPropertySpec("Scale", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(1.0, 1.0), 0.0, 10.0),
        text("Measure Scale (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Rotation", "General", 0.0, -3600.0, 3600.0),
        text("Measure Rot (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Opacity", "General", 100.0, 0.0, 100.0),
        text("Measure Opacity (Measure What)", "General", "Nothing", MeasureWhatEngine.options)
    )

    private val particlesAppearance = listOf(
        text("Blend Mode Content", "Appearance", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        text("Color", "Appearance", "#FFFFFFFF"),
        integer("Source Composition Index", "Appearance", 0, 0.0, 255.0),
        text("Mask Image", "Appearance", ""),
        bool("Show Unblurred Content", "Appearance", false),
        bool("Show Unblurred Content Under", "Appearance", false),
        text("Texture / Image", "Appearance", ""),
        text("Color Over Life", "Appearance", "Gradient", listOf("Constant", "Gradient", "Fade To Transparent")),
        text("Color Over Life Start", "Appearance", "#FFFFFFFF"),
        text("Color Over Life End", "Appearance", "#00FFFFFF"),
        text("Alpha Over Life", "Appearance", "Fade In Out", listOf("Constant", "Fade In", "Fade Out", "Fade In Out"))
    )

    private val particles = listOf(
        integer("Particle Count", "Particles", 256, 1.0, 10000.0),
        number("Emission Rate", "Particles", 30.0, 0.0, 10000.0),
        number("Life Span", "Particles", 3.0, 0.01, 3600.0),
        number("Start Size", "Particles", 8.0, 0.0, 1000.0),
        number("End Size", "Particles", 2.0, 0.0, 1000.0),
        number("Start Velocity", "Particles", 80.0, -5000.0, 5000.0),
        number("End Velocity", "Particles", 0.0, -5000.0, 5000.0),
        number("Direction / Angle", "Particles", -90.0, -360.0, 360.0),
        number("Spread", "Particles", 360.0, 0.0, 360.0),
        ElementPropertySpec("Gravity", "Particles", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.0, 0.0), -2000.0, 2000.0),
        ElementPropertySpec("Wind", "Particles", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.0, 0.0), -2000.0, 2000.0),
        number("Friction / Drag", "Particles", 0.0, 0.0, 100.0),
        number("Turbulence / Noise", "Particles", 0.0, 0.0, 100.0),
        number("Rotation Speed", "Particles", 0.0, -3600.0, 3600.0),
        bool("Collision", "Particles", false),
        number("Bounce", "Particles", 0.5, 0.0, 1.0),
        text("Spawn Area", "Particles", "Point", listOf("Point", "Rectangle", "Circle", "Line", "Edge")),
        number("Spawn Area Width", "Particles", 1.0, 0.0, 10000.0),
        number("Spawn Area Height", "Particles", 1.0, 0.0, 10000.0),
        number("Particle Size Random", "Particles", 0.0, 0.0, 1.0),
        number("Velocity Random", "Particles", 0.0, 0.0, 1.0),
        number("Lifetime Random", "Particles", 0.0, 0.0, 1.0),
        number("Direction Random", "Particles", 0.0, 0.0, 360.0),
        integer("Count Limit", "Overall", 128, 1.0, 10000.0),
        bool("Mirror X", "Overall", false),
        bool("Mirror Y", "Overall", false),
        number("Perspective Depth", "Overall", 0.0, 0.0, 1000.0),
        number("Perspective Fov", "Overall", 60.0, 1.0, 179.0),
        bool("Vector Relative To Center", "Overall", false),
        bool("Use Random Vector Instead", "Overall", false),
        number("Vector Angle", "Overall", -90.0, -360.0, 360.0),
        number("Vector Angle Z", "Overall", 0.0, -360.0, 360.0),
        text("Force Field", "Overall", "None", listOf("None", "Attract", "Repel", "Directional", "Radial")),
        number("Force Field Strength", "Overall", 0.0, -5000.0, 5000.0),
        ElementPropertySpec("Force Field Position", "Overall", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        text("Custom Image", "Appearance", ""),
        number("Particle Scale", "Appearance", 100.0, 0.0, 1000.0),
        number("Trail Length", "Appearance", 0.0, 0.0, 256.0),
        text("Color From", "Appearance", "#FFFFFFFF"),
        number("Fade In And Out Time", "Appearance", 0.25, 0.0, 30.0),
        bool("Velocity Angle", "Appearance", false),
        number("Size Aspect Ratio", "Appearance", 1.0, 0.01, 20.0),
        number("Fade In Size", "Appearance", 1.0, 0.0, 10.0),
        number("End Size Random", "Appearance", 0.0, 0.0, 1.0),
        number("End Size ZFactor", "Appearance", 0.0, -10.0, 10.0),
        number("Near Camera Fade Out Distance", "Appearance", 0.0, 0.0, 1000.0),
        number("Spawn Time", "Behaviour", 0.02, 0.0, 10.0),
        text("Measure Overall Speed (Measure What)", "Behaviour", "Nothing", MeasureWhatEngine.options),
        number("Measure Overall Speed A (X Amount)", "Behaviour", 0.0, -1000.0, 1000.0),
        number("Measure Overall Speed B (Y Amount)", "Behaviour", 0.0, -1000.0, 1000.0),
        number("Speed", "Behaviour", 80.0, -5000.0, 5000.0),
        number("Speed Random", "Behaviour", 0.0, 0.0, 1.0),
        number("Lifetime", "Behaviour", 3.0, 0.01, 3600.0),
        number("Side Sine Wave Freq", "Wave Behaviour", 0.0, -100.0, 100.0),
        number("Side Sine Wave Freq Random", "Wave Behaviour", 0.0, 0.0, 1.0),
        number("Side Sine Wave Mag", "Wave Behaviour", 0.0, -1000.0, 1000.0),
        number("Side Sine Wave Mag Random", "Wave Behaviour", 0.0, 0.0, 1.0)
    )

    private val textGeneral = listOf(
        text("Tag", "General", "Text"),
        bool("Visible", "General", true),
        text("Blend Mode", "General", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        ElementPropertySpec("Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        text("Anchor X", "General", "Center", listOf(
            "Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left",
            "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right"
        )),
        text("Anchor Y", "General", "Center", listOf(
            "Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left",
            "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right"
        )),
        text("Measure Pos (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        ElementPropertySpec("Alignment Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        bool("Scale Is Uniform", "General", true),
        ElementPropertySpec("Scale", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(1.0, 1.0), 0.0, 10.0),
        text("Measure Scale (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Rotation", "General", 0.0, -3600.0, 3600.0),
        text("Measure Rot (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Opacity", "General", 100.0, 0.0, 100.0),
        text("Measure Opacity (Measure What)", "General", "Nothing", MeasureWhatEngine.options)
    )

    private val textAppearance = listOf(
        text("Blend Mode Content", "Appearance", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        text("Color", "Appearance", "#FFFFFFFF"),
        integer("Source Composition Index", "Appearance", 0, 0.0, 255.0),
        text("Mask Image", "Appearance", ""),
        bool("Show Unblurred Content", "Appearance", false),
        bool("Show Unblurred Content Under", "Appearance", false)
    )

    private val textTypography = listOf(
        text("Text", "Typography", "VOOC"),
        text("Font Family", "Typography", "Poppins"),
        number("Font Size", "Typography", 48.0, 1.0, 512.0),
        text("Font Style", "Typography", "Bold", listOf("Regular", "Medium", "SemiBold", "Bold", "Italic", "Bold Italic")),
        bool("Underline", "Typography", false),
        number("Letter Spacing", "Typography", 0.0, -20.0, 100.0),
        number("Line Spacing", "Typography", 1.0, 0.5, 4.0),
        text("Alignment", "Typography", "Center", listOf("Left", "Center", "Right", "Justified")),
        text("Text Gradient Start", "Typography", "#FFFFFFFF"),
        text("Text Gradient End", "Typography", "#FFFFFFFF"),
        bool("Stroke Enabled", "Typography", false),
        text("Stroke Color", "Typography", "#FF000000"),
        number("Stroke Thickness", "Typography", 0.0, 0.0, 32.0),
        bool("Shadow Enabled", "Typography", false),
        text("Shadow Color", "Typography", "#99000000"),
        number("Shadow Offset X", "Typography", 2.0, -100.0, 100.0),
        number("Shadow Offset Y", "Typography", 2.0, -100.0, 100.0),
        number("Shadow Blur", "Typography", 4.0, 0.0, 100.0),
        number("Max Width", "Typography", 1000.0, 1.0, 10000.0),
        bool("Wrap Text", "Typography", true),
        bool("Auto-Scale", "Typography", false),
        text("Case", "Typography", "Original", listOf("Original", "Uppercase", "Lowercase", "Capitalize"))
    )

    private val motionBlurGeneral = listOf(
        text("Tag", "General", "Motion Blur Effect"),
        bool("Visible", "General", true),
        text("Blend Mode", "General", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        ElementPropertySpec("Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        text("Anchor X", "General", "Center", listOf(
            "Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left",
            "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right"
        )),
        text("Anchor Y", "General", "Center", listOf(
            "Center", "Top-Left", "Top-Center", "Top-Right", "Middle-Left",
            "Middle-Right", "Bottom-Left", "Bottom-Center", "Bottom-Right"
        )),
        text("Measure Pos (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        ElementPropertySpec("Alignment Position", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        bool("Scale Is Uniform", "General", true),
        ElementPropertySpec("Scale", "General", ElementValueType.VECTOR2,
            ElementValue.Vector2(1.0, 1.0), 0.0, 10.0),
        text("Measure Scale (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Rotation", "General", 0.0, -3600.0, 3600.0),
        text("Measure Rot (Measure What)", "General", "Nothing", MeasureWhatEngine.options),
        number("Opacity", "General", 100.0, 0.0, 100.0),
        text("Measure Opacity (Measure What)", "General", "Nothing", MeasureWhatEngine.options)
    )

    private val motionBlurAppearance = listOf(
        text("Blend Mode Content", "Appearance", "Alpha", listOf(
            "Alpha", "PreMulAlpha", "Add", "Screen", "Multiply", "Darken",
            "Lighten", "Exclusion", "Subtractive", "Copy"
        )),
        text("Color", "Appearance", "#FFFFFFFF"),
        integer("Source Composition Index", "Appearance", 0, 0.0, 255.0),
        text("Mask Image", "Appearance", ""),
        text("Mask Mode", "Appearance", "Alpha Mask", listOf("Alpha Mask", "Luminance Mask", "Inverted Mask", "Normal Mask")),
        number("Mask Strength", "Appearance", 1.0, 0.0, 1.0),
        bool("Show Unblurred Content", "Appearance", false),
        bool("Show Unblurred Content Under", "Appearance", false)
    )

    private val motionBlur = listOf(
        text("Target Image", "Appearance", ""),
        number("Blur Amount / Strength", "Motion Blur", 1.0, 0.0, 64.0),
        number("Blur Length", "Motion Blur", 24.0, 0.0, 512.0),
        number("Blur Direction / Angle", "Motion Blur", 0.0, -360.0, 360.0),
        integer("Samples / Iterations", "Motion Blur", 8, 1.0, 64.0),
        integer("Trails / Echo", "Motion Blur", 0, 0.0, 32.0),
        number("Trail Opacity / Fade", "Motion Blur", 0.75, 0.0, 1.0),
        ElementPropertySpec("Center / Focus Point", "Motion Blur", ElementValueType.VECTOR2,
            ElementValue.Vector2(0.5, 0.5), 0.0, 1.0),
        bool("Radial Motion", "Motion Blur", false),
        bool("Velocity Influence", "Motion Blur", true),
        text("Motion Source", "Motion Blur", "Transform Movement", listOf(
            "Transform Movement", "Measured Movement", "Target Motion"
        )),
        text("Relative Motion Mode", "Motion Blur", "Composition", listOf(
            "Relative", "Absolute", "Target Relative"
        )),
        number("Blur Amount Multiplier", "Motion Blur", 1.0, 0.0, 64.0)
    )

    fun forElement(type: StudioElementType): List<ElementPropertySpec> {
        if (type == StudioElementType.AUDIO_PROVIDER) return provider
        if (type == StudioElementType.BLUR_EFFECT) {
            return (blurGeneral + blurAppearance + blurEffect)
                .groupBy { it.name }.values.map { sameName -> sameName.last() }
        }
        if (type == StudioElementType.PARTICLES) {
            return (particlesGeneral + particlesAppearance + particles)
                .groupBy { it.name }.values.map { sameName -> sameName.last() }
        }
        if (type == StudioElementType.TEXT) {
            return (textGeneral + textAppearance + textTypography)
                .groupBy { it.name }.values.map { sameName -> sameName.last() }
        }
        if (type == StudioElementType.BACKGROUND) {
            return (backgroundGeneral + backgroundAppearance + backgroundSpecific)
                .groupBy { it.name }.values.map { sameName -> sameName.last() }
        }
        if (type == StudioElementType.IMAGE) {
            return (imageGeneral + imageAppearance + imageSpecific)
                .groupBy { it.name }.values.map { sameName -> sameName.last() }
        }
        if (type == StudioElementType.RGB_SPLIT) {
            return (rgbGeneral + rgbAppearance + rgbSplit)
                .groupBy { it.name }.values.map { sameName -> sameName.last() }
        }
        if (type == StudioElementType.MOTION_BLUR_EFFECT) {
            return (motionBlurGeneral + motionBlurAppearance + motionBlur)
                .groupBy { it.name }.values.map { sameName -> sameName.last() }
        }
        if (type == StudioElementType.BARS_SEGMENTS) {
            return (barsGeneral + barsAppearance + bars)
                .groupBy { it.name }.values.map { sameName -> sameName.last() }
        }
        if (type == StudioElementType.SHAPE) {
            return (shapeGeneral + shapeAppearance + shapeSpecific)
                .groupBy { it.name }.values.map { sameName -> sameName.last() }
        }
        val specific = when (type) {
            StudioElementType.BACKGROUND -> backgroundSpecific
            StudioElementType.IMAGE -> imageSpecific
            StudioElementType.RGB_SPLIT -> rgbSplit
            StudioElementType.BLUR_EFFECT -> emptyList()
            StudioElementType.BARS_SEGMENTS -> bars
            StudioElementType.PARTICLES -> particles
            StudioElementType.TEXT -> emptyList()
            StudioElementType.MOTION_BLUR_EFFECT -> motionBlur
            StudioElementType.SHAPE -> shapeSpecific
            StudioElementType.AUDIO_PROVIDER -> emptyList()
        }
        return (sharedGeneral + specific).groupBy { it.name }.values.map { sameName -> sameName.last() }
    }

    fun create(type: StudioElementType, id: String, tag: String = type.displayName): ElementDocument {
        val properties = forElement(type).associate { spec ->
            spec.name to when (spec.name) {
                "Tag" -> ElementValue.Text(tag)
                else -> spec.defaultValue
            }
        }
        return ElementDocument(id = id, type = type, tag = tag, properties = properties).normalized()
    }
}

internal fun ElementDocument.toJson(): JSONObject {
    val values = JSONObject()
    properties.forEach { (name, value) ->
        when (value) {
            is ElementValue.Bool -> values.put(name, value.value)
            is ElementValue.Number -> values.put(name, value.value)
            is ElementValue.Integer -> values.put(name, value.value)
            is ElementValue.Text -> values.put(name, value.value)
            is ElementValue.Vector2 -> values.put(name, JSONObject().put("x", value.x).put("y", value.y))
        }
    }
    val mappings = JSONObject()
    measurements.forEach { (name, mapping) ->
        val m = mapping.validated()
        mappings.put(name, JSONObject()
            .put("source", m.source).put("inputMin", m.inputMin).put("inputMax", m.inputMax)
            .put("outputMin", m.outputMin).put("outputMax", m.outputMax)
            .put("multiplier", m.multiplier).put("offset", m.offset).put("smoothing", m.smoothing)
            .put("responseCurve", m.responseCurve).put("invert", m.invert).put("enabled", m.enabled))
    }
    return JSONObject().put("id", id).put("type", type.name).put("tag", tag)
        .put("visible", visible).put("locked", locked).put("properties", values).put("measurements", mappings)
}

internal fun elementDocumentFromJson(json: JSONObject): ElementDocument? {
    val id = json.optString("id", "").takeIf { it.isNotBlank() } ?: return null
    val type = runCatching { StudioElementType.valueOf(json.optString("type")) }.getOrNull() ?: return null
    val raw = json.optJSONObject("properties") ?: JSONObject()
    val properties = LinkedHashMap<String, ElementValue>()
    ElementPropertyCatalog.forElement(type).forEach { spec ->
        if (!raw.has(spec.name)) return@forEach
        val value: ElementValue? = when (spec.type) {
            ElementValueType.BOOLEAN -> if (raw.has(spec.name)) ElementValue.Bool(raw.optBoolean(spec.name, (spec.defaultValue as ElementValue.Bool).value)) else null
            ElementValueType.NUMBER -> ElementValue.Number(raw.optDouble(spec.name, Double.NaN))
            ElementValueType.INTEGER, ElementValueType.COLOR -> ElementValue.Integer(raw.optLong(spec.name, Long.MIN_VALUE))
            ElementValueType.TEXT, ElementValueType.ENUM -> ElementValue.Text(raw.optString(spec.name, ""))
            ElementValueType.VECTOR2 -> raw.optJSONObject(spec.name)?.let { ElementValue.Vector2(it.optDouble("x", 0.0), it.optDouble("y", 0.0)) }
        }
        value?.let { spec.validate(it)?.let { safe -> properties[spec.name] = safe } }
    }
    val rawMappings = json.optJSONObject("measurements") ?: JSONObject()
    val mappings = LinkedHashMap<String, MeasurementMapping>()
    val keys = rawMappings.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val m = rawMappings.optJSONObject(key) ?: continue
        mappings[key] = MeasurementMapping(
            source = m.optString("source", "Overall Audio Level"),
            inputMin = m.optDouble("inputMin", 0.0).toFloat(),
            inputMax = m.optDouble("inputMax", 1.0).toFloat(),
            outputMin = m.optDouble("outputMin", 0.0).toFloat(),
            outputMax = m.optDouble("outputMax", 1.0).toFloat(),
            multiplier = m.optDouble("multiplier", 1.0).toFloat(),
            offset = m.optDouble("offset", 0.0).toFloat(),
            smoothing = m.optDouble("smoothing", 0.0).toFloat(),
            responseCurve = m.optString("responseCurve", "LINEAR"),
            invert = m.optBoolean("invert", false),
            enabled = m.optBoolean("enabled", false)
        ).validated()
    }
    return ElementDocument(
        id = id, type = type, tag = json.optString("tag", type.displayName),
        visible = json.optBoolean("visible", true), locked = json.optBoolean("locked", false),
        properties = properties, measurements = mappings
    ).normalized()
}
