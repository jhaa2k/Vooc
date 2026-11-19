package com.vooc.visualizer.analyzer

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Maps FFT bins to N display bars using linear / logarithmic / custom frequency spacing. */
object SpectrumMapper {
    fun map(
        spectrum: FloatArray,
        sampleRate: Int,
        fftSize: Int,
        bars: Int,
        lowHz: Float,
        highHz: Float,
        scale: FrequencyScale,
        interpolation: Interpolation,
        out: FloatArray,
        customCurve: ((Float) -> Float)? = null
    ) {
        require(bars > 0 && out.size >= bars) { "Output must hold $bars bars" }
        val nyquist = sampleRate / 2f
        val lo = lowHz.coerceIn(1f, nyquist - 1f)
        val hi = highHz.coerceIn(lo + 1f, nyquist)
        val binHz = sampleRate.toFloat() / fftSize

        fun freqAt(t: Float): Float {
            val tt = if (scale == FrequencyScale.CUSTOM) (customCurve?.invoke(t) ?: t).coerceIn(0f, 1f) else t
            return if (scale == FrequencyScale.LOGARITHMIC) lo * (hi / lo).pow(tt) else lo + (hi - lo) * tt
        }

        for (i in 0 until bars) {
            val b0 = freqAt(i.toFloat() / bars) / binHz
            val b1 = freqAt((i + 1f) / bars) / binHz
            val bc = freqAt((i + 0.5f) / bars) / binHz
            out[i] = if (b1 - b0 > 1f) {
                val s = ceil(b0).toInt().coerceIn(0, spectrum.lastIndex)
                val e = floor(b1).toInt().coerceIn(s, spectrum.lastIndex)
                var m = 0f
                for (k in s..e) m = max(m, spectrum[k])
                m
            } else {
                sample(spectrum, bc, interpolation)
            }
        }
    }

    fun sample(s: FloatArray, position: Float, interpolation: Interpolation): Float {
        val p = position.coerceIn(0f, s.lastIndex.toFloat())
        val i = floor(p).toInt()
        val f = p - i
        return when (interpolation) {
            Interpolation.NONE -> s[p.roundToInt().coerceIn(0, s.lastIndex)]
            Interpolation.LINEAR -> s[i] * (1f - f) + s[(i + 1).coerceAtMost(s.lastIndex)] * f
            Interpolation.CUBIC -> {
                val y0 = s[(i - 1).coerceAtLeast(0)]
                val y1 = s[i]
                val y2 = s[(i + 1).coerceAtMost(s.lastIndex)]
                val y3 = s[(i + 2).coerceAtMost(s.lastIndex)]
                val v = 0.5f * ((2f * y1) + (-y0 + y2) * f +
                    (2f * y0 - 5f * y1 + 4f * y2 - y3) * f * f +
                    (-y0 + 3f * y1 - 3f * y2 + y3) * f * f * f)
                max(v, 0f)
            }
        }
    }
}
