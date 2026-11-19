package com.vooc.visualizer

import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.ComponentName
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
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import android.graphics.BitmapFactory
import com.vooc.visualizer.analyzer.ChannelSelect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.vooc.visualizer.analyzer.AnalysisFrame
import com.vooc.visualizer.analyzer.AudioAnalyzer
import com.vooc.visualizer.analyzer.FftSize
import com.vooc.visualizer.analyzer.ChannelMode
import com.vooc.visualizer.analyzer.WindowFunction
import com.vooc.visualizer.analyzer.Overlap
import com.vooc.visualizer.analyzer.BeatAlgorithm
import com.vooc.visualizer.playback.VoocAudioEngine
import com.vooc.visualizer.playback.VoocPlaybackService
import com.vooc.visualizer.studio.LayerKind
import com.vooc.visualizer.studio.ProjectStore
import com.vooc.visualizer.studio.StudioProject
import com.vooc.visualizer.studio.StudioPresets
import com.vooc.visualizer.studio.VisualLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val Lime = Color(0xFFC6F36A)
private val Ink = Color(0xFF0C0E0B)
private val Panel = Color(0xFF171A14)
private val Muted = Color(0xFF92998A)
private val Poppins = FontFamily(Font(com.vooc.visualizer.R.font.poppins_regular))

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoocTheme {
                Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
                    Text("CONNECTING TO VOOC PLAYER…", color = Lime, letterSpacing = 2.sp)
                }
            }
        }
        val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val token = SessionToken(this, ComponentName(this, VoocPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull() ?: return@addListener
            val restored = ProjectStore.load(this) ?: StudioProject()
            setContent {
                VoocTheme {
                    VoocStudio(
                        player = controller,
                        analyzer = VoocAudioEngine.analyzer,
                        initialProject = restored,
                        onSave = { ProjectStore.save(this, it) },
                        onPickAudio = { uris ->
                            uris.forEach { uri ->
                                runCatching {
                                    contentResolver.takePersistableUriPermission(
                                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    )
                                }
                            }
                            controller.setMediaItems(uris.map { MediaItem.fromUri(it) })
                            controller.prepare()
                            controller.playWhenReady = true
                        }
                    )
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onDestroy()
    }
}

@Composable
private fun VoocTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Lime, onPrimary = Ink, background = Ink,
            surface = Panel, onSurface = Color(0xFFE8EBDD), secondary = Color(0xFF9DAA85)),
        typography = Typography().let { base ->
            base.copy(
                displayLarge = base.displayLarge.copy(fontFamily = Poppins),
                displayMedium = base.displayMedium.copy(fontFamily = Poppins),
                displaySmall = base.displaySmall.copy(fontFamily = Poppins),
                headlineLarge = base.headlineLarge.copy(fontFamily = Poppins),
                headlineMedium = base.headlineMedium.copy(fontFamily = Poppins),
                headlineSmall = base.headlineSmall.copy(fontFamily = Poppins),
                titleLarge = base.titleLarge.copy(fontFamily = Poppins, fontSize = 18.sp, lineHeight = 22.sp),
                titleMedium = base.titleMedium.copy(fontFamily = Poppins, fontSize = 16.sp, lineHeight = 20.sp),
                titleSmall = base.titleSmall.copy(fontFamily = Poppins, fontSize = 14.sp, lineHeight = 18.sp),
                bodyLarge = base.bodyLarge.copy(fontFamily = Poppins, fontSize = 14.sp, lineHeight = 18.sp),
                bodyMedium = base.bodyMedium.copy(fontFamily = Poppins, fontSize = 13.sp, lineHeight = 17.sp),
                bodySmall = base.bodySmall.copy(fontFamily = Poppins, fontSize = 11.sp, lineHeight = 14.sp),
                labelLarge = base.labelLarge.copy(fontFamily = Poppins, fontSize = 12.sp, lineHeight = 16.sp),
                labelMedium = base.labelMedium.copy(fontFamily = Poppins, fontSize = 11.sp, lineHeight = 14.sp),
                labelSmall = base.labelSmall.copy(fontFamily = Poppins, fontSize = 10.sp, lineHeight = 12.sp)
            )
        },
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoocStudio(
    player: Player,
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
    var analyzerConfig by remember { mutableStateOf(analyzer.config) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && selectedId != 0L) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            project = updateLayer(project, selectedId) { it.copy(imageUri = uri.toString()) }
        }
    }
    val projectExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(ProjectStore.encode(project).toByteArray(Charsets.UTF_8)) }
        }
    }
    val projectImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            json?.let { ProjectStore.decode(it) }?.let { imported ->
                project = imported
                selectedId = imported.layers.lastOrNull()?.id ?: 0L
                onSave(imported)
            }
        }
    }
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
    LaunchedEffect(project) { delay(500); onSave(project) }
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
                    TextButton(onClick = { projectImport.launch(arrayOf("application/json", "text/*")) }) { Text("OPEN", color = Lime) }
                    TextButton(onClick = { projectExport.launch("vooc-project.json") }) { Text("JSON OUT", color = Lime) }
                    IconButton(onClick = { onSave(project) }) { Icon(Icons.Default.Save, "Save project", tint = Lime) }
                    IconButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Icon(Icons.Default.FolderOpen, "Import audio", tint = Lime) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
                listOf("LIBRARY", "STUDIO", "TEMPLATES", "EXPORT", "SETTINGS").forEach { item ->
                    NavigationBarItem(
                        selected = screen == item,
                        onClick = { screen = item },
                        icon = { Text(when(item) { "LIBRARY" -> "♫"; "STUDIO" -> "◈"; "TEMPLATES" -> "▦"; "EXPORT" -> "⇧"; else -> "⚙" }, color = if (screen == item) Lime else Muted) },
                        label = { Text(item, letterSpacing = 1.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedTextColor = Lime, indicatorColor = Color(0xFF303A20))
                    )
                }
            }
        }
    ) { padding ->
        when (screen) {
            "LIBRARY" -> Column(Modifier.fillMaxSize().padding(padding)) {
                PreviewPane(project, frame, playing, Modifier.fillMaxWidth().weight(0.40f).padding(horizontal = 10.dp, vertical = 6.dp))
                HorizontalDivider(color = Color(0xFF343A2B), thickness = 1.dp)
                PlayerScreen(player, trackName, playing, position, duration, picker, Modifier.weight(0.60f))
            }
            "EXPORT" -> Column(Modifier.fillMaxSize().padding(padding)) {
                PreviewPane(project, frame, playing, Modifier.fillMaxWidth().weight(0.40f).padding(horizontal = 10.dp, vertical = 6.dp))
                HorizontalDivider(color = Color(0xFF343A2B), thickness = 1.dp)
                ExportScreen(project, onProjectChange = { project = it; onSave(it) }, Modifier.weight(0.60f))
            }
            "TEMPLATES" -> Column(Modifier.fillMaxSize().padding(padding)) {
                PreviewPane(project, frame, playing, Modifier.fillMaxWidth().weight(0.40f).padding(horizontal = 10.dp, vertical = 6.dp))
                HorizontalDivider(color = Color(0xFF343A2B), thickness = 1.dp)
                TemplateLibraryScreen(project, onApply = { next ->
                    project = next
                    selectedId = next.layers.lastOrNull()?.id ?: 0L
                    onSave(next)
                }, Modifier.weight(0.60f))
            }
            "SETTINGS" -> Column(Modifier.fillMaxSize().padding(padding)) {
                PreviewPane(project, frame, playing, Modifier.fillMaxWidth().weight(0.40f).padding(horizontal = 10.dp, vertical = 6.dp))
                HorizontalDivider(color = Color(0xFF343A2B), thickness = 1.dp)
                AnalyzerSettingsScreen(analyzerConfig, onConfigChange = { next ->
                    analyzerConfig = next
                    analyzer.updateConfig(next)
                }, Modifier.weight(0.60f))
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                PreviewPane(project, frame, playing, Modifier.fillMaxWidth().weight(0.40f).padding(horizontal = 10.dp, vertical = 6.dp))
                HorizontalDivider(color = Color(0xFF343A2B), thickness = 1.dp)
                LazyColumn(
                    modifier = Modifier.weight(0.60f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                item {
                    Text("START WITH A PRESET", color = Muted, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        StudioPresets.names.forEach { preset ->
                            OutlinedButton(onClick = {
                                project = StudioPresets.create(preset)
                                selectedId = project.layers.lastOrNull()?.id ?: 0L
                            }, shape = RectangleShape, contentPadding = PaddingValues(horizontal = 11.dp, vertical = 5.dp)) {
                                Text(preset, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
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
                            Text(layer.name.uppercase(), color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(layer.kind.name, color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = {
                            project = project.copy(layers = project.layers.filterNot { it.id == layer.id })
                            if (selectedId == layer.id) selectedId = project.layers.lastOrNull()?.id ?: 0L
                        }) { Icon(Icons.Default.Delete, "Delete layer", tint = Muted) }
                        TextButton(onClick = {
                            val index = project.layers.indexOfFirst { it.id == layer.id }
                            if (index > 0) {
                                val list = project.layers.toMutableList()
                                val item = list.removeAt(index)
                                list.add(index - 1, item)
                                project = project.copy(layers = list)
                            }
                        }) { Text("↑", color = Muted) }
                        TextButton(onClick = {
                            val index = project.layers.indexOfFirst { it.id == layer.id }
                            if (index in 0 until project.layers.lastIndex) {
                                val list = project.layers.toMutableList()
                                val item = list.removeAt(index)
                                list.add(index + 1, item)
                                project = project.copy(layers = list)
                            }
                        }) { Text("↓", color = Muted) }
                        TextButton(onClick = {
                            val id = (project.layers.maxOfOrNull { it.id } ?: 0L) + 1L
                            project = project.copy(layers = project.layers + layer.copy(id = id, name = layer.name + " Copy"))
                            selectedId = id
                        }) { Text("DUP", color = Lime) }
                    }
                }
                item {
                    EditorSection("AUDIO ANALYZER", expandedSection, { expandedSection = if (expandedSection == "AUDIO ANALYZER") "" else "AUDIO ANALYZER" }) {
                        OptionDropdown("FFT SIZE", analyzerConfig.fftSize.size.toString(), FftSize.entries.map { it.size.toString() }) { value ->
                            analyzerConfig = analyzerConfig.copy(fftSize = FftSize.fromSize(value.toInt()))
                            analyzer.updateConfig(analyzerConfig)
                        }
                        OptionDropdown("CHANNEL MODE", analyzerConfig.channelMode.name, ChannelMode.entries.map { it.name }) { value ->
                            analyzerConfig = analyzerConfig.copy(channelMode = ChannelMode.valueOf(value))
                            analyzer.updateConfig(analyzerConfig)
                        }
                        OptionDropdown("WINDOW", analyzerConfig.window.name, WindowFunction.entries.map { it.name }) { value ->
                            analyzerConfig = analyzerConfig.copy(window = WindowFunction.valueOf(value))
                            analyzer.updateConfig(analyzerConfig)
                        }
                        OptionDropdown("OVERLAP", analyzerConfig.overlap.name, Overlap.entries.map { it.name }) { value ->
                            analyzerConfig = analyzerConfig.copy(overlap = Overlap.valueOf(value))
                            analyzer.updateConfig(analyzerConfig)
                        }
                        OptionDropdown("BEAT DETECTOR", analyzerConfig.beat.algorithm.name, BeatAlgorithm.entries.map { it.name }) { value ->
                            analyzerConfig = analyzerConfig.copy(beat = analyzerConfig.beat.copy(algorithm = BeatAlgorithm.valueOf(value)))
                            analyzer.updateConfig(analyzerConfig)
                        }
                        LayerSlider("BEAT SENSITIVITY", analyzerConfig.beat.sensitivity * 100f, 0f..100f) { value ->
                            analyzerConfig = analyzerConfig.copy(beat = analyzerConfig.beat.copy(sensitivity = value / 100f))
                            analyzer.updateConfig(analyzerConfig)
                        }
                        LayerSlider("MANUAL BPM (0 = AUTO)", analyzerConfig.beat.manualBpm ?: 0f, 0f..240f) { value ->
                            val bpm = if (value < 40f) null else value
                            analyzerConfig = analyzerConfig.copy(beat = analyzerConfig.beat.copy(manualBpm = bpm))
                            analyzer.updateConfig(analyzerConfig)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = analyzerConfig.outputDb, onCheckedChange = { checked ->
                                analyzerConfig = analyzerConfig.copy(outputDb = checked)
                                analyzer.updateConfig(analyzerConfig)
                            }, colors = CheckboxDefaults.colors(checkedColor = Lime))
                            Text("DECIBEL DISPLAY", color = Color.White)
                        }
                    }
                }
                if (selected != null) {
                    item {
                        Text("PROPERTIES  /  ${selected.name.uppercase()}", color = Lime, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.5.sp)
                        OutlinedTextField(value = selected.name, onValueChange = { value ->
                            project = updateLayer(project, selectedId) { it.copy(name = value) }
                        }, label = { Text("LAYER NAME") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                            LayerSlider("RED", android.graphics.Color.red(selected.color.toInt()).toFloat(), 0f..255f) { value ->
                                val r = value.toInt()
                                val g = android.graphics.Color.green(selected.color.toInt())
                                val b = android.graphics.Color.blue(selected.color.toInt())
                                val argb = 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
                                project = updateLayer(project, selectedId) { it.copy(color = argb) }
                            }
                            LayerSlider("GREEN", android.graphics.Color.green(selected.color.toInt()).toFloat(), 0f..255f) { value ->
                                val r = android.graphics.Color.red(selected.color.toInt())
                                val g = value.toInt()
                                val b = android.graphics.Color.blue(selected.color.toInt())
                                val argb = 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
                                project = updateLayer(project, selectedId) { it.copy(color = argb) }
                            }
                            LayerSlider("BLUE", android.graphics.Color.blue(selected.color.toInt()).toFloat(), 0f..255f) { value ->
                                val r = android.graphics.Color.red(selected.color.toInt())
                                val g = android.graphics.Color.green(selected.color.toInt())
                                val b = value.toInt()
                                val argb = 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
                                project = updateLayer(project, selectedId) { it.copy(color = argb) }
                            }
                            Text("SECONDARY / GRADIENT COLOR", color = Muted, style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0xFF55D6FF, 0xFFFFFFFF, 0xFFFF5F7A, 0xFFB58CFF, 0xFFFFB454, 0xFFC6F36A).forEach { value ->
                                    Box(Modifier.size(28.dp).background(Color(value)).clickable {
                                        project = updateLayer(project, selectedId) { it.copy(gradientColor = value) }
                                    }.border(if (selected.gradientColor == value) 2.dp else 0.dp, Color.White))
                                }
                            }
                        }
                    }
                    item {
                        EditorSection("AUDIO / MEASURE", expandedSection, { expandedSection = if (expandedSection == "AUDIO / MEASURE") "" else "AUDIO / MEASURE" }) {
                            OptionDropdown("MEASURE WHAT", selected.measureWhat, listOf("Spectrum", "Bass", "Mid", "Treble", "RMS", "Peak", "Beat", "Waveform")) { value ->
                                project = updateLayer(project, selectedId) { it.copy(measureWhat = value) }
                            }
                            OptionDropdown("CHANNEL", selected.channel, listOf("MIX", "LEFT", "RIGHT", "MID", "SIDE")) { value ->
                                project = updateLayer(project, selectedId) { it.copy(channel = value) }
                                val mode = if (value == "SIDE") ChannelMode.MID_SIDE else if (value == "LEFT" || value == "RIGHT") ChannelMode.STEREO_SPLIT else analyzerConfig.channelMode
                                if (mode != analyzerConfig.channelMode) {
                                    analyzerConfig = analyzerConfig.copy(channelMode = mode)
                                    analyzer.updateConfig(analyzerConfig)
                                }
                            }
                            LayerSlider("BAR / SAMPLE COUNT", selected.barCount.toFloat(), 8f..256f) { v ->
                                project = updateLayer(project, selectedId) { it.copy(barCount = v.toInt()) }
                            }
                        }
                    }
                    item {
                        EditorSection("STYLE / GEOMETRY", expandedSection, { expandedSection = if (expandedSection == "STYLE / GEOMETRY") "" else "STYLE / GEOMETRY" }) {
                            LayerSlider("THICKNESS", selected.thickness, 0.5f..16f) { v -> project = updateLayer(project, selectedId) { it.copy(thickness = v) } }
                            LayerSlider("SPACING", selected.spacing, 0f..12f) { v -> project = updateLayer(project, selectedId) { it.copy(spacing = v) } }
                            LayerSlider("RADIUS", selected.radius, 2f..48f) { v -> project = updateLayer(project, selectedId) { it.copy(radius = v) } }
                            LayerSlider("PARTICLE COUNT", selected.particleCount.toFloat(), 4f..256f) { v -> project = updateLayer(project, selectedId) { it.copy(particleCount = v.toInt()) } }
                        }
                    }
                    if (selected.kind == LayerKind.TEXT) {
                        item {
                            EditorSection("TEXT CONTENT", expandedSection, { expandedSection = if (expandedSection == "TEXT CONTENT") "" else "TEXT CONTENT" }) {
                                var textValue by remember(selected.id, selected.textContent) { mutableStateOf(selected.textContent) }
                                OutlinedTextField(value = textValue, onValueChange = { textValue = it; project = updateLayer(project, selectedId) { layer -> layer.copy(textContent = it) } },
                                    label = { Text("TEXT CONTENT") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        }
                    }
                    if (selected.kind == LayerKind.IMAGE) {
                        item {
                            OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }, shape = RectangleShape) {
                                Icon(Icons.Default.FolderOpen, null); Text(if (selected.imageUri.isBlank()) "CHOOSE IMAGE" else "CHANGE IMAGE")
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
}


@Composable
private fun PreviewPane(project: StudioProject, frame: AnalysisFrame?, playing: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(project.name.uppercase(), color = Color.White, style = MaterialTheme.typography.labelLarge,
                letterSpacing = 1.1.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${project.width} × ${project.height}  •  ${project.fps} FPS", color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        VisualizerPreview(modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, Color(0xFF343A2B)),
            layers = project.layers.filter { it.enabled }, frame = frame, playing = playing)
    }
}

@Composable
private fun TemplateLibraryScreen(project: StudioProject, onApply: (StudioProject) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item {
            Text("BUILT-IN TEMPLATE LIBRARY", color = Lime, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.2.sp)
            Text("SELECT A TEMPLATE TO UPDATE THE LIVE PREVIEW.", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        items(StudioPresets.names) { preset ->
            Row(Modifier.fillMaxWidth().background(Panel).clickable { onApply(StudioPresets.create(preset)) }
                .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(preset.uppercase(), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Text("${StudioPresets.create(preset).layers.size} LAYERS", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                Text(if (project.name.equals("$preset Preset", true)) "APPLIED" else "APPLY", color = Lime, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AnalyzerSettingsScreen(config: com.vooc.visualizer.analyzer.AnalyzerConfig,
    onConfigChange: (com.vooc.visualizer.analyzer.AnalyzerConfig) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("AUDIO ANALYZER SETTINGS", color = Lime, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.2.sp)
            OptionDropdown("FFT SIZE", config.fftSize.size.toString(), FftSize.entries.map { it.size.toString() }) {
                onConfigChange(config.copy(fftSize = FftSize.fromSize(it.toInt())))
            }
            OptionDropdown("WINDOW FUNCTION", config.window.name, WindowFunction.entries.map { it.name }) {
                onConfigChange(config.copy(window = WindowFunction.valueOf(it)))
            }
            OptionDropdown("OVERLAP", config.overlap.name, Overlap.entries.map { it.name }) {
                onConfigChange(config.copy(overlap = Overlap.valueOf(it)))
            }
            OptionDropdown("CHANNEL MODE", config.channelMode.name, ChannelMode.entries.map { it.name }) {
                onConfigChange(config.copy(channelMode = ChannelMode.valueOf(it)))
            }
            OptionDropdown("BEAT ALGORITHM", config.beat.algorithm.name, BeatAlgorithm.entries.map { it.name }) {
                onConfigChange(config.copy(beat = config.beat.copy(algorithm = BeatAlgorithm.valueOf(it))))
            }
            LayerSlider("BEAT SENSITIVITY", config.beat.sensitivity * 100f, 0f..100f) {
                onConfigChange(config.copy(beat = config.beat.copy(sensitivity = it / 100f)))
            }
            LayerSlider("BEAT COOLDOWN", config.beat.cooldownMs.toFloat(), 50f..500f) {
                onConfigChange(config.copy(beat = config.beat.copy(cooldownMs = it.toInt())))
            }
            LayerSlider("BEAT THRESHOLD", config.beat.threshold, 1f..5f) {
                onConfigChange(config.copy(beat = config.beat.copy(threshold = it)))
            }
            OptionDropdown("TIME SIGNATURE", config.beat.timeSignature.label, com.vooc.visualizer.analyzer.TimeSignature.entries.map { it.label }) {
                val sig = com.vooc.visualizer.analyzer.TimeSignature.entries.first { item -> item.label == it }
                onConfigChange(config.copy(beat = config.beat.copy(timeSignature = sig)))
            }
            OptionDropdown("NORMALIZATION", config.normalization.name, com.vooc.visualizer.analyzer.Normalization.entries.map { it.name }) {
                onConfigChange(config.copy(normalization = com.vooc.visualizer.analyzer.Normalization.valueOf(it)))
            }
            OptionDropdown("INTERPOLATION", config.interpolation.name, com.vooc.visualizer.analyzer.Interpolation.entries.map { it.name }) {
                onConfigChange(config.copy(interpolation = com.vooc.visualizer.analyzer.Interpolation.valueOf(it)))
            }
            LayerSlider("TEMPORAL SMOOTHING", config.smoothing.temporal * 100f, 0f..99f) {
                onConfigChange(config.copy(smoothing = config.smoothing.copy(temporal = it / 100f, asymmetric = false)))
            }
            LayerSlider("ATTACK", config.smoothing.attackMs, 0f..1000f) {
                onConfigChange(config.copy(smoothing = config.smoothing.copy(attackMs = it)))
            }
            LayerSlider("RELEASE", config.smoothing.releaseMs, 0f..1000f) {
                onConfigChange(config.copy(smoothing = config.smoothing.copy(releaseMs = it)))
            }
            LayerSlider("RING BUFFER", config.ringBufferSamples.toFloat(), 4096f..16384f) {
                onConfigChange(config.copy(ringBufferSamples = (it.toInt() / 1024 * 1024).coerceIn(4096, 16384)))
            }
            LayerSlider("WAVEFORM SAMPLES", config.waveformSamples.toFloat(), 256f..8192f) {
                onConfigChange(config.copy(waveformSamples = (it.toInt() / 256 * 256).coerceIn(256, 8192)))
            }
        }
    }
}


private fun updateLayer(project: StudioProject, id: Long, transform: (VisualLayer) -> VisualLayer): StudioProject =
    project.copy(layers = project.layers.map { if (it.id == id) transform(it) else it })

@Composable
private fun VisualizerPreview(modifier: Modifier, layers: List<VisualLayer>, frame: AnalysisFrame?, playing: Boolean) {
    var phase by remember { mutableFloatStateOf(0f) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val imageBitmaps by produceState(initialValue = emptyMap<Long, androidx.compose.ui.graphics.ImageBitmap>(), key1 = layers.map { it.id to it.imageUri }) {
        value = withContext(Dispatchers.IO) {
            layers.mapNotNull { layer ->
                if (layer.kind != LayerKind.IMAGE || layer.imageUri.isBlank()) null
                else runCatching {
                    context.contentResolver.openInputStream(Uri.parse(layer.imageUri))?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                }.getOrNull()?.let { layer.id to it }
            }.toMap()
        }
    }
    LaunchedEffect(playing) {
        while (playing) { phase = (phase + 0.08f) % (2f * PI.toFloat()); delay(16) }
    }
    Canvas(modifier.background(Color.Black)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        layers.forEach { layer ->
            val alpha = (layer.opacity / 100f).coerceIn(0f, 1f)
            val color = Color(layer.color).copy(alpha = alpha)
            val accent = Color(layer.gradientColor).copy(alpha = alpha)
            val scale = (layer.scale / 100f).coerceIn(0f, 4f)
            val center = Offset(cx + layer.x / 100f * size.width, cy + layer.y / 100f * size.height)
            val channel = runCatching { frame?.channel(ChannelSelect.valueOf(layer.channel)) }.getOrNull()
            val spectrum = channel?.spectrum ?: frame?.spectrum ?: FloatArray(64)
            val waveform = frame?.waveform ?: FloatArray(64)
            val band = when (layer.measureWhat) {
                "Bass" -> frame?.band(0) ?: 0f
                "Mid" -> frame?.band(1) ?: 0f
                "Treble" -> frame?.band(2) ?: 0f
                "RMS" -> channel?.rms ?: 0f
                "Peak" -> channel?.peak ?: 0f
                "Beat" -> frame?.beat?.pulse ?: 0f
                "Waveform" -> waveform.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
                else -> 0f
            }.coerceIn(0f, 1f)
            val count = layer.barCount.coerceIn(8, 256)
            val values = when (layer.measureWhat) {
                "Bass", "Mid", "Treble", "RMS", "Peak", "Beat" -> FloatArray(count) { band }
                "Waveform" -> waveform
                else -> spectrum
            }
            val valueAt: (Int) -> Float = { i ->
                if (values.isEmpty()) 0f else values[(i * values.size / count).coerceIn(0, values.lastIndex)].coerceIn(0f, 1f) * layer.sensitivity / 100f
            }
            rotate(layer.rotation, center) {
                when (layer.kind) {
                    LayerKind.BACKGROUND -> drawRect(Color(0xFF070906))
                    LayerKind.BARS, LayerKind.BARSH, LayerKind.BLOCKS -> {
                        val gap = layer.spacing.dp.toPx()
                        val bw = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
                        for (i in 0 until count) {
                            val v = valueAt(i)
                            val h = (size.height * (0.025f + v * 0.75f) * scale).coerceIn(1f, size.height)
                            val x = i * (bw + gap)
                            val barColor = if (i % 2 == 0) color else accent
                            when (layer.kind) {
                                LayerKind.BARSH -> {
                                    drawRect(barColor, Offset(x, cy - h / 2f), androidx.compose.ui.geometry.Size(bw, h))
                                }
                                LayerKind.BLOCKS -> {
                                    val segments = 12
                                    val segGap = 2.dp.toPx()
                                    val segH = (h - segGap * (segments - 1)) / segments
                                    for (s in 0 until segments) {
                                        if (s.toFloat() / segments <= v) drawRect(barColor,
                                            Offset(x, size.height - (s + 1) * (segH + segGap)),
                                            androidx.compose.ui.geometry.Size(bw, segH.coerceAtLeast(1f)))
                                    }
                                }
                                else -> drawRect(barColor, Offset(x, size.height - h), androidx.compose.ui.geometry.Size(bw, h))
                            }
                        }
                    }
                    LayerKind.GRID -> {
                        val rows = 10
                        val cols = count.coerceAtMost(80)
                        for (r in 0..rows) drawLine(color.copy(alpha = alpha * 0.35f), Offset(0f, r * size.height / rows), Offset(size.width, r * size.height / rows), layer.thickness.dp.toPx())
                        for (i in 0 until cols) {
                            val v = valueAt(i)
                            val x = i * size.width / cols
                            drawLine(if (i % 2 == 0) color else accent, Offset(x, size.height), Offset(x, size.height - v * size.height * 0.7f), layer.thickness.dp.toPx())
                        }
                    }
                    LayerKind.WAVE, LayerKind.WAVEC -> {
                        val vals = if (layer.kind == LayerKind.WAVE && values.isNotEmpty()) values else waveform
                        val step = size.width / (vals.size - 1).coerceAtLeast(1)
                        val path = Path()
                        vals.forEachIndexed { i, v ->
                            val x = i * step
                            val y = cy + v * cy * 0.9f * scale
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color, style = Stroke(width = layer.thickness.dp.toPx()))
                        if (layer.kind == LayerKind.WAVE) {
                            val mirror = Path()
                            vals.forEachIndexed { i, v ->
                                val x = i * step
                                val y = cy - v * cy * 0.9f * scale
                                if (i == 0) mirror.moveTo(x, y) else mirror.lineTo(x, y)
                            }
                            drawPath(mirror, accent, style = Stroke(width = layer.thickness.dp.toPx()))
                        }
                    }
                    LayerKind.SCOPE -> {
                        val radius = size.minDimension * 0.22f * scale
                        val path = Path()
                        waveform.forEachIndexed { i, v ->
                            val a = i * 2f * PI.toFloat() / waveform.size.coerceAtLeast(1)
                            val r = radius * (1f + v * 0.45f)
                            val x = center.x + cos(a) * r
                            val y = center.y + sin(a) * r
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        path.close()
                        drawPath(path, color, style = Stroke(width = layer.thickness.dp.toPx()))
                    }
                    LayerKind.CIRCLE, LayerKind.RING, LayerKind.MULTIRING, LayerKind.CONCENTRIC, LayerKind.PULSE -> {
                        val bass = frame?.band(0) ?: band
                        val baseRadius = size.minDimension * layer.radius / 100f * scale
                        val radius = if (layer.kind == LayerKind.PULSE) baseRadius * (1f + (frame?.beat?.pulse ?: 0f) * 0.5f) else baseRadius
                        val rings = when (layer.kind) { LayerKind.MULTIRING -> 6; LayerKind.CONCENTRIC -> 4; else -> 1 }
                        repeat(rings) { i ->
                            val ringRadius = radius + i * (layer.spacing + layer.thickness).dp.toPx()
                            drawCircle(if (i % 2 == 0) color else accent, ringRadius, center,
                                style = Stroke(width = layer.thickness.dp.toPx()))
                        }
                        if (layer.kind == LayerKind.CIRCLE) {
                            for (i in 0 until count) {
                                val v = valueAt(i)
                                val a = i * 2f * PI.toFloat() / count
                                val r1 = radius
                                val r2 = r1 + v * 44.dp.toPx()
                                drawLine(if (i % 2 == 0) color else accent,
                                    Offset(center.x + cos(a) * r1, center.y + sin(a) * r1),
                                    Offset(center.x + cos(a) * r2, center.y + sin(a) * r2), layer.thickness.dp.toPx())
                            }
                        }
                    }
                    LayerKind.DOTS, LayerKind.PARTICLES -> {
                        val n = if (layer.kind == LayerKind.PARTICLES) layer.particleCount else count
                        repeat(n) { i ->
                            val a = i * 2f * PI.toFloat() / n + phase * (if (layer.kind == LayerKind.PARTICLES) 1f else 0f)
                            val v = valueAt(i % count)
                            val r = size.minDimension * (0.08f + (i % 9) * 0.035f) * scale * (1f + v * 0.25f)
                            drawCircle(if (i % 2 == 0) color else accent, (layer.thickness * 0.55f + v * 3f).dp.toPx(),
                                Offset(center.x + cos(a) * r, center.y + sin(a) * r))
                        }
                    }
                    LayerKind.LINES -> {
                        for (i in 0 until count) {
                            val v = valueAt(i)
                            val x = i * size.width / count
                            drawLine(if (i % 2 == 0) color else accent, Offset(x, cy - v * cy), Offset(x, cy + v * cy), layer.thickness.dp.toPx())
                        }
                    }
                    LayerKind.SHAPE -> {
                        val radius = size.minDimension * layer.radius / 100f * scale
                        val path = Path()
                        repeat(6) { i ->
                            val a = i * 2f * PI.toFloat() / 6f
                            val r = radius * (1f + valueAt(i) * 0.35f)
                            val x = center.x + cos(a) * r
                            val y = center.y + sin(a) * r
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        path.close()
                        drawPath(path, color, style = Stroke(width = layer.thickness.dp.toPx()))
                    }
                    LayerKind.TEXT -> drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            this.color = color.toArgb()
                            textSize = (24f * scale).coerceAtLeast(8f).sp.toPx()
                            textAlign = android.graphics.Paint.Align.CENTER
                            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                        }
                        canvas.nativeCanvas.drawText(layer.textContent, center.x, center.y, paint)
                    }
                    LayerKind.IMAGE -> {
                        val bitmap = imageBitmaps[layer.id]
                        if (bitmap != null) {
                            val side = (size.minDimension * 0.45f * scale).coerceAtLeast(1f)
                            drawImage(bitmap, dstOffset = androidx.compose.ui.unit.IntOffset((center.x - side / 2).toInt(), (center.y - side / 2).toInt()),
                                dstSize = androidx.compose.ui.unit.IntSize(side.toInt(), side.toInt()))
                        } else {
                            drawCircle(color.copy(alpha = 0.3f), size.minDimension * 0.12f, center, style = Stroke(layer.thickness.dp.toPx()))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionDropdown(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RectangleShape) {
                Text(value.uppercase(), modifier = Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("▾", color = Lime)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(Panel)) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option.uppercase(), maxLines = 1) }, onClick = { onSelect(option); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun EditorSection(title: String, expanded: String, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color.White, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp)
            Text(if (expanded == title) "−" else "+", color = Lime)
        }
        if (expanded == title) Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), content = content)
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
            modifier = Modifier.fillMaxWidth().height(24.dp),
            colors = SliderDefaults.colors(thumbColor = Lime, activeTrackColor = Lime, inactiveTrackColor = Color(0xFF42483A)))
    }
}

@Composable
private fun PlayerScreen(player: Player, trackName: String, playing: Boolean, position: Long, duration: Long,
                         picker: androidx.activity.result.ActivityResultLauncher<Array<String>>, modifier: Modifier = Modifier) {
    var shuffle by remember { mutableStateOf(player.shuffleModeEnabled) }
    var repeat by remember { mutableIntStateOf(player.repeatMode) }
    var seek by remember { mutableFloatStateOf(position.toFloat()) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    LaunchedEffect(position) { if (!player.isPlaying) seek = position.toFloat() }
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("MUSIC LIBRARY / PLAYER", color = Lime, letterSpacing = 2.sp)
        Box(Modifier.fillMaxWidth().height(150.dp).background(Panel), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("♫", color = Lime, style = MaterialTheme.typography.displayLarge)
                Text(trackName.uppercase(), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("QUEUE  •  ${player.mediaItemCount} TRACKS", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Slider(value = seek.coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
            modifier = Modifier.fillMaxWidth().height(24.dp),
            onValueChange = { seek = it },
            onValueChangeFinished = { player.seekTo(seek.toLong()) },
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            colors = SliderDefaults.colors(thumbColor = Lime, activeTrackColor = Lime))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(formatTime(duration), color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White) }
            IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Lime, modifier = Modifier.size(42.dp))
            }
            IconButton(onClick = { player.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
        }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("PLAYBACK SPEED", color = Muted, style = MaterialTheme.typography.labelSmall)
                Text(String.format("%.2fx", speed), color = Lime, style = MaterialTheme.typography.labelSmall)
            }
            Slider(value = speed, onValueChange = { speed = it; player.setPlaybackParameters(androidx.media3.common.PlaybackParameters(it, 1f)) },
                modifier = Modifier.fillMaxWidth().height(24.dp),
                valueRange = 0.5f..2f, steps = 5,
                colors = SliderDefaults.colors(thumbColor = Lime, activeTrackColor = Lime))
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
        Column(Modifier.weight(1f).fillMaxWidth().background(Panel)) {
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

@Composable
private fun ExportScreen(project: StudioProject, onProjectChange: (StudioProject) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("VIDEO EXPORT", color = Lime, letterSpacing = 2.sp, style = MaterialTheme.typography.titleMedium)
        Text("OUTPUT RESOLUTION", color = Muted, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1280 to 720, 1920 to 1080, 2560 to 1440).forEach { size ->
                OutlinedButton(
                    onClick = { onProjectChange(project.copy(width = size.first, height = size.second)) },
                    shape = RectangleShape,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (project.width == size.first) Lime else Color.White
                    )
                ) { Text("${size.first}p") }
            }
        }
        Text("FRAME RATE", color = Muted, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 60).forEach { rate ->
                OutlinedButton(
                    onClick = { onProjectChange(project.copy(fps = rate)) },
                    shape = RectangleShape,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (project.fps == rate) Lime else Color.White
                    )
                ) { Text("$rate FPS") }
            }
        }
        Text("OUTPUT SUMMARY", color = Muted, style = MaterialTheme.typography.labelSmall)
        Text("${project.width} × ${project.height}  •  ${project.fps} FPS", color = Color.White)
        Text("CONTAINER: MP4  •  VIDEO: H.264  •  AUDIO: AAC", color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text("The export encoder and audio/video muxer are not implemented yet. These settings are saved with the project, but this build cannot render an MP4. No fake export action is shown.", color = Muted)
    }
}

private fun formatTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
