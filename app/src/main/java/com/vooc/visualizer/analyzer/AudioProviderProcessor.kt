package com.vooc.visualizer.analyzer

import com.vooc.visualizer.studio.ElementDocument
import com.vooc.visualizer.studio.ElementValue
import com.vooc.visualizer.studio.StudioElementType
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class ProviderAnalysisMode { SPECTRUM, BEAT, SPECTRUM_AND_BEAT }
enum class ProviderChannelMode { MONO_MIX, LEFT, RIGHT, STEREO_AVERAGE }

/**
 * Per-provider spectrum shaping. It consumes an already analysed frame and never captures system audio.
 * The processor is stateful only for temporal smoothing and beat-onset tracking; call reset() on seek/track change.
 */
data class AudioProviderConfig(
    val sampleOutCount: Int = 201,
    val mirrorSamples: Boolean = false,
    val repeatSamples: Boolean = true,
    /** Equal start/end exclusion, matching the current single "Star And End Gap" editor property. */
    val startAndEndGap: Int = 0,
    val smooth: Float = 0.595f,
    val preSmooth: Float = 1f,
    val filterRadius: Int = 1,
    val filterStrength: Float = 1f,
    val aWeight: Float = 0.2f,
    val outputMultiplier: Float = 7f,
    val lowerHz: Float = 20f,
    val higherHz: Float = 18000f,
    /** -1..0 biases the log curve toward lower frequencies; 0 is logarithmic; 0..1 blends toward linear. */
    val hzLinearFactor: Float = 0.06f,
    val freqShift: Float = 0f,
    val beatSmooth: Float = 0.685f,
    val beatRangeBarFirst: Int = 0,
    val beatRangeBarLast: Int = 70,
    val beatRangeValueLower: Float = 0f,
    val beatRangeValueHigher: Float = 50f,
    val fftSize: Int = 2048,
    val providerEnabled: Boolean = true,
    val analysisMode: ProviderAnalysisMode = ProviderAnalysisMode.SPECTRUM_AND_BEAT,
    val channelMode: ProviderChannelMode = ProviderChannelMode.MONO_MIX,
    val windowFunction: WindowFunction = WindowFunction.HANN,
    val beatDetectionEnabled: Boolean = true,
    val beatSensitivity: Float = 1f,
    val beatThreshold: Float = 0.65f,
    val beatRelease: Float = 0.25f
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
            aWeight = aWeight.finiteClamp(0f, 1f),
            lowerHz = low,
            higherHz = high,
            hzLinearFactor = hzLinearFactor.finiteClamp(-1f, 1f),
            freqShift = (freqShift.takeIf { it.isFinite() } ?: 0f).coerceIn(-maxHz, maxHz),
            beatSmooth = beatSmooth.finiteClamp(0f, 1f),
            beatRangeBarFirst = beatRangeBarFirst.coerceAtLeast(0),
            beatRangeBarLast = beatRangeBarLast.coerceAtLeast(0),
            beatRangeValueLower = beatRangeValueLower.finiteClamp(0f, 100f),
            beatRangeValueHigher = beatRangeValueHigher.finiteClamp(0f, 100f),
            fftSize = fftSize.takeIf { it in setOf(256, 512, 1024, 2048, 4096, 8192) } ?: 2048,
            beatSensitivity = beatSensitivity.finiteClamp(0f, 8f),
            beatThreshold = beatThreshold.finiteClamp(0f, 1f),
            beatRelease = beatRelease.finiteClamp(0f, 0.5f)
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
        if (!cfg.providerEnabled) {
            reset()
            return AudioProviderFrame(FloatArray(cfg.sampleOutCount), 0f, frame.timeSec, frame.sampleRate)
        }
        val source = when (cfg.channelMode) {
            ProviderChannelMode.MONO_MIX -> frame.rawMagnitude
            ProviderChannelMode.LEFT -> frame.channel(ChannelSelect.LEFT).rawMagnitude
            ProviderChannelMode.RIGHT -> frame.channel(ChannelSelect.RIGHT).rawMagnitude
            ProviderChannelMode.STEREO_AVERAGE -> {
                val left = frame.channel(ChannelSelect.LEFT).rawMagnitude
                val right = frame.channel(ChannelSelect.RIGHT).rawMagnitude
                if (left.isEmpty() || right.isEmpty()) frame.rawMagnitude
                else FloatArray(min(left.size, right.size)) { index ->
                    (left[index] + right[index]) * 0.5f
                }
            }
        }
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
            if (cfg.aWeight > 0f) {
                val weighted = aWeightAmplitude(frequency)
                magnitude *= 1f + (weighted - 1f) * cfg.aWeight.coerceIn(0f, 1f)
            }
            generated[i] = magnitude.coerceAtLeast(0f)
        }

        if (cfg.mirrorSamples) {
            for (i in effectiveCount until count) generated[i] = generated[count - 1 - i]
        }

        if (cfg.repeatSamples && count > source.size) {
            // Repeat the already frequency-mapped output, never raw FFT bins.
            for (i in source.size until count) generated[i] = generated[i % source.size]
        }

        val filtered = spatialFilter(generated, cfg.filterRadius, cfg.filterStrength)
        val gap = cfg.startAndEndGap.coerceAtMost((count - 1) / 2)
        for (i in 0 until gap) {
            filtered[i] = 0f
            filtered[count - 1 - i] = 0f
        }

        if (smoothed.size != count) smoothed = FloatArray(count)
        // Normalize smoothing to elapsed time (60 Hz reference), so live preview and
        // offline export produce the same response even when their analysis cadence differs.
        val alpha = cfg.smooth.coerceIn(0f, 1f).pow(dt * 60f)
        for (i in 0 until count) {
            smoothed[i] = if (!primed) filtered[i] else alpha * smoothed[i] + (1f - alpha) * filtered[i]
        }
        primed = true

        val outputSamples = if (cfg.analysisMode != ProviderAnalysisMode.BEAT) {
            FloatArray(count) { (smoothed[it] * cfg.outputMultiplier).coerceIn(0f, 32f) }
        } else FloatArray(count)

        val first = cfg.beatRangeBarFirst.coerceIn(0, count - 1)
        val last = cfg.beatRangeBarLast.coerceIn(first, count - 1)
        var level = 0f
        for (i in first..last) level += outputSamples[i]
        level /= (last - first + 1)
        val lower = cfg.beatRangeValueLower
        val upper = max(lower + 1e-4f, cfg.beatRangeValueHigher)
        val normalizedLevel = ((level - lower) / (upper - lower)).coerceIn(0f, 1f)
        // A range crossing alone is not a musical beat: the analyzer onset detector must confirm it.
        val target = if (cfg.analysisMode != ProviderAnalysisMode.SPECTRUM && cfg.beatDetectionEnabled && frame.beat.beat) {
            max(normalizedLevel, frame.beat.pulse)
        } else 0f
        val beatAlpha = cfg.beatSmooth.coerceIn(0f, 1f).pow(dt * 60f)
        beatValue = beatAlpha * beatValue + (1f - beatAlpha) * target
        if (cfg.analysisMode == ProviderAnalysisMode.SPECTRUM || !cfg.beatDetectionEnabled) beatValue = 0f

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
    fun boolean(name: String, fallback: Boolean) = when (val v = value(name)) {
        is ElementValue.Bool -> v.value
        is ElementValue.Integer -> v.value != 0L
        is ElementValue.Number -> v.value != 0.0
        else -> fallback
    }
    fun text(name: String, fallback: String) = (value(name) as? ElementValue.Text)?.value ?: fallback
    return AudioProviderConfig(
        sampleOutCount = integer("Sample Out Count", 128),
        mirrorSamples = boolean("Mirror Samples", false),
        repeatSamples = boolean("Repeat Samples", false),
        startAndEndGap = integer("Star And End Gap", 0),
        smooth = number("Smooth", 0.35).toFloat(),
        preSmooth = number("Pre Smooth", 0.0).toFloat(),
        filterRadius = integer("Filter Radius", 0),
        filterStrength = number("Filter Strength", 0.0).toFloat(),
        aWeight = number("AWeight", 0.0).toFloat(),
        outputMultiplier = number("Output Multiplier", 1.0).toFloat(),
        lowerHz = number("Lower Hz", 30.0).toFloat(),
        higherHz = number("Higher Hz", 16000.0).toFloat(),
        hzLinearFactor = number("Hz Linear Factor", 0.0).toFloat(),
        freqShift = number("Freq Shift", 0.0).toFloat(),
        beatSmooth = number("Beat Smooth", 0.35).toFloat(),
        beatRangeBarFirst = number("Beat Range Bar First", 0.0).let { if (it in 0.0..1.0) (it * (integer("Sample Out Count", 128) - 1)).toInt() else it.toInt() },
        beatRangeBarLast = number("Beat Range Bar Last", 127.0).let { if (it in 0.0..1.0) (it * (integer("Sample Out Count", 128) - 1)).toInt() else it.toInt() },
        beatRangeValueLower = number("Beat Range Value Lower", 0.08).toFloat(),
        beatRangeValueHigher = number("Beat Range Value Higher", 0.8).toFloat(),
        providerEnabled = boolean("Provider Enabled", true),
        analysisMode = when (text("Analysis Mode", "Spectrum + Beat")) {
            "Spectrum" -> ProviderAnalysisMode.SPECTRUM
            "Beat" -> ProviderAnalysisMode.BEAT
            else -> ProviderAnalysisMode.SPECTRUM_AND_BEAT
        },
        channelMode = when (text("Channel Mode", "Mono Mix")) {
            "Left" -> ProviderChannelMode.LEFT
            "Right" -> ProviderChannelMode.RIGHT
            "Stereo Average" -> ProviderChannelMode.STEREO_AVERAGE
            else -> ProviderChannelMode.MONO_MIX
        },
        windowFunction = when (text("Window Function", "Hann")) {
            "Rectangular" -> WindowFunction.RECTANGULAR
            "Hamming" -> WindowFunction.HAMMING
            "Blackman" -> WindowFunction.BLACKMAN
            else -> WindowFunction.HANN
        },
        beatDetectionEnabled = boolean("Beat Detection Enabled", true),
        beatSensitivity = number("Beat Sensitivity", 1.0).toFloat(),
        beatThreshold = number("Beat Threshold", 0.65).toFloat(),
        beatRelease = number("Beat Release", 0.25).toFloat(),
        fftSize = run {
            val raw = when (val fft = value("Fft Size")) {
                is ElementValue.Text -> fft.value.toIntOrNull() ?: 2048
                is ElementValue.Integer -> fft.value.toInt()
                is ElementValue.Number -> fft.value.toInt()
                else -> 2048
            }
            if (raw in 7..13) 1 shl raw else raw
        }
    )
}

/** Apply provider DSP settings while preserving unrelated analyzer configuration. */
fun AudioProviderConfig.applyTo(base: AnalyzerConfig): AnalyzerConfig {
    val cfg = validated()
    val channelMode = when (cfg.channelMode) {
        ProviderChannelMode.LEFT, ProviderChannelMode.RIGHT, ProviderChannelMode.STEREO_AVERAGE -> ChannelMode.STEREO_SPLIT
        ProviderChannelMode.MONO_MIX -> ChannelMode.MONO
    }
    val beat = base.beat.copy(
        sensitivity = (cfg.beatSensitivity / 8f).coerceIn(0f, 1f),
        threshold = (1f + cfg.beatThreshold * 4f).coerceIn(1f, 5f),
        cooldownMs = (cfg.beatRelease * 1000f).toInt().coerceIn(50, 500)
    )
    return base.copy(fftSize = FftSize.fromSize(cfg.fftSize), window = cfg.windowFunction,
        channelMode = channelMode, beat = beat)
}
