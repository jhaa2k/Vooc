package com.vooc.visualizer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.launch

@Composable
internal fun PlayerScreen(player: Player, trackName: String, playing: Boolean, position: Long, duration: Long,
                         picker: androidx.activity.result.ActivityResultLauncher<Array<String>>, modifier: Modifier = Modifier) {
    var shuffle by remember { mutableStateOf(player.shuffleModeEnabled) }
    var repeat by remember { mutableIntStateOf(player.repeatMode) }
    var seek by remember { mutableFloatStateOf(position.toFloat()) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    LaunchedEffect(position) { if (!player.isPlaying) seek = position.toFloat() }
    Column(
        modifier.fillMaxSize().offset(y = (-8).dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("MUSIC LIBRARY / PLAYER", color = Lime, letterSpacing = 2.sp)
        Box(Modifier.fillMaxWidth().height(76.dp).background(Panel), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("♫", color = Lime, style = MaterialTheme.typography.headlineMedium)
                Text(trackName.uppercase(), color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("QUEUE  •  ${player.mediaItemCount} TRACKS", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
            IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Lime, modifier = Modifier.size(42.dp))
            }
            IconButton(onClick = { player.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
        }
        ThinSlider(
            label = "PLAYBACK SEEK",
            value = seek.coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            resetValue = 0f,
            onValueChange = { seek = it },
            onValueChangeFinished = { player.seekTo(seek.toLong()) },
            onDoubleTapReset = { seek = 0f; player.seekTo(0L) }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(formatTime(duration), color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("PLAYBACK SPEED", color = Muted, style = MaterialTheme.typography.labelSmall)
                Text(String.format("%.2fx", speed), color = Lime, style = MaterialTheme.typography.labelSmall)
            }
            ThinSlider(
                label = "PLAYBACK SPEED",
                value = speed,
                valueRange = 0.5f..2f,
                resetValue = 1f,
                steps = 5,
                onValueChange = { speed = it; player.setPlaybackParameters(androidx.media3.common.PlaybackParameters(it, 1f)) },
                onDoubleTapReset = {
                    speed = 1f
                    player.setPlaybackParameters(androidx.media3.common.PlaybackParameters(1f, 1f))
                }
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { shuffle = !shuffle; player.shuffleModeEnabled = shuffle }) {
                Text(if (shuffle) "SHUFFLE ON" else "SHUFFLE OFF", color = if (shuffle) Lime else Muted)
            }
            TextButton(onClick = {
                repeat = when (repeat) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                player.repeatMode = repeat
            }) {
                Text(when (repeat) { Player.REPEAT_MODE_ALL -> "REPEAT ALL"; Player.REPEAT_MODE_ONE -> "REPEAT ONE"; else -> "REPEAT OFF" },
                    color = if (repeat == Player.REPEAT_MODE_OFF) Muted else Lime)
            }
        }
        Text("UP NEXT", color = Lime, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
        Column(Modifier.heightIn(min = 64.dp, max = 120.dp).fillMaxWidth().background(Panel)) {
            if (player.mediaItemCount == 0) {
                Text("YOUR QUEUE IS EMPTY. IMPORT AUDIO TO ADD TRACKS.", color = Muted, modifier = Modifier.padding(14.dp))
            } else {
                for (index in 0 until player.mediaItemCount) {
                    val item = player.getMediaItemAt(index)
                    val title = item.mediaMetadata.title?.toString()
                        ?: item.localConfiguration?.uri?.lastPathSegment ?: "Track ${index + 1}"
                    Row(Modifier.fillMaxWidth().clickable { player.seekTo(index, 0L); player.play() }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text("${index + 1}.", color = Muted, modifier = Modifier.width(28.dp))
                        Text(title, color = if (index == player.currentMediaItemIndex) Lime else Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                        IconButton(onClick = { player.removeMediaItem(index) }) {
                            Icon(Icons.Default.Delete, "Remove track", tint = Muted)
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth(), shape = RectangleShape) {
            Icon(Icons.Default.FolderOpen, null); Text("IMPORT MUSIC")
        }
    }
}

internal fun formatTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
