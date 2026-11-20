package com.vooc.visualizer.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.vooc.visualizer.analyzer.decode.PcmDecoder
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.min

/**
 * Transcodes any decodable audio file (MP3, WAV, FLAC, OGG, OPUS, M4A ...) into an AAC-LC .m4a so it can be
 * muxed into the exported MP4. Timestamps follow the source timeline starting at 0, so project trim offsets
 * stay valid. Rates the AAC encoder does not accept are linearly resampled to 44.1 kHz.
 */
object AudioTranscoder {
    private const val AAC = MediaFormat.MIMETYPE_AUDIO_AAC
    private val SUPPORTED_RATES = intArrayOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

    /**
     * Decodes [source] up to [maxDurationUs] and writes AAC to [output]. Throws [CancellationException] when
     * [isCancelled] turns true. On any failure the partial output file is deleted.
     */
    fun transcodeToAacM4a(
        context: Context,
        source: Uri,
        output: File,
        bitrate: Int,
        maxDurationUs: Long,
        isCancelled: () -> Boolean = { false }
    ) {
        PcmDecoder(context, source).use { decoder ->
            val info = decoder.open()
            val outRate = if (info.sampleRate in SUPPORTED_RATES) info.sampleRate else 44100
            val outChannels = if (info.channels == 1) 1 else 2
            val sink = AacSink(output, outRate, outChannels, bitrate.coerceIn(64_000, 320_000))
            val shaper = PcmShaper(info.sampleRate, outRate, info.channels, outChannels)
            var finished = false
            var cancelled = false
            try {
                decoder.decode { samples, frames, ptsUs ->
                    if (isCancelled()) {
                        cancelled = true
                        return@decode false
                    }
                    if (ptsUs > maxDurationUs) return@decode false
                    val shorts = shaper.process(samples, frames)
                    sink.write(shorts, shaper.count)
                    true
                }
                if (cancelled) throw CancellationException("Export cancelled")
                sink.finish()
                finished = true
            } finally {
                sink.release()
                if (!finished) output.delete()
            }
        }
    }

    /** Down/up-mixes to mono or stereo, resamples when needed and converts float PCM to interleaved 16-bit. */
    private class PcmShaper(srcRate: Int, dstRate: Int, private val srcChannels: Int, private val outChannels: Int) {
        private val step = srcRate.toDouble() / dstRate
        private val passthrough = srcRate == dstRate
        private var phase = 1.0
        private var prevL = 0f
        private var prevR = 0f
        private var out = ShortArray(0)

        /** Number of valid shorts in the array returned by the last [process] call. */
        var count = 0
            private set

        fun process(samples: FloatArray, frames: Int): ShortArray {
            if (frames <= 0) {
                count = 0
                return out
            }
            val maxFrames = if (passthrough) frames else (frames / step).toInt() + 4
            val needed = maxFrames * outChannels
            if (out.size < needed) out = ShortArray(needed)
            var n = 0
            if (passthrough) {
                for (f in 0 until frames) {
                    n = put(n, left(samples, f), right(samples, f))
                }
            } else {
                while (phase < frames) {
                    val i = phase.toInt()
                    val frac = (phase - i).toFloat()
                    val aL = if (i == 0) prevL else left(samples, i - 1)
                    val aR = if (i == 0) prevR else right(samples, i - 1)
                    val bL = left(samples, i)
                    val bR = right(samples, i)
                    n = put(n, aL + (bL - aL) * frac, aR + (bR - aR) * frac)
                    phase += step
                }
                phase -= frames
                prevL = left(samples, frames - 1)
                prevR = right(samples, frames - 1)
            }
            count = n
            return out
        }

        private fun left(s: FloatArray, frame: Int): Float {
            val base = frame * srcChannels
            if (outChannels == 1 && srcChannels > 1) {
                var sum = 0f
                for (c in 0 until srcChannels) sum += s[base + c]
                return sum / srcChannels
            }
            return s[base]
        }

        private fun right(s: FloatArray, frame: Int): Float {
            val base = frame * srcChannels
            return if (srcChannels > 1) s[base + 1] else s[base]
        }

        private fun put(index: Int, l: Float, r: Float): Int {
            var n = index
            out[n++] = toShort(l)
            if (outChannels == 2) out[n++] = toShort(r)
            return n
        }

        private fun toShort(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    }

    /** AAC-LC encoder writing straight into an audio-only MPEG-4 container. */
    private class AacSink(file: File, private val sampleRate: Int, private val channels: Int, bitrate: Int) {
        private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val encoder: MediaCodec
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        private var started = false
        private var queuedFrames = 0L

        init {
            val format = MediaFormat.createAudioFormat(AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(AAC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        }

        fun write(pcm: ShortArray, shortCount: Int) {
            var offset = 0
            while (offset < shortCount) {
                val index = encoder.dequeueInputBuffer(10_000)
                if (index < 0) {
                    drain(false)
                    continue
                }
                val buffer = encoder.getInputBuffer(index) ?: throw IllegalStateException("AAC encoder input unavailable.")
                buffer.clear()
                var n = min(shortCount - offset, buffer.capacity() / 2)
                n -= n % channels
                if (n <= 0) throw IllegalStateException("AAC encoder input buffer is too small.")
                buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, offset, n)
                encoder.queueInputBuffer(index, 0, n * 2, queuedFrames * 1_000_000L / sampleRate, 0)
                queuedFrames += n / channels
                offset += n
                drain(false)
            }
        }

        fun finish() {
            var index = encoder.dequeueInputBuffer(10_000)
            var tries = 0
            while (index < 0) {
                drain(false)
                if (++tries > 500) throw IllegalStateException("AAC encoder stalled.")
                index = encoder.dequeueInputBuffer(10_000)
            }
            encoder.queueInputBuffer(index, 0, 0, queuedFrames * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        }

        private fun drain(endOfStream: Boolean) {
            var idle = 0
            while (true) {
                val index = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) return
                    if (++idle > 500) throw IllegalStateException("AAC encoder stalled.")
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (started) throw IllegalStateException("AAC output format changed twice.")
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    started = true
                } else if (index >= 0) {
                    idle = 0
                    val buffer = encoder.getOutputBuffer(index)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (buffer != null && info.size > 0 && started) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }

        fun release() {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
