package com.vooc.visualizer.analyzer.decode

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.vooc.visualizer.analyzer.PcmConverter
import com.vooc.visualizer.analyzer.PcmFormat
import java.io.Closeable
import java.io.IOException

class AudioDecodeException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { MISSING_FILE, CORRUPTED, UNSUPPORTED_CODEC, DECODE_FAILURE }
}

data class StreamInfo(
    val mime: String,
    val sampleRate: Int,
    val channels: Int,
    val durationUs: Long,
    val pcmFormat: PcmFormat,
    val decoderName: String,
    val hardware: Boolean
)

/**
 * MediaExtractor + MediaCodec decoder producing interleaved float PCM (MP3, AAC, FLAC, WAV, OGG, OPUS).
 * Hardware decoders are tried first; software decoders (OMX.google.* / c2.android.*) are the fallback.
 * Used for deterministic offline analysis and export.
 */
class PcmDecoder(private val context: Context, private val uri: Uri) : Closeable {
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var pcm = PcmFormat.PCM_16
    private var channels = 2
    private var sampleRate = 44100
    var info: StreamInfo? = null
        private set

    fun open(): StreamInfo {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
        } catch (e: IOException) {
            ex.release()
            throw AudioDecodeException(AudioDecodeException.Reason.MISSING_FILE, "Cannot open audio file", e)
        } catch (e: SecurityException) {
            ex.release()
            throw AudioDecodeException(AudioDecodeException.Reason.MISSING_FILE, "No permission to read audio file", e)
        } catch (e: RuntimeException) {
            ex.release()
            throw AudioDecodeException(AudioDecodeException.Reason.CORRUPTED, "Audio file is corrupted", e)
        }
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
        }
        if (track < 0 || format == null) {
            ex.release()
            throw AudioDecodeException(AudioDecodeException.Reason.CORRUPTED, "No audio track found")
        }
        ex.selectTrack(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        var chosen: MediaCodec? = null
        var chosenInfo: MediaCodecInfo? = null
        for (candidate in decoderCandidates(mime)) {
            try {
                val c = MediaCodec.createByCodecName(candidate.name)
                c.configure(format, null, null, 0)
                c.start()
                chosen = c
                chosenInfo = candidate
                break
            } catch (e: Exception) {
                // try the next decoder (software fallback)
            }
        }
        if (chosen == null || chosenInfo == null) {
            ex.release()
            throw AudioDecodeException(AudioDecodeException.Reason.UNSUPPORTED_CODEC, "No decoder for $mime")
        }
        extractor = ex
        codec = chosen
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val result = StreamInfo(mime, sampleRate, channels, duration, pcm, chosenInfo.name, !isSoftware(chosenInfo))
        info = result
        return result
    }

    /** Decodes to the end (or until [onChunk] returns false). [onChunk] gets interleaved floats, frame count and pts. */
    fun decode(onChunk: (samples: FloatArray, frames: Int, ptsUs: Long) -> Boolean) {
        val c = codec ?: throw IllegalStateException("open() must be called first")
        val ex = extractor ?: throw IllegalStateException("open() must be called first")
        val bi = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var scratch = FloatArray(0)
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = c.getInputBuffer(ii)!!
                        val size = ex.readSampleData(ib, 0)
                        if (size < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(bi, 10_000)
                if (oi >= 0) {
                    var keepGoing = true
                    if (bi.size > 0) {
                        val ob = c.getOutputBuffer(oi)!!
                        ob.position(bi.offset)
                        ob.limit(bi.offset + bi.size)
                        val needed = bi.size / pcm.bytesPerSample
                        if (scratch.size < needed) scratch = FloatArray(needed)
                        val count = PcmConverter.toFloat(ob, pcm, scratch)
                        keepGoing = onChunk(scratch, count / channels, bi.presentationTimeUs)
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (!keepGoing || bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    readOutputFormat(c.outputFormat)
                }
            }
        } catch (e: IllegalStateException) {
            throw AudioDecodeException(AudioDecodeException.Reason.DECODE_FAILURE, "Decoder failed", e)
        } catch (e: MediaCodec.CodecException) {
            throw AudioDecodeException(AudioDecodeException.Reason.DECODE_FAILURE, "Decoder failed", e)
        }
    }

    fun seekTo(positionUs: Long) {
        extractor?.seekTo(positionUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec?.flush()
    }

    private fun readOutputFormat(f: MediaFormat) {
        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        pcm = if (Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            when (f.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                4 -> PcmFormat.PCM_FLOAT
                21 -> PcmFormat.PCM_24
                22 -> PcmFormat.PCM_32
                else -> PcmFormat.PCM_16
            }
        } else PcmFormat.PCM_16
    }

    private fun decoderCandidates(mime: String): List<MediaCodecInfo> {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        val (software, hardware) = all.partition { isSoftware(it) }
        return hardware + software
    }

    private fun isSoftware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
        else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")

    override fun close() {
        try { codec?.stop() } catch (_: Exception) {}
        codec?.release()
        extractor?.release()
        codec = null
        extractor = null
    }
}
