package com.vooc.visualizer.analyzer

import kotlin.math.PI
import kotlin.math.cos

object Windows {
    fun create(type: WindowFunction, n: Int): FloatArray {
        require(n >= 2) { "Window length must be >= 2" }
        val w = FloatArray(n)
        val d = (n - 1).toDouble()
        for (i in 0 until n) {
            val x = 2.0 * PI * i / d
            val v = when (type) {
                WindowFunction.HANN -> 0.5 - 0.5 * cos(x)
                WindowFunction.HAMMING -> 0.54 - 0.46 * cos(x)
                WindowFunction.BLACKMAN -> 0.42 - 0.5 * cos(x) + 0.08 * cos(2.0 * x)
                WindowFunction.RECTANGULAR -> 1.0
                WindowFunction.BLACKMAN_HARRIS ->
                    0.35875 - 0.48829 * cos(x) + 0.14128 * cos(2.0 * x) - 0.01168 * cos(3.0 * x)
            }
            w[i] = v.toFloat()
        }
        return w
    }

    /** Mean of the window; dividing the spectrum by it makes a unit sine read as amplitude 1.0. */
    fun coherentGain(w: FloatArray): Double {
        var s = 0.0
        for (v in w) s += v
        return s / w.size
    }
}
