package com.vooc.visualizer.studio

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** Audio measurements shared by the live preview and offline export renderers. */
data class AudioReactivitySources(
    val overall: Float = 0f,
    val bass: Float = 0f,
    val mid: Float = 0f,
    val treble: Float = 0f,
    val beat: Float = 0f,
    val peak: Float = 0f,
    val rms: Float = 0f,
    val waveform: Float = 0f,
    val spectrumAverage: Float = 0f,
    val spectrumMaximum: Float = 0f,
    val left: Float = 0f,
    val right: Float = 0f
) {
    fun value(source: String): Float = when (source) {
        "Bass" -> bass
        "Mid" -> mid
        "Treble" -> treble
        "Beat" -> beat
        "Peak" -> peak
        "RMS" -> rms
        "Waveform" -> waveform
        "Spectrum Average" -> spectrumAverage
        "Spectrum Maximum" -> spectrumMaximum
        "Left Channel" -> left
        "Right Channel" -> right
        else -> overall
    }.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
}

/**
 * Stateless mapping stage. Temporal attack/release smoothing is applied by the
 * renderers' frame runtimes; this function guarantees identical mapping in preview/export.
 */
object AudioReactivityEngine {
    fun map(input: Float, config: MeasurementMapping, curve: String = config.responseCurve,
            threshold: Float = 0f, clampOutput: Boolean = true): Float {
        val safe = config.validated()
        val value = input.takeIf { it.isFinite() } ?: 0f
        if (value < threshold.coerceIn(0f, 1f)) return safe.outputMin
        val range = safe.inputMax - safe.inputMin
        if (range <= 0f) return safe.outputMin
        var normalized = ((value - safe.inputMin) / range).coerceIn(0f, 1f)
        if (safe.invert) normalized = 1f - normalized
        normalized = when (curve.uppercase()) {
            "SQUARE" -> normalized * normalized
            "SQUARE ROOT", "SQUARE_ROOT", "SQRT" -> sqrt(normalized)
            "CUBIC" -> normalized * normalized * normalized
            "EXPONENTIAL" -> (exp(normalized * 4f) - 1f) / (exp(4f) - 1f)
            "LOGARITHMIC" -> ln(1f + normalized * 9f) / ln(10f)
            else -> normalized
        }
        val mapped = safe.outputMin + normalized * (safe.outputMax - safe.outputMin) * safe.multiplier + safe.offset
        return if (clampOutput) mapped.coerceIn(minOf(safe.outputMin, safe.outputMax), maxOf(safe.outputMin, safe.outputMax)) else mapped
    }

    fun config(properties: Map<String, ElementValue>): MeasurementMapping {
        fun number(name: String, default: Float): Float = when (val value = properties[name]) {
            is ElementValue.Number -> value.value.toFloat()
            is ElementValue.Integer -> value.value.toFloat()
            else -> default
        }
        fun text(name: String, default: String) = (properties[name] as? ElementValue.Text)?.value ?: default
        fun bool(name: String, default: Boolean) = (properties[name] as? ElementValue.Bool)?.value ?: default
        return MeasurementMapping(
            inputMin = number("Audio Reactivity Input Min", 0f),
            inputMax = number("Audio Reactivity Input Max", 1f),
            outputMin = number("Audio Reactivity Output Min", 0f),
            outputMax = number("Audio Reactivity Output Max", 1f),
            multiplier = number("Audio Reactivity Multiplier", 1f),
            offset = number("Audio Reactivity Offset", 0f),
            smoothing = number("Audio Reactivity Smoothing", 0f),
            responseCurve = text("Audio Reactivity Response Curve", "Linear").uppercase().replace(" ", "_"),
            invert = bool("Audio Reactivity Invert", false),
            enabled = bool("Audio Reactivity Enabled", false)
        ).validated()
    }

    fun applyTo(properties: Map<String, ElementValue>, sources: AudioReactivitySources): Float? {
        if ((properties["Audio Reactivity Enabled"] as? ElementValue.Bool)?.value != true) return null
        val source = (properties["Audio Reactivity Source"] as? ElementValue.Text)?.value ?: "Overall Audio Level"
        val curve = (properties["Audio Reactivity Response Curve"] as? ElementValue.Text)?.value ?: "Linear"
        val threshold = when (val v = properties["Audio Reactivity Threshold"]) {
            is ElementValue.Number -> v.value.toFloat()
            is ElementValue.Integer -> v.value.toFloat()
            else -> 0f
        }
        val clamp = (properties["Audio Reactivity Clamp Output"] as? ElementValue.Bool)?.value ?: true
        return map(sources.value(source), config(properties), curve, threshold, clamp)
    }
}


/** Per-layer temporal envelope; call once per rendered frame for stable attack/release. */
class AudioReactivityEnvelope {
    private var value = 0f
    private var primed = false

    fun reset() { value = 0f; primed = false }

    fun update(target: Float, deltaSeconds: Float, attackSeconds: Float, releaseSeconds: Float): Float {
        val safeTarget = target.takeIf { it.isFinite() } ?: 0f
        val dt = (deltaSeconds.takeIf { it.isFinite() } ?: (1f / 60f)).coerceIn(0.001f, 0.25f)
        val duration = if (safeTarget > value) attackSeconds else releaseSeconds
        val safeDuration = duration.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val alpha = if (!primed || safeDuration <= 0f) 1f else 1f - exp(-dt / safeDuration)
        value += (safeTarget - value) * alpha.coerceIn(0f, 1f)
        primed = true
        return value
    }
}

/** Builds the same compact measurement snapshot for preview and offline export. */
fun audioReactivitySources(
    samples: FloatArray?,
    overall: Float,
    beat: Float,
    peak: Float = overall,
    rms: Float = overall,
    waveform: Float = overall,
    left: Float = overall,
    right: Float = overall
): AudioReactivitySources {
    val data = samples?.takeIf { it.isNotEmpty() }
    fun average(from: Int, until: Int): Float {
        if (data == null) return overall.coerceIn(0f, 1f)
        val start = from.coerceIn(0, data.size - 1)
        val end = until.coerceIn(start + 1, data.size)
        var sum = 0f
        for (index in start until end) {
            val sample = data[index]
            if (sample.isFinite()) sum += sample.coerceIn(0f, 1f)
        }
        return (sum / (end - start)).coerceIn(0f, 1f)
    }
    val size = data?.size ?: 0
    val fifth = (size / 5).coerceAtLeast(1)
    var spectrumSum = 0f
    var spectrumMaximum = 0f
    if (data != null) {
        for (sample in data) {
            val safeSample = if (sample.isFinite()) sample.coerceIn(0f, 1f) else 0f
            spectrumSum += safeSample
            if (safeSample > spectrumMaximum) spectrumMaximum = safeSample
        }
    }
    val fallback = overall.coerceIn(0f, 1f)
    return AudioReactivitySources(
        overall = fallback,
        bass = average(0, fifth),
        mid = average(size / 2 - fifth / 2, size / 2 + fifth / 2),
        treble = average(size - fifth, size),
        beat = beat.coerceIn(0f, 1f),
        peak = peak.coerceIn(0f, 1f),
        rms = rms.coerceIn(0f, 1f),
        waveform = waveform.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: fallback,
        spectrumAverage = if (size == 0) fallback else spectrumSum / size,
        spectrumMaximum = if (size == 0) fallback else spectrumMaximum,
        left = left.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: fallback,
        right = right.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: fallback
    )
}

/** Per-layer state. Create one runtime per element and reset it when the track changes. */
class AudioReactivityRuntime {
    private val envelope = AudioReactivityEnvelope()

    fun reset() = envelope.reset()

    fun update(properties: Map<String, ElementValue>, sources: AudioReactivitySources, deltaSeconds: Float): Float? {
        val mapped = AudioReactivityEngine.applyTo(properties, sources) ?: run {
            envelope.reset()
            return null
        }
        fun number(name: String, fallback: Float): Float = when (val value = properties[name]) {
            is ElementValue.Number -> value.value.toFloat()
            is ElementValue.Integer -> value.value.toFloat()
            else -> fallback
        }
        val smoothing = number("Audio Reactivity Smoothing", 0f).coerceIn(0f, 1f)
        val attack = maxOf(number("Audio Reactivity Attack", 0.12f), smoothing * 0.5f)
        val release = maxOf(number("Audio Reactivity Release", 0.72f), smoothing)
        return envelope.update(mapped, deltaSeconds, attack, release)
    }
}

/** Interprets the mapped signal consistently in both renderers. */
data class AudioReactivityTransform(
    val xOffset: Float = 0f,
    val yOffset: Float = 0f,
    val scaleMultiplier: Float = 1f,
    val rotationOffset: Float = 0f,
    val opacityMultiplier: Float = 1f,
    val colorMultiplier: Float = 1f,
    val effectStrengthMultiplier: Float = 1f
)

fun audioReactivityTransform(properties: Map<String, ElementValue>, value: Float?): AudioReactivityTransform {
    if (value == null) return AudioReactivityTransform()
    val target = (properties["Audio Reactivity Apply To"] as? ElementValue.Text)?.value ?: "All Properties"
    val amount = value.takeIf(Float::isFinite) ?: 0f
    val all = target == "All Properties"
    return AudioReactivityTransform(
        xOffset = if (all || target == "Position") amount * 100f else 0f,
        yOffset = if (all || target == "Position") amount * 100f else 0f,
        scaleMultiplier = if (all || target == "Scale" || target == "Size") (1f + amount).coerceAtLeast(0f) else 1f,
        rotationOffset = if (all || target == "Rotation") amount else 0f,
        opacityMultiplier = if (all || target == "Opacity") amount.coerceIn(0f, 1f) else 1f,
        colorMultiplier = if (all || target == "Color") (0.25f + amount.coerceIn(0f, 1f) * 0.75f) else 1f,
        effectStrengthMultiplier = if (all || target == "Effect Strength") amount.coerceIn(0f, 4f) else 1f
    )
}

/** Scales RGB intensity without changing the source alpha channel. */
fun audioReactivityColor(color: Int, multiplier: Float): Int {
    val factor = multiplier.takeIf { it.isFinite() }?.coerceIn(0f, 4f) ?: 1f
    val alpha = color ushr 24 and 0xFF
    val red = ((color ushr 16 and 0xFF) * factor).roundToInt().coerceIn(0, 255)
    val green = ((color ushr 8 and 0xFF) * factor).roundToInt().coerceIn(0, 255)
    val blue = ((color and 0xFF) * factor).roundToInt().coerceIn(0, 255)
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
