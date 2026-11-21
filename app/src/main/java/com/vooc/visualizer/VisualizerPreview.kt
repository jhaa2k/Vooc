package com.vooc.visualizer

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import android.graphics.BitmapFactory
import com.vooc.visualizer.analyzer.ChannelSelect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.unit.sp
import com.vooc.visualizer.analyzer.AnalysisFrame
import com.vooc.visualizer.analyzer.AudioProviderFrame
import com.vooc.visualizer.analyzer.SpectrumBarsRuntime
import com.vooc.visualizer.studio.LayerKind
import com.vooc.visualizer.studio.ElementDocument
import com.vooc.visualizer.studio.ElementValue
import com.vooc.visualizer.studio.StudioElementType
import com.vooc.visualizer.studio.MeasureWhatEngine
import com.vooc.visualizer.studio.VisualLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow

@Composable
internal fun VisualizerPreview(modifier: Modifier, layers: List<VisualLayer>, elements: List<ElementDocument>, frame: AnalysisFrame?, providerFrame: AudioProviderFrame?, playing: Boolean) {
    var phase by remember { mutableFloatStateOf(0f) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val poppinsTypeface = remember(context) {
        androidx.core.content.res.ResourcesCompat.getFont(context, com.vooc.visualizer.R.font.poppins_variable)
    }
    val imageBitmaps by produceState(initialValue = emptyMap<Long, androidx.compose.ui.graphics.ImageBitmap>(), key1 = layers.map { it.id to it.imageUri }) {
        value = withContext(Dispatchers.IO) {
            layers.mapNotNull { layer: VisualLayer ->
                if (layer.kind != LayerKind.IMAGE || layer.imageUri.isBlank()) null
                else {
                    val bitmap: androidx.compose.ui.graphics.ImageBitmap? = runCatching {
                        context.contentResolver.openInputStream(Uri.parse(layer.imageUri))
                            ?.use { stream -> BitmapFactory.decodeStream(stream)?.asImageBitmap() }
                    }.getOrNull()
                    bitmap?.let { image -> layer.id to image }
                }
            }.toMap()
        }
    }
    val elementImages by produceState(
        initialValue = emptyMap<String, androidx.compose.ui.graphics.ImageBitmap>(),
        key1 = elements.map { it.id to ((it.value("Custom Bar Image") as? ElementValue.Text)?.value?.takeIf { value -> value.isNotBlank() } ?: (it.value("Custom Image") as? ElementValue.Text)?.value.orEmpty()) }
    ) {
        value = withContext(Dispatchers.IO) {
            elements.mapNotNull { element ->
                val uriText = ((element.value("Custom Bar Image") as? ElementValue.Text)?.value?.takeIf { it.isNotBlank() } ?: (element.value("Custom Image") as? ElementValue.Text)?.value.orEmpty())
                if (uriText.isBlank()) null else {
                    val bitmap = runCatching {
                        context.contentResolver.openInputStream(Uri.parse(uriText))?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                    }.getOrNull()
                    bitmap?.let { element.id to it }
                }
            }.toMap()
        }
    }
    val layerSmoothing = remember { mutableMapOf<Long, FloatArray>() }
    val barRuntimes = remember { mutableMapOf<String, SpectrumBarsRuntime>() }
    val barLastUpdateMs = remember { mutableMapOf<String, Long>() }
    LaunchedEffect(playing) {
        while (playing) { phase += 0.08f; delay(16) }
    }
    Canvas(modifier.background(Color.Black)) {
        clipRect {
        val cx = size.width / 2f
        val cy = size.height / 2f
        layers.forEach { layer ->
            val alpha = (layer.opacity / 100f).coerceIn(0f, 1f)
            val color = Color(layer.color).copy(alpha = alpha)
            val accent = Color(layer.gradientColor).copy(alpha = alpha)
            val baseScale = (layer.scale / 100f).coerceIn(0f, 4f)
            val baseCenter = Offset(cx + layer.x / 100f * size.width, cy + layer.y / 100f * size.height)
            val channel = runCatching { frame?.channel(ChannelSelect.valueOf(layer.channel)) }.getOrNull()
            val providerSamples = providerFrame?.samples
            val spectrum = providerSamples ?: channel?.spectrum ?: frame?.spectrum ?: FloatArray(64)
            val waveform = frame?.waveform ?: FloatArray(64)
            val band = when (layer.measureWhat) {
                "Bass" -> frame?.band(0) ?: 0f
                "Mid" -> frame?.band(1) ?: 0f
                "Treble" -> frame?.band(2) ?: 0f
                "RMS" -> channel?.rms ?: 0f
                "Peak" -> channel?.peak ?: 0f
                "Beat" -> providerFrame?.beatValue ?: frame?.beat?.pulse ?: 0f
                "Waveform" -> waveform.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
                else -> 0f
            }.coerceIn(0f, 1f)
            val bassLevel = providerSamples?.take((providerSamples.size / 5).coerceAtLeast(1))
                ?.average()?.toFloat() ?: (frame?.band(0) ?: 0f)
            val trebleLevel = providerSamples?.takeLast((providerSamples.size / 5).coerceAtLeast(1))
                ?.average()?.toFloat() ?: (frame?.band(2) ?: 0f)
            val motion = MeasureWhatEngine.evaluate(
                mode = layer.measureWhat,
                runtimeSeconds = android.os.SystemClock.elapsedRealtime() / 1000f,
                trackPositionSeconds = (frame?.timeSec ?: 0.0).toFloat(),
                playing = playing,
                beatPulse = providerFrame?.beatValue ?: frame?.beat?.pulse ?: 0f,
                bass = bassLevel,
                treble = trebleLevel,
                bpm = frame?.beat?.bpm ?: 120f,
                seed = layer.id,
                playingSeconds = phase
            )
            val center = Offset(
                baseCenter.x + motion.offsetX * size.width,
                baseCenter.y + motion.offsetY * size.height
            )
            val scale = (baseScale * motion.scale).coerceIn(0f, 4f)
            val count: Int = layer.barCount.coerceIn(8, 256)
            val values: FloatArray = when (layer.measureWhat) {
                "Bass", "Mid", "Treble", "RMS", "Peak", "Beat" -> FloatArray(count) { band }
                "Waveform" -> waveform
                else -> spectrum
            }
            val valueAt: (Int) -> Float = { index: Int ->
                val rawValue = if (values.isEmpty()) {
                    0f
                } else if (layer.measureWhat == "Spectrum" && frame != null && providerSamples == null) {
                    val x = index.toFloat() / (count - 1).coerceAtLeast(1)
                    val minHz: Float = layer.spectrumMinHz.coerceIn(10f, frame.sampleRate.toFloat() * 0.45f)
                    val maxHz: Float = layer.spectrumMaxHz.coerceIn(minHz + 1f, frame.sampleRate.toFloat() * 0.49f)
                    val hz = if (layer.spectrumScale == "LOGARITHMIC")
                        minHz * kotlin.math.exp(kotlin.math.ln(maxHz / minHz) * x)
                    else minHz + (maxHz - minHz) * x
                    val bin = (hz / frame.binWidthHz).coerceIn(0f, values.lastIndex.toFloat())
                    val lowBin = bin.toInt()
                    val highBin = (lowBin + 1).coerceAtMost(values.lastIndex)
                    values[lowBin] + (values[highBin] - values[lowBin]) * (bin - lowBin.toFloat())
                } else {
                    val sampleIndex = (index * values.size / count).coerceIn(0, values.lastIndex)
                    values[sampleIndex].coerceIn(0f, 1f)
                }
                (rawValue * layer.sensitivity / 100f).coerceIn(0f, 1f)
            }
            // Presets reuse layer IDs; resize buffers when the sample count changes.
            val smoothed = layerSmoothing[layer.id]?.takeIf { it.size == count }
                ?: FloatArray(count).also { layerSmoothing[layer.id] = it }
            val smoothingFactor = (1f - layer.smoothing / 100f * 0.95f).coerceIn(0.03f, 1f)
            for (sampleIndex in 0 until count) {
                val targetValue = valueAt(sampleIndex)
                smoothed[sampleIndex] += (targetValue - smoothed[sampleIndex]) * smoothingFactor
            }
            val responsiveValueAt: (Int) -> Float = { sampleIndex ->
                smoothed[sampleIndex.coerceIn(0, count - 1)]
            }
            rotate(layer.rotation + motion.rotation, center) {
                when (layer.kind) {
                    LayerKind.BACKGROUND -> drawRect(Color(0xFF070906))
                    LayerKind.BARS, LayerKind.BARSH, LayerKind.BLOCKS -> {
                        val gap = layer.spacing.dp.toPx()
                        val bw = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
                        for (i in 0 until count) {
                            val v = responsiveValueAt(i)
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
                            val v = responsiveValueAt(i)
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
                                val v = responsiveValueAt(i)
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
                            val v = responsiveValueAt(i % count)
                            val r = size.minDimension * (0.08f + (i % 9) * 0.035f) * scale * (1f + v * 0.25f)
                            drawCircle(if (i % 2 == 0) color else accent, (layer.thickness * 0.55f + v * 3f).dp.toPx(),
                                Offset(center.x + cos(a) * r, center.y + sin(a) * r))
                        }
                    }
                    LayerKind.LINES -> {
                        for (i in 0 until count) {
                            val v = responsiveValueAt(i)
                            val x = i * size.width / count
                            drawLine(if (i % 2 == 0) color else accent, Offset(x, cy - v * cy), Offset(x, cy + v * cy), layer.thickness.dp.toPx())
                        }
                    }
                    LayerKind.SHAPE -> {
                        val radius = size.minDimension * layer.radius / 100f * scale
                        val path = Path()
                        repeat(6) { i ->
                            val a = i * 2f * PI.toFloat() / 6f
                            val r = radius * (1f + responsiveValueAt(i) * 0.35f)
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
                            typeface = poppinsTypeface?.let { android.graphics.Typeface.create(it, android.graphics.Typeface.BOLD) }
                                ?: android.graphics.Typeface.DEFAULT_BOLD
                        }
                        canvas.nativeCanvas.drawText(layer.textContent.uppercase(), center.x, center.y, paint)
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
        elements.filter { it.visible && it.type != StudioElementType.AUDIO_PROVIDER }.forEach { element ->
            val props = element.properties
            fun number(name: String, fallback: Float): Float = when (val value = props[name]) {
                is ElementValue.Number -> value.value.toFloat()
                is ElementValue.Integer -> value.value.toFloat()
                else -> fallback
            }
            fun textValue(name: String, fallback: String = ""): String = (props[name] as? ElementValue.Text)?.value ?: fallback
            val position = props["Position"] as? ElementValue.Vector2 ?: ElementValue.Vector2(0.0, 0.0)
            val elementSamples = providerFrame?.samples ?: frame?.spectrum ?: FloatArray(0)
            val bandSize = (elementSamples.size / 5).coerceAtLeast(1)
            val bass = elementSamples.take(bandSize).average().toFloat().coerceIn(0f, 1f)
            val treble = elementSamples.takeLast(bandSize).average().toFloat().coerceIn(0f, 1f)
            fun motionFor(property: String) = MeasureWhatEngine.evaluate(
                mode = textValue(property, "Nothing"),
                runtimeSeconds = phase,
                trackPositionSeconds = (frame?.timeSec ?: 0.0).toFloat(),
                playing = playing,
                beatPulse = providerFrame?.beatValue ?: frame?.beat?.pulse ?: 0f,
                bass = bass, treble = treble, bpm = frame?.beat?.bpm ?: 120f,
                seed = element.id.hashCode().toLong(), playingSeconds = phase
            )
            val posMotion = motionFor("Measure Pos (Measure What)")
            val scaleMotion = motionFor("Measure Scale (Measure What)")
            val rotMotion = motionFor("Measure Rot (Measure What)")
            val center = Offset(
                cx + (position.x.toFloat() / 200f + posMotion.offsetX) * size.width,
                cy + (position.y.toFloat() / 200f + posMotion.offsetY) * size.height
            )
            val scale = (number("Scale", 100f) / 100f * scaleMotion.scale).coerceIn(0f, 10f)
            val rotation = number("Rotation", 0f) + posMotion.rotation + scaleMotion.rotation + rotMotion.rotation
            val opacity = (number("Opacity", 100f) / 100f).coerceIn(0f, 1f)
            val colorText = textValue("Color", "#FFFFFFFF")
            val elementColor = runCatching { Color(android.graphics.Color.parseColor(colorText)).copy(alpha = opacity) }.getOrDefault(Color.White.copy(alpha = opacity))
            val label = textValue("Text", element.tag).take(120)
            rotate(rotation, center) {
                when (element.type) {
                    StudioElementType.TEXT -> drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = elementColor.toArgb()
                            textSize = (number("Font Size", 48f) * scale).coerceAtLeast(6f).sp.toPx()
                            textAlign = android.graphics.Paint.Align.CENTER
                            typeface = poppinsTypeface?.let { android.graphics.Typeface.create(it, android.graphics.Typeface.BOLD) } ?: android.graphics.Typeface.DEFAULT_BOLD
                        }
                        canvas.nativeCanvas.drawText(label, center.x, center.y, paint)
                    }
                    StudioElementType.BARS_SEGMENTS -> {
                        val rawSamples: FloatArray = providerFrame?.samples ?: frame?.spectrum ?: FloatArray(64)
                        val count = number("Bar Count", 64f).toInt().coerceIn(1, 512)
                        val amplitudeScale = number("Amplitude Scale", 1f).coerceIn(0f, 16f)
                        val gamma = number("Amplitude Gamma", 1f).coerceIn(0.1f, 4f)
                        val input = FloatArray(rawSamples.size.coerceAtLeast(1)) { i ->
                            (rawSamples.getOrElse(i) { 0f }.coerceAtLeast(0f) * amplitudeScale).coerceAtLeast(0f)
                                .let { value -> (value / (1f + value)).pow(gamma).coerceIn(0f, 1f) }
                        }
                        val nowMs = android.os.SystemClock.elapsedRealtime()
                        val previousMs = barLastUpdateMs.put(element.id, nowMs) ?: nowMs - 16L
                        val delta = ((nowMs - previousMs).coerceIn(1L, 100L) / 1000f)
                        val runtime = barRuntimes.getOrPut(element.id) { SpectrumBarsRuntime() }
                        val barFrame = runtime.update(
                            input = input,
                            count = count,
                            deltaSeconds = delta,
                            damping = number("Smoothing / Damping", 0.72f),
                            peakHold = number("Peak Hold Time", number("Peak Hold", 0.65f)),
                            peakFallSpeed = number("Peak Fall Speed", 0.8f),
                            logarithmicInput = providerFrame == null && textValue("Frequency Scale", "Logarithmic") == "Logarithmic",
                            reactionDelayMs = number("Reaction Delay", 0f),
                            accumulatedDelayMs = number("Accumulated Delay", 0f),
                            attackSmoothing = number("Attack Smoothing", 0.12f),
                            releaseSmoothing = number("Release Smoothing", number("Smoothing / Damping", 0.72f))
                        )
                        val levels = barFrame.levels
                        val peaks = barFrame.peaks
                        val gap = number("Spacing / Gap", 3f).dp.toPx().coerceAtLeast(0f)
                        val scaleVector = props["Scale"] as? ElementValue.Vector2
                        val scaleX = ((scaleVector?.x ?: 1.0).toFloat() * scale).coerceIn(0f, 10f)
                        val scaleYRaw = (scaleVector?.y ?: 1.0).toFloat()
                        val scaleY = ((if (props["Scale Is Uniform"] == ElementValue.Bool(true)) (scaleVector?.x ?: 1.0).toFloat() else scaleYRaw) * scale).coerceIn(0f, 10f)
                        val totalWidth = (size.width * 0.86f * scaleX).coerceAtMost(size.width)
                        val slot = totalWidth / count
                        val requestedWidth = number("Bar Width", 8f).dp.toPx()
                        val barWidth = requestedWidth.coerceIn(0.5f, (slot - gap).coerceAtLeast(0.5f))
                        val maxHeight = (number("Max Height", size.height * 0.35f) * scaleY).coerceIn(1f, size.height * 0.95f)
                        val minHeight = number("Min Height", 0f).coerceIn(0f, maxHeight)
                        val design = textValue("Bar Design", "Rounded Rect")
                        val alignment = textValue("Alignment", "Bottom")
                        val mirrorMode = textValue("Mirror Mode", if (props["Mirror"] == ElementValue.Bool(true)) "Horizontal" else "Off")
                        val horizontalMirror = mirrorMode == "Horizontal" || mirrorMode == "Both" || props["Mirror"] == ElementValue.Bool(true)
                        val verticalMirror = mirrorMode == "Vertical" || mirrorMode == "Both"
                        val centerSymmetry = props["Center Symmetry"] == ElementValue.Bool(true)
                        val outline = props["Outline / Stroke"] == ElementValue.Bool(true)
                        val strokeWidth = number("Stroke Width", 1.5f).dp.toPx().coerceAtLeast(0.5f)
                        val strokeColor = runCatching { Color(android.graphics.Color.parseColor(textValue("Stroke Color", colorText))) }.getOrDefault(elementColor)
                        val colorA = runCatching { Color(android.graphics.Color.parseColor(textValue("Bar Color A", "#FF56D8FF"))) }.getOrDefault(elementColor)
                        val colorB = runCatching { Color(android.graphics.Color.parseColor(textValue("Bar Color B", "#FF9B5CFF"))) }.getOrDefault(elementColor)
                        val peakColor = runCatching { Color(android.graphics.Color.parseColor(textValue("Peak Color", "#FFFFFFFF"))) }.getOrDefault(strokeColor)
                        val capColor = runCatching { Color(android.graphics.Color.parseColor(textValue("Cap Color", "#FFFFFFFF"))) }.getOrDefault(strokeColor)
                        val colorMode = if (props["Gradient Fill"] == ElementValue.Bool(true)) "Gradient" else textValue("Color Mode", "Alternating")
                        val capMode = textValue("Top/Bottom Cap", "None")
                        val capPlacement = textValue("Cap Placement", "Top")
                        val capSize = number("Cap Size", 4f).dp.toPx().coerceAtLeast(1f)
                        val customImage = elementImages[element.id]
                        val corner = when (design) {
                            "Capsule / Pill" -> barWidth / 2f
                            "Rounded Rect" -> (barWidth * 0.22f).coerceAtLeast(1f)
                            else -> 0f
                        }
                        val dashEffect = when (textValue("Stroke Style", "Solid")) {
                            "Dashed" -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(strokeWidth * 3f, strokeWidth * 2f))
                            "Dotted" -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(strokeWidth, strokeWidth * 1.8f))
                            else -> null
                        }
                        val shapeStyle = if (outline) Stroke(strokeWidth, pathEffect = dashEffect) else androidx.compose.ui.graphics.drawscope.Fill
                        val gradientBrush = if (textValue("Gradient Direction", "Vertical") == "Horizontal") androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(colorA, colorB)) else androidx.compose.ui.graphics.Brush.verticalGradient(listOf(colorA, colorB))
                        val barOffset = number("Bar Offset", 0f).dp.toPx()
                        val extrusion = props["3D / Extrusion"] == ElementValue.Bool(true)
                        val extrusionDepth = number("Extrusion Depth", 5f).dp.toPx()
                        val glowEnabled = props["Glow / Shadow"] == ElementValue.Bool(true)
                        val glowAlpha = number("Glow Strength", 0.35f).coerceIn(0f, 1f)
                        val peakEnabled = props["Peak / Cap"] == ElementValue.Bool(true)
                        val peakWidth = number("Peak Width", 2f).dp.toPx().coerceAtLeast(0.5f)
                        val barRotation = number("Bar Rotation", 0f)
                        fun drawBarShape(left: Float, top: Float, width: Float, height: Float, fill: Color, peakValue: Float, index: Int) {
                            val rectSize = androidx.compose.ui.geometry.Size(width, height.coerceAtLeast(1f))
                            val rectTop = Offset(left, top)
                            val path = Path().apply {
                                when (design) {
                                    "Triangle" -> { moveTo(left, top + height); lineTo(left + width / 2f, top); lineTo(left + width, top + height); close() }
                                    "Diamond" -> { moveTo(left + width / 2f, top); lineTo(left + width, top + height / 2f); lineTo(left + width / 2f, top + height); lineTo(left, top + height / 2f); close() }
                                    "Hexagon" -> { moveTo(left + width * 0.22f, top); lineTo(left + width * 0.78f, top); lineTo(left + width, top + height / 2f); lineTo(left + width * 0.78f, top + height); lineTo(left + width * 0.22f, top + height); lineTo(left, top + height / 2f); close() }
                                    "Star" -> { for (point in 0 until 10) { val angle = (point * 36f - 90f) * PI.toFloat() / 180f; val radius = if (point % 2 == 0) 0.5f else 0.22f; val px = left + width * (0.5f + cos(angle) * radius); val py = top + height * (0.5f + sin(angle) * radius); if (point == 0) moveTo(px, py) else lineTo(px, py) }; close() }
                                    "Chevron" -> { moveTo(left, top + height * 0.28f); lineTo(left + width * 0.5f, top); lineTo(left + width, top + height * 0.28f); lineTo(left + width, top + height * 0.72f); lineTo(left + width * 0.5f, top + height); lineTo(left, top + height * 0.72f); close() }
                                    "Arrow" -> { moveTo(left + width * 0.35f, top); lineTo(left + width, top + height * 0.5f); lineTo(left + width * 0.35f, top + height); lineTo(left + width * 0.35f, top + height * 0.68f); lineTo(left, top + height * 0.68f); lineTo(left, top + height * 0.32f); lineTo(left + width * 0.35f, top + height * 0.32f); close() }
                                    "Cross" -> { moveTo(left + width * 0.35f, top); lineTo(left + width * 0.65f, top); lineTo(left + width * 0.65f, top + height * 0.35f); lineTo(left + width, top + height * 0.35f); lineTo(left + width, top + height * 0.65f); lineTo(left + width * 0.65f, top + height * 0.65f); lineTo(left + width * 0.65f, top + height); lineTo(left + width * 0.35f, top + height); lineTo(left + width * 0.35f, top + height * 0.65f); lineTo(left, top + height * 0.65f); lineTo(left, top + height * 0.35f); lineTo(left + width * 0.35f, top + height * 0.35f); close() }
                                    "Wave" -> { moveTo(left, top + height * 0.2f); lineTo(left + width * 0.5f, top); lineTo(left + width, top + height * 0.2f); lineTo(left + width, top + height); lineTo(left + width * 0.5f, top + height * 0.8f); lineTo(left, top + height); close() }
                                    else -> { moveTo(left, top); lineTo(left + width, top); lineTo(left + width, top + height); lineTo(left, top + height); close() }
                                }
                            }
                            if (glowEnabled) {
                                drawRoundRect(fill.copy(alpha = glowAlpha * 0.18f), Offset(left - capSize, top - capSize),
                                    androidx.compose.ui.geometry.Size(width + capSize * 2f, height + capSize * 2f),
                                    androidx.compose.ui.geometry.CornerRadius(corner + capSize))
                            }
                            if (extrusion) {
                                val shifted = Path().apply { addPath(path, Offset(extrusionDepth, extrusionDepth)) }
                                drawPath(shifted, fill.copy(alpha = opacity * 0.32f), style = shapeStyle)
                            }
                            val modeBrush = when (colorMode) {
                                "Gradient" -> if (textValue("Gradient Direction", "Vertical") == "Horizontal") gradientBrush
                                    else androidx.compose.ui.graphics.Brush.verticalGradient(listOf(colorA, colorB), top, top + height.coerceAtLeast(1f))
                                "By Amplitude" -> androidx.compose.ui.graphics.Brush.verticalGradient(listOf(colorA, colorB), top, top + height.coerceAtLeast(1f))
                                "Alternating" -> androidx.compose.ui.graphics.SolidColor(if (index % 2 == 0) colorA else colorB)
                                else -> androidx.compose.ui.graphics.SolidColor(elementColor)
                            }
                            if (outline) {
                                when (design) {
                                    "Circle / Dot" -> drawCircle(strokeColor.copy(alpha = opacity), width * 0.5f, Offset(left + width / 2f, top + height / 2f), style = shapeStyle)
                                    "Line" -> drawLine(strokeColor, Offset(left + width / 2f, top), Offset(left + width / 2f, top + height), strokeWidth, cap = StrokeCap.Round)
                                    "Rounded Rect", "Capsule / Pill" -> drawRoundRect(strokeColor, rectTop, rectSize, androidx.compose.ui.geometry.CornerRadius(corner), style = shapeStyle)
                                    else -> drawPath(path, strokeColor, style = shapeStyle)
                                }
                            } else if (design == "Custom Image" && customImage != null) {
                                drawImage(customImage, dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                                    dstSize = androidx.compose.ui.unit.IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)))
                            } else {
                                when (design) {
                                    "Circle / Dot" -> drawCircle(fill, (height * 0.5f).coerceAtLeast(1f), Offset(left + width / 2f, top + height / 2f))
                                    "Line" -> drawLine(fill, Offset(left + width / 2f, top), Offset(left + width / 2f, top + height), width.coerceAtLeast(1f), cap = StrokeCap.Round)
                                    "Rounded Rect", "Capsule / Pill" -> drawRoundRect(brush = modeBrush, topLeft = rectTop, size = rectSize, cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner))
                                    else -> drawPath(path, modeBrush)
                                }
                            }
                            if (capMode != "None") {
                                val capYTop = top
                                val capYBottom = top + height
                                val capY = when (capPlacement) {
                                    "Bottom" -> capYBottom
                                    "Both" -> capYTop
                                    else -> capYTop
                                }
                                fun capAt(y: Float) {
                                    val cx = left + width / 2f
                                    when (capMode) {
                                        "Circle" -> drawCircle(capColor.copy(alpha = opacity), capSize, Offset(cx, y))
                                        "Square" -> drawRect(capColor.copy(alpha = opacity), Offset(cx - capSize, y - capSize), androidx.compose.ui.geometry.Size(capSize * 2f, capSize * 2f))
                                        "Diamond" -> {
                                            val capPath = Path().apply { moveTo(cx, y - capSize); lineTo(cx + capSize, y); lineTo(cx, y + capSize); lineTo(cx - capSize, y); close() }
                                            drawPath(capPath, capColor.copy(alpha = opacity))
                                        }
                                    }
                                }
                                if (capPlacement != "Bottom") capAt(capYTop)
                                if (capPlacement != "Top") capAt(capYBottom)
                            }
                            if (peakEnabled) {
                                val peakY = top - peakValue * maxHeight
                                drawLine(peakColor.copy(alpha = opacity), Offset(left - peakWidth, peakY), Offset(left + width + peakWidth, peakY), peakWidth)
                            }
                        }
                        repeat(count) { index ->
                            val sourceIndex = if (horizontalMirror) minOf(index, count - 1 - index) else index
                            var level = levels[sourceIndex].coerceIn(0f, 1f)
                            if (props["Flip Input"] == ElementValue.Bool(true)) level = 1f - level
                            val barHeight = (minHeight + level * maxHeight).coerceIn(0f, size.height * 0.95f)
                            val drawHeight = if (centerSymmetry) barHeight / 2f else barHeight
                            val x = center.x - totalWidth / 2f + index * slot + (slot - barWidth) / 2f + barOffset
                            val baseline = when (alignment) {
                                "Top" -> center.y - size.height * 0.38f
                                "Center" -> center.y
                                "Stretch" -> center.y + size.height * 0.44f
                                else -> center.y + size.height * 0.38f
                            }
                            val top = if (centerSymmetry) baseline - drawHeight else if (alignment == "Center") baseline - barHeight / 2f
                                else if (alignment == "Top") baseline else baseline - barHeight
                            val height = if (alignment == "Stretch") (drawHeight * 1.25f).coerceAtMost(size.height * 0.95f) else drawHeight
                            val colorForBar = when (colorMode) {
                                "Alternating" -> if (index % 2 == 0) colorA else colorB
                                "Gradient", "By Amplitude" -> Color(
                                    colorA.red + (colorB.red - colorA.red) * level,
                                    colorA.green + (colorB.green - colorA.green) * level,
                                    colorA.blue + (colorB.blue - colorA.blue) * level,
                                    opacity
                                )
                                else -> elementColor
                            }
                            rotate(barRotation, Offset(x + barWidth / 2f, baseline)) {
                                drawBarShape(x, top, barWidth, height, colorForBar, peaks[sourceIndex], index)
                                if (verticalMirror || centerSymmetry) {
                                    drawBarShape(x, baseline, barWidth, height, colorForBar, peaks[sourceIndex], index)
                                }
                            }
                        }
                    }
                    StudioElementType.PARTICLES -> {
                        val count = number("Count Limit", 64f).toInt().coerceIn(1, 300)
                        val particleScale = number("Particle Scale", 100f) / 100f * scale
                        val colorTo = runCatching { Color(android.graphics.Color.parseColor(textValue("Color To", colorText))) }.getOrDefault(elementColor)
                        repeat(count) { index ->
                            val angle = index * 2f * PI.toFloat() / count + phase * 0.35f
                            val radius = size.minDimension * (0.06f + (index % 11) * 0.018f) * particleScale
                            drawCircle(if (index % 2 == 0) elementColor else colorTo.copy(alpha = opacity),
                                (2f + number("Start Size", 8f) * 0.08f).dp.toPx(),
                                Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius))
                        }
                    }
                    StudioElementType.IMAGE -> {
                        val bitmap = elementImages[element.id]
                        if (bitmap != null) {
                            val side = (size.minDimension * 0.45f * scale).coerceAtLeast(1f)
                            drawImage(bitmap, dstOffset = androidx.compose.ui.unit.IntOffset((center.x - side / 2f).toInt(), (center.y - side / 2f).toInt()),
                                dstSize = androidx.compose.ui.unit.IntSize(side.toInt(), side.toInt()))
                        } else {
                            drawCircle(elementColor.copy(alpha = 0.35f), size.minDimension * 0.11f * scale, center, style = Stroke(width = 2.dp.toPx()))
                            drawLine(elementColor, Offset(center.x - 18.dp.toPx(), center.y), Offset(center.x + 18.dp.toPx(), center.y), 2.dp.toPx())
                            drawLine(elementColor, Offset(center.x, center.y - 18.dp.toPx()), Offset(center.x, center.y + 18.dp.toPx()), 2.dp.toPx())
                        }
                    }
                    StudioElementType.RGB_SPLIT -> {
                        val amount = number("Split Amount", 4f).coerceIn(0f, 64f).dp.toPx()
                        listOf(Color.Red, Color.Green, Color.Blue).forEachIndexed { index, tint ->
                            drawCircle(tint.copy(alpha = opacity * 0.65f), size.minDimension * (0.09f + index * 0.015f) * scale,
                                Offset(center.x + (index - 1) * amount, center.y), style = Stroke(width = 2.dp.toPx()))
                        }
                    }
                    StudioElementType.BLUR_EFFECT, StudioElementType.MOTION_BLUR_EFFECT -> {
                        val radius = (number("Blur Radius", 12f) * scale).coerceIn(1f, size.minDimension)
                        drawCircle(elementColor.copy(alpha = opacity * 0.28f), radius, center, style = Stroke(width = maxOf(1f, radius * 0.12f)))
                        if (element.type == StudioElementType.MOTION_BLUR_EFFECT) {
                            drawLine(elementColor.copy(alpha = opacity * 0.4f), Offset(center.x - radius, center.y - radius * 0.25f),
                                Offset(center.x + radius, center.y + radius * 0.25f), maxOf(1f, radius * 0.08f))
                        }
                    }
                    StudioElementType.SHAPE -> {
                        val w = number("Width", 300f).dp.toPx() * scale
                        val h = number("Height", 300f).dp.toPx() * scale
                        val shapeColor = runCatching { Color(android.graphics.Color.parseColor(textValue("Fill / Color", colorText))) }
                            .getOrDefault(elementColor).copy(alpha = opacity * number("Opacity / Alpha", 1f).coerceIn(0f, 1f))
                        val left = center.x - w / 2f
                        val top = center.y - h / 2f
                        when (textValue("Shape Type", "Rectangle")) {
                            "Circle/Oval" -> drawOval(shapeColor, Offset(left, top), androidx.compose.ui.geometry.Size(w, h))
                            "Line" -> drawLine(shapeColor, Offset(left, center.y), Offset(left + w, center.y),
                                number("Stroke / Border Width", 3f).dp.toPx().coerceAtLeast(1f))
                            "Triangle" -> {
                                val p = Path().apply { moveTo(center.x, top); lineTo(left + w, top + h); lineTo(left, top + h); close() }
                                drawPath(p, shapeColor)
                            }
                            else -> drawRoundRect(shapeColor, Offset(left, top), androidx.compose.ui.geometry.Size(w, h),
                                androidx.compose.ui.geometry.CornerRadius(number("Corner Radius", 0f).dp.toPx()))
                        }
                    }
                    StudioElementType.BACKGROUND -> Unit
                    StudioElementType.AUDIO_PROVIDER -> Unit
                }
            }
        }
        }
    }
}
