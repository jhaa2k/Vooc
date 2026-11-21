package com.vooc.visualizer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vooc.visualizer.analyzer.AnalysisFrame
import com.vooc.visualizer.analyzer.AudioProviderFrame
import com.vooc.visualizer.analyzer.FftSize
import com.vooc.visualizer.analyzer.ChannelMode
import com.vooc.visualizer.analyzer.WindowFunction
import com.vooc.visualizer.analyzer.Overlap
import com.vooc.visualizer.analyzer.BeatAlgorithm
import com.vooc.visualizer.studio.ElementPropertyCatalog
import com.vooc.visualizer.studio.ElementDocument
import com.vooc.visualizer.studio.ElementValue
import com.vooc.visualizer.studio.ElementValueType
import com.vooc.visualizer.studio.StudioProject
import com.vooc.visualizer.studio.StudioPresets
import com.vooc.visualizer.studio.VisualLayer

internal fun evaluateAnimatedLayer(project: StudioProject, layer: VisualLayer, timeMs: Long): VisualLayer {
    fun value(property: String, base: Float): Float {
        val points = project.document.keyframes
            .filter { it.layerId == layer.id && it.property == property }
            .sortedBy { it.timeMs }
        if (points.isEmpty()) return base
        if (timeMs <= points.first().timeMs) return points.first().value
        if (timeMs >= points.last().timeMs) return points.last().value
        val rightIndex = points.indexOfFirst { it.timeMs >= timeMs }.coerceAtLeast(1)
        val left = points[rightIndex - 1]
        val right = points[rightIndex]
        val span = (right.timeMs - left.timeMs).coerceAtLeast(1L)
        var t = ((timeMs - left.timeMs).toFloat() / span).coerceIn(0f, 1f)
        t = when (right.interpolation) {
            "STEP" -> 0f
            "EASE_IN_OUT" -> t * t * (3f - 2f * t)
            else -> t
        }
        return left.value + (right.value - left.value) * t
    }
    return layer.copy(
        x = value("X", layer.x).coerceIn(-200f, 200f),
        y = value("Y", layer.y).coerceIn(-200f, 200f),
        scale = value("SCALE", layer.scale).coerceIn(0f, 400f),
        rotation = value("ROTATION", layer.rotation).coerceIn(-3600f, 3600f),
        opacity = value("OPACITY", layer.opacity).coerceIn(0f, 100f)
    )
}

@Composable
internal fun PreviewPane(project: StudioProject, frame: AnalysisFrame?, providerFrame: AudioProviderFrame?, playing: Boolean, timeMs: Long, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(project.name.uppercase(), color = Color.White, style = MaterialTheme.typography.labelLarge,
                letterSpacing = 1.1.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${project.width} × ${project.height}  •  ${project.fps} FPS", color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        VisualizerPreview(modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, Color(0xFF343A2B)),
            layers = project.layers.filter { it.enabled }.map { evaluateAnimatedLayer(project, it, timeMs) }, elements = project.document.elements, frame = frame, providerFrame = providerFrame, playing = playing)
    }
}

@Composable
internal fun TemplateLibraryScreen(
    project: StudioProject,
    savedTemplates: List<StudioProject>,
    onApply: (StudioProject) -> Unit,
    onSaveCurrent: () -> Unit,
    onDeleteSaved: (String) -> Unit,
    onImportPreset: () -> Unit,
    onExportPreset: () -> Unit,
    presetStatus: String,
    modifier: Modifier = Modifier
) {
    var deleteName by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("MY TEMPLATES", color = Lime, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.2.sp)
                Text("${savedTemplates.size} SAVED", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            Button(onClick = onSaveCurrent, modifier = Modifier.fillMaxWidth(), shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) {
                Text("SAVE CURRENT PROJECT AS TEMPLATE")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = onImportPreset, modifier = Modifier.weight(1f), shape = RectangleShape) {
                    Icon(Icons.Outlined.FileUpload, null, tint = Lime)
                    Spacer(Modifier.width(5.dp))
                    Text("IMPORT .VIZ / VOOC", color = Lime)
                }
                OutlinedButton(onClick = onExportPreset, modifier = Modifier.weight(1f), shape = RectangleShape) {
                    Icon(Icons.Default.Save, null, tint = Lime)
                    Spacer(Modifier.width(5.dp))
                    Text("EXPORT .VOOCPRESET", color = Lime)
                }
            }
            Text(presetStatus, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        if (savedTemplates.isEmpty()) {
            item { Text("NO SAVED TEMPLATES YET. SAVE THE CURRENT PROJECT TO REUSE ITS LAYERS AND SETTINGS.", color = Muted, style = MaterialTheme.typography.labelSmall) }
        } else {
            items(savedTemplates, key = { it.name }) { template ->
                Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(template.name.uppercase(), color = Color.White, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("${template.layers.size} LAYERS  •  ${template.width} × ${template.height}", color = Muted,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { onApply(template) }) { Text("APPLY", color = Lime) }
                    TextButton(onClick = { deleteName = template.name }) { Text("DELETE", color = Muted) }
                }
            }
        }
        item {
            Text("BUILT-IN TEMPLATES", color = Lime, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.2.sp)
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
    if (deleteName != null) {
        AlertDialog(
            onDismissRequest = { deleteName = null },
            title = { Text("DELETE TEMPLATE?") },
            text = { Text("THIS WILL REMOVE ${deleteName!!.uppercase()} FROM YOUR SAVED TEMPLATE LIBRARY.") },
            confirmButton = {
                TextButton(onClick = { onDeleteSaved(deleteName!!); deleteName = null }) { Text("DELETE", color = Color(0xFFFF7777)) }
            },
            dismissButton = { TextButton(onClick = { deleteName = null }) { Text("CANCEL") } },
            containerColor = Panel
        )
    }
}

@Composable
internal fun ElementStudioEditor(
    elements: List<ElementDocument>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onPickImage: (String) -> Unit,
    onChange: (ElementDocument) -> Unit,
    onDelete: (String) -> Unit
) {
    var expandedCategory by remember(selectedId) { mutableStateOf("General") }
    val selected = elements.firstOrNull { it.id == selectedId } ?: elements.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("PROPERTIES", color = Lime, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.5.sp)
        if (selected != null) {
            Text("PROPERTIES / ${selected.tag.uppercase()}", color = Lime, style = MaterialTheme.typography.labelMedium)
            val specs = remember(selected.type) { ElementPropertyCatalog.forElement(selected.type) }
            val categories = remember(specs) { specs.groupBy { it.category } }
            categories.forEach { (category, properties) ->
                EditorSection(category.uppercase(), expandedCategory.uppercase(), {
                    expandedCategory = if (expandedCategory == category) "" else category
                }) {
                    properties.forEach { spec ->
                        val value = selected.value(spec.name) ?: spec.defaultValue
                        when (spec.type) {
                            ElementValueType.BOOLEAN -> {
                                val checked = (value as? ElementValue.Bool)?.value ?: false
                                Row(Modifier.fillMaxWidth().heightIn(min = 38.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(spec.name.uppercase(), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                    Checkbox(checked = checked, onCheckedChange = { onChange(selected.withValue(spec.name, ElementValue.Bool(it))) }, colors = CheckboxDefaults.colors(checkedColor = Lime))
                                }
                            }
                            ElementValueType.ENUM -> {
                                val selectedValue = (value as? ElementValue.Text)?.value ?: spec.options.firstOrNull().orEmpty()
                                if (spec.name.contains("Measure What")) {
                                    MeasureWhatDropdown(selectedValue, spec.options) { onChange(selected.withValue(spec.name, ElementValue.Text(it))) }
                                } else {
                                    OptionDropdown(spec.name.uppercase(), selectedValue, spec.options) { onChange(selected.withValue(spec.name, ElementValue.Text(it))) }
                                }
                            }
                            ElementValueType.NUMBER, ElementValueType.INTEGER -> {
                                val current = when (value) {
                                    is ElementValue.Number -> value.value.toFloat()
                                    is ElementValue.Integer -> value.value.toFloat()
                                    else -> 0f
                                }
                                val minimum = (spec.min ?: 0.0).toFloat()
                                val maximum = (spec.max ?: 100.0).toFloat().coerceAtLeast(minimum + 0.001f)
                                val reset = when (val default = spec.defaultValue) {
                                    is ElementValue.Number -> default.value.toFloat()
                                    is ElementValue.Integer -> default.value.toFloat()
                                    else -> current
                                }
                                LayerSlider(spec.name.uppercase(), current, minimum..maximum, resetValue = reset) { next ->
                                    val nextValue = if (spec.type == ElementValueType.INTEGER) ElementValue.Integer(next.toLong()) else ElementValue.Number(next.toDouble())
                                    onChange(selected.withValue(spec.name, nextValue))
                                }
                            }
                            ElementValueType.TEXT, ElementValueType.COLOR -> {
                                val current = when (value) {
                                    is ElementValue.Text -> value.value
                                    is ElementValue.Integer -> String.format("#%08X", value.value)
                                    else -> ""
                                }
                                if (spec.name.contains("color", ignoreCase = true) && current.matches(Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?"))) {
                                    ColorWheelField(spec.name.uppercase(), current) { hex ->
                                        val nextValue = if (spec.type == ElementValueType.COLOR) ElementValue.Integer(android.graphics.Color.parseColor(hex).toLong() and 0xFFFFFFFFL) else ElementValue.Text(hex)
                                        onChange(selected.withValue(spec.name, nextValue))
                                    }
                                } else if (spec.name.contains("image", ignoreCase = true) || spec.name.contains("target", ignoreCase = true)) {
                                    Column(Modifier.fillMaxWidth()) {
                                        CompactTextInput(spec.name.uppercase(), current) { onChange(selected.withValue(spec.name, ElementValue.Text(it))) }
                                        TextButton(onClick = { onPickImage(spec.name) }) { Text("BROWSE IMAGE", color = Lime) }
                                    }
                                } else {
                                    CompactTextInput(spec.name.uppercase(), current) {
                                        val nextValue = if (spec.type == ElementValueType.COLOR) ElementValue.Integer(it.toLongOrNull() ?: 0L) else ElementValue.Text(it)
                                        onChange(selected.withValue(spec.name, nextValue))
                                    }
                                }
                            }
                            ElementValueType.VECTOR2 -> {
                                val vector = value as? ElementValue.Vector2 ?: ElementValue.Vector2(0.0, 0.0)
                                val minimum = (spec.min ?: -1000.0).toFloat()
                                val maximum = (spec.max ?: 1000.0).toFloat().coerceAtLeast(minimum + 0.001f)
                                LayerSlider("${spec.name.uppercase()} X", vector.x.toFloat(), minimum..maximum) { x ->
                                    onChange(selected.withValue(spec.name, ElementValue.Vector2(x.toDouble(), vector.y)))
                                }
                                LayerSlider("${spec.name.uppercase()} Y", vector.y.toFloat(), minimum..maximum) { y ->
                                    onChange(selected.withValue(spec.name, ElementValue.Vector2(vector.x, y.toDouble())))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AnalyzerSettingsScreen(
    config: com.vooc.visualizer.analyzer.AnalyzerConfig,
    onConfigChange: (com.vooc.visualizer.analyzer.AnalyzerConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var expandedCategory by remember { mutableStateOf("AUDIO INPUT") }
    val bandLabels = config.bands.bands.map { it.label }
    val defaultBandSpecs = remember(bandLabels) {
        if (bandLabels == listOf("Bass", "Mid", "Treble")) {
            com.vooc.visualizer.analyzer.BandConfig.defaultThreeBand()
        } else {
            com.vooc.visualizer.analyzer.BandConfig.logSpaced(config.bands.bands.size)
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Text("SETTINGS", color = Lime, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.5.sp)
            Text("AUDIO ANALYSIS • PROCESSING • FREQUENCY BANDS", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        item {
            EditorSection("AUDIO INPUT", expandedCategory, { expandedCategory = if (expandedCategory == "AUDIO INPUT") "" else "AUDIO INPUT" }) {
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
            }
        }
        item {
            EditorSection("BEAT DETECTION", expandedCategory, { expandedCategory = if (expandedCategory == "BEAT DETECTION") "" else "BEAT DETECTION" }) {
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
                    val signature = com.vooc.visualizer.analyzer.TimeSignature.entries.first { item -> item.label == it }
                    onConfigChange(config.copy(beat = config.beat.copy(timeSignature = signature)))
                }
            }
        }
        item {
            EditorSection("SIGNAL RESPONSE", expandedCategory, { expandedCategory = if (expandedCategory == "SIGNAL RESPONSE") "" else "SIGNAL RESPONSE" }) {
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
            }
        }
        item {
            EditorSection("ANALYSIS BUFFER", expandedCategory, { expandedCategory = if (expandedCategory == "ANALYSIS BUFFER") "" else "ANALYSIS BUFFER" }) {
                LayerSlider("RING BUFFER", config.ringBufferSamples.toFloat(), 4096f..16384f) {
                    onConfigChange(config.copy(ringBufferSamples = (it.toInt() / 1024 * 1024).coerceIn(4096, 16384)))
                }
                LayerSlider("WAVEFORM SAMPLES", config.waveformSamples.toFloat(), 256f..8192f) {
                    onConfigChange(config.copy(waveformSamples = (it.toInt() / 256 * 256).coerceIn(256, 8192)))
                }
            }
        }
        item {
            EditorSection("FREQUENCY BAND SETUP", expandedCategory, { expandedCategory = if (expandedCategory == "FREQUENCY BAND SETUP") "" else "FREQUENCY BAND SETUP" }) {
                OptionDropdown("CUSTOM FREQUENCY BANDS", config.bands.bands.size.toString(), (1..32).map { it.toString() }) {
                    onConfigChange(config.copy(bands = config.bands.copy(bands = com.vooc.visualizer.analyzer.BandConfig.logSpaced(it.toInt()))))
                }
                OptionDropdown("CROSSOVER FILTER", config.bands.crossover.name, com.vooc.visualizer.analyzer.CrossoverOrder.entries.map { it.name }) {
                    onConfigChange(config.copy(bands = config.bands.copy(crossover = com.vooc.visualizer.analyzer.CrossoverOrder.valueOf(it))))
                }
                OptionDropdown("FILTER SLOPE", config.bands.slope.name, com.vooc.visualizer.analyzer.BandSlope.entries.map { it.name }) {
                    onConfigChange(config.copy(bands = config.bands.copy(slope = com.vooc.visualizer.analyzer.BandSlope.valueOf(it))))
                }
                LayerSlider("DB FLOOR", config.bands.dbFloor, -120f..-10f) {
                    onConfigChange(config.copy(bands = config.bands.copy(dbFloor = it)))
                }
            }
        }
        items(config.bands.bands, key = { it.label }) { band ->
            val bandIndex = config.bands.bands.indexOfFirst { it.label == band.label }
            val defaultBand = defaultBandSpecs.getOrNull(bandIndex) ?: band
            EditorSection("BAND • ${band.label.uppercase()}", expandedCategory, {
                val title = "BAND • ${band.label.uppercase()}"
                expandedCategory = if (expandedCategory == title) "" else title
            }) {
                LayerSlider("LOW CUT HZ", band.lowHz, 20f..10000f, resetValue = defaultBand.lowHz) { value ->
                    val updated = config.bands.bands.map { current ->
                        if (current.label == band.label) current.copy(lowHz = value.coerceAtMost(current.highHz - 1f)) else current
                    }
                    onConfigChange(config.copy(bands = config.bands.copy(bands = updated)))
                }
                LayerSlider("HIGH CUT HZ", band.highHz, 250f..20000f, resetValue = defaultBand.highHz) { value ->
                    val updated = config.bands.bands.map { current ->
                        if (current.label == band.label) current.copy(highHz = value.coerceAtLeast(current.lowHz + 1f)) else current
                    }
                    onConfigChange(config.copy(bands = config.bands.copy(bands = updated)))
                }
                LayerSlider("GAIN", band.gain, 0f..2f) { value ->
                    val updated = config.bands.bands.map { current -> if (current.label == band.label) current.copy(gain = value) else current }
                    onConfigChange(config.copy(bands = config.bands.copy(bands = updated)))
                }
                LayerSlider("SMOOTHING", band.smoothing * 100f, 0f..99f, resetValue = 30f) { value ->
                    val updated = config.bands.bands.map { current -> if (current.label == band.label) current.copy(smoothing = value / 100f) else current }
                    onConfigChange(config.copy(bands = config.bands.copy(bands = updated)))
                }
                LayerSlider("ATTACK MS", band.attackMs, 0f..1000f) { value ->
                    val updated = config.bands.bands.map { current -> if (current.label == band.label) current.copy(attackMs = value) else current }
                    onConfigChange(config.copy(bands = config.bands.copy(bands = updated)))
                }
                LayerSlider("RELEASE MS", band.releaseMs, 0f..1000f) { value ->
                    val updated = config.bands.bands.map { current -> if (current.label == band.label) current.copy(releaseMs = value) else current }
                    onConfigChange(config.copy(bands = config.bands.copy(bands = updated)))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = band.agc, onCheckedChange = { enabled ->
                        val updated = config.bands.bands.map { current -> if (current.label == band.label) current.copy(agc = enabled) else current }
                        onConfigChange(config.copy(bands = config.bands.copy(bands = updated)))
                    }, colors = CheckboxDefaults.colors(checkedColor = Lime))
                    Text("AUTO GAIN CONTROL", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

internal fun updateLayer(project: StudioProject, id: Long, transform: (VisualLayer) -> VisualLayer): StudioProject =
    project.copy(layers = project.layers.map { if (it.id == id) transform(it) else it })
