package com.vooc.visualizer.studio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

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
            "SQUARE ROOT", "SQRT" -> sqrt(normalized)
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
