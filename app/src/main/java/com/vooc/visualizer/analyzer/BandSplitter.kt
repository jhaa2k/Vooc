package com.vooc.visualizer.analyzer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Frequency-band level extraction from an amplitude spectrum.
 * Per band: gain, AGC, independent attack/release and independent smoothing.
 * Slope: BRICK_WALL (hard edges) or GENTLE (quarter-octave raised-cosine skirts).
 */
class BandSplitter(private val sampleRate: Int, private val fftSize: Int, private val cfg: BandConfig) {
    val count: Int = cfg.bands.size
    val labels: List<String> = cfg.bands.map { it.label }

    private val bins = fftSize / 2
    private val masks: Array<FloatArray> = Array(count) { buildMask(cfg.bands[it]) }
    private val starts = IntArray(count) { b -> masks[b].indexOfFirst { it > 0f }.coerceAtLeast(1) }
    private val ends = IntArray(count) { b -> masks[b].indexOfLast { it > 0f }.coerceAtLeast(1) }
    private val envelope = FloatArray(count)
    private val smooth = FloatArray(count)
    private val agcPeak = FloatArray(count) { 0.25f }
    private var primed = false

    fun reset() {
        envelope.fill(0f)
        smooth.fill(0f)
        agcPeak.fill(0.25f)
        primed = false
    }

    private fun buildMask(band: BandSpec): FloatArray {
        val mask = FloatArray(bins)
        val binHz = sampleRate.toFloat() / fftSize
        var sum = 0f
        for (k in 1 until bins) {
            val f = k * binHz
            val w = when {
                f >= band.lowHz && f <= band.highHz -> 1f
                cfg.slope == BandSlope.BRICK_WALL -> 0f
                else -> {
                    val d = if (f < band.lowHz) log2(band.lowHz / f) else log2(f / band.highHz)
                    if (d >= GENTLE_OCTAVES) 0f else (0.5 + 0.5 * cos(PI * d / GENTLE_OCTAVES)).toFloat()
                }
            }
            mask[k] = w
            sum += w
        }
        if (sum <= 0f) {
            val center = sqrt(band.lowHz * band.highHz)
            mask[(center / binHz).roundToInt().coerceIn(1, bins - 1)] = 1f
        }
        return mask
    }

    /** [magnitude] is the raw amplitude spectrum. [out] receives one level (0..1) per band. */
    fun process(magnitude: FloatArray, dtSec: Float, out: FloatArray) {
        require(out.size >= count) { "Output must hold $count bands" }
        val agcDecay = exp(-dtSec / AGC_RELEASE_SEC)
        for (b in 0 until count) {
            val spec = cfg.bands[b]
            val mask = masks[b]
            var s = 0.0
            var w = 0.0
            for (k in starts[b]..min(ends[b], magnitude.size - 1)) {
                val m = mask[k]
                if (m > 0f) {
                    s += (m * magnitude[k] * magnitude[k]).toDouble()
                    w += m.toDouble()
                }
            }
            val rms = if (w > 0.0) sqrt(s / w).toFloat() else 0f
            val db = 20f * log10(max(rms, 1e-6f))
            var v = ((db - cfg.dbFloor) / -cfg.dbFloor).coerceIn(0f, 1f)
            if (spec.agc) {
                agcPeak[b] = max(v, agcPeak[b] * agcDecay)
                v = (v / max(agcPeak[b], 0.2f)).coerceIn(0f, 1f)
            }
            v *= spec.gain
            if (!primed) {
                envelope[b] = v
                smooth[b] = v
            } else {
                val c = smoothingCoeff(if (v > envelope[b]) spec.attackMs else spec.releaseMs, dtSec)
                envelope[b] = c * envelope[b] + (1f - c) * v
                smooth[b] = spec.smoothing * smooth[b] + (1f - spec.smoothing) * envelope[b]
            }
            out[b] = smooth[b].coerceIn(0f, 1f)
        }
        primed = true
    }

    companion object {
        private const val GENTLE_OCTAVES = 0.25f
        private const val AGC_RELEASE_SEC = 4f

        /** Routes several bands to one layer value. */
        fun combine(levels: FloatArray, indices: IntArray, mode: BandCombine, weights: FloatArray? = null): Float {
            if (indices.isEmpty()) return 0f
            return when (mode) {
                BandCombine.MAX -> indices.maxOf { levels[it] }
                BandCombine.AVERAGE -> indices.sumOf { levels[it].toDouble() }.toFloat() / indices.size
                BandCombine.WEIGHTED -> {
                    var acc = 0f
                    var wSum = 0f
                    for ((n, i) in indices.withIndex()) {
                        val w = weights?.getOrNull(n) ?: 1f
                        acc += levels[i] * w
                        wSum += w
                    }
                    if (wSum > 0f) acc / wSum else 0f
                }
            }
        }
    }
}

class Biquad(private var b0: Double, private var b1: Double, private var b2: Double, private var a1: Double, private var a2: Double) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0.0; z2 = 0.0 }

    companion object {
        fun lowPass2(fs: Int, fc: Float, q: Double): Biquad {
            val w0 = 2.0 * PI * fc / fs
            val alpha = sin(w0) / (2.0 * q)
            val c = cos(w0)
            val a0 = 1.0 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2.0 * c / a0, (1 - alpha) / a0)
        }

        fun highPass2(fs: Int, fc: Float, q: Double): Biquad {
            val w0 = 2.0 * PI * fc / fs
            val alpha = sin(w0) / (2.0 * q)
            val c = cos(w0)
            val a0 = 1.0 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2.0 * c / a0, (1 - alpha) / a0)
        }

        fun lowPass1(fs: Int, fc: Float): Biquad {
            val k = tan(PI * fc / fs)
            val n = 1.0 + k
            return Biquad(k / n, k / n, 0.0, (k - 1.0) / n, 0.0)
        }

        fun highPass1(fs: Int, fc: Float): Biquad {
            val k = tan(PI * fc / fs)
            val n = 1.0 + k
            return Biquad(1.0 / n, -1.0 / n, 0.0, (k - 1.0) / n, 0.0)
        }
    }
}

/** Time-domain Linkwitz-Riley crossover (12 / 24 / 48 dB per octave) splitting a signal into low and high. */
class LinkwitzRiley(sampleRate: Int, frequencyHz: Float, order: CrossoverOrder) {
    private val low: List<Biquad>
    private val high: List<Biquad>
    private val invertHigh: Boolean = order == CrossoverOrder.LR12

    init {
        val fc = frequencyHz.coerceIn(10f, sampleRate * 0.45f)
        when (order) {
            CrossoverOrder.LR12 -> {
                low = listOf(Biquad.lowPass1(sampleRate, fc), Biquad.lowPass1(sampleRate, fc))
                high = listOf(Biquad.highPass1(sampleRate, fc), Biquad.highPass1(sampleRate, fc))
            }
            CrossoverOrder.LR24 -> {
                val q = 0.7071067811865476
                low = listOf(Biquad.lowPass2(sampleRate, fc, q), Biquad.lowPass2(sampleRate, fc, q))
                high = listOf(Biquad.highPass2(sampleRate, fc, q), Biquad.highPass2(sampleRate, fc, q))
            }
            CrossoverOrder.LR48 -> {
                val qs = doubleArrayOf(0.5411961001461969, 1.3065629648763766, 0.5411961001461969, 1.3065629648763766)
                low = qs.map { Biquad.lowPass2(sampleRate, fc, it) }
                high = qs.map { Biquad.highPass2(sampleRate, fc, it) }
            }
        }
    }

    /** Writes the low-pass output to out[0] and the high-pass output to out[1]. */
    fun process(x: Float, out: FloatArray) {
        var l = x.toDouble()
        for (f in low) l = f.process(l)
        var h = x.toDouble()
        for (f in high) h = f.process(h)
        out[0] = l.toFloat()
        out[1] = (if (invertHigh) -h else h).toFloat()
    }

    fun reset() {
        low.forEach { it.reset() }
        high.forEach { it.reset() }
    }
}

/** Splits a signal into edges.size + 1 bands by cascading crossovers. */
class CrossoverBank(sampleRate: Int, edgesHz: FloatArray, order: CrossoverOrder) {
    private val stages = edgesHz.map { LinkwitzRiley(sampleRate, it, order) }
    private val tmp = FloatArray(2)
    val bandCount: Int = edgesHz.size + 1

    fun process(x: Float, out: FloatArray) {
        require(out.size >= bandCount) { "Output must hold $bandCount bands" }
        var rest = x
        for (i in stages.indices) {
            stages[i].process(rest, tmp)
            out[i] = tmp[0]
            rest = tmp[1]
        }
        out[stages.size] = rest
    }

    fun reset() = stages.forEach { it.reset() }
}
