package com.vooc.visualizer.analyzer

import kotlin.math.abs

/**
 * Audio clock = master, visual clock = slave.
 * Between audio position updates the time is extrapolated with a monotonic clock; every
 * [resyncIntervalSec] the extrapolation is corrected against the real audio position.
 * [seekEpoch] increments on every seek so consumers know to reset their analyzers.
 */
class AudioClock(
    private val audioPositionUs: () -> Long,
    private val nanoTime: () -> Long = System::nanoTime,
    private val resyncIntervalSec: Double = 5.0
) {
    @Volatile
    var seekEpoch: Int = 0
        private set

    /** Constant output-latency compensation (seconds) added to the reported time. */
    @Volatile
    var latencyCompensationSec: Double = 0.0

    private var playing = false
    private var speed = 1.0
    private var anchorAudioSec = 0.0
    private var anchorNano = 0L
    private var lastSyncNano = 0L

    @Synchronized
    fun play() {
        if (playing) return
        val now = nanoTime()
        anchorAudioSec = audioPositionUs() / 1e6
        anchorNano = now
        lastSyncNano = now
        playing = true
    }

    @Synchronized
    fun pause() {
        if (!playing) return
        anchorAudioSec = extrapolate(nanoTime())
        playing = false
    }

    @Synchronized
    fun setSpeed(newSpeed: Double) {
        val now = nanoTime()
        anchorAudioSec = extrapolate(now)
        anchorNano = now
        speed = newSpeed
    }

    @Synchronized
    fun seek(positionUs: Long) {
        val now = nanoTime()
        anchorAudioSec = positionUs / 1e6
        anchorNano = now
        lastSyncNano = now
        seekEpoch++
    }

    /** Call when the app returns to the foreground: hard re-lock to the audio position. */
    @Synchronized
    fun onForeground() {
        val now = nanoTime()
        anchorAudioSec = audioPositionUs() / 1e6
        anchorNano = now
        lastSyncNano = now
    }

    @Synchronized
    fun timeSeconds(): Double {
        if (!playing) return anchorAudioSec + latencyCompensationSec
        val now = nanoTime()
        if ((now - lastSyncNano) / 1e9 >= resyncIntervalSec) correctDrift(now)
        return extrapolate(now) + latencyCompensationSec
    }

    private fun extrapolate(now: Long): Double = anchorAudioSec + (now - anchorNano) / 1e9 * speed

    private fun correctDrift(now: Long) {
        val estimate = extrapolate(now)
        val actual = audioPositionUs() / 1e6
        val error = actual - estimate
        anchorAudioSec = if (abs(error) > 0.05) actual else estimate + error * 0.5
        anchorNano = now
        lastSyncNano = now
    }
}
