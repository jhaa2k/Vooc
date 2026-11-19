package com.vooc.visualizer.analyzer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/** Optional bundled onset model for [BeatAlgorithm.NEURAL]. Returns onset probability 0..1 for a frame. */
interface BeatModel {
    fun onsetProbability(magnitude: FloatArray, sampleRate: Int, fftSize: Int): Float
}

class BeatState(
    val beat: Boolean,
    /** 1.0 on the beat, decaying to 0 over one beat duration. */
    val pulse: Float,
    val bpm: Float,
    val bpmIsManual: Boolean,
    val halfTimeSuspected: Boolean,
    val doubleTimeSuspected: Boolean,
    val bandBeats: BooleanArray,
    val bandPulses: FloatArray,
    val bandLabels: List<String>,
    val barCount: Int,
    val beatInBar: Int,
    val downbeat: Boolean,
    val secondsSinceBeat: Float
) {
    companion object {
        val NONE = BeatState(false, 0f, 0f, false, false, false, BooleanArray(0), FloatArray(0), emptyList(), 0, 0, false, Float.MAX_VALUE)
    }
}

internal class OnsetDetector(
    private val sampleRate: Int,
    private val fftSize: Int,
    lowHz: Float,
    highHz: Float,
    private val algorithm: BeatAlgorithm,
    private val model: BeatModel?
) {
    private val maxBin = fftSize / 2 - 1
    private val k0 = (lowHz * fftSize / sampleRate).toInt().coerceIn(1, maxBin)
    private val k1 = (highHz * fftSize / sampleRate).toInt().coerceIn(k0, maxBin)
    private val prevLog = FloatArray(fftSize / 2)

    fun reset() = prevLog.fill(0f)

    fun compute(m: FloatArray): Float = when (algorithm) {
        BeatAlgorithm.ENERGY -> {
            var e = 0f
            for (k in k0..k1) e += m[k] * m[k]
            e
        }
        BeatAlgorithm.SPECTRAL_FLUX, BeatAlgorithm.COMB -> {
            var f = 0f
            for (k in k0..k1) {
                val l = ln(1f + 100f * m[k])
                val d = l - prevLog[k]
                if (d > 0f) f += d
                prevLog[k] = l
            }
            f
        }
        BeatAlgorithm.NEURAL -> (model?.onsetProbability(m, sampleRate, fftSize) ?: 0f).coerceIn(0f, 1f)
    }
}

/**
 * Single-range beat detector. With [withTempo] it also estimates BPM by autocorrelation of the onset curve.
 * All timing is driven by the supplied timestamps, so offline export is deterministic.
 */
class BeatDetector(
    private val sampleRate: Int,
    private val fftSize: Int,
    private var cfg: BeatConfig,
    lowHz: Float,
    highHz: Float,
    private val withTempo: Boolean,
    allowComb: Boolean,
    private val model: BeatModel? = null
) {
    private val algorithm: BeatAlgorithm = when {
        cfg.algorithm == BeatAlgorithm.NEURAL && model == null -> BeatAlgorithm.ENERGY
        cfg.algorithm == BeatAlgorithm.COMB && !allowComb -> BeatAlgorithm.ENERGY
        else -> cfg.algorithm
    }
    private val onset = OnsetDetector(sampleRate, fftSize, lowHz, highHz, algorithm, model)
    private val times = ArrayDeque<Double>()
    private val values = ArrayDeque<Float>()

    var lastBeatTime = -1e9
        private set
    var bpmEstimate = 0f
        private set
    var halfTimeSuspected = false
        private set
    var doubleTimeSuspected = false
        private set
    var manualBpm: Float? = cfg.manualBpm

    private var armed = true
    private var lastOnsetTime = -1e9
    private var nextBeat = Double.NaN
    private var lastTempoTime = -1e9
    private var candidateBpm = 0f
    private var candidateCount = 0

    fun reset() {
        times.clear()
        values.clear()
        onset.reset()
        lastBeatTime = -1e9
        bpmEstimate = 0f
        halfTimeSuspected = false
        doubleTimeSuspected = false
        armed = true
        lastOnsetTime = -1e9
        nextBeat = Double.NaN
        lastTempoTime = -1e9
        candidateBpm = 0f
        candidateCount = 0
    }

    fun effectiveBpm(): Float = manualBpm ?: bpmEstimate

    fun process(magnitude: FloatArray, t: Double): Boolean {
        val odf = onset.compute(magnitude)
        var sum = 0.0
        var cnt = 0
        val tMin = t - cfg.historySec
        for (i in times.indices.reversed()) {
            if (times[i] < tMin) break
            sum += values[i]
            cnt++
        }
        val mean = if (cnt > 0) (sum / cnt).toFloat() else odf
        times.addLast(t)
        values.addLast(odf)
        while (times.isNotEmpty() && times.first() < t - HORIZON_SEC) {
            times.removeFirst()
            values.removeFirst()
        }
        if (withTempo) updateTempo(t)

        val effThr = 1f + (cfg.threshold - 1f) * (1.5f - cfg.sensitivity)
        val above = when (algorithm) {
            BeatAlgorithm.NEURAL -> odf > 1f - 0.5f * cfg.sensitivity
            BeatAlgorithm.ENERGY -> odf > mean * effThr && odf > 1e-5f
            BeatAlgorithm.SPECTRAL_FLUX, BeatAlgorithm.COMB -> odf > mean * effThr + 1f
        }
        val cooldownSec = cfg.cooldownMs / 1000.0
        val candidate = above && armed
        if (above) armed = false else armed = true
        if (candidate) lastOnsetTime = t

        var beat = false
        val bpm = effectiveBpm()
        if (algorithm == BeatAlgorithm.COMB && bpm > 0f) {
            val period = 60.0 / bpm
            if (!nextBeat.isNaN() && t - lastOnsetTime > 3 * period) nextBeat = Double.NaN
            if (candidate && t - lastBeatTime >= cooldownSec) {
                if (nextBeat.isNaN() || t >= nextBeat - 0.3 * period) {
                    beat = true
                    nextBeat = t + period
                }
            } else if (!nextBeat.isNaN() && t >= nextBeat + 0.3 * period && t - lastBeatTime >= cooldownSec) {
                beat = true
                nextBeat += period
            }
        } else if (candidate && t - lastBeatTime >= cooldownSec) {
            beat = true
        }
        if (beat) lastBeatTime = t
        return beat
    }

    fun pulse(t: Double): Float {
        val bpm = effectiveBpm()
        val duration = if (bpm > 0f) 60.0 / bpm else 0.5
        val x = ((t - lastBeatTime) / duration).toFloat()
        if (x < 0f || x >= 1f) return 0f
        return when (cfg.decay) {
            DecayCurve.LINEAR -> 1f - x
            DecayCurve.EXPONENTIAL -> exp(-4f * x)
            DecayCurve.CUSTOM -> (cfg.customDecay?.invoke(x) ?: (1f - x)).coerceIn(0f, 1f)
        }
    }

    private fun updateTempo(t: Double) {
        if (t - lastTempoTime < 0.5) return
        lastTempoTime = t
        if (times.size < 2) return
        val span = t - times.first()
        if (span < 3.0) return
        val n = (min(span, 6.0) * GRID).toInt()
        val x = FloatArray(n)
        var p = 0
        for (j in 0 until n) {
            val gt = t - (n - 1 - j) / GRID
            while (p + 1 < times.size && times[p + 1] <= gt) p++
            x[j] = if (times[p] <= gt) values[p] else 0f
        }
        var mean = 0f
        for (v in x) mean += v
        mean /= n
        var r0 = 0f
        for (j in 0 until n) {
            x[j] -= mean
            r0 += x[j] * x[j]
        }
        if (r0 < 1e-9f) return
        val lagMin = (GRID * 60.0 / 240.0).toInt()
        val lagMax = min((GRID * 60.0 / 40.0).toInt(), n / 2)
        if (lagMax <= lagMin + 2) return
        val r = FloatArray(lagMax + 1)
        for (lag in lagMin..lagMax) {
            var acc = 0f
            for (i in 0 until n - lag) acc += x[i] * x[i + lag]
            r[lag] = acc / (n - lag) / (r0 / n)
        }
        var best = lagMin
        var bestScore = -Float.MAX_VALUE
        for (lag in lagMin..lagMax) {
            val bpmAtLag = GRID * 60.0 / lag
            val octaves = ln(bpmAtLag / 120.0) / ln(2.0)
            val score = r[lag] * exp(-0.5 * octaves * octaves).toFloat()
            if (score > bestScore) { bestScore = score; best = lag }
        }
        var refined = best.toDouble()
        if (best > lagMin && best < lagMax) {
            val y0 = r[best - 1]; val y1 = r[best]; val y2 = r[best + 1]
            val denom = y0 - 2f * y1 + y2
            if (abs(denom) > 1e-9f) refined += (0.5f * (y0 - y2) / denom).coerceIn(-1f, 1f)
        }
        val raw = (GRID * 60.0 / refined).toFloat()
        doubleTimeSuspected = best / 2 >= lagMin && r[best / 2] >= 0.8f * r[best]
        halfTimeSuspected = best * 2 <= lagMax && r[best * 2] >= 0.8f * r[best]

        var b = raw
        while (b < cfg.autoMinBpm) b *= 2f
        while (b > cfg.autoMaxBpm) b /= 2f
        b = when (cfg.tempoMode) {
            TempoMode.AUTO -> b
            TempoMode.HALF_TIME -> b / 2f
            TempoMode.DOUBLE_TIME -> b * 2f
        }
        if (bpmEstimate <= 0f) {
            bpmEstimate = b
        } else if (abs(b - bpmEstimate) / bpmEstimate < 0.08f) {
            bpmEstimate = 0.8f * bpmEstimate + 0.2f * b
            candidateCount = 0
        } else {
            if (candidateCount > 0 && abs(b - candidateBpm) / candidateBpm < 0.04f) candidateCount++ else candidateCount = 1
            candidateBpm = b
            if (candidateCount >= 2) { bpmEstimate = b; candidateCount = 0 }
        }
    }

    private companion object {
        const val GRID = 100.0
        const val HORIZON_SEC = 8.0
    }
}

/** Full-band detector (tempo + bars) plus independent per-band detectors (kick vs snare). */
class BeatTracker(
    private val sampleRate: Int,
    private val fftSize: Int,
    private val cfg: BeatConfig,
    model: BeatModel? = null
) {
    private val nyquist = sampleRate / 2f
    private val main = BeatDetector(
        sampleRate, fftSize, cfg, 30f, min(8000f, nyquist - 1f), withTempo = true, allowComb = true, model = model
    )
    private val bandRanges = cfg.perBandRanges.filter { it.lowHz < nyquist - 1f }
    private val bands = bandRanges.map {
        BeatDetector(sampleRate, fftSize, cfg, it.lowHz, min(it.highHz, nyquist - 1f), withTempo = false, allowComb = false, model = model)
    }
    private val bandLabels = bandRanges.map { it.label }
    private val kb0 = (30f * fftSize / sampleRate).toInt().coerceIn(1, fftSize / 2 - 1)
    private val kb1 = (250f * fftSize / sampleRate).toInt().coerceIn(kb0, fftSize / 2 - 1)
    private val pulsesPerBar = cfg.timeSignature.pulsesPerBar
    private val phaseEnergy = FloatArray(pulsesPerBar)
    private var beatCounter = -1
    private var barCount = 0
    private var downbeatOffset = 0
    private val taps = ArrayList<Double>()

    fun reset() {
        main.reset()
        bands.forEach { it.reset() }
        phaseEnergy.fill(0f)
        beatCounter = -1
        barCount = 0
        downbeatOffset = 0
        taps.clear()
    }

    /** null restores automatic detection; otherwise clamped to 40..240. */
    fun setManualBpm(bpm: Float?) { main.manualBpm = bpm?.coerceIn(40f, 240f) }

    /** Tap-tempo input; returns the BPM derived from recent taps (or 0 until two taps are known). */
    fun tapTempo(timeSec: Double): Float {
        if (taps.isNotEmpty() && timeSec - taps.last() > 2.0) taps.clear()
        taps.add(timeSec)
        while (taps.size > 8) taps.removeAt(0)
        if (taps.size < 2) return 0f
        val avg = (taps.last() - taps.first()) / (taps.size - 1)
        val bpm = (60.0 / avg).toFloat().coerceIn(40f, 240f)
        main.manualBpm = bpm
        return bpm
    }

    fun process(magnitude: FloatArray, t: Double): BeatState {
        val beat = main.process(magnitude, t)
        var downbeat = false
        if (beat) {
            var bass = 0f
            for (k in kb0..kb1) bass += magnitude[k] * magnitude[k]
            beatCounter++
            val idx = beatCounter % pulsesPerBar
            phaseEnergy[idx] += bass
            if (idx == pulsesPerBar - 1) for (i in phaseEnergy.indices) phaseEnergy[i] *= 0.9f
            val candidate = phaseEnergy.indices.maxByOrNull { phaseEnergy[it] } ?: 0
            // Hysteresis: only move the downbeat when another phase is clearly stronger (kick on "1").
            if (candidate != downbeatOffset && phaseEnergy[candidate] > phaseEnergy[downbeatOffset] * 1.25f) {
                downbeatOffset = candidate
            }
            val inBar = (idx - downbeatOffset + pulsesPerBar) % pulsesPerBar
            if (inBar == 0) { downbeat = true; barCount++ }
        }
        val bandBeats = BooleanArray(bands.size)
        val bandPulses = FloatArray(bands.size)
        for (i in bands.indices) {
            bandBeats[i] = bands[i].process(magnitude, t)
            bandPulses[i] = bands[i].pulse(t)
        }
        val current = if (beatCounter < 0) 0 else (beatCounter % pulsesPerBar - downbeatOffset + pulsesPerBar) % pulsesPerBar
        return BeatState(
            beat = beat,
            pulse = main.pulse(t),
            bpm = main.effectiveBpm(),
            bpmIsManual = main.manualBpm != null,
            halfTimeSuspected = main.halfTimeSuspected,
            doubleTimeSuspected = main.doubleTimeSuspected,
            bandBeats = bandBeats,
            bandPulses = bandPulses,
            bandLabels = bandLabels,
            barCount = barCount,
            beatInBar = current,
            downbeat = downbeat,
            secondsSinceBeat = (t - main.lastBeatTime).toFloat()
        )
    }
}
