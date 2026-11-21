package com.vooc.visualizer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.vooc.visualizer.analyzer.AnalysisFrame
import com.vooc.visualizer.analyzer.AudioAnalyzer
import com.vooc.visualizer.analyzer.AudioProviderFrame
import com.vooc.visualizer.analyzer.AudioProviderProcessor
import com.vooc.visualizer.analyzer.applyTo
import com.vooc.visualizer.analyzer.toAudioProviderConfig
import com.vooc.visualizer.analyzer.FftSize
import com.vooc.visualizer.analyzer.ChannelMode
import com.vooc.visualizer.analyzer.WindowFunction
import com.vooc.visualizer.analyzer.Overlap
import com.vooc.visualizer.analyzer.BeatAlgorithm
import com.vooc.visualizer.studio.LayerKind
import com.vooc.visualizer.studio.ElementPropertyCatalog
import com.vooc.visualizer.studio.ElementValue
import com.vooc.visualizer.studio.StudioElementType
import com.vooc.visualizer.studio.PresetFileCodec
import com.vooc.visualizer.studio.ProjectRepository
import com.vooc.visualizer.studio.ProjectKeyframe
import com.vooc.visualizer.studio.StudioProject
import com.vooc.visualizer.studio.MeasureWhatEngine
import com.vooc.visualizer.studio.TemplateStore
import com.vooc.visualizer.studio.VisualLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoocStudio(
    player: Player,
    analyzer: AudioAnalyzer,
    initialProject: StudioProject,
    onSave: (StudioProject) -> Unit,
    onPickAudio: (List<Uri>) -> Unit
) {
    var project by remember { mutableStateOf(initialProject) }
    var selectedId by remember { mutableLongStateOf(project.layers.lastOrNull()?.id ?: 0L) }
    var selectedElementId by remember { mutableStateOf(project.document.elements.firstOrNull()?.id ?: "audio-provider-default") }
    var activeSelection by remember { mutableStateOf("LAYER") }
    var expandedSection by remember { mutableStateOf("GENERAL") }
    var showLayerMenu by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf("STUDIO") }
    var playing by remember { mutableStateOf(false) }
    var trackName by remember { mutableStateOf("No audio imported") }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var frame by remember { mutableStateOf<AnalysisFrame?>(null) }
    var providerFrame by remember { mutableStateOf<AudioProviderFrame?>(null) }
    val providerProcessor = remember { AudioProviderProcessor() }
    val providerElement = remember(project.document.elements) {
        project.document.elements.firstOrNull { it.type == StudioElementType.AUDIO_PROVIDER && it.visible }
            ?: ElementPropertyCatalog.create(StudioElementType.AUDIO_PROVIDER, "audio-provider-default")
    }
    val providerConfig = remember(providerElement) { providerElement.toAudioProviderConfig() }
    var analyzerConfig by remember { mutableStateOf(analyzer.config) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var projectEntries by remember { mutableStateOf(ProjectRepository.list(context)) }
    var currentProjectId by remember { mutableStateOf(projectEntries.firstOrNull()?.id) }
    var repositoryReady by remember { mutableStateOf(false) }
    var showAudioPlayer by remember { mutableStateOf(true) }
    var timelineProperty by remember { mutableStateOf("X") }
    var multiSelectedIds by remember { mutableStateOf(setOf<Long>()) }
    var multiSelectedElementIds by remember { mutableStateOf(setOf<String>()) }
    var pendingElementImageProperty by remember { mutableStateOf<String?>(null) }
    var savedTemplates by remember { mutableStateOf(TemplateStore.load(context)) }
    var presetStatus by remember { mutableStateOf("VOOC PRESETS: IMPORT OR EXPORT YOUR VISUALIZER DESIGN") }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && selectedId != 0L) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            project = updateLayer(project, selectedId) { it.copy(imageUri = uri.toString()) }
        }
    }
    val elementImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val property = pendingElementImageProperty
        val element = project.document.elements.firstOrNull { it.id == selectedElementId }
        if (uri != null && property != null && element != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val updated = element.withValue(property, ElementValue.Text(uri.toString()))
            val next = project.copy(document = project.document.copy(elements = project.document.elements.map { if (it.id == updated.id) updated else it }))
            project = next; onSave(next)
        }
        pendingElementImageProperty = null
    }
    val projectExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(PresetFileCodec.exportVooc(project)) }
            presetStatus = "EXPORTED VOOC PRESET"
        }.onFailure { presetStatus = "EXPORT FAILED: ${it.message ?: "UNKNOWN ERROR"}" }
    }
    val projectImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported-preset"
                val stream = context.contentResolver.openInputStream(uri) ?: error("Could not open selected file")
                val result = PresetFileCodec.import(stream, name)
                val imported = result.project
                project = imported
                selectedId = imported.layers.lastOrNull()?.id ?: 0L
                activeSelection = "LAYER"
                onSave(imported)
                val created = ProjectRepository.create(context, imported)
                currentProjectId = created?.id
                projectEntries = ProjectRepository.list(context)
                savedTemplates = TemplateStore.save(context, imported)
                presetStatus = buildString {
                    append(if (result.sourceFormat == "AVEE_VIZ") "IMPORTED AVEE .VIZ (BEST-EFFORT)" else "IMPORTED VOOC PRESET")
                    if (result.warnings.isNotEmpty()) append(" • ${result.warnings.size} COMPATIBILITY NOTES")
                }
            }.onFailure { presetStatus = "IMPORT FAILED: ${it.message ?: "UNSUPPORTED FILE"}" }
        }
    }
    val selected = if (activeSelection == "LAYER") project.layers.firstOrNull { it.id == selectedId } ?: project.layers.firstOrNull() else null
    val selectedElement = if (activeSelection == "ELEMENT") project.document.elements.firstOrNull { it.id == selectedElementId } else null
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
    LaunchedEffect(Unit) {
        val existing = projectEntries.firstOrNull()
        if (existing != null) {
            ProjectRepository.load(context, existing.id)?.let { restored ->
                project = restored
                selectedId = restored.layers.lastOrNull()?.id ?: 0L
                currentProjectId = existing.id
            }
        } else {
            val created = ProjectRepository.create(context, project)
            currentProjectId = created?.id
            projectEntries = ProjectRepository.list(context)
        }
        repositoryReady = true
    }
    LaunchedEffect(project, currentProjectId, repositoryReady) {
        if (!repositoryReady) return@LaunchedEffect
        delay(450)
        onSave(project)
        val id = currentProjectId
        if (id != null) ProjectRepository.save(context, id, project)
        projectEntries = ProjectRepository.list(context)
    }
    LaunchedEffect(player, analyzer, providerConfig) {
        providerProcessor.reset()
        val providerAnalyzerConfig = providerConfig.applyTo(analyzer.config)
        if (providerAnalyzerConfig != analyzer.config) {
            analyzer.updateConfig(providerAnalyzerConfig)
            analyzerConfig = providerAnalyzerConfig
        }
        var previousPosition = -1L
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            if (player.isPlaying) {
                val currentPosition = player.currentPosition.coerceAtLeast(0L)
                if (previousPosition >= 0L && (currentPosition < previousPosition || currentPosition - previousPosition > 350L)) {
                    providerProcessor.reset()
                }
                val analyzed = analyzer.analyzeLive(currentPosition / 1000.0)
                frame = analyzed
                providerFrame = providerProcessor.process(analyzed, providerConfig)
                previousPosition = currentPosition
            } else {
                previousPosition = -1L
            }
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
                    val selected = screen == item
                    val icon = when (item) {
                        "LIBRARY" -> Icons.Outlined.LibraryMusic
                        "STUDIO" -> Icons.Outlined.Tune
                        "TEMPLATES" -> Icons.Outlined.Collections
                        "EXPORT" -> Icons.Outlined.FileUpload
                        else -> Icons.Outlined.Settings
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = { screen = item },
                        icon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = item,
                                tint = if (selected) Lime else Muted,
                                modifier = Modifier.size(23.dp)
                            )
                        },
                        label = null,
                        alwaysShowLabel = false,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Lime,
                            unselectedIconColor = Muted,
                            indicatorColor = Color(0xFF303A20)
                        )
                    )
                }
            }
        }
    ) { padding ->
        when (screen) {
            "LIBRARY" -> Column(Modifier.fillMaxSize().padding(padding)) {
                ProjectBrowserScreen(
                    entries = projectEntries,
                    currentId = currentProjectId,
                    onCreate = {
                        val next = StudioProject(name = "Untitled Project")
                        val entry = ProjectRepository.create(context, next)
                        if (entry != null) {
                            currentProjectId = entry.id
                            project = next
                            selectedId = next.layers.lastOrNull()?.id ?: 0L
                            projectEntries = ProjectRepository.list(context)
                            screen = "STUDIO"
                        }
                    },
                    onOpen = { entry ->
                        ProjectRepository.load(context, entry.id)?.let { loaded ->
                            currentProjectId = entry.id
                            project = loaded
                            selectedId = loaded.layers.lastOrNull()?.id ?: 0L
                            screen = "STUDIO"
                        }
                    },
                    onDuplicate = { entry ->
                        ProjectRepository.duplicate(context, entry.id)
                        projectEntries = ProjectRepository.list(context)
                    },
                    onRename = { entry, name ->
                        ProjectRepository.rename(context, entry.id, name)
                        projectEntries = ProjectRepository.list(context)
                    },
                    onDelete = { entry ->
                        ProjectRepository.delete(context, entry.id)
                        val refreshed = ProjectRepository.list(context)
                        projectEntries = refreshed
                        if (currentProjectId == entry.id) {
                            val fallback = refreshed.firstOrNull()
                            currentProjectId = fallback?.id
                            fallback?.let { ProjectRepository.load(context, it.id) }?.let {
                                project = it
                                selectedId = it.layers.lastOrNull()?.id ?: 0L
                            }
                        }
                    },
                    onToggleAudio = { showAudioPlayer = !showAudioPlayer },
                    showAudio = showAudioPlayer,
                    modifier = Modifier.weight(if (showAudioPlayer) 0.58f else 1f)
                )
                if (showAudioPlayer) {
                    HorizontalDivider(color = Color(0xFF343A2B), thickness = 1.dp)
                    PlayerScreen(player, trackName, playing, position, duration, picker, Modifier.weight(0.42f))
                }
            }
            "EXPORT" -> Column(Modifier.fillMaxSize().padding(padding)) {
                PreviewPane(project, frame, providerFrame, playing, position, Modifier.fillMaxWidth().weight(0.40f).padding(horizontal = 10.dp, vertical = 6.dp))
                HorizontalDivider(color = Color(0xFF343A2B), thickness = 1.dp)
                ExportScreen(project, onProjectChange = { project = it; onSave(it) }, Modifier.weight(0.60f))
            }
            "TEMPLATES" -> Column(Modifier.fillMaxSize().padding(padding)) {
                PreviewPane(project, frame, providerFrame, playing, position, Modifier.fillMaxWidth().weight(0.40f).padding(horizontal = 10.dp, vertical = 6.dp))
                HorizontalDivider(color = Color(0xFF343A2B), thickness = 1.dp)
                TemplateLibraryScreen(
                    project = project,
                    savedTemplates = savedTemplates,
                    onApply = { next ->
                        project = next
                        selectedId = next.layers.lastOrNull()?.id ?: 0L
                        onSave(next)
                    },
                    onSaveCurrent = { savedTemplates = TemplateStore.save(context, project) },
                    onDeleteSaved = { name -> savedTemplates = TemplateStore.delete(context, name) },
                    onImportPreset = { projectImport.launch(arrayOf("*/*")) },
                    onExportPreset = { projectExport.launch(project.name.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".voocpreset") },
                    presetStatus = presetStatus,
                    modifier = Modifier.weight(0.60f)
                )
            }
            "SETTINGS" -> Column(Modifier.fillMaxSize().padding(padding)) {
                PreviewPane(project, frame, providerFrame, playing, position, Modifier.fillMaxWidth().weight(0.40f).padding(horizontal = 10.dp, vertical = 6.dp))
                HorizontalDivider(color = Color(0xFF343A2B), thickness = 1.dp)
                AnalyzerSettingsScreen(analyzerConfig, onConfigChange = { next ->
                    analyzerConfig = next
                    analyzer.updateConfig(next)
                }, Modifier.weight(0.60f))
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                PreviewPane(project, frame, providerFrame, playing, position, Modifier.fillMaxWidth().weight(0.40f).padding(horizontal = 10.dp, vertical = 6.dp))
                HorizontalDivider(color = Color(0xFF343A2B), thickness = 1.dp)
                LazyColumn(
                    modifier = Modifier.weight(0.60f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("OBJECTS & LAYERS", color = Lime, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.5.sp)
                            Box {
                            OutlinedButton(onClick = { showLayerMenu = true }, shape = RectangleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) {
                                Icon(Icons.Default.Add, null, tint = Lime); Text("ADD OBJECT")
                            }
                            DropdownMenu(expanded = showLayerMenu, onDismissRequest = { showLayerMenu = false },
                                modifier = Modifier.background(Panel)) {
                                LayerKind.entries.forEach { kind ->
                                    DropdownMenuItem(text = { Text(kind.title.uppercase()) }, onClick = {
                                        val id = (project.layers.maxOfOrNull { it.id } ?: 0L) + 1L
                                        val layer = VisualLayer(id, kind)
                                        project = project.copy(layers = project.layers + layer)
                                        selectedId = id
                                        activeSelection = "LAYER"
                                        showLayerMenu = false
                                    })
                                }
                                StudioElementType.entries.filter { it != StudioElementType.AUDIO_PROVIDER && it != StudioElementType.BARS_SEGMENTS }.forEach { type ->
                                    DropdownMenuItem(text = { Text(type.displayName.uppercase()) }, onClick = {
                                        val id = "${type.name.lowercase()}-${System.currentTimeMillis()}"
                                        val created = ElementPropertyCatalog.create(type, id)
                                        val next = project.copy(document = project.document.copy(elements = project.document.elements + created))
                                        project = next
                                        selectedElementId = id
                                        activeSelection = "ELEMENT"
                                        onSave(next)
                                        showLayerMenu = false
                                    })
                                }
                            }
                        }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${multiSelectedIds.size + multiSelectedElementIds.size} SELECTED", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically).weight(1f))
                            TextButton(enabled = multiSelectedIds.size >= 2, onClick = {
                                val group = "group-${System.nanoTime()}"
                                project = project.copy(layers = project.layers.map { if (it.id in multiSelectedIds) it.copy(groupId = group) else it })
                                multiSelectedIds = emptySet()
                            }) { Text("GROUP", color = Lime) }
                            TextButton(enabled = multiSelectedIds.isNotEmpty() || multiSelectedElementIds.isNotEmpty(), onClick = {
                                project = project.copy(layers = project.layers.map { if (it.id in multiSelectedIds) it.copy(groupId = "") else it })
                                multiSelectedIds = emptySet()
                            }) { Text("UNGROUP", color = Muted) }
                            TextButton(enabled = multiSelectedIds.isNotEmpty(), onClick = {
                                project = project.copy(
                                    layers = project.layers.filterNot { it.id in multiSelectedIds },
                                    document = project.document.copy(
                                        keyframes = project.document.keyframes.filterNot { it.layerId in multiSelectedIds },
                                        elements = project.document.elements.filterNot { it.id in multiSelectedElementIds }
                                    )
                                )
                                if (selectedId in multiSelectedIds) selectedId = project.layers.lastOrNull()?.id ?: 0L
                                if (selectedElementId in multiSelectedElementIds) selectedElementId = project.document.elements.firstOrNull { it.id !in multiSelectedElementIds }?.id ?: "audio-provider-default"
                                multiSelectedIds = emptySet()
                                multiSelectedElementIds = emptySet()
                                onSave(project)
                            }) { Text("DELETE", color = Color(0xFFFF7777)) }
                        }
                    }
                }
                item(key = "composition-layer-rows") {
                    project.document.elements.reversed().forEach { element ->
                        Row(Modifier.fillMaxWidth().height(42.dp).background(if (activeSelection == "ELEMENT" && element.id == selectedElementId) Color(0xFF29321F) else Panel).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(24.dp).clickable { multiSelectedElementIds = if (element.id in multiSelectedElementIds) multiSelectedElementIds - element.id else multiSelectedElementIds + element.id }, contentAlignment = Alignment.Center) {
                                Icon(if (element.id in multiSelectedElementIds) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, "Multi-select composition layer", tint = if (element.id in multiSelectedElementIds) Lime else Muted, modifier = Modifier.size(16.dp))
                            }
                            Column(Modifier.weight(1f).padding(start = 5.dp).clickable { if (!element.locked) { selectedElementId = element.id; activeSelection = "ELEMENT" } }) {
                                Text(element.tag.uppercase(), color = if (element.locked) Muted else Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(element.type.displayName.uppercase(), color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            Box(Modifier.size(24.dp).clickable { if (!element.locked) { selectedElementId = element.id; activeSelection = "ELEMENT" } }, contentAlignment = Alignment.Center) { Icon(Icons.Default.RadioButtonChecked, "Select composition layer", tint = if (activeSelection == "ELEMENT" && element.id == selectedElementId) Lime else Muted, modifier = Modifier.size(15.dp)) }
                            Box(Modifier.size(24.dp).clickable {
                                val updated = element.withValue("Visible", ElementValue.Bool(!element.visible))
                                val next = project.copy(document = project.document.copy(elements = project.document.elements.map { if (it.id == element.id) updated else it }))
                                project = next; onSave(next)
                            }, contentAlignment = Alignment.Center) { Icon(if (element.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff, if (element.visible) "Hide composition layer" else "Show composition layer", tint = if (element.visible) Lime else Muted, modifier = Modifier.size(15.dp)) }
                            Box(Modifier.size(24.dp).clickable {
                                val updated = element.copy(locked = !element.locked)
                                val next = project.copy(document = project.document.copy(elements = project.document.elements.map { if (it.id == element.id) updated else it }))
                                project = next; onSave(next)
                            }, contentAlignment = Alignment.Center) { Icon(if (element.locked) Icons.Default.Lock else Icons.Default.LockOpen, if (element.locked) "Unlock composition layer" else "Lock composition layer", tint = if (element.locked) Lime else Muted, modifier = Modifier.size(15.dp)) }
                            Box(Modifier.size(24.dp).clickable {
                                val list = project.document.elements.toMutableList(); val index = list.indexOfFirst { it.id == element.id }
                                if (index > 0) { val moved = list.removeAt(index); list.add(index - 1, moved) }
                                val next = project.copy(document = project.document.copy(elements = list)); project = next; onSave(next)
                            }, contentAlignment = Alignment.Center) { Icon(Icons.Default.ArrowUpward, "Move composition layer up", tint = Muted, modifier = Modifier.size(15.dp)) }
                            Box(Modifier.size(24.dp).clickable {
                                val list = project.document.elements.toMutableList(); val index = list.indexOfFirst { it.id == element.id }
                                if (index in 0 until list.lastIndex) { val moved = list.removeAt(index); list.add(index + 1, moved) }
                                val next = project.copy(document = project.document.copy(elements = list)); project = next; onSave(next)
                            }, contentAlignment = Alignment.Center) { Icon(Icons.Default.ArrowDownward, "Move composition layer down", tint = Muted, modifier = Modifier.size(15.dp)) }
                            Box(Modifier.size(24.dp).clickable {
                                val copyId = "${element.type.name.lowercase()}-${System.currentTimeMillis()}"
                                val duplicate = element.copy(id = copyId, tag = element.tag + " Copy")
                                val next = project.copy(document = project.document.copy(elements = project.document.elements + duplicate))
                                project = next; selectedElementId = copyId; activeSelection = "ELEMENT"; onSave(next)
                            }, contentAlignment = Alignment.Center) { Icon(Icons.Default.ContentCopy, "Duplicate composition layer", tint = Lime, modifier = Modifier.size(15.dp)) }
                            Box(Modifier.size(24.dp).clickable {
                                val next = project.copy(document = project.document.copy(elements = project.document.elements.filterNot { it.id == element.id }))
                                project = next
                                if (selectedElementId == element.id) selectedElementId = next.document.elements.firstOrNull()?.id ?: "audio-provider-default"
                                multiSelectedElementIds = multiSelectedElementIds - element.id
                                onSave(next)
                            }, contentAlignment = Alignment.Center) { Icon(Icons.Default.Delete, "Delete composition layer", tint = Color(0xFFFF7777), modifier = Modifier.size(15.dp)) }
                        }
                    }
                }
                items(project.layers.reversed(), key = { it.id }) { layer ->
                    Row(
                        Modifier.fillMaxWidth().height(42.dp)
                            .background(if (activeSelection == "LAYER" && layer.id == selectedId) Color(0xFF29321F) else Panel)
                            .padding(horizontal = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(24.dp).clickable { multiSelectedIds = if (layer.id in multiSelectedIds) multiSelectedIds - layer.id else multiSelectedIds + layer.id }, contentAlignment = Alignment.Center) {
                            Icon(if (layer.id in multiSelectedIds) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, "Multi-select layer", tint = if (layer.id in multiSelectedIds) Lime else Muted, modifier = Modifier.size(16.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 5.dp).clickable { if (!layer.locked) { selectedId = layer.id; activeSelection = "LAYER" } }, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            Text(layer.name.uppercase(), color = if (layer.locked) Muted else Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(layer.kind.name.uppercase(), color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        Box(Modifier.size(24.dp).clickable { if (!layer.locked) { selectedId = layer.id; activeSelection = "LAYER" } }, contentAlignment = Alignment.Center) { Icon(Icons.Default.RadioButtonChecked, "Select layer", tint = if (activeSelection == "LAYER" && layer.id == selectedId) Lime else Muted, modifier = Modifier.size(15.dp)) }
                        Box(Modifier.size(24.dp).clickable { project = project.copy(layers = project.layers.map { if (it.id == layer.id) it.copy(enabled = !it.enabled) else it }) }, contentAlignment = Alignment.Center) { Icon(if (layer.enabled) Icons.Default.Visibility else Icons.Default.VisibilityOff, if (layer.enabled) "Hide layer" else "Show layer", tint = if (layer.enabled) Lime else Muted, modifier = Modifier.size(15.dp)) }
                        Box(Modifier.size(24.dp).clickable { project = project.copy(layers = project.layers.map { if (it.id == layer.id) it.copy(locked = !it.locked) else it }); if (!layer.locked && selectedId == layer.id) selectedId = project.layers.firstOrNull { it.id != layer.id && !it.locked }?.id ?: 0L }, contentAlignment = Alignment.Center) { Icon(if (layer.locked) Icons.Default.Lock else Icons.Default.LockOpen, if (layer.locked) "Unlock layer" else "Lock layer", tint = if (layer.locked) Lime else Muted, modifier = Modifier.size(15.dp)) }
                        Box(Modifier.size(24.dp).clickable { val index = project.layers.indexOfFirst { it.id == layer.id }; if (index > 0) { val list = project.layers.toMutableList(); val moved = list.removeAt(index); list.add(index - 1, moved); project = project.copy(layers = list) } }, contentAlignment = Alignment.Center) { Icon(Icons.Default.ArrowUpward, "Move layer up", tint = Muted, modifier = Modifier.size(15.dp)) }
                        Box(Modifier.size(24.dp).clickable { val index = project.layers.indexOfFirst { it.id == layer.id }; if (index in 0 until project.layers.lastIndex) { val list = project.layers.toMutableList(); val moved = list.removeAt(index); list.add(index + 1, moved); project = project.copy(layers = list) } }, contentAlignment = Alignment.Center) { Icon(Icons.Default.ArrowDownward, "Move layer down", tint = Muted, modifier = Modifier.size(15.dp)) }
                        Box(Modifier.size(24.dp).clickable { val id = (project.layers.maxOfOrNull { it.id } ?: 0L) + 1L; project = project.copy(layers = project.layers + layer.copy(id = id, name = layer.name + " Copy")); selectedId = id; activeSelection = "LAYER" }, contentAlignment = Alignment.Center) { Icon(Icons.Default.ContentCopy, "Copy layer", tint = Lime, modifier = Modifier.size(15.dp)) }
                        Box(Modifier.size(24.dp).clickable { project = project.copy(layers = project.layers.filterNot { it.id == layer.id }, document = project.document.copy(keyframes = project.document.keyframes.filterNot { it.layerId == layer.id })); if (selectedId == layer.id) selectedId = project.layers.lastOrNull()?.id ?: 0L; multiSelectedIds = multiSelectedIds - layer.id }, contentAlignment = Alignment.Center) { Icon(Icons.Default.Delete, "Delete layer", tint = Color(0xFFFF7777), modifier = Modifier.size(15.dp)) }
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
                if (activeSelection == "ELEMENT" && selectedElement != null) item {
                    val editableElements = project.document.elements
                    ElementStudioEditor(
                        elements = editableElements,
                        selectedId = selectedElementId,
                        onSelect = { selectedElementId = it; activeSelection = "ELEMENT" },
                        onPickImage = { property -> pendingElementImageProperty = property; elementImagePicker.launch(arrayOf("image/*")) },
                        onChange = { updated ->
                            val next = project.copy(document = project.document.copy(
                                elements = project.document.elements.filterNot { it.id == updated.id } + updated
                            ))
                            project = next
                            onSave(next)
                        },
                        onDelete = { id ->
                            val nextElements = project.document.elements.filterNot { it.id == id }
                            val next = project.copy(document = project.document.copy(elements = nextElements))
                            project = next
                            selectedElementId = nextElements.firstOrNull()?.id ?: "audio-provider-default"
                            onSave(next)
                        }
                    )
                }
                if (selected != null && activeSelection == "LAYER") {
                    item { Text("PROPERTIES / ${selected.name.uppercase()}", color = Lime, style = MaterialTheme.typography.labelMedium) }
                    item {
                        EditorSection("GENERAL", expandedSection, { expandedSection = if (expandedSection == "GENERAL") "" else "GENERAL" }) {
                            CompactTextInput("NAME / TAG", selected.name) { value ->
                                project = updateLayer(project, selectedId) { it.copy(name = value) }
                            }
                            if (selected.kind == LayerKind.TEXT) {
                                CompactTextInput("TEXT CONTENT", selected.textContent) { value ->
                                    project = updateLayer(project, selectedId) { it.copy(textContent = value) }
                                }
                            }
                        }
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
                            ColorWheelField("PRIMARY COLOR", String.format("#%08X", selected.color)) { hex ->
                                val parsed = runCatching { android.graphics.Color.parseColor(hex) }.getOrDefault(selected.color.toInt())
                                project = updateLayer(project, selectedId) { it.copy(color = parsed.toLong() and 0xFFFFFFFFL) }
                            }
                            ColorWheelField("SECONDARY / GRADIENT COLOR", String.format("#%08X", selected.gradientColor)) { hex ->
                                val parsed = runCatching { android.graphics.Color.parseColor(hex) }.getOrDefault(selected.gradientColor.toInt())
                                project = updateLayer(project, selectedId) { it.copy(gradientColor = parsed.toLong() and 0xFFFFFFFFL) }
                            }
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
                            MeasureWhatDropdown(selected.measureWhat, MeasureWhatEngine.options + listOf("Spectrum", "Bass", "Mid", "Treble", "RMS", "Peak", "Waveform")) { value ->
                                project = updateLayer(project, selectedId) { it.copy(measureWhat = value) }
                            }
                            if (selected.measureWhat == "Spectrum") {
                                OptionDropdown("FREQUENCY SCALE", selected.spectrumScale, listOf("LOGARITHMIC", "LINEAR")) { value ->
                                    project = updateLayer(project, selectedId) { it.copy(spectrumScale = value) }
                                }
                                LayerSlider("MIN FREQUENCY HZ", selected.spectrumMinHz, 10f..1000f) { value ->
                                    project = updateLayer(project, selectedId) { it.copy(spectrumMinHz = value.coerceAtMost(selected.spectrumMaxHz - 1f)) }
                                }
                                LayerSlider("MAX FREQUENCY HZ", selected.spectrumMaxHz, 100f..20000f) { value ->
                                    project = updateLayer(project, selectedId) { it.copy(spectrumMaxHz = value.coerceAtLeast(selected.spectrumMinHz + 1f)) }
                                }
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
                    if (selected.kind == LayerKind.IMAGE) {
                        item {
                            OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }, shape = RectangleShape) {
                                Icon(Icons.Default.FolderOpen, null); Text(if (selected.imageUri.isBlank()) "CHOOSE IMAGE" else "CHANGE IMAGE")
                            }
                        }
                    }
                }
                item {
                    EditorSection("TIMELINE / KEYFRAMES", expandedSection, {
                        expandedSection = if (expandedSection == "TIMELINE / KEYFRAMES") "" else "TIMELINE / KEYFRAMES"
                    }) {
                        val timelineLength = maxOf(duration, project.document.durationMs, 30_000L).coerceAtLeast(1L)
                        Text("PLAYHEAD  ${position / 1000f}s  /  ${timelineLength / 1000f}s",
                            color = Muted, style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = position.coerceIn(0L, timelineLength).toFloat(),
                            onValueChange = { player.seekTo(it.toLong()) },
                            valueRange = 0f..timelineLength.toFloat()
                        )
                        OptionDropdown("ANIMATED PROPERTY", timelineProperty,
                            listOf("X", "Y", "SCALE", "ROTATION", "OPACITY")) { timelineProperty = it }
                        // `selected` is null while a composition element is selected; never force-unwrap it.
                        val currentValue = when (timelineProperty) {
                            "X" -> selected?.x ?: 0f
                            "Y" -> selected?.y ?: 0f
                            "SCALE" -> selected?.scale ?: 100f
                            "ROTATION" -> selected?.rotation ?: 0f
                            else -> selected?.opacity ?: 100f
                        }
                        Button(
                            enabled = selected != null,
                            onClick = {
                                val keyframe = ProjectKeyframe(
                                    id = "kf-${selectedId}-${timelineProperty}-${position}-${System.nanoTime()}",
                                    layerId = selectedId,
                                    property = timelineProperty,
                                    timeMs = position.coerceAtLeast(0L),
                                    value = currentValue,
                                    interpolation = "LINEAR"
                                )
                                project = project.copy(document = project.document.copy(
                                    keyframes = (project.document.keyframes.filterNot {
                                        it.layerId == selectedId && it.property == timelineProperty && it.timeMs == keyframe.timeMs
                                    } + keyframe).sortedBy { it.timeMs }
                                ))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RectangleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)
                        ) { Text("ADD KEYFRAME AT PLAYHEAD") }
                        val tracks = project.document.keyframes.filter { it.layerId == selectedId }
                            .sortedWith(compareBy<ProjectKeyframe> { it.property }.thenBy { it.timeMs })
                        Text("${tracks.size} KEYFRAMES ON THIS LAYER", color = Muted,
                            style = MaterialTheme.typography.labelSmall)
                        tracks.forEach { key ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${key.property}  •  ${key.timeMs / 1000f}s  •  ${"%.2f".format(key.value)}",
                                    color = Color.White, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    project = project.copy(document = project.document.copy(
                                        keyframes = project.document.keyframes.filterNot { it.id == key.id }
                                    ))
                                }) { Text("REMOVE", color = Color(0xFFFF7777)) }
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
