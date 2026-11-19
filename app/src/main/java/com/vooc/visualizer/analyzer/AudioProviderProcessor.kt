package com.vooc.visualizer.analyzer

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Per-provider spectrum shaping. It consumes an already analysed frame and never captures system audio.
 * The processor is stateful only for temporal smoothing and beat-onset tracking; call reset() on seek/track change.
 */
data class AudioProviderConfig(
    val sampleOutCount: Int = 128,
    val mirrorSamples: Boolean = false,
    val repeatSamples: Boolean = false,
    /** Equal start/end exclusion, matching the current single "Star And End Gap" editor property. */
    val startAndEndGap: Int = 0,
    val smooth: Float = 0.35f,
    val preSmooth: Float = 0f,
    val filterRadius: Int = 0,
    val filterStrength: Float = 0f,
    val aWeight: Boolean = false,
    val outputMultiplier: Float = 1f,
    val lowerHz: Float = 30f,
    val higherHz: Float = 16000f,
    /** -1..0 biases the log curve toward lower frequencies; 0 is logarithmic; 0..1 blends toward linear. */
    val hzLinearFactor: Float = 0f,
    val freqShift: Float = 0f,
    val beatSmooth: Float = 0.35f,
    val beatRangeBarFirst: Int = 0,
    val beatRangeBarLast: Int = 127,
    val beatRangeValueLower: Float = 0.08f,
    val beatRangeValueHigher: Float = 0.8f,
    val fftSize: Int = 2048
) {
    fun validated(nyquist: Float = 96000f): AudioProviderConfig {
        val maxHz = nyquist.coerceAtLeast(2f)
        val low = lowerHz.takeIf { it.isFinite() }?.coerceIn(1f, maxHz - 1f) ?: 30f.coerceAtMost(maxHz - 1f)
        val high = higherHz.takeIf { it.isFinite() }?.coerceIn(low + 1f, maxHz) ?: max(low + 1f, 16000f.coerceAtMost(maxHz))
        return copy(
            sampleOutCount = sampleOutCount.coerceIn(1, 2048),
            startAndEndGap = startAndEndGap.coerceIn(0, 1024),
            smooth = smooth.finiteClamp(0f, 1f),
            preSmooth = preSmooth.finiteClamp(0f, 1f),
            filterRadius = filterRadius.coerceIn(0, 64),
            filterStrength = filterStrength.finiteClamp(0f, 1f),
            outputMultiplier = outputMultiplier.finiteClamp(0f, 32f),
            lowerHz = low,
            higherHz = high,
            hzLinearFactor = hzLinearFactor.finiteClamp(-1f, 1f),
            freqShift = (freqShift.takeIf { it.isFinite() } ?: 0f).coerceIn(-maxHz, maxHz),
            beatSmooth = beatSmooth.finiteClamp(0f, 1f),
            beatRangeBarFirst = beatRangeBarFirst.coerceAtLeast(0),
            beatRangeBarLast = beatRangeBarLast.coerceAtLeast(0),
            beatRangeValueLower = beatRangeValueLower.finiteClamp(0f, 1f),
            beatRangeValueHigher = beatRangeValueHigher.finiteClamp(0f, 1f),
            fftSize = fftSize.takeIf { it in setOf(256, 512, 1024, 2048, 4096, 8192) } ?: 2048
        )
    }

    private fun Float.finiteClamp(min: Float, max: Float): Float =
        (takeIf { it.isFinite() } ?: min).coerceIn(min, max)
}

/** Immutable result snapshot safe to hand to one or more visual elements. */
data class AudioProviderFrame(
    val samples: FloatArray,
    val beatValue: Float,
    val sourceTimeSec: Double,
    val sampleRate: Int
)

class AudioProviderProcessor {
    private var smoothed = FloatArray(0)
    private var beatValue = 0f
    private var lastTimeSec = Double.NaN
    private var primed = false

    fun reset() {
        smoothed = FloatArray(0)
        beatValue = 0f
        lastTimeSec = Double.NaN
        primed = false
    }

    fun process(frame: AnalysisFrame, requested: AudioProviderConfig): AudioProviderFrame {
        val cfg = requested.validated(frame.sampleRate / 2f)
        val source = frame.rawMagnitude
        if (source.isEmpty()) return AudioProviderFrame(FloatArray(cfg.sampleOutCount), 0f, frame.timeSec, frame.sampleRate)

        val dt = if (lastTimeSec.isNaN() || frame.timeSec <= lastTimeSec) 1f / 60f
            else (frame.timeSec - lastTimeSec).toFloat().coerceIn(0.001f, 0.25f)
        lastTimeSec = frame.timeSec

        // Pre Smooth is spatial smoothing on raw FFT magnitudes, before frequency remapping.
        val pre = spatialFilter(source, radius = 1, strength = cfg.preSmooth)
        val nyquist = frame.sampleRate / 2f
        val shift = cfg.freqShift
        val low = (cfg.lowerHz + shift).coerceIn(1f, nyquist - 1f)
        val high = (cfg.higherHz + shift).coerceIn(low + 1f, nyquist)
        val count = cfg.sampleOutCount
        val generated = FloatArray(count)
        val effectiveCount = if (cfg.mirrorSamples) max(1, (count + 1) / 2) else count
        val binHz = frame.sampleRate.toFloat() / frame.fftSize

        for (i in 0 until effectiveCount) {
            val t = if (effectiveCount == 1) 0.5f else i.toFloat() / (effectiveCount - 1)
            val frequency = frequencyAt(t, low, high, cfg.hzLinearFactor)
            var magnitude = SpectrumMapper.sample(pre, frequency / binHz, Interpolation.LINEAR)
            if (cfg.aWeight) magnitude *= aWeightAmplitude(frequency)
            generated[i] = magnitude.coerceAtLeast(0f)
        }

        if (cfg.mirrorSamples) {
            for (i in effectiveCount until count) generated[i] = generated[count - 1 - i]
        }

        if (cfg.repeatSamples && count > source.size) {
            for (i in source.size until count) generated[i] = generated[i % source.size]
        }

        val filtered = spatialFilter(generated, cfg.filterRadius, cfg.filterStrength)
        val gap = cfg.startAndEndGap.coerceAtMost((count - 1) / 2)
        for (i in 0 until gap) {
            filtered[i] = 0f
            filtered[count - 1 - i] = 0f
        }

        if (smoothed.size != count) smoothed = FloatArray(count)
        val alpha = cfg.smooth.coerceIn(0f, 1f)
        for (i in 0 until count) {
            smoothed[i] = if (!primed) filtered[i] else alpha * smoothed[i] + (1f - alpha) * filtered[i]
        }
        primed = true

        val outputSamples = FloatArray(count) { (smoothed[it] * cfg.outputMultiplier).coerceIn(0f, 32f) }

        val first = cfg.beatRangeBarFirst.coerceIn(0, count - 1)
        val last = cfg.beatRangeBarLast.coerceIn(first, count - 1)
        var level = 0f
        for (i in first..last) level += outputSamples[i]
        level /= (last - first + 1)
        val lower = cfg.beatRangeValueLower
        val upper = max(lower + 1e-4f, cfg.beatRangeValueHigher)
        val normalizedLevel = ((level - lower) / (upper - lower)).coerceIn(0f, 1f)
        // A range crossing alone is not a musical beat: the analyzer onset detector must confirm it.
        val target = if (frame.beat.beat) max(normalizedLevel, frame.beat.pulse) else 0f
        val beatAlpha = cfg.beatSmooth
        beatValue = beatAlpha * beatValue + (1f - beatAlpha) * target

        return AudioProviderFrame(outputSamples, beatValue.coerceIn(0f, 1f), frame.timeSec, frame.sampleRate)
    }

    private fun frequencyAt(t: Float, low: Float, high: Float, factor: Float): Float {
        val log = low * (high / low).pow(t)
        val linear = low + (high - low) * t
        return when {
            factor >= 0f -> log + (linear - log) * factor
            else -> {
                val biasedT = t.pow(1f + (-factor * 3f))
                low * (high / low).pow(biasedT)
            }
        }.coerceIn(low, high)
    }

    private fun spatialFilter(input: FloatArray, radius: Int, strength: Float): FloatArray {
        if (input.isEmpty() || radius <= 0 || strength <= 0f) return input.copyOf()
        val out = FloatArray(input.size)
        for (i in input.indices) {
            var total = 0f
            var weight = 0f
            val from = max(0, i - radius)
            val to = min(input.lastIndex, i + radius)
            for (j in from..to) {
                val w = 1f / (1f + abs(j - i))
                total += input[j] * w
                weight += w
            }
            val average = if (weight > 0f) total / weight else input[i]
            out[i] = input[i] + (average - input[i]) * strength
        }
        return out
    }

    /** IEC/ANSI A-weighting response, converted from dB to linear amplitude. */
    private fun aWeightAmplitude(frequencyHz: Float): Float {
        val f2 = frequencyHz.toDouble() * frequencyHz
        val c1 = 20.6 * 20.6
        val c2 = 107.7 * 107.7
        val c3 = 737.9 * 737.9
        val c4 = 12194.0 * 12194.0
        val numerator = c4 * f2 * f2
        val denominator = (f2 + c1) * kotlin.math.sqrt((f2 + c2) * (f2 + c3)) * (f2 + c4)
        if (denominator <= 0.0) return 0f
        val ra = numerator / denominator
        val db = 20.0 * kotlin.math.log10(ra) + 2.0
        return (10.0.pow(db / 20.0)).toFloat().coerceIn(0f, 1.2f)
    }
}


/** Converts the Audio Provider element's persisted controls into the runtime processor configuration. */
fun ElementDocument.toAudioProviderConfig(): AudioProviderConfig {
    require(type == StudioElementType.AUDIO_PROVIDER) { "Audio Provider configuration requires an Audio Provider element" }
    fun number(name: String, fallback: Double) = (value(name) as? ElementValue.Number)?.value
        ?: (value(name) as? ElementValue.Integer)?.value?.toDouble() ?: fallback
    fun integer(name: String, fallback: Int) = number(name, fallback.toDouble()).toInt()
    fun boolean(name: String, fallback: Boolean) = (value(name) as? ElementValue.Bool)?.value ?: fallback
    return AudioProviderConfig(
        sampleOutCount = integer("Sample Out Count", 128),
        mirrorSamples = boolean("Mirror Samples", false),
        repeatSamples = boolean("Repeat Samples", false),
        startAndEndGap = integer("Star And End Gap", 0),
        smooth = number("Smooth", 0.35).toFloat(),
        preSmooth = number("Pre Smooth", 0.0).toFloat(),
        filterRadius = integer("Filter Radius", 0),
        filterStrength = number("Filter Strength", 0.0).toFloat(),
        aWeight = boolean("AWeight", false),
        outputMultiplier = number("Output Multiplier", 1.0).toFloat(),
        lowerHz = number("Lower Hz", 30.0).toFloat(),
        higherHz = number("Higher Hz", 16000.0).toFloat(),
        hzLinearFactor = number("Hz Linear Factor", 0.0).toFloat(),
        freqShift = number("Freq Shift", 0.0).toFloat(),
        beatSmooth = number("Beat Smooth", 0.35).toFloat(),
        beatRangeBarFirst = integer("Beat Range Bar First", 0),
        beatRangeBarLast = integer("Beat Range Bar Last", 127),
        beatRangeValueLower = number("Beat Range Value Lower", 0.08).toFloat(),
        beatRangeValueHigher = number("Beat Range Value Higher", 0.8).toFloat(),
        fftSize = (value("Fft Size") as? ElementValue.Text)?.value?.toIntOrNull() ?: 2048
    )
}

/** Apply the provider's FFT size while preserving all other analyzer settings. */
fun AudioProviderConfig.applyTo(base: AnalyzerConfig): AnalyzerConfig =
    base.copy(fftSize = FftSize.fromSize(validated().fftSize))
