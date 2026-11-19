package com.vooc.visualizer.analyzer

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** Stateful per-element bar envelope and peak-hold processor shared by preview/export. */
class SpectrumBarsRuntime {
    private var envelope = FloatArray(0)
    private var peaks = FloatArray(0)
    private var peakTimers = FloatArray(0)

    data class Frame(val levels: FloatArray, val peaks: FloatArray)

    fun reset() {
        envelope = FloatArray(0)
        peaks = FloatArray(0)
        peakTimers = FloatArray(0)
    }

    fun update(
        input: FloatArray,
        count: Int,
        deltaSeconds: Float,
        damping: Float,
        peakHold: Float,
        peakFallSpeed: Float,
        logarithmicInput: Boolean = false
    ): Frame {
        val n = count.coerceIn(1, 512)
        if (envelope.size != n) {
            envelope = FloatArray(n)
            peaks = FloatArray(n)
            peakTimers = FloatArray(n)
        }
        val source = input.takeIf { it.isNotEmpty() } ?: FloatArray(1)
        val dt = deltaSeconds.takeIf { it.isFinite() }?.coerceIn(0.001f, 0.1f) ?: (1f / 60f)
        val smooth = damping.coerceIn(0f, 1f)
        val tau = 0.008f + smooth * 0.32f
        val coefficient = exp(-dt / tau)
        val holdSeconds = 0.08f + peakHold.coerceIn(0f, 1f) * 0.9f
        val fallPerSecond = 0.15f + peakFallSpeed.coerceIn(0f, 1f) * 3.5f
        for (i in 0 until n) {
            val t = if (n == 1) 0.5f else i.toFloat() / (n - 1)
            val sourcePosition = if (logarithmicInput && source.size > 1) {
                (source.size.toFloat().pow(t) - 1f).coerceIn(0f, source.lastIndex.toFloat())
            } else t * source.lastIndex
            val lo = sourcePosition.toInt().coerceIn(0, source.lastIndex)
            val hi = (lo + 1).coerceAtMost(source.lastIndex)
            val fraction = sourcePosition - lo
            val target = (source[lo] + (source[hi] - source[lo]) * fraction).coerceIn(0f, 1f)
            envelope[i] = if (i == 0 && envelope.all { it == 0f }) target
                else coefficient * envelope[i] + (1f - coefficient) * target
            if (envelope[i] >= peaks[i]) {
                peaks[i] = envelope[i]
                peakTimers[i] = holdSeconds
            } else if (peakTimers[i] > 0f) {
                peakTimers[i] = (peakTimers[i] - dt).coerceAtLeast(0f)
            } else {
                peaks[i] = (peaks[i] - fallPerSecond * dt).coerceAtLeast(envelope[i])
            }
        }
        return Frame(envelope.copyOf(), peaks.copyOf())
    }
}
