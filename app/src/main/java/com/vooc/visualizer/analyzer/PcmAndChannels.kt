package com.vooc.visualizer.analyzer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

enum class PcmFormat(val bytesPerSample: Int) { PCM_16(2), PCM_24(3), PCM_32(4), PCM_FLOAT(4) }

object PcmConverter {
    /**
     * Converts the remaining little-endian samples of [buffer] into [out].
     * Does NOT consume [buffer]. Returns the number of samples written.
     */
    fun toFloat(buffer: ByteBuffer, format: PcmFormat, out: FloatArray): Int {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val count = min(b.remaining() / format.bytesPerSample, out.size)
        var p = b.position()
        for (i in 0 until count) {
            out[i] = when (format) {
                PcmFormat.PCM_16 -> b.getShort(p) / 32768f
                PcmFormat.PCM_24 -> {
                    val v = (b.get(p).toInt() and 0xFF) or
                        ((b.get(p + 1).toInt() and 0xFF) shl 8) or
                        (b.get(p + 2).toInt() shl 16)
                    v / 8388608f
                }
                PcmFormat.PCM_32 -> b.getInt(p) / 2147483648f
                PcmFormat.PCM_FLOAT -> b.getFloat(p)
            }
            p += format.bytesPerSample
        }
        return count
    }
}

object ChannelMixer {
    /** Splits interleaved samples into L/R. Mono input feeds both; channels beyond 2 are ignored. */
    fun deinterleave(src: FloatArray, frames: Int, channels: Int, left: FloatArray, right: FloatArray) {
        require(channels >= 1) { "channels must be >= 1" }
        for (f in 0 until frames) {
            val base = f * channels
            val l = src[base]
            left[f] = l
            right[f] = if (channels > 1) src[base + 1] else l
        }
    }

    fun mono(l: Float, r: Float): Float = (l + r) * 0.5f
    fun mid(l: Float, r: Float): Float = (l + r) * 0.5f
    fun side(l: Float, r: Float): Float = (l - r) * 0.5f

    fun mix(select: ChannelSelect, left: FloatArray, right: FloatArray, n: Int, out: FloatArray) {
        for (i in 0 until n) {
            out[i] = when (select) {
                ChannelSelect.MIX, ChannelSelect.MID -> mid(left[i], right[i])
                ChannelSelect.LEFT -> left[i]
                ChannelSelect.RIGHT -> right[i]
                ChannelSelect.SIDE -> side(left[i], right[i])
            }
        }
    }
}
