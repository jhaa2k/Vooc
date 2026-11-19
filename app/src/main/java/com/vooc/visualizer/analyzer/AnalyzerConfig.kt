package com.vooc.visualizer.analyzer

import kotlin.math.pow

/** Coarse device performance class used by FFT auto mode and quality presets. */
enum class DeviceTier {
    LOW, MID, HIGH;

    companion object {
        fun fromRamMb(ramMb: Long): DeviceTier = when {
            ramMb < 2048 -> LOW
            ramMb <= 6144 -> MID
            else -> HIGH
        }
    }
}

enum class FftSize(val size: Int) {
    S512(512), S1024(1024), S2048(2048), S4096(4096), S8192(8192);

    companion object {
        fun auto(tier: DeviceTier): FftSize = when (tier) {
            DeviceTier.LOW -> S1024
            DeviceTier.MID -> S2048
            DeviceTier.HIGH -> S4096
        }

        fun fromSize(size: Int): FftSize = entries.firstOrNull { it.size == size }
            ?: throw IllegalArgumentException("Unsupported FFT size: $size")
    }
}

enum class WindowFunction { HANN, HAMMING, BLACKMAN, RECTANGULAR, BLACKMAN_HARRIS }

enum class Overlap(val fraction: Float) {
    NONE(0f), PERCENT_25(0.25f), PERCENT_50(0.5f), PERCENT_75(0.75f)
}

enum class ChannelMode { MONO, STEREO_SPLIT, MID_SIDE }

/** Per-layer channel selector. MID resolves to MIX because mid = (L+R)/2 = mono downmix. */
enum class ChannelSelect { MIX, LEFT, RIGHT, MID, SIDE }

enum class Normalization { NONE, PEAK, RMS }
enum class Interpolation { NONE, LINEAR, CUBIC }
enum class FrequencyScale { LINEAR, LOGARITHMIC, CUSTOM }
enum class BeatAlgorithm { ENERGY, SPECTRAL_FLUX, COMB, NEURAL }
enum class DecayCurve { LINEAR, EXPONENTIAL, CUSTOM }
enum class TempoMode { AUTO, HALF_TIME, DOUBLE_TIME }

enum class TimeSignature(val pulsesPerBar: Int, val label: String) {
    FOUR_FOUR(4, "4/4"), THREE_FOUR(3, "3/4"), SIX_EIGHT(6, "6/8")
}

enum class BandSlope { BRICK_WALL, GENTLE }
enum class CrossoverOrder(val dbPerOctave: Int) { LR12(12), LR24(24), LR48(48) }
enum class BandCombine { MAX, AVERAGE, WEIGHTED }

data class SmoothingConfig(
    /** alpha in S[n] = alpha*S[n-1] + (1-alpha)*|FFT[n]|, used when [asymmetric] is false. */
    val temporal: Float = 0.5f,
    val attackMs: Float = 20f,
    val releaseMs: Float = 180f,
    /** true = separate attack/release time constants (fast attack, slow release). */
    val asymmetric: Boolean = true
) {
    init {
        require(temporal in 0f..0.99f) { "temporal smoothing must be in 0.0..0.99" }
        require(attackMs in 0f..1000f) { "attackMs must be in 0..1000" }
        require(releaseMs in 0f..1000f) { "releaseMs must be in 0..1000" }
    }
}

data class FreqRange(val label: String, val lowHz: Float, val highHz: Float) {
    init { require(lowHz > 0f && highHz > lowHz) { "Invalid frequency range $lowHz..$highHz" } }
}

data class BeatConfig(
    val algorithm: BeatAlgorithm = BeatAlgorithm.ENERGY,
    /** 0 = rare beats, 1 = every transient. */
    val sensitivity: Float = 0.6f,
    val cooldownMs: Int = 150,
    /** Multiple of the rolling average the onset must exceed. */
    val threshold: Float = 1.5f,
    val historySec: Float = 1.5f,
    val autoMinBpm: Float = 60f,
    val autoMaxBpm: Float = 200f,
    /** 40..240 or null for automatic detection. */
    val manualBpm: Float? = null,
    val tempoMode: TempoMode = TempoMode.AUTO,
    val decay: DecayCurve = DecayCurve.EXPONENTIAL,
    /** Maps normalized beat position 0..1 to pulse 0..1 when [decay] is CUSTOM. */
    val customDecay: ((Float) -> Float)? = null,
    val timeSignature: TimeSignature = TimeSignature.FOUR_FOUR,
    /** Independent detectors, e.g. kick vs snare. */
    val perBandRanges: List<FreqRange> = listOf(FreqRange("Kick", 40f, 150f), FreqRange("Snare", 200f, 4000f))
) {
    init {
        require(sensitivity in 0f..1f) { "sensitivity must be in 0..1" }
        require(cooldownMs in 50..500) { "cooldownMs must be in 50..500" }
        require(threshold in 1f..5f) { "threshold must be in 1..5" }
        require(historySec in 0.5f..5f) { "historySec must be in 0.5..5" }
        require(autoMinBpm >= 40f && autoMaxBpm <= 240f && autoMaxBpm > autoMinBpm * 2f) { "invalid auto BPM range" }
        require(manualBpm == null || manualBpm in 40f..240f) { "manualBpm must be in 40..240" }
    }
}

data class BandSpec(
    val label: String,
    val lowHz: Float,
    val highHz: Float,
    val gain: Float = 1f,
    val smoothing: Float = 0.3f,
    val attackMs: Float = 20f,
    val releaseMs: Float = 150f,
    val agc: Boolean = false
) {
    init {
        require(lowHz > 0f && highHz > lowHz) { "Band '$label': invalid range $lowHz..$highHz" }
        require(gain in 0f..2f) { "Band '$label': gain must be in 0..2" }
        require(smoothing in 0f..0.99f) { "Band '$label': smoothing must be in 0..0.99" }
        require(attackMs in 0f..1000f && releaseMs in 0f..1000f) { "Band '$label': attack/release must be in 0..1000" }
    }
}

data class BandConfig(
    val bands: List<BandSpec> = defaultThreeBand(),
    val slope: BandSlope = BandSlope.GENTLE,
    /** dB value mapped to level 0.0 (0 dB maps to 1.0). */
    val dbFloor: Float = -70f,
    val crossover: CrossoverOrder = CrossoverOrder.LR24
) {
    init {
        require(bands.size in 1..32) { "Band count must be in 1..32" }
        require(dbFloor < -10f && dbFloor >= -120f) { "dbFloor must be in -120..-10" }
    }

    companion object {
        fun defaultThreeBand(): List<BandSpec> = listOf(
            BandSpec("Bass", 20f, 250f),
            BandSpec("Mid", 250f, 4000f),
            BandSpec("Treble", 4000f, 20000f)
        )

        /** [count] log-spaced bands between [lowHz] and [highHz]. */
        fun logSpaced(count: Int, lowHz: Float = 20f, highHz: Float = 20000f): List<BandSpec> {
            require(count in 1..32) { "Band count must be in 1..32" }
            val ratio = highHz / lowHz
            return List(count) { i ->
                val lo = lowHz * ratio.pow(i.toFloat() / count)
                val hi = lowHz * ratio.pow((i + 1f) / count)
                BandSpec("B${i + 1}", lo, hi)
            }
        }
    }
}

data class AnalyzerConfig(
    val fftSize: FftSize = FftSize.S2048,
    val window: WindowFunction = WindowFunction.HANN,
    val overlap: Overlap = Overlap.PERCENT_50,
    val channelMode: ChannelMode = ChannelMode.MONO,
    val smoothing: SmoothingConfig = SmoothingConfig(),
    val normalization: Normalization = Normalization.NONE,
    val interpolation: Interpolation = Interpolation.LINEAR,
    /** true = [AnalysisFrame.spectrum] is dB-mapped to 0..1 using [BandConfig.dbFloor]. */
    val outputDb: Boolean = false,
    val beat: BeatConfig = BeatConfig(),
    val bands: BandConfig = BandConfig(),
    /** Minimum analysis ring size, 4096..16384 samples. Grown automatically to cover FFT + alignment history. */
    val ringBufferSamples: Int = 8192,
    /** How much audio history is retained so the analysis window can follow the audible position. */
    val alignmentHistoryMs: Int = 600,
    val waveformSamples: Int = 2048,
    val peakAttackMs: Float = 5f,
    val peakReleaseMs: Float = 300f
) {
    init {
        require(ringBufferSamples in 4096..16384) { "ringBufferSamples must be in 4096..16384" }
        require(alignmentHistoryMs in 100..2000) { "alignmentHistoryMs must be in 100..2000" }
        require(waveformSamples in 256..8192) { "waveformSamples must be in 256..8192" }
    }
}
