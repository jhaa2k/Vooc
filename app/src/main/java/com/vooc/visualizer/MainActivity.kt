package com.vooc.visualizer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vooc.visualizer.analyzer.AnalysisFrame
import com.vooc.visualizer.analyzer.AudioAnalyzer
import com.vooc.visualizer.analyzer.player.VoocPlayer
import com.vooc.visualizer.studio.LayerKind
import com.vooc.visualizer.studio.ProjectStore
import com.vooc.visualizer.studio.StudioProject
import com.vooc.visualizer.studio.VisualLayer
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val Lime = Color(0xFFC6F36A)
private val Ink = Color(0xFF0C0E0B)
private val Panel = Color(0xFF171A14)
private val Muted = Color(0xFF92998A)

class MainActivity : ComponentActivity() {
    private val analyzer = AudioAnalyzer()
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = VoocPlayer.create(this, analyzer)
        val restored = ProjectStore.load(this) ?: StudioProject()
        setContent {
            VoocTheme {
                VoocStudio(
                    player = player!!,
                    analyzer = analyzer,
                    initialProject = restored,
                    onSave = { ProjectStore.save(this, it) },
                    onPickAudio = { uris ->
                        uris.forEach { uri ->
                            runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        }
                        player?.setMediaItems(uris.map { MediaItem.fromUri(it) })
                        player?.prepare()
                        player?.playWhenReady = true
                    }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Playback remains in-app while the activity is visible. A MediaSessionService owns
        // background playback in the next service integration; do not pretend this is enabled yet.
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
        colorScheme = darkColorScheme(primary = Lime, onPrimary = Ink, background = Ink,
            surface = Panel, onSurface = Color(0xFFE8EBDD), secondary = Color(0xFF9DAA85)),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoocStudio(
    player: ExoPlayer,
    analyzer: AudioAnalyzer,
    initialProject: StudioProject,
    onSave: (StudioProject) -> Unit,
    onPickAudio: (List<Uri>) -> Unit
) {
    var project by remember { mutableStateOf(initialProject) }
    var selectedId by remember { mutableLongStateOf(project.layers.lastOrNull()?.id ?: 0L) }
    var expandedSection by remember { mutableStateOf("TRANSFORM") }
    var showLayerMenu by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf("STUDIO") }
    var playing by remember { mutableStateOf(false) }
    var trackName by remember { mutableStateOf("No audio imported") }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var frame by remember { mutableStateOf<AnalysisFrame?>(null) }
    val selected = project.layers.firstOrNull { it.id == selectedId }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            onPickAudio(uris)
            trackName = if (uris.size == 1) (uris.first().lastPathSegment?.substringAfterLast('/') ?: "Audio track")
                else "${uris.size} tracks in queue"
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) { duration = player.duration.coerceAtLeast(0L) }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                trackName = mediaItem?.localConfiguration?.uri?.lastPathSegment ?: trackName
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, analyzer) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            if (player.isPlaying) frame = analyzer.analyzeLive(player.currentPosition / 1000.0)
            delay(16)
        }
    }

    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VOOC", letterSpacing = 4.sp, color = Lime, style = MaterialTheme.typography.titleLarge)
                        Text("AUDIO VISUALIZER STUDIO", color = Muted, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { onSave(project) }) { Icon(Icons.Default.Save, "Save project", tint = Lime) }
                    IconButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Icon(Icons.Default.FolderOpen, "Import audio", tint = Lime) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
                listOf("STUDIO", "PLAYER", "EXPORT").forEach { item ->
                    NavigationBarItem(
                        selected = screen == item,
                        onClick = { screen = item },
                        icon = { Text(when(item) { "STUDIO" -> "◈"; "PLAYER" -> "♫"; else -> "⇧" }, color = if (screen == item) Lime else Muted) },
                        label = { Text(item, letterSpacing = 1.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedTextColor = Lime, indicatorColor = Color(0xFF303A20))
                    )
                }
            }
        }
    ) { padding ->
        when (screen) {
            "PLAYER" -> PlayerScreen(player, trackName, playing, position, duration, picker)
            "EXPORT" -> ExportScreen(project)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(project.name.uppercase(), color = Color.White, style = MaterialTheme.typography.titleSmall, letterSpacing = 1.5.sp)
                        Text("${project.width} × ${project.height}  •  ${project.fps} FPS", color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                item {
                    VisualizerPreview(
                        modifier = Modifier.fillMaxWidth().height(220.dp).border(1.dp, Color(0xFF343A2B)),
                        layers = project.layers.filter { it.enabled },
                        frame = frame,
                        playing = playing
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("LAYERS", color = Lime, style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp)
                        Box {
                            OutlinedButton(onClick = { showLayerMenu = true }, shape = RectangleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) {
                                Icon(Icons.Default.Add, null, tint = Lime); Text("ADD LAYER")
                            }
                            DropdownMenu(expanded = showLayerMenu, onDismissRequest = { showLayerMenu = false },
                                modifier = Modifier.background(Panel)) {
                                LayerKind.entries.forEach { kind ->
                                    DropdownMenuItem(text = { Text(kind.title) }, onClick = {
                                        val id = (project.layers.maxOfOrNull { it.id } ?: 0L) + 1L
                                        val layer = VisualLayer(id, kind)
                                        project = project.copy(layers = project.layers + layer)
                                        selectedId = id
                                        showLayerMenu = false
                                    })
                                }
                            }
                        }
                    }
                }
                items(project.layers.reversed(), key = { it.id }) { layer ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (layer.id == selectedId) Color(0xFF29321D) else Panel)
                            .clickable { selectedId = layer.id }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = layer.enabled, onCheckedChange = { checked ->
                            project = project.copy(layers = project.layers.map { if (it.id == layer.id) it.copy(enabled = checked) else it })
                        }, colors = CheckboxDefaults.colors(checkedColor = Lime, uncheckedColor = Muted))
                        Column(Modifier.weight(1f)) {
                            Text(layer.name, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            Text(layer.kind.name, color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = {
                            project = project.copy(layers = project.layers.filterNot { it.id == layer.id })
                            if (selectedId == layer.id) selectedId = project.layers.lastOrNull()?.id ?: 0L
                        }) { Icon(Icons.Default.Delete, "Delete layer", tint = Muted) }
                    }
                }
                if (selected != null) {
                    item {
                        Text("PROPERTIES  /  ${selected.name.uppercase()}", color = Lime, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.5.sp)
                    }
                    item {
                        EditorSection("TRANSFORM", expandedSection, { expandedSection = if (expandedSection == "TRANSFORM") "" else "TRANSFORM" }) {
                            LayerSlider("POSITION X", selected.x, -100f..100f) { v -> updateLayer(project, selectedId) { it.copy(x = v) }.also { project = it } }
                            LayerSlider("POSITION Y", selected.y, -100f..100f) { v -> updateLayer(project, selectedId) { it.copy(y = v) }.also { project = it } }
                            LayerSlider("SCALE", selected.scale, 0f..300f) { v -> updateLayer(project, selectedId) { it.copy(scale = v) }.also { project = it } }
                            LayerSlider("ROTATION", selected.rotation, -180f..180f) { v -> updateLayer(project, selectedId) { it.copy(rotation = v) }.also { project = it } }
                        }
                    }
                    item {
                        EditorSection("APPEARANCE", expandedSection, { expandedSection = if (expandedSection == "APPEARANCE") "" else "APPEARANCE" }) {
                            LayerSlider("OPACITY", selected.opacity, 0f..100f) { v -> updateLayer(project, selectedId) { it.copy(opacity = v) }.also { project = it } }
                            LayerSlider("AUDIO SENSITIVITY", selected.sensitivity, 0f..300f) { v -> updateLayer(project, selectedId) { it.copy(sensitivity = v) }.also { project = it } }
                            LayerSlider("SMOOTHING", selected.smoothing, 0f..100f) { v -> updateLayer(project, selectedId) { it.copy(smoothing = v) }.also { project = it } }
                        }
                    }
                    item {
                        EditorSection("COLOR", expandedSection, { expandedSection = if (expandedSection == "COLOR") "" else "COLOR" }) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0xFFC6F36A, 0xFFFFFFFF, 0xFF55D6FF, 0xFFFF5F7A, 0xFFB58CFF, 0xFFFFB454).forEach { value ->
                                    Box(Modifier.size(32.dp).background(Color(value)).clickable {
                                        project = updateLayer(project, selectedId) { it.copy(color = value) }
                                    }.border(if (selected.color == value) 2.dp else 0.dp, Color.White))
                                }
                            }
                        }
                    }
                }
                item {
                    Button(onClick = { onSave(project) }, modifier = Modifier.fillMaxWidth(), shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) {
                        Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("SAVE PROJECT")
                    }
                }
            }
        }
    }
}

private fun updateLayer(project: StudioProject, id: Long, transform: (VisualLayer) -> VisualLayer): StudioProject =
    project.copy(layers = project.layers.map { if (it.id == id) transform(it) else it })

@Composable
private fun VisualizerPreview(modifier: Modifier, layers: List<VisualLayer>, frame: AnalysisFrame?, playing: Boolean) {
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        while (playing) { phase += 0.09f; delay(16) }
    }
    Canvas(modifier.background(Color.Black)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val spectrum = frame?.spectrum ?: FloatArray(64)
        val waveform = frame?.waveform ?: FloatArray(64)
        layers.forEach { layer ->
            val color = Color(layer.color).copy(alpha = (layer.opacity / 100f).coerceIn(0f, 1f))
            val scale = layer.scale / 100f
            val center = Offset(cx + layer.x / 100f * size.width, cy + layer.y / 100f * size.height)
            when (layer.kind) {
                LayerKind.BACKGROUND -> drawRect(Color(0xFF070906))
                LayerKind.BARS, LayerKind.BARSH, LayerKind.BLOCKS, LayerKind.GRID -> {
                    val n = if (layer.kind == LayerKind.GRID) 32 else 64
                    val gap = size.width * 0.004f
                    val bw = (size.width - gap * (n - 1)) / n
                    for (i in 0 until n) {
                        val v = if (spectrum.isNotEmpty()) spectrum[i * spectrum.size / n].coerceIn(0f, 1f) else 0f
                        val h = (size.height * (0.03f + v * 0.72f * layer.sensitivity / 100f) * scale).coerceAtMost(size.height)
                        val x = i * (bw + gap)
                        if (layer.kind == LayerKind.BARSH) {
                            drawLine(color, Offset(x + bw/2, cy - h/2), Offset(x + bw/2, cy + h/2), bw.coerceAtLeast(1f))
                        } else {
                            drawRect(color, Offset(x, size.height - h), androidx.compose.ui.geometry.Size(bw.coerceAtLeast(1f), h))
                        }
                    }
                }
                LayerKind.WAVE, LayerKind.WAVEC, LayerKind.SCOPE -> {
                    val values = if (waveform.isNotEmpty()) waveform else FloatArray(64)
                    val step = size.width / (values.size - 1).coerceAtLeast(1)
                    for (i in 1 until values.size) {
                        drawLine(color, Offset((i-1)*step, cy + values[i-1]*cy*0.85f*scale),
                            Offset(i*step, cy + values[i]*cy*0.85f*scale), 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
                LayerKind.CIRCLE, LayerKind.RING, LayerKind.MULTIRING, LayerKind.CONCENTRIC, LayerKind.PULSE -> {
                    val bass = frame?.band(0) ?: 0f
                    val radius = size.minDimension * 0.18f * scale * (1f + bass * 0.35f)
                    val rings = if (layer.kind == LayerKind.MULTIRING || layer.kind == LayerKind.CONCENTRIC) 4 else 1
                    repeat(rings) { i ->
                        drawCircle(color.copy(alpha = color.alpha / (i + 1)), radius = radius + i * 12.dp.toPx(),
                            center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                    }
                    if (layer.kind == LayerKind.CIRCLE || layer.kind == LayerKind.PULSE) {
                        for (i in 0 until 64) {
                            val v = spectrum.getOrElse(i * spectrum.size / 64) { 0f }
                            val a = i * (2f * PI.toFloat() / 64f)
                            val r1 = radius + 3.dp.toPx()
                            val r2 = r1 + v * 46.dp.toPx() * layer.sensitivity / 100f
                            drawLine(color, Offset(center.x + cos(a)*r1, center.y + sin(a)*r1),
                                Offset(center.x + cos(a)*r2, center.y + sin(a)*r2), 2.dp.toPx())
                        }
                    }
                }
                LayerKind.DOTS, LayerKind.PARTICLES -> {
                    for (i in 0 until 48) {
                        val a = i * 0.41f + phase
                        val r = (size.minDimension * (0.08f + (i % 7) * 0.035f)) * scale
                        val v = spectrum.getOrElse(i * spectrum.size / 48) { 0f }
                        drawCircle(color, radius = (1.5f + v * 4f).dp.toPx(), center = Offset(center.x + cos(a)*r, center.y + sin(a)*r))
                    }
                }
                LayerKind.LINES, LayerKind.SHAPE -> {
                    for (i in 0 until 8) {
                        val x = size.width * i / 8f
                        drawLine(color, Offset(x, 0f), Offset(size.width - x, size.height), 1.dp.toPx())
                    }
                }
                LayerKind.TEXT -> drawContext.canvas.nativeCanvas.drawText("VOOC", center.x, center.y, android.graphics.Paint().apply {
                    this.color = color.toArgb(); textSize = 26.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
                })
                LayerKind.IMAGE -> drawCircle(color.copy(alpha = 0.35f), radius = 22.dp.toPx(), center = center)
            }
        }
        if (layers.isEmpty()) drawContext.canvas.nativeCanvas.drawText("ADD A LAYER TO START", cx, cy, android.graphics.Paint().apply {
            color = android.graphics.Color.GRAY; textSize = 12.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
        })
    }
}

@Composable
private fun EditorSection(title: String, expanded: String, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color.White, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp)
            Text(if (expanded == title) "−" else "+", color = Lime)
        }
        if (expanded == title) Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), content = content)
    }
}

@Composable
private fun LayerSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(value.toInt().toString(), color = Lime, style = MaterialTheme.typography.labelSmall)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Lime, activeTrackColor = Lime, inactiveTrackColor = Color(0xFF42483A)))
    }
}

@Composable
private fun PlayerScreen(player: ExoPlayer, trackName: String, playing: Boolean, position: Long, duration: Long,
                         picker: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("MUSIC PLAYER", color = Lime, letterSpacing = 2.sp)
        Box(Modifier.fillMaxWidth().weight(1f).background(Panel), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("♫", color = Lime, style = MaterialTheme.typography.displayLarge)
                Text(trackName, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("QUEUE  •  ${player.mediaItemCount} TRACKS", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Slider(value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
            onValueChange = { player.seekTo(it.toLong()) }, valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            colors = SliderDefaults.colors(thumbColor = Lime, activeTrackColor = Lime))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
            IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Lime, modifier = Modifier.size(42.dp))
            }
            IconButton(onClick = { player.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth(), shape = RectangleShape) {
            Icon(Icons.Default.FolderOpen, null); Text("IMPORT MUSIC")
        }
    }
}

@Composable
private fun ExportScreen(project: StudioProject) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("VIDEO EXPORT", color = Lime, letterSpacing = 2.sp, style = MaterialTheme.typography.titleMedium)
        Text("PROJECT OUTPUT", color = Muted, style = MaterialTheme.typography.labelSmall)
        Text("${project.width} × ${project.height}  •  ${project.fps} FPS", color = Color.White)
        Text("MP4 / H.264", color = Color.White)
        Text("Export rendering is not wired into this build yet. This screen is intentionally not showing a fake progress bar or a false success state.", color = Muted)
    }
}

private fun formatTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
