package com.vooc.visualizer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vooc.visualizer.studio.StudioProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
internal fun ExportScreen(project: StudioProject, onProjectChange: (StudioProject) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var exportRunning by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("Ready to export") }
    val createVideo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null && !exportRunning) {
            exportRunning = true
            exportProgress = 0f
            exportStatus = "Preparing video encoder…"
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        com.vooc.visualizer.export.VideoExportEngine.export(context, context.contentResolver, project, uri) { value ->
                            exportProgress = value
                        }
                    }
                    exportStatus = "Export complete • ${result.frames} frames • ${result.durationMs / 1000}s" +
                        if (result.audioMuxed) " • AAC audio included" else " • no audio track"
                } catch (error: Exception) {
                    exportStatus = "Export failed: ${error.message ?: "Unknown encoder error"}"
                } finally {
                    exportRunning = false
                }
            }
        }
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("VIDEO EXPORT", color = Lime, letterSpacing = 2.sp, style = MaterialTheme.typography.titleMedium)
        Text("OUTPUT RESOLUTION", color = Muted, style = MaterialTheme.typography.labelSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(854 to 480, 1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160).forEach { size ->
                OutlinedButton(
                    onClick = { onProjectChange(project.copy(width = size.first, height = size.second)) },
                    enabled = !exportRunning, shape = RectangleShape,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (project.width == size.first) Lime else Color.White)
                ) { Text("${size.second}P") }
            }
        }
        Text("ASPECT RATIO", color = Muted, style = MaterialTheme.typography.labelSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("16:9" to (1920 to 1080), "9:16" to (1080 to 1920), "1:1" to (1080 to 1080),
                "4:3" to (1440 to 1080), "21:10" to (2520 to 1200)).forEach { ratio ->
                OutlinedButton(
                    onClick = { onProjectChange(project.copy(width = ratio.second.first, height = ratio.second.second)) },
                    enabled = !exportRunning, shape = RectangleShape,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (project.width * ratio.second.second == project.height * ratio.second.first) Lime else Color.White)
                ) { Text(ratio.first) }
            }
        }
        Text("FRAME RATE", color = Muted, style = MaterialTheme.typography.labelSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(24, 25, 30, 50, 60).forEach { rate ->
                OutlinedButton(
                    onClick = { onProjectChange(project.copy(fps = rate)) },
                    enabled = !exportRunning, shape = RectangleShape,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (project.fps == rate) Lime else Color.White)
                ) { Text("$rate FPS") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = project.document.export.includeAudio,
                enabled = !exportRunning,
                onCheckedChange = { enabled ->
                    onProjectChange(project.copy(document = project.document.copy(export = project.document.export.copy(includeAudio = enabled))))
                },
                colors = CheckboxDefaults.colors(checkedColor = Lime)
            )
            Text("INCLUDE PROJECT MUSIC", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Text("OUTPUT SUMMARY", color = Muted, style = MaterialTheme.typography.labelSmall)
        Text("${project.width} × ${project.height}  •  ${project.fps} FPS", color = Color.White)
        Text("MP4 CONTAINER  •  H.264 VIDEO" + if (project.document.export.includeAudio) "  •  AAC AUDIO" else "", color = Color.White)
        if (exportRunning) {
            LinearProgressIndicator(progress = { exportProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Lime, trackColor = Panel)
            Text("RENDERING ${(exportProgress * 100).toInt()}%", color = Lime, style = MaterialTheme.typography.labelSmall)
        }
        Text(exportStatus, color = if (exportStatus.startsWith("Export failed")) Color(0xFFFF8A80) else Muted, style = MaterialTheme.typography.labelSmall)
        Button(
            onClick = { createVideo.launch("${project.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.mp4") },
            enabled = !exportRunning,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
            shape = RectangleShape
        ) {
            Icon(Icons.Outlined.FileUpload, null)
            Spacer(Modifier.width(8.dp))
            Text(if (exportRunning) "EXPORTING…" else "RENDER & SAVE MP4")
        }
        Text(
            "AAC/M4A music is muxed with the video. Other audio formats are reported as unsupported rather than silently exported without sound.",
            color = Muted, style = MaterialTheme.typography.labelSmall
        )
    }
}
