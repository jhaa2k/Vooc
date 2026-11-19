package com.vooc.visualizer.analyzer

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BarTrackingTest {
    private val sr = 44100

    /** A steady 4/4 kick pattern must yield one downbeat per four beats, not one per beat. */
    @Test
    fun steadyKickGivesOneDownbeatPerFourBeats() {
        val n = sr * 14
        val kick = FloatArray(n)
        var start = 0
        while (start < n) {
            for (i in 0 until (sr * 0.15).toInt()) {
                if (start + i < n) kick[start + i] = (0.9 * exp(-i / (sr * 0.03)) * sin(2.0 * PI * 60.0 * i / sr)).toFloat()
            }
            start += sr / 2
        }
        val analyzer = AudioAnalyzer(sr)
        var beats = 0
        var downbeats = 0
        var t = 0.0
        while (t < 14.0) {
            val f = analyzer.analyzeOffline(kick, kick, (t * sr).toInt(), t)
            if (f.beat.beat) beats++
            if (f.beat.downbeat) downbeats++
            t += 1.0 / 60.0
        }
        assertTrue("beats=$beats", beats >= 24)
        assertEquals("downbeats vs beats/4", (beats / 4.0), downbeats.toDouble(), 1.0)
    }
}
