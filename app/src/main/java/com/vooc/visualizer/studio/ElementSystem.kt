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
    RGB_SPLIT("Rgb Split"),
    BLUR_EFFECT("Blur Effect"),
    BARS_SEGMENTS("Bars/Segments"),
    PARTICLES("Particles"),
    TEXT("Text"),
    MOTION_BLUR_EFFECT("Motion Blur Effect")
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
        return (outputMin + curved * (outputMax - outputMin)) * multiplier + offset
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

    private val provider = listOf(
        text("Sample Provider", "General", "Spectrum", listOf("Spectrum")),
        integer("Sample Out Count", "Spectrum", 128, 1.0, 2048.0),
        bool("Mirror Samples", "Spectrum", false),
        bool("Repeat Samples", "Spectrum", false),
        integer("Star And End Gap", "Spectrum", 0, 0.0, 2048.0),
        number("Smooth", "Spectrum", 0.35, 0.0, 1.0),
        number("Pre Smooth", "Spectrum", 0.0, 0.0, 1.0),
        integer("Filter Radius", "Spectrum", 0, 0.0, 64.0),
        number("Filter Strength", "Spectrum", 0.0, 0.0, 1.0),
        bool("AWeight", "Spectrum", false),
        number("Output Multiplier", "Spectrum", 1.0, 0.0, 32.0),
        number("Lower Hz", "Spectrum Hz", 30.0, 1.0, 96000.0),
        number("Higher Hz", "Spectrum Hz", 16000.0, 2.0, 96000.0),
        number("Hz Linear Factor", "Spectrum Hz", 0.0, -1.0, 1.0),
        number("Freq Shift", "Spectrum Hz", 0.0, -24000.0, 24000.0),
        number("Beat Smooth", "Beat", 0.35, 0.0, 1.0),
        integer("Beat Range Bar First", "Beat", 0, 0.0, 2048.0),
        integer("Beat Range Bar Last", "Beat", 127, 0.0, 2048.0),
        number("Beat Range Value Lower", "Beat", 0.08, 0.0, 1.0),
        number("Beat Range Value Higher", "Beat", 0.8, 0.0, 1.0),
        text("Fft Size", "Performance", "2048", listOf("256", "512", "1024", "2048", "4096", "8192"))
    )

    private val imageSpecific = listOf(
        text("Custom Image", "Image", ""),
        number("Measure Animation Speed", "Image", 0.0),
        bool("Keep Aspect Ratio And Crop To Fit", "Image", false),
        bool("Keep Aspect Ratio", "Image", true),
        bool("Blurred Border", "Image", false),
        text("Shape", "Image", "Rectangle", listOf("Rectangle", "Square", "Circle", "Ellipse")),
        text("Mask Image", "Image", ""),
        text("Mask Mode", "Image", "Alpha Mask", listOf("Alpha Mask", "Luminance Mask", "Inverted Mask", "Normal Mask")),
        text("Color", "Color", "#FFFFFFFF"),
        text("Color To", "Color", "#FFFFFFFF"),
        number("Measure Color Blend", "Color", 0.0, 0.0, 1.0),
        number("Saturation", "Color", 1.0, 0.0, 2.0),
        bool("Color Key Enabled", "Color Key", false),
        bool("Auto Detect Color Key", "Color Key", false),
        text("Color Key", "Color Key", "#FF000000"),
        number("Transparency Strength", "Color Key", 1.0, 0.0, 1.0),
        number("Opacity Strength", "Color Key", 1.0, 0.0, 1.0),
        bool("Blur Enabled", "Blur", false),
        number("Blur Divider", "Blur", 2.0, 1.0, 32.0),
        number("Blur Radius", "Blur", 8.0, 0.0, 128.0),
        number("Generated Album Art Hint Shift Hue", "Generated Album Art", 0.0, -360.0, 360.0),
        bool("Generated Album Art Hint No Text", "Generated Album Art", false),
        bool("Generated Album Art Hint Force Gen", "Generated Album Art", false),
        text("Generated Album Art Color", "Generated Album Art", "#FFFFFFFF")
    )

    private val rgbSplit = listOf(
        text("Blend Mode Content", "Appearance", "Normal"),
        text("Color", "Appearance", "#FFFFFFFF"),
        text("Target Image", "Appearance", ""),
        bool("Show Unblurred Content", "Appearance", false),
        bool("Show Unblurred Content Under", "Appearance", false),
        number("Split Multiplier", "Split Effect", 1.0, 0.0, 32.0),
        number("Split Amount", "Split Effect", 4.0, 0.0, 256.0),
        text("Split Color 0", "Split Effect", "#FFFF0000"),
        text("Split Color 1", "Split Effect", "#FF00FF00"),
        text("Split Color 2", "Split Effect", "#FF0000FF")
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
        text("Measure Rot (Measure What)", "General", "Nothing", MeasureWhatEngine.options)
    )

    private val blurAppearance = listOf(
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

    private val bars = listOf(
        integer("Audio Provider Index", "Bars", 0, 0.0, 31.0),
        integer("Bar Count", "Bars", 64, 1.0, 2048.0),
        number("Min Height", "Bars", 0.0, 0.0, 10000.0),
        number("Max Height", "Bars", 300.0, 0.0, 10000.0),
        bool("Flip Input", "Bars", false),
        bool("Mirror", "Bars", false),
        bool("Alternating Flip", "Behavior", false),
        number("Reaction Delay", "Behavior", 0.0, 0.0, 5000.0),
        number("Accumulated Delay", "Behavior", 0.0, 0.0, 5000.0),
        number("Softness", "Behavior", 0.0, 0.0, 1.0)
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
        text("Measure Rot (Measure What)", "General", "Nothing", MeasureWhatEngine.options)
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
        text("Color Over Life", "Appearance", "#FFFFFFFF"),
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
        number("Direction Random", "Particles", 0.0, 0.0, 360.0)
    )

    private val textElement = listOf(
        text("Text", "Appearance", "VOOC"),
        text("Color", "Appearance", "#FFFFFFFF"),
        number("Font Size", "Appearance", 48.0, 1.0, 512.0),
        text("Type Face", "Appearance", "Poppins")
    )

    private val motionBlur = listOf(
        text("Blend Mode Content", "Appearance", "Normal"),
        text("Color", "Appearance", "#FFFFFFFF"),
        text("Target Image", "Appearance", ""),
        bool("Show Unblurred Content", "Appearance", false),
        bool("Show Unblurred Content Under", "Appearance", false),
        number("Blur Amount Multiplier", "Motion Blur", 1.0, 0.0, 64.0),
        text("Motion Source", "Motion Blur", "Transform Movement", listOf("Transform Movement", "Measured Movement", "Target Motion")),
        text("Relative Motion Mode", "Motion Blur", "Composition", listOf("Relative", "Absolute", "Target Relative"))
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
        val specific = when (type) {
            StudioElementType.IMAGE -> imageSpecific
            StudioElementType.RGB_SPLIT -> rgbSplit
            StudioElementType.BLUR_EFFECT -> emptyList()
            StudioElementType.BARS_SEGMENTS -> bars
            StudioElementType.PARTICLES -> particles
            StudioElementType.TEXT -> textElement
            StudioElementType.MOTION_BLUR_EFFECT -> motionBlur
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
