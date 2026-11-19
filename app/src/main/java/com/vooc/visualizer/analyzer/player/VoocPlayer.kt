package com.vooc.visualizer.analyzer.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.common.audio.BaseAudioProcessor
import com.vooc.visualizer.analyzer.AudioAnalyzer
import com.vooc.visualizer.analyzer.PcmFormat
import java.nio.ByteBuffer

/**
 * Pass-through ExoPlayer audio processor that copies the in-app playback PCM into the analyzer.
 * Only audio played by this player is analyzed (no microphone, no system capture, no other apps).
 */
@UnstableApi
class AnalyzerTapAudioProcessor(private val analyzer: AudioAnalyzer) : BaseAudioProcessor() {
    private var format = PcmFormat.PCM_16
    private var channels = 2

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        format = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> PcmFormat.PCM_16
            C.ENCODING_PCM_24BIT -> PcmFormat.PCM_24
            C.ENCODING_PCM_32BIT -> PcmFormat.PCM_32
            C.ENCODING_PCM_FLOAT -> PcmFormat.PCM_FLOAT
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        analyzer.setSampleRate(inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        analyzer.pushPcm(inputBuffer, format, channels)
        val out = replaceOutputBuffer(size)
        out.put(inputBuffer)
        out.flip()
    }
}

object VoocPlayer {
    /** Builds an ExoPlayer whose audio output is mirrored into [analyzer]; seeks/track changes re-anchor it. */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun create(context: Context, analyzer: AudioAnalyzer): ExoPlayer {
        val tap = AnalyzerTapAudioProcessor(analyzer)
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(tap))
                .build()
        }
        val player = ExoPlayer.Builder(context, renderers).build()
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                analyzer.onStreamDiscontinuity(newPosition.positionMs / 1000.0)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                analyzer.onStreamDiscontinuity(0.0)
            }
        })
        return player
    }
}
