package com.vooc.visualizer.analyzer

import kotlin.math.exp
import kotlin.math.pow

/** Stateful per-element bar envelope and peak-hold processor shared by preview/export. */
class SpectrumBarsRuntime {
    private var envelope = FloatArray(0)
    private var peaks = FloatArray(0)
    private var peakTimers = FloatArray(0)
    private var primed = false
    private var clockSeconds = 0f
    private val history = java.util.ArrayDeque<Pair<Float, FloatArray>>()

    data class Frame(val levels: FloatArray, val peaks: FloatArray)

    fun reset() {
        envelope = FloatArray(0)
        peaks = FloatArray(0)
        peakTimers = FloatArray(0)
        primed = false
        clockSeconds = 0f
        history.clear()
    }

    fun update(
        input: FloatArray,
        count: Int,
        deltaSeconds: Float,
        damping: Float,
        peakHold: Float,
        peakFallSpeed: Float,
        logarithmicInput: Boolean = false,
        reactionDelayMs: Float = 0f,
        accumulatedDelayMs: Float = 0f
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
        val wasPrimed = primed
        clockSeconds += dt
        history.addLast(clockSeconds to source.copyOf())
        while (history.size > 360 || (history.isNotEmpty() && clockSeconds - history.first.first > 8f)) history.removeFirst()
        val reactionSeconds = reactionDelayMs.coerceIn(0f, 5000f) / 1000f
        val accumulatedSeconds = accumulatedDelayMs.coerceIn(0f, 5000f) / 1000f
        val holdSeconds = 0.08f + peakHold.coerceIn(0f, 1f) * 0.9f
        val fallPerSecond = 0.15f + peakFallSpeed.coerceIn(0f, 1f) * 3.5f
        for (i in 0 until n) {
            val t = if (n == 1) 0.5f else i.toFloat() / (n - 1)
            val delaySeconds = reactionSeconds + accumulatedSeconds * t
            val targetTime = clockSeconds - delaySeconds
            val delayedSource = if (delaySeconds <= 0f || history.size <= 1) source else {
                history.firstOrNull { it.first >= targetTime }?.second
                    ?: history.lastOrNull { it.first <= targetTime }?.second
                    ?: history.first.second
            }
            val sourcePosition = if (logarithmicInput && delayedSource.size > 1) {
                (delayedSource.size.toFloat().pow(t) - 1f).coerceIn(0f, delayedSource.lastIndex.toFloat())
            } else t * delayedSource.lastIndex
            val lo = sourcePosition.toInt().coerceIn(0, delayedSource.lastIndex)
            val hi = (lo + 1).coerceAtMost(delayedSource.lastIndex)
            val fraction = sourcePosition - lo
            val target = (delayedSource[lo] + (delayedSource[hi] - delayedSource[lo]) * fraction).coerceIn(0f, 1f)
            envelope[i] = if (!wasPrimed) target else coefficient * envelope[i] + (1f - coefficient) * target
            if (envelope[i] >= peaks[i]) {
                peaks[i] = envelope[i]
                peakTimers[i] = holdSeconds
            } else if (peakTimers[i] > 0f) {
                peakTimers[i] = (peakTimers[i] - dt).coerceAtLeast(0f)
            } else {
                peaks[i] = (peaks[i] - fallPerSecond * dt).coerceAtLeast(envelope[i])
            }
        }
        primed = true
        return Frame(envelope.copyOf(), peaks.copyOf())
    }
}
