package com.vooc.visualizer.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln

/** Radix-2 FFT with Hann window and normalized magnitude output. */
class FFTProcessor(private val size: Int = 2048) {
    init { require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" } }

    fun magnitudes(input: FloatArray): FloatArray {
        require(input.size >= size) { "Input must contain at least $size samples" }
        val real = DoubleArray(size)
        val imag = DoubleArray(size)
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            val reversed = Integer.reverse(i) ushr (32 - bits)
            val hann = 0.5 - 0.5 * cos(2.0 * PI * i / (size - 1))
            real[reversed] = input[i] * hann
        }
        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            var base = 0
            while (base < size) {
                for (j in 0 until length / 2) {
                    val wr = cos(angle * j)
                    val wi = kotlin.math.sin(angle * j)
                    val even = base + j
                    val odd = even + length / 2
                    val tr = wr * real[odd] - wi * imag[odd]
                    val ti = wr * imag[odd] + wi * real[odd]
                    real[odd] = real[even] - tr
                    imag[odd] = imag[even] - ti
                    real[even] += tr
                    imag[even] += ti
                }
                base += length
            }
            length = length shl 1
        }
        return FloatArray(size / 2) { i ->
            (hypot(real[i], imag[i]) / (size / 2)).toFloat().let { ln(1.0 + it * 20.0).toFloat() }
        }
    }
}
