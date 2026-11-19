package com.vooc.visualizer.analyzer

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ChannelAnalysis(val spectrum: FloatArray, val bands: FloatArray, val rms: Float, val peak: Float)

class AnalysisFrame(
    val timeSec: Double,
    val sampleRate: Int,
    val fftSize: Int,
    val binWidthHz: Float,
    /** Smoothed spectrum of the mono mix (dB-mapped 0..1 when [AnalyzerConfig.outputDb]). */
    val spectrum: FloatArray,
    val rawMagnitude: FloatArray,
    val phase: FloatArray,
    val db: FloatArray,
    val bands: FloatArray,
    val bandLabels: List<String>,
    val beat: BeatState,
    val rms: Float,
    val peak: Float,
    val channels: Map<ChannelSelect, ChannelAnalysis>,
    /** Most recent mono samples, for waveform rendering. */
    val waveform: FloatArray
) {
    /** Falls back to the mono mix when the requested channel is not analyzed in the current mode. */
    fun channel(select: ChannelSelect): ChannelAnalysis = channels[select] ?: channels.getValue(ChannelSelect.MIX)
    fun band(index: Int): Float = bands.getOrElse(index) { 0f }
}

/**
 * Facade of the audio-analyzer module.
 *
 * Live: the audio thread calls [pushInterleaved] / [pushPcm]; the render thread calls [analyzeLive] with the
 * audio-clock time, so the analysis window follows what is audible rather than what was decoded.
 * Offline/export: [analyzeOffline] / [analyzeWindow] are deterministic functions of the samples and timestamp.
 */
class AudioAnalyzer(
    sampleRate: Int = 44100,
    config: AnalyzerConfig = AnalyzerConfig(),
    private val beatModel: BeatModel? = null
) {
    private class ChannelPipeline(val select: ChannelSelect, sampleRate: Int, fftSize: Int, cfg: AnalyzerConfig) {
        val result = FftResult(fftSize / 2)
        val smoothed = FloatArray(fftSize / 2)
        val smoother = SpectrumSmoother(fftSize / 2, cfg.smoothing)
        val bands = BandSplitter(sampleRate, fftSize, cfg.bands)
        val bandOut = FloatArray(bands.count)
        var peakEnv = 0f

        fun reset() {
            smoother.reset()
            bands.reset()
            peakEnv = 0f
        }
    }

    private val lock = Any()

    var sampleRate: Int = sampleRate
        private set
    var config: AnalyzerConfig = config
        private set

    private var fftSize = 0
    private var hop = 1
    private lateinit var fft: FFTProcessor
    private lateinit var ringL: FloatRingBuffer
    private lateinit var ringR: FloatRingBuffer
    private lateinit var winL: FloatArray
    private lateinit var winR: FloatArray
    private lateinit var mix: FloatArray
    private lateinit var side: FloatArray
    private lateinit var pipelines: List<ChannelPipeline>
    private lateinit var tracker: BeatTracker
    private var pushL = FloatArray(0)
    private var pushR = FloatArray(0)
    private var pushScratch = FloatArray(0)
    private var lastTime = -1.0
    private var lastEnd = -1L
    private var cached: AnalysisFrame? = null

    init { rebuild() }

    private fun rebuild() {
        val cfg = config
        val n = cfg.fftSize.size
        fftSize = n
        fft = FFTProcessor(n, cfg.window)
        hop = max(1, (n * (1f - cfg.overlap.fraction)).roundToInt())
        val history = (sampleRate * cfg.alignmentHistoryMs / 1000f).toInt()
        val capacity = max(cfg.ringBufferSamples, n + history + hop)
        ringL = FloatRingBuffer(capacity)
        ringR = FloatRingBuffer(capacity)
        winL = FloatArray(n)
        winR = FloatArray(n)
        mix = FloatArray(n)
        side = FloatArray(n)
        val selects = when (cfg.channelMode) {
            ChannelMode.MONO -> listOf(ChannelSelect.MIX)
            ChannelMode.STEREO_SPLIT -> listOf(ChannelSelect.MIX, ChannelSelect.LEFT, ChannelSelect.RIGHT)
            ChannelMode.MID_SIDE -> listOf(ChannelSelect.MIX, ChannelSelect.SIDE)
        }
        pipelines = selects.map { ChannelPipeline(it, sampleRate, n, cfg) }
        tracker = BeatTracker(sampleRate, n, cfg.beat, beatModel)
        lastTime = -1.0
        lastEnd = -1L
        cached = null
    }

    fun updateConfig(newConfig: AnalyzerConfig) = synchronized(lock) {
        config = newConfig
        rebuild()
    }

    fun setSampleRate(newRate: Int) = synchronized(lock) {
        require(newRate in 8000..192000) { "Unsupported sample rate: $newRate" }
        if (newRate != sampleRate) {
            sampleRate = newRate
            rebuild()
        }
    }

    // ---------------------------------------------------------------- audio-thread input

    fun pushInterleaved(samples: FloatArray, frames: Int, channels: Int) {
        if (frames <= 0) return
        if (pushL.size < frames) { pushL = FloatArray(frames); pushR = FloatArray(frames) }
        ChannelMixer.deinterleave(samples, frames, channels, pushL, pushR)
        ringL.write(pushL, 0, frames)
        ringR.write(pushR, 0, frames)
    }

    /** Converts little-endian PCM from a player/decoder buffer (not consumed) and pushes it. */
    fun pushPcm(buffer: ByteBuffer, format: PcmFormat, channels: Int) {
        val needed = buffer.remaining() / format.bytesPerSample
        if (pushScratch.size < needed) pushScratch = FloatArray(needed)
        val count = PcmConverter.toFloat(buffer, format, pushScratch)
        pushInterleaved(pushScratch, count / channels, channels)
    }

    /** Seek / track change: flush buffers and re-anchor the stream to [positionSec]. */
    fun onStreamDiscontinuity(positionSec: Double) = synchronized(lock) {
        val start = (positionSec * sampleRate).toLong().coerceAtLeast(0L)
        ringL.clear(start)
        ringR.clear(start)
        resetStateLocked()
    }

    fun resetState() = synchronized(lock) { resetStateLocked() }

    private fun resetStateLocked() {
        pipelines.forEach { it.reset() }
        tracker.reset()
        lastTime = -1.0
        lastEnd = -1L
        cached = null
    }

    fun setManualBpm(bpm: Float?) = synchronized(lock) { tracker.setManualBpm(bpm) }
    fun tapTempo(timeSec: Double): Float = synchronized(lock) { tracker.tapTempo(timeSec) }

    val underruns: Long get() = ringL.underrunFills
    val overruns: Long get() = ringL.overrunDrops

    // ---------------------------------------------------------------- analysis

    /**
     * Analyzes the audio ending at [timeSec] (audio-clock time). With [alignToClock] the window follows the
     * clock; otherwise it uses the newest decoded samples. Returns the previous frame while fewer than one
     * overlap hop of new audio is available.
     */
    fun analyzeLive(timeSec: Double, alignToClock: Boolean = true): AnalysisFrame = synchronized(lock) {
        val total = ringL.totalWritten
        val end = if (alignToClock) min((timeSec * sampleRate).toLong(), total) else total
        val previous = cached
        if (previous != null && end >= lastEnd && end - lastEnd < hop) {
            previous
        } else {
            ringL.copyWindowEnding(end, winL, fftSize)
            ringR.copyWindowEnding(end, winR, fftSize)
            lastEnd = end
            analyzeWindowLocked(winL, winR, timeSec)
        }
    }

    /** Deterministic analysis of the window of [left]/[right] that ends (exclusive) at [endSample]. */
    fun analyzeOffline(left: FloatArray, right: FloatArray, endSample: Int, timeSec: Double): AnalysisFrame =
        synchronized(lock) {
            val n = fftSize
            val start = endSample - n
            for (i in 0 until n) {
                val idx = start + i
                val ok = idx >= 0 && idx < left.size && idx < right.size
                winL[i] = if (ok) left[idx] else 0f
                winR[i] = if (ok) right[idx] else 0f
            }
            analyzeWindowLocked(winL, winR, timeSec)
        }

    /** [left]/[right] hold the latest [AnalyzerConfig.fftSize] samples, oldest first. */
    fun analyzeWindow(left: FloatArray, right: FloatArray, timeSec: Double): AnalysisFrame =
        synchronized(lock) { analyzeWindowLocked(left, right, timeSec) }

    private fun display(smoothed: FloatArray): FloatArray {
        val out = smoothed.copyOf()
        if (config.outputDb) {
            val floor = config.bands.dbFloor
            for (i in out.indices) {
                val db = 20f * log10(max(out[i], 1e-9f))
                out[i] = ((db - floor) / -floor).coerceIn(0f, 1f)
            }
        }
        return out
    }

    private fun analyzeWindowLocked(left: FloatArray, right: FloatArray, timeSec: Double): AnalysisFrame {
        val n = fftSize
        require(left.size >= n && right.size >= n) { "Window must contain at least $n samples" }
        if (lastTime >= 0.0 && timeSec < lastTime - 1e-6) resetStateLocked()
        val dt = if (lastTime < 0.0) 1f / 60f else (timeSec - lastTime).toFloat().coerceIn(0.001f, 0.25f)
        lastTime = timeSec
        for (i in 0 until n) {
            mix[i] = ChannelMixer.mono(left[i], right[i])
            side[i] = ChannelMixer.side(left[i], right[i])
        }
        val channels = LinkedHashMap<ChannelSelect, ChannelAnalysis>()
        for (p in pipelines) {
            val signal = when (p.select) {
                ChannelSelect.MIX, ChannelSelect.MID -> mix
                ChannelSelect.LEFT -> left
                ChannelSelect.RIGHT -> right
                ChannelSelect.SIDE -> side
            }
            fft.process(signal, 0, p.result, config.normalization)
            p.smoother.update(p.result.magnitude, dt, p.smoothed)
            p.bands.process(p.result.raw, dt, p.bandOut)
            var sq = 0.0
            var pk = 0f
            for (i in 0 until n) {
                val v = signal[i]
                sq += (v * v).toDouble()
                val a = abs(v)
                if (a > pk) pk = a
            }
            val rms = sqrt(sq / n).toFloat()
            val c = smoothingCoeff(if (pk > p.peakEnv) config.peakAttackMs else config.peakReleaseMs, dt)
            p.peakEnv = c * p.peakEnv + (1f - c) * pk
            channels[p.select] = ChannelAnalysis(display(p.smoothed), p.bandOut.copyOf(), rms, p.peakEnv)
        }
        val mixPipe = pipelines.first { it.select == ChannelSelect.MIX }
        val mixAnalysis = channels.getValue(ChannelSelect.MIX)
        val beat = tracker.process(mixPipe.result.raw, timeSec)
        val waveN = min(config.waveformSamples, n)
        val frame = AnalysisFrame(
            timeSec = timeSec,
            sampleRate = sampleRate,
            fftSize = n,
            binWidthHz = fft.binWidthHz(sampleRate),
            spectrum = mixAnalysis.spectrum,
            rawMagnitude = mixPipe.result.raw.copyOf(),
            phase = mixPipe.result.phase.copyOf(),
            db = mixPipe.result.db.copyOf(),
            bands = mixAnalysis.bands,
            bandLabels = mixPipe.bands.labels,
            beat = beat,
            rms = mixAnalysis.rms,
            peak = mixAnalysis.peak,
            channels = channels,
            waveform = mix.copyOfRange(n - waveN, n)
        )
        cached = frame
        return frame
    }
}
