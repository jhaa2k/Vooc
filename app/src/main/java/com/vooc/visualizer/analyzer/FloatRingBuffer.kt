package com.vooc.visualizer.analyzer

import kotlin.math.max
import kotlin.math.min

/**
 * Sliding-history ring buffer addressed by absolute sample index, so the analysis window can follow the
 * audible playback position instead of the (earlier) decoded position.
 *
 * - Overrun: writes larger than the capacity drop the oldest samples ([overrunDrops]).
 * - Underrun: requested samples that are not available are filled with silence ([underrunFills]).
 */
class FloatRingBuffer(val capacity: Int) {
    init { require(capacity > 0) { "capacity must be > 0" } }

    private val data = FloatArray(capacity)
    private var total = 0L
    private var origin = 0L

    var overrunDrops = 0L
        private set
    var underrunFills = 0L
        private set

    /** Absolute index one past the newest sample. */
    val totalWritten: Long
        @Synchronized get() = total

    @Synchronized
    fun clear(startIndex: Long = 0L) {
        total = startIndex
        origin = startIndex
    }

    @Synchronized
    fun write(src: FloatArray, off: Int, len: Int) {
        if (len <= 0) return
        var o = off
        var l = len
        if (l > capacity) {
            val d = l - capacity
            overrunDrops += d
            o += d
            l = capacity
            total += d
        }
        val w = (total % capacity).toInt()
        val first = min(l, capacity - w)
        System.arraycopy(src, o, data, w, first)
        if (l > first) System.arraycopy(src, o + first, data, 0, l - first)
        total += l
    }

    /**
     * Copies the [len] samples that end (exclusive) at absolute index [endExclusive] into [dst], oldest first.
     * [endExclusive] is clamped to the newest sample. Missing samples are zero. Returns the real sample count.
     */
    @Synchronized
    fun copyWindowEnding(endExclusive: Long, dst: FloatArray, len: Int): Int {
        val end = min(endExclusive, total)
        val start = end - len
        val oldest = max(origin, total - capacity)
        val validStart = max(start, oldest)
        val missingHead = if (validStart > end) len else (validStart - start).toInt()
        for (i in 0 until missingHead) dst[i] = 0f
        var real = 0
        if (validStart < end) {
            real = (end - validStart).toInt()
            var idx = (validStart % capacity).toInt()
            var out = missingHead
            var remaining = real
            while (remaining > 0) {
                val chunk = min(remaining, capacity - idx)
                System.arraycopy(data, idx, dst, out, chunk)
                out += chunk
                remaining -= chunk
                idx = 0
            }
        }
        if (real < len) underrunFills += (len - real)
        return real
    }
}
