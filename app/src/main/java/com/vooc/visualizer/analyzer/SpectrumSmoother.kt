package com.vooc.visualizer.analyzer

import kotlin.math.exp

internal fun smoothingCoeff(ms: Float, dtSec: Float): Float =
    if (ms <= 0f) 0f else exp(-dtSec * 1000f / ms)

/**
 * Temporal smoothing of a spectrum.
 * - asymmetric: separate attack (rising) and release (falling) time constants.
 * - otherwise: S[n] = alpha*S[n-1] + (1-alpha)*|FFT[n]|.
 */
class SpectrumSmoother(bins: Int, private var config: SmoothingConfig) {
    private val state = FloatArray(bins)
    private var primed = false

    fun setConfig(config: SmoothingConfig) { this.config = config }

    fun reset() {
        state.fill(0f)
        primed = false
    }

    fun update(input: FloatArray, dtSec: Float, out: FloatArray) {
        val n = state.size
        if (!primed) {
            for (i in 0 until n) { state[i] = input[i]; out[i] = input[i] }
            primed = true
            return
        }
        if (config.asymmetric) {
            val ca = smoothingCoeff(config.attackMs, dtSec)
            val cr = smoothingCoeff(config.releaseMs, dtSec)
            for (i in 0 until n) {
                val x = input[i]
                val c = if (x > state[i]) ca else cr
                state[i] = c * state[i] + (1f - c) * x
                out[i] = state[i]
            }
        } else {
            val a = config.temporal
            for (i in 0 until n) {
                state[i] = a * state[i] + (1f - a) * input[i]
                out[i] = state[i]
            }
        }
    }
}
