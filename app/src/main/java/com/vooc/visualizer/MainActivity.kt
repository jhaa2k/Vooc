package com.vooc.visualizer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

private val Lime = Color(0xFFC6F36A)
private val Ink = Color(0xFF10120E)
private val Panel = Color(0xFF1A1D17)

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent {
            VoocTheme {
                VisualizerHome(
                    player = player!!,
                    onPickAudio = { uri -> player?.setMediaItem(MediaItem.fromUri(uri)); player?.prepare() }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}

@Composable
private fun VoocTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Lime, onPrimary = Ink, background = Ink, surface = Panel,
            onSurface = Color(0xFFE8EBDD), secondary = Color(0xFF9DAA85)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisualizerHome(player: ExoPlayer, onPickAudio: (Uri) -> Unit) {
    var trackName by remember { mutableStateOf("No track selected") }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onPickAudio(uri)
            trackName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected audio"
            playing = false
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                duration = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            delay(250)
        }
    }

    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = { Text("VOOC", letterSpacing = 4.sp) },
                actions = {
                    TextButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                        Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("IMPORT")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { picker.launch(arrayOf("audio/*")) },
                shape = RectangleShape,
                containerColor = Lime,
                contentColor = Ink
            ) { Icon(Icons.Default.Add, contentDescription = "Add audio") }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("AUDIO VISUALIZER STUDIO", color = Lime, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp)
                    Text("Create to the rhythm.", style = MaterialTheme.typography.headlineMedium)
                }
            }
            item {
                Canvas(Modifier.fillMaxWidth().height(230.dp).background(Color.Black)) {
                    val count = 72
                    val gap = 3.dp.toPx()
                    val barWidth = (size.width - gap * (count - 1)) / count
                    for (i in 0 until count) {
                        val wave = (0.12f + 0.78f * kotlin.math.abs(kotlin.math.sin(i * 0.19f)) *
                            (0.55f + 0.45f * kotlin.math.cos(i * 0.071f))).coerceIn(0.08f, 0.95f)
                        val h = size.height * wave
                        drawRoundRect(
                            color = Lime.copy(alpha = 0.45f + wave * 0.55f),
                            topLeft = Offset(i * (barWidth + gap), (size.height - h) / 2),
                            size = androidx.compose.ui.geometry.Size(barWidth, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())
                        )
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().background(Panel).padding(16.dp)) {
                    Text("NOW PLAYING", color = Lime, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(trackName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Slider(
                        value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                        onValueChange = { position = it.toLong() },
                        onValueChangeFinished = { player.seekTo(position) },
                        valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()),
                        colors = SliderDefaults.colors(thumbColor = Lime, activeTrackColor = Lime)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(formatTime(position), style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                        IconButton(onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                        }) {
                            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", tint = Lime)
                        }
                        Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                    }
                }
            }
            item {
                Text("EDITOR", color = Lime, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("LAYERS", "EFFECTS", "AUDIO").forEach {
                        AssistChip(onClick = {}, label = { Text(it) }, shape = RectangleShape)
                    }
                }
                Text("Import a track to begin. Visualizer editing and export modules are being added in the next implementation phases.",
                    color = Color(0xFF9DA394), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
