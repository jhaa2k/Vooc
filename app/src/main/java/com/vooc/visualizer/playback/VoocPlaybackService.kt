package com.vooc.visualizer.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.vooc.visualizer.analyzer.AudioAnalyzer
import com.vooc.visualizer.analyzer.AnalyzerConfig
import com.vooc.visualizer.analyzer.ChannelMode
import com.vooc.visualizer.analyzer.player.VoocPlayer

/** Shared analyzer instance: the service's decoded PCM is the source for the studio preview. */
object VoocAudioEngine {
    val analyzer: AudioAnalyzer by lazy { AudioAnalyzer(config = AnalyzerConfig(channelMode = ChannelMode.STEREO_SPLIT)) }
}

class VoocPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val player = VoocPlayer.create(this, VoocAudioEngine.analyzer)
        exoPlayer = player
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }
}
