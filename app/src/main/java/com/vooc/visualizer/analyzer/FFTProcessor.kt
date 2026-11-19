package com.vooc.visualizer.analyzer

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Output of one FFT. All arrays have size/2 entries. */
class FftResult(bins: Int) {
    /** Amplitude spectrum before normalization (a unit sine reads 1.0). Used by bands and beat detection. */
    val raw = FloatArray(bins)
    /** Amplitude spectrum after the selected [Normalization]. */
    val magnitude = FloatArray(bins)
    val phase = FloatArray(bins)
    /** 20*log10 of [magnitude]. */
    val db = FloatArray(bins)
}

/** Radix-2 FFT with precomputed twiddles and bit-reversal, selectable window. Not thread-safe. */
class FFTProcessor(val size: Int, windowFunction: WindowFunction = WindowFunction.HANN) {
    init {
        require(size >= 8 && size and (size - 1) == 0) { "FFT size must be a power of two >= 8" }
    }

    val bins: Int = size / 2
    private val win: FloatArray = Windows.create(windowFunction, size)
    private val coherentGain: Double = Windows.coherentGain(win)
    private val cosT = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinT = DoubleArray(size / 2) { -sin(2.0 * PI * it / size) }
    private val rev = IntArray(size).also { r ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) r[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)

    fun binWidthHz(sampleRate: Int): Float = sampleRate.toFloat() / size

    fun process(
        input: FloatArray,
        offset: Int,
        out: FftResult,
        normalization: Normalization = Normalization.NONE
    ) {
        require(offset >= 0 && input.size - offset >= size) { "Input must contain at least $size samples" }
        for (i in 0 until size) {
            val r = rev[i]
            re[r] = (input[offset + i] * win[i]).toDouble()
            im[r] = 0.0
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            var base = 0
            while (base < size) {
                for (j in 0 until half) {
                    val wr = cosT[j * step]
                    val wi = sinT[j * step]
                    val even = base + j
                    val odd = even + half
                    val tr = wr * re[odd] - wi * im[odd]
                    val ti = wr * im[odd] + wi * re[odd]
                    re[odd] = re[even] - tr
                    im[odd] = im[even] - ti
                    re[even] += tr
                    im[even] += ti
                }
                base += length
            }
            length = length shl 1
        }
        val scale = 2.0 / (size * coherentGain)
        var peak = 0f
        var sumSq = 0.0
        for (k in 0 until bins) {
            val dc = if (k == 0) 0.5 else 1.0
            val m = (sqrt(re[k] * re[k] + im[k] * im[k]) * scale * dc).toFloat()
            out.raw[k] = m
            out.phase[k] = atan2(im[k], re[k]).toFloat()
            if (m > peak) peak = m
            sumSq += (m * m).toDouble()
        }
        val divisor = when (normalization) {
            Normalization.NONE -> 1f
            Normalization.PEAK -> if (peak > 1e-9f) peak else 1f
            Normalization.RMS -> sqrt(sumSq / bins).toFloat().let { if (it > 1e-9f) it else 1f }
        }
        for (k in 0 until bins) {
            val m = out.raw[k] / divisor
            out.magnitude[k] = m
            out.db[k] = 20f * log10(max(m, 1e-9f))
        }
    }
}
