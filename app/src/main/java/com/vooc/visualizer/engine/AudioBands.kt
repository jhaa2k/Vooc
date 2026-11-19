package com.vooc.visualizer.engine

data class AudioBands(val bass: Float, val mid: Float, val treble: Float)

class AudioBandsAnalyzer(private val sampleRate: Int, private val fftSize: Int = 2048) {
    private val fft = FFTProcessor(fftSize)

    fun analyze(samples: FloatArray): AudioBands {
        val spectrum = fft.magnitudes(samples)
        fun average(lowHz: Double, highHz: Double): Float {
            val first = (lowHz * fftSize / sampleRate).toInt().coerceAtLeast(1)
            val last = (highHz * fftSize / sampleRate).toInt().coerceAtMost(spectrum.lastIndex)
            if (last < first) return 0f
            var sum = 0f
            for (i in first..last) sum += spectrum[i]
            return (sum / (last - first + 1)).coerceIn(0f, 1f)
        }
        return AudioBands(
            bass = average(20.0, 250.0),
            mid = average(250.0, 4000.0),
            treble = average(4000.0, 16000.0)
        )
    }
}
