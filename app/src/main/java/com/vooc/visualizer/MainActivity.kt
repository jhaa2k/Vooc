package com.vooc.visualizer

import com.vooc.visualizer.studio.replacePreservingOrder
import com.vooc.visualizer.studio.buildNormalizedImageShapePath
import com.vooc.visualizer.studio.BackgroundImageProcessor

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import android.graphics.BitmapFactory
import com.vooc.visualizer.analyzer.ChannelSelect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.vooc.visualizer.analyzer.AnalysisFrame
import com.vooc.visualizer.analyzer.AudioAnalyzer
import com.vooc.visualizer.analyzer.AudioProviderFrame
import com.vooc.visualizer.analyzer.AudioProviderProcessor
import com.vooc.visualizer.analyzer.SpectrumBarsRuntime
import com.vooc.visualizer.analyzer.SpectrumMapper
import com.vooc.visualizer.analyzer.applyTo
import com.vooc.visualizer.analyzer.toAudioProviderConfig
import com.vooc.visualizer.analyzer.FftSize
import com.vooc.visualizer.analyzer.ChannelMode
import com.vooc.visualizer.analyzer.WindowFunction
import com.vooc.visualizer.analyzer.Overlap
import com.vooc.visualizer.analyzer.BeatAlgorithm
import com.vooc.visualizer.playback.VoocAudioEngine
import com.vooc.visualizer.playback.VoocPlaybackService
import com.vooc.visualizer.studio.LayerKind
import com.vooc.visualizer.studio.LayerAddMenuCatalog
import com.vooc.visualizer.studio.LegacyLayerAdapter
import com.vooc.visualizer.studio.ElementPropertyCatalog
import com.vooc.visualizer.studio.ElementDocument
import com.vooc.visualizer.studio.ElementValue
import com.vooc.visualizer.studio.ElementValueType
import com.vooc.visualizer.studio.StudioElementType
import com.vooc.visualizer.studio.ProjectStore
import com.vooc.visualizer.studio.PresetFileCodec
import com.vooc.visualizer.studio.ProjectRepository
import com.vooc.visualizer.studio.ProjectEntry
import com.vooc.visualizer.studio.ProjectKeyframe
import com.vooc.visualizer.studio.StudioProject
import com.vooc.visualizer.studio.MeasureWhatEngine
import com.vooc.visualizer.studio.AudioReactivityRuntime
import com.vooc.visualizer.studio.audioReactivitySources
import com.vooc.visualizer.studio.audioReactivityColor
import com.vooc.visualizer.studio.processBarSpectrum
import com.vooc.visualizer.studio.audioReactivityTransform
import com.vooc.visualizer.studio.StudioPresets
import com.vooc.visualizer.studio.TemplateStore
import com.vooc.visualizer.studio.VisualLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.min

private val Lime = Color(0xFFC6F36A)
private val Ink = Color(0xFF0C0E0B)
private val Panel = Color(0xFF171A14)
private val Muted = Color(0xFF92998A)
private fun processBlurEffectForPreview(source: android.graphics.Bitmap, element: ElementDocument): android.graphics.Bitmap {
    fun number(name: String, fallback: Float): Float = when (val value = element.value(name)) {
        is ElementValue.Number -> value.value.toFloat()
        is ElementValue.Integer -> value.value.toFloat()
        else -> fallback
    }
    fun text(name: String, fallback: String): String = (element.value(name) as? ElementValue.Text)?.value ?: fallback
    fun vector(name: String): ElementValue.Vector2 =
        element.value(name) as? ElementValue.Vector2 ?: ElementValue.Vector2(1.0, 1.0)
    val radius = (number("Blur Radius", 2f) * number("Blur Multiplier", 4.1f)).coerceIn(0f, 512f)
    val blurred = if (radius <= 0.1f) source else {
        val reduction = (1f / (1f + radius / 3f)).coerceIn(0.05f, 1f)
        val reduced = android.graphics.Bitmap.createScaledBitmap(source,
            (source.width * reduction).toInt().coerceAtLeast(1),
            (source.height * reduction).toInt().coerceAtLeast(1), true)
        val result = android.graphics.Bitmap.createScaledBitmap(reduced, source.width, source.height, true)
        if (reduced !== source) reduced.recycle()
        result
    }
    val output = android.graphics.Bitmap.createBitmap(source.width, source.height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    val mode = when (text("Blend Mode Content", "Alpha")) {
        "Add" -> android.graphics.PorterDuff.Mode.ADD
        "Screen" -> android.graphics.PorterDuff.Mode.SCREEN
        "Multiply", "Subtractive" -> android.graphics.PorterDuff.Mode.MULTIPLY
        "Darken" -> android.graphics.PorterDuff.Mode.DARKEN
        "Lighten" -> android.graphics.PorterDuff.Mode.LIGHTEN
        "Exclusion" -> android.graphics.PorterDuff.Mode.XOR
        "Copy" -> android.graphics.PorterDuff.Mode.SRC
        else -> android.graphics.PorterDuff.Mode.SRC_OVER
    }
    for (index in 1..3) {
        val scale = vector("${index}layer Scale")
        val sx = scale.x.toFloat().coerceIn(0f, 10f)
        val sy = scale.y.toFloat().coerceIn(0f, 10f)
        if (sx <= 0f || sy <= 0f) continue
        val color = runCatching { android.graphics.Color.parseColor(text("${index}layer Color", "#FFFFFFFF")) }
            .getOrDefault(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            if (color != android.graphics.Color.WHITE) {
                colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.MULTIPLY)
            }
            if (mode != android.graphics.PorterDuff.Mode.SRC_OVER) xfermode = android.graphics.PorterDuffXfermode(mode)
        }
        val halfW = source.width * sx / 2f
        val halfH = source.height * sy / 2f
        canvas.drawBitmap(blurred, null, android.graphics.RectF(
            source.width / 2f - halfW, source.height / 2f - halfH,
            source.width / 2f + halfW, source.height / 2f + halfH
        ), paint)
    }
    if (blurred !== source) blurred.recycle()
    return output
}

private fun processImageForPreview(source: android.graphics.Bitmap, element: ElementDocument, maskSource: android.graphics.Bitmap? = null): android.graphics.Bitmap {
    fun number(name: String, fallback: Float): Float = when (val value = element.value(name)) {
        is ElementValue.Number -> value.value.toFloat()
        is ElementValue.Integer -> value.value.toFloat()
        else -> fallback
    }
    fun text(name: String, fallback: String = ""): String = (element.value(name) as? ElementValue.Text)?.value ?: fallback
    fun enabled(name: String): Boolean = (element.value(name) as? ElementValue.Bool)?.value == true
    val blurRadius = if (enabled("Blur Enabled")) number("Blur Radius", 0f).coerceIn(0f, 128f) else 0f
    val blurDivider = number("Blur Divider", 2f).coerceIn(1f, 32f)
    val edgeBlur = number("Edge Blur", 0f).coerceIn(0f, 128f)
    val blurredBorder = enabled("Blurred Border")
    val keyEnabled = enabled("Color Key Enabled")
    val brightness = number("Image Brightness", 1f).coerceIn(0f, 4f)
    val contrast = number("Image Contrast", 1f).coerceIn(0f, 4f)
    val hueShift = number("Image Hue", 0f).coerceIn(-360f, 360f)
    val gamma = number("Image Gamma", 1f).coerceIn(0.1f, 4f)
    val temperature = number("Image Color Temperature", 6500f).coerceIn(1000f, 12000f)
    val warm = ((temperature - 6500f) / 5500f).coerceIn(-1f, 1f)
    val adjustImageColor = brightness != 1f || contrast != 1f || hueShift != 0f || gamma != 1f || temperature != 6500f
    if (blurRadius <= 0f && edgeBlur <= 0f && !keyEnabled && maskSource == null && !adjustImageColor) return source
    val output = source.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val w = output.width
    val h = output.height
    val pixels = IntArray(w * h)
    output.getPixels(pixels, 0, w, 0, 0, w, h)
    val mask = maskSource?.let { if (it.width == w && it.height == h) it else android.graphics.Bitmap.createScaledBitmap(it, w, h, true) }
    val maskPixels = mask?.let { IntArray(w * h).also { values -> it.getPixels(values, 0, w, 0, 0, w, h) } }
    val maskMode = (element.value("Mask Mode") as? ElementValue.Text)?.value ?: "Alpha Mask"
    val keyColor = if (enabled("Auto Detect Color Key")) {
        val samples = intArrayOf(pixels[0], pixels[w - 1], pixels[(h - 1) * w], pixels[h * w - 1])
        android.graphics.Color.rgb(
            samples.map { android.graphics.Color.red(it) }.average().toInt(),
            samples.map { android.graphics.Color.green(it) }.average().toInt(),
            samples.map { android.graphics.Color.blue(it) }.average().toInt()
        )
    } else runCatching { android.graphics.Color.parseColor(text("Color Key", "#FF000000")) }.getOrDefault(android.graphics.Color.BLACK)
    val transparency = number("Transparency Strength", 1f).coerceIn(0f, 1f)
    val keyR = android.graphics.Color.red(keyColor).toFloat()
    val keyG = android.graphics.Color.green(keyColor).toFloat()
    val keyB = android.graphics.Color.blue(keyColor).toFloat()
    val edge = edgeBlur * min(w, h) / 1080f
    if (keyEnabled || edge > 0f || maskPixels != null || adjustImageColor) {
        for (y in 0 until h) for (x in 0 until w) {
            val index = y * w + x
            val pixel = pixels[index]
            var alpha = android.graphics.Color.alpha(pixel).toFloat()
            if (maskPixels != null) {
                val maskPixel = maskPixels[index]
                val maskAlpha = android.graphics.Color.alpha(maskPixel).toFloat() / 255f
                val luminance = (0.2126f * android.graphics.Color.red(maskPixel) + 0.7152f * android.graphics.Color.green(maskPixel) + 0.0722f * android.graphics.Color.blue(maskPixel)) / 255f
                val maskFactor = when (maskMode) {
                    "Luminance Mask" -> luminance
                    "Inverted Mask" -> 1f - maskAlpha
                    "Normal Mask" -> (luminance * maskAlpha).coerceIn(0f, 1f)
                    else -> maskAlpha
                }
                val maskStrength = number("Mask Strength", 1f).coerceIn(0f, 1f)
                alpha *= 1f - (1f - maskFactor) * maskStrength
            }
            if (keyEnabled) {
                val dr = android.graphics.Color.red(pixel) - keyR
                val dg = android.graphics.Color.green(pixel) - keyG
                val db = android.graphics.Color.blue(pixel) - keyB
                val distance = kotlin.math.sqrt(dr * dr + dg * dg + db * db) / 441.673f
                val tolerance = number("Color Key Tolerance", 0.16f).coerceIn(0.001f, 1f)
                alpha *= 1f - (1f - (distance / tolerance).coerceIn(0f, 1f)) * transparency
            }
            if (edge > 0f) {
                val edgeLinear = (min(min(x + 1f, w - x.toFloat()), min(y + 1f, h - y.toFloat())) / edge).coerceIn(0f, 1f)
                val edgeFactor = if (blurredBorder) edgeLinear * edgeLinear * (3f - 2f * edgeLinear) else edgeLinear
                alpha *= edgeFactor
            }
            if (adjustImageColor) {
                var red = android.graphics.Color.red(pixel).toFloat() / 255f
                var green = android.graphics.Color.green(pixel).toFloat() / 255f
                var blue = android.graphics.Color.blue(pixel).toFloat() / 255f
                val pivot = 0.5f
                red = ((red - pivot) * contrast + pivot) * brightness * (1f + warm * 0.10f)
                green = ((green - pivot) * contrast + pivot) * brightness * (1f + warm * 0.025f)
                blue = ((blue - pivot) * contrast + pivot) * brightness * (1f - warm * 0.10f)
                red = Math.pow(red.coerceAtLeast(0f).toDouble(), 1.0 / gamma.toDouble()).toFloat().coerceIn(0f, 1f)
                green = Math.pow(green.coerceAtLeast(0f).toDouble(), 1.0 / gamma.toDouble()).toFloat().coerceIn(0f, 1f)
                blue = Math.pow(blue.coerceAtLeast(0f).toDouble(), 1.0 / gamma.toDouble()).toFloat().coerceIn(0f, 1f)
                val hsv = FloatArray(3)
                android.graphics.Color.RGBToHSV((red * 255f).toInt(), (green * 255f).toInt(), (blue * 255f).toInt(), hsv)
                hsv[0] = (hsv[0] + hueShift + 360f) % 360f
                val adjusted = android.graphics.Color.HSVToColor(alpha.toInt().coerceIn(0, 255), hsv)
                pixels[index] = adjusted
            } else pixels[index] = (pixel and 0x00FFFFFF) or (alpha.toInt().coerceIn(0, 255) shl 24)
        }
    }
    output.setPixels(pixels, 0, w, 0, 0, w, h)
    if (mask != null && mask !== maskSource) mask.recycle()
    if (blurRadius <= 0f) return output
    val scale = (1f / (1f + blurRadius / blurDivider)).coerceIn(0.08f, 1f)
    val smallW = (w * scale).toInt().coerceAtLeast(1)
    val smallH = (h * scale).toInt().coerceAtLeast(1)
    val reduced = android.graphics.Bitmap.createScaledBitmap(output, smallW, smallH, true)
    val blurred = android.graphics.Bitmap.createScaledBitmap(reduced, w, h, true)
    if (reduced !== output) reduced.recycle()
    output.recycle()
    return blurred
}

@OptIn(ExperimentalTextApi::class)
private fun poppinsVariableFont(weight: Int) = Font(
    com.vooc.visualizer.R.font.poppins_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

private val Poppins = FontFamily(
    poppinsVariableFont(100),
    poppinsVariableFont(200),
    poppinsVariableFont(300),
    poppinsVariableFont(400),
    poppinsVariableFont(500),
    poppinsVariableFont(600),
    poppinsVariableFont(700),
    poppinsVariableFont(800),
    poppinsVariableFont(900)
)

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
private fun ProjectBrowserScreen(
    entries: List<ProjectEntry>,
    currentId: String?,
    onCreate: () -> Unit,
    onOpen: (ProjectEntry) -> Unit,
    onDuplicate: (ProjectEntry) -> Unit,
    onRename: (ProjectEntry, String) -> Unit,
    onDelete: (ProjectEntry) -> Unit,
    onToggleAudio: () -> Unit,
    showAudio: Boolean,
    modifier: Modifier = Modifier
) {
    var renameTarget by remember { mutableStateOf<ProjectEntry?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ProjectEntry?>(null) }
    Column(modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YOUR PROJECTS", color = Lime, style = MaterialTheme.typography.titleLarge, letterSpacing = 1.2.sp)
                Text("${entries.size} SAVED PROJECTS", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onToggleAudio) {
                Icon(Icons.Outlined.LibraryMusic, if (showAudio) "Hide audio player" else "Show audio player", tint = if (showAudio) Lime else Muted)
            }
            Button(onClick = onCreate, shape = RectangleShape, colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("NEW")
            }
        }
        Spacer(Modifier.height(12.dp))
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Collections, null, tint = Muted, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("NO PROJECTS YET", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Create a project to start designing.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onCreate, shape = RectangleShape) { Text("CREATE PROJECT", color = Lime) }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items(entries, key = { it.id }) { entry ->
                    Column(
                        Modifier.fillMaxWidth().background(if (entry.id == currentId) Color(0xFF20291A) else Panel)
                            .border(if (entry.id == currentId) 1.dp else 0.dp, if (entry.id == currentId) Lime else Panel)
                            .clickable { onOpen(entry) }.padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(width = 76.dp, height = 54.dp).background(Color(0xFF090B08))
                                .border(1.dp, Color(0xFF3A4430)), contentAlignment = Alignment.Center) {
                                Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                                    val mid = size.height * 0.5f
                                    val barWidth = size.width / 18f
                                    for (i in 0 until 16) {
                                        val h = size.height * (0.18f + ((i * 37) % 70) / 100f)
                                        drawLine(Lime, Offset(i * barWidth + barWidth / 2f, mid - h / 2f),
                                            Offset(i * barWidth + barWidth / 2f, mid + h / 2f), barWidth * 0.55f)
                                    }
                                }
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.name.uppercase(), color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text("${entry.width} × ${entry.height}  •  ${entry.fps} FPS", color = Muted, style = MaterialTheme.typography.labelSmall)
                                Text(entry.aspectRatioLabel, color = Lime, style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { onOpen(entry) }) { Text("OPEN", color = Lime) }
                        }
                        Spacer(Modifier.height(5.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { renameTarget = entry; renameValue = entry.name }) { Text("RENAME", color = Muted) }
                            TextButton(onClick = { onDuplicate(entry) }) { Text("DUPLICATE", color = Muted) }
                            TextButton(onClick = { deleteTarget = entry }) { Text("DELETE", color = Color(0xFFFF7777)) }
                        }
                    }
                }
            }
        }
    }
    renameTarget?.let { target ->
        AlertDialog(onDismissRequest = { renameTarget = null }, containerColor = Panel,
            title = { Text("RENAME PROJECT", color = Color.White) },
            text = { CompactTextInput("PROJECT NAME", renameValue) { renameValue = it.take(80) } },
            confirmButton = { TextButton(onClick = { onRename(target, renameValue); renameTarget = null }) { Text("SAVE", color = Lime) } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("CANCEL", color = Muted) } }
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, containerColor = Panel,
            title = { Text("DELETE PROJECT?", color = Color.White) },
            text = { Text("This permanently deletes “${target.name}”.", color = Muted) },
            confirmButton = { TextButton(onClick = { onDelete(target); deleteTarget = null }) { Text("DELETE", color = Color(0xFFFF7777)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("CANCEL", color = Muted) } }
        )
    }
}

@Composable
private fun VoocTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Lime, onPrimary = Ink, background = Ink,
            surface = Panel, onSurface = Color(0xFFE8EBDD), secondary = Color(0xFF9DAA85)),
        typography = Typography().let { base ->
            base.copy(
                displayLarge = base.displayLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.Bold),
                displayMedium = base.displayMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.Bold),
                displaySmall = base.displaySmall.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
                headlineLarge = base.headlineLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.Bold),
                headlineMedium = base.headlineMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
                headlineSmall = base.headlineSmall.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold),
                titleLarge = base.titleLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 22.sp),
                titleMedium = base.titleMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp),
                titleSmall = base.titleSmall.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
                bodyLarge = base.bodyLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 18.sp),
                bodyMedium = base.bodyMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp),
                bodySmall = base.bodySmall.copy(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp),
                labelLarge = base.labelLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
                labelMedium = base.labelMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
                labelSmall = base.labelSmall.copy(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 12.sp)
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
    var providerFrames by remember { mutableStateOf<List<AudioProviderFrame>>(emptyList()) }
    val providerElements = remember(project.document.elements) {
        project.document.elements.filter { it.type == StudioElementType.AUDIO_PROVIDER && it.visible }.take(32)
            .ifEmpty { listOf(ElementPropertyCatalog.create(StudioElementType.AUDIO_PROVIDER, "audio-provider-default")) }
    }
    val providerConfigs = remember(providerElements) { providerElements.map { it.toAudioProviderConfig() } }
    val providerProcessors = remember(providerElements.map { it.id }) {
        providerElements.associate { it.id to AudioProviderProcessor() }
    }
    val providerConfig = providerConfigs.first()
    val needsStereoReactivity = providerConfigs.any { it.channelMode != com.vooc.visualizer.analyzer.ProviderChannelMode.MONO_MIX } ||
        project.document.elements.any { element ->
        val source = (element.properties["Audio Reactivity Source"] as? ElementValue.Text)?.value
        (element.properties["Audio Reactivity Enabled"] as? ElementValue.Bool)?.value == true &&
            (source == "Left Channel" || source == "Right Channel")
    } || project.document.elements.any { element ->
        element.type == StudioElementType.BARS_SEGMENTS &&
            (element.value("Audio Channel") as? ElementValue.Text)?.value in setOf("Left", "Right", "Side")
    }
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
    var pendingElementFontProperty by remember { mutableStateOf<String?>(null) }
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
    val elementFontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val property = pendingElementFontProperty
        val element = project.document.elements.firstOrNull { it.id == selectedElementId }
        if (uri != null && property != null && element != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val updated = element.withValue(property, ElementValue.Text(uri.toString()))
            val next = project.copy(document = project.document.copy(elements = project.document.elements.map { if (it.id == updated.id) updated else it }))
            project = next
            onSave(next)
        }
        pendingElementFontProperty = null
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
    LaunchedEffect(player, analyzer, providerConfigs, providerProcessors, needsStereoReactivity) {
        providerProcessors.values.forEach { it.reset() }
        val baseProviderConfig = providerConfig.applyTo(analyzer.config)
        val providerAnalyzerConfig = if (needsStereoReactivity) baseProviderConfig.copy(channelMode = ChannelMode.STEREO_SPLIT) else baseProviderConfig
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
                    providerProcessors.values.forEach { it.reset() }
                }
                val analyzed = analyzer.analyzeLive(currentPosition / 1000.0)
                frame = analyzed
                val processedProviders = providerElements.mapIndexed { index, element ->
                    providerProcessors.getValue(element.id).process(analyzed, providerConfigs[index])
                }
                providerFrames = processedProviders
                providerFrame = processedProviders.firstOrNull()
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
                PreviewPane(project, frame, providerFrame, providerFrames, playing, position, Modifier.fillMaxWidth().weight(0.40f).padding(horizontal = 10.dp, vertical = 6.dp))
                HorizontalDivider(color = Color(0xFF343A2B), thickness = 1.dp)
                ExportScreen(project, onProjectChange = { project = it; onSave(it) }, Modifier.weight(0.60f))
            }
            "TEMPLATES" -> Column(Modifier.fillMaxSize().padding(padding)) {
                PreviewPane(project, frame, providerFrame, providerFrames, playing, position, Modifier.fillMaxWidth().weight(0.40f).padding(horizontal = 10.dp, vertical = 6.dp))
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
                PreviewPane(project, frame, providerFrame, providerFrames, playing, position, Modifier.fillMaxWidth().weight(0.40f).padding(horizontal = 10.dp, vertical = 6.dp))
                HorizontalDivider(color = Color(0xFF343A2B), thickness = 1.dp)
                AnalyzerSettingsScreen(analyzerConfig, onConfigChange = { next ->
                    analyzerConfig = next
                    analyzer.updateConfig(next)
                }, Modifier.weight(0.60f))
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                PreviewPane(project, frame, providerFrame, providerFrames, playing, position, Modifier.fillMaxWidth().weight(0.40f).padding(horizontal = 10.dp, vertical = 6.dp))
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
                                LayerAddMenuCatalog.spectrumStyles.forEach { kind ->
                                    DropdownMenuItem(text = { Text(kind.title.uppercase()) }, onClick = {
                                        val id = "spectrum-${kind.name.lowercase()}-${System.currentTimeMillis()}"
                                        val created = LegacyLayerAdapter.createSpectrumElement(kind, id)
                                        val next = project.copy(document = project.document.copy(elements = project.document.elements + created))
                                        project = next
                                        selectedElementId = id
                                        activeSelection = "ELEMENT"
                                        onSave(next)
                                        showLayerMenu = false
                                    })
                                }
                                LayerAddMenuCatalog.elementTypes.forEach { type ->
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
                        onPickFont = { property ->
                            pendingElementFontProperty = property
                            elementFontPicker.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-opentype", "*/*"))
                        },
                        onChange = { updated ->
                            val next = project.copy(document = project.document.copy(
                                elements = project.document.elements.replacePreservingOrder(updated)
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
                        ThinSlider(
                            label = "TIMELINE PLAYHEAD",
                            value = position.coerceIn(0L, timelineLength).toFloat(),
                            valueRange = 0f..timelineLength.toFloat(),
                            defaultValue = 0f,
                            onValueChange = { player.seekTo(it.toLong()) }
                        )
                        OptionDropdown("ANIMATED PROPERTY", timelineProperty,
                            listOf("X", "Y", "SCALE", "ROTATION", "OPACITY")) { timelineProperty = it }
                        val currentValue = when (timelineProperty) {
                            "X" -> selected!!.x
                            "Y" -> selected!!.y
                            "SCALE" -> selected!!.scale
                            "ROTATION" -> selected!!.rotation
                            else -> selected!!.opacity
                        }
                        Button(
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



private fun evaluateAnimatedLayer(project: StudioProject, layer: VisualLayer, timeMs: Long): VisualLayer {
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
private fun PreviewPane(project: StudioProject, frame: AnalysisFrame?, providerFrame: AudioProviderFrame?, providerFrames: List<AudioProviderFrame>, playing: Boolean, timeMs: Long, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(project.name.uppercase(), color = Color.White, style = MaterialTheme.typography.labelLarge,
                letterSpacing = 1.1.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${project.width} × ${project.height}  •  ${project.fps} FPS", color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        VisualizerPreview(modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, Color(0xFF343A2B)),
            layers = project.layers.filter { it.enabled }.map { evaluateAnimatedLayer(project, it, timeMs) }, elements = project.document.elements, frame = frame, providerFrame = providerFrame, providerFrames = providerFrames, playing = playing)
    }
}

@Composable
private fun TemplateLibraryScreen(
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
private fun ElementStudioEditor(
    elements: List<ElementDocument>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onPickImage: (String) -> Unit,
    onPickFont: (String) -> Unit,
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
                                LayerSlider(spec.name.uppercase(), current, minimum..maximum, defaultValue = reset) { next ->
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
                                        Text(
                                            if (current.isBlank()) "NO IMAGE SELECTED" else "IMAGE IMPORTED",
                                            color = if (current.isBlank()) Muted else Lime,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(onClick = { onPickImage(spec.name) }) {
                                                Text(if (current.isBlank()) "IMPORT IMAGE" else "REPLACE IMAGE", color = Lime)
                                            }
                                            if (current.isNotBlank()) {
                                                TextButton(onClick = { onChange(selected.withValue(spec.name, ElementValue.Text(""))) }) {
                                                    Text("REMOVE", color = Muted)
                                                }
                                            }
                                        }
                                    }
                                } else if (spec.name == "Font File") {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(
                                            if (current.isBlank()) "NO FONT FILE SELECTED" else "CUSTOM FONT IMPORTED",
                                            color = if (current.isBlank()) Muted else Lime,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(onClick = { onPickFont(spec.name) }) {
                                                Text(if (current.isBlank()) "IMPORT FONT" else "REPLACE FONT", color = Lime)
                                            }
                                            if (current.isNotBlank()) {
                                                TextButton(onClick = { onChange(selected.withValue(spec.name, ElementValue.Text(""))) }) {
                                                    Text("REMOVE", color = Muted)
                                                }
                                            }
                                        }
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
                                LayerSlider("${spec.name.uppercase()} X", vector.x.toFloat(), minimum..maximum, defaultValue = (spec.defaultValue as? ElementValue.Vector2)?.x?.toFloat() ?: 0f) { x ->
                                    onChange(selected.withValue(spec.name, ElementValue.Vector2(x.toDouble(), vector.y)))
                                }
                                LayerSlider("${spec.name.uppercase()} Y", vector.y.toFloat(), minimum..maximum, defaultValue = (spec.defaultValue as? ElementValue.Vector2)?.y?.toFloat() ?: 0f) { y ->
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
private fun AnalyzerSettingsScreen(
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
                LayerSlider("LOW CUT HZ", band.lowHz, 20f..10000f, defaultValue = defaultBandSpecs.firstOrNull { it.label == band.label }?.lowHz ?: band.lowHz) { value ->
                    val updated = config.bands.bands.map { current ->
                        if (current.label == band.label) current.copy(lowHz = value.coerceAtMost(current.highHz - 1f)) else current
                    }
                    onConfigChange(config.copy(bands = config.bands.copy(bands = updated)))
                }
                LayerSlider("HIGH CUT HZ", band.highHz, 250f..20000f, defaultValue = defaultBandSpecs.firstOrNull { it.label == band.label }?.highHz ?: band.highHz) { value ->
                    val updated = config.bands.bands.map { current ->
                        if (current.label == band.label) current.copy(highHz = value.coerceAtLeast(current.lowHz + 1f)) else current
                    }
                    onConfigChange(config.copy(bands = config.bands.copy(bands = updated)))
                }
                LayerSlider("GAIN", band.gain, 0f..2f, defaultValue = 1f) { value ->
                    val updated = config.bands.bands.map { current -> if (current.label == band.label) current.copy(gain = value) else current }
                    onConfigChange(config.copy(bands = config.bands.copy(bands = updated)))
                }
                LayerSlider("SMOOTHING", band.smoothing * 100f, 0f..99f, defaultValue = 30f) { value ->
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

private fun updateLayer(project: StudioProject, id: Long, transform: (VisualLayer) -> VisualLayer): StudioProject =
    project.copy(layers = project.layers.map { if (it.id == id) transform(it) else it })

@Composable
private fun VisualizerPreview(modifier: Modifier, layers: List<VisualLayer>, elements: List<ElementDocument>, frame: AnalysisFrame?, providerFrame: AudioProviderFrame?, providerFrames: List<AudioProviderFrame>, playing: Boolean) {
    // AnalysisFrame.timeSec is anchored to Media3's current playback position.
    // Using it for motion keeps preview animation synchronized through pause, seek and track changes.
    val playbackSeconds = (frame?.timeSec ?: 0.0).toFloat().coerceAtLeast(0f)
    val context = androidx.compose.ui.platform.LocalContext.current
    val poppinsTypeface = remember(context) {
        androidx.core.content.res.ResourcesCompat.getFont(context, com.vooc.visualizer.R.font.poppins_variable)
    }
    val elementFonts by produceState(
        initialValue = emptyMap<String, android.graphics.Typeface>(),
        key1 = elements.filter { it.type == StudioElementType.TEXT }.map { element ->
            Triple(element.id, element.value("Font Asset"), element.value("Font File"))
        }
    ) {
        value = withContext(Dispatchers.IO) {
            elements.filter { it.type == StudioElementType.TEXT &&
                (it.value("Font Asset") as? ElementValue.Text)?.value == "Imported Font"
            }.mapNotNull { element ->
                val uriText = (element.value("Font File") as? ElementValue.Text)?.value.orEmpty()
                if (uriText.isBlank()) null else runCatching {
                    val cacheFile = java.io.File(context.cacheDir, "vooc-font-" + uriText.hashCode() + ".font")
                    if (!cacheFile.exists() || cacheFile.length() == 0L) {
                        context.contentResolver.openInputStream(Uri.parse(uriText))?.use { input ->
                            cacheFile.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IllegalStateException("Unable to open imported font.")
                    }
                    android.graphics.Typeface.createFromFile(cacheFile)
                }.getOrNull()?.let { element.id to it }
            }.toMap()
        }
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
    val elementOriginalImages by produceState(
        initialValue = emptyMap<String, androidx.compose.ui.graphics.ImageBitmap>(),
        key1 = elements.filter { it.type == StudioElementType.IMAGE || it.type == StudioElementType.BLUR_EFFECT || it.type == StudioElementType.MOTION_BLUR_EFFECT || it.type == StudioElementType.RGB_SPLIT }.map { element -> element.id to element.properties }
    ) {
        value = withContext(Dispatchers.IO) {
            elements.filter { it.type == StudioElementType.IMAGE || it.type == StudioElementType.BLUR_EFFECT || it.type == StudioElementType.MOTION_BLUR_EFFECT || it.type == StudioElementType.RGB_SPLIT }.mapNotNull { element ->
                val uriText = if (element.type == StudioElementType.IMAGE) {
                    (element.value("Import Image") as? ElementValue.Text)?.value.orEmpty()
                        .ifBlank {
                            listOf("Custom Image", "Image Source / File").firstNotNullOfOrNull { key ->
                                (element.value(key) as? ElementValue.Text)?.value?.takeIf { it.isNotBlank() }
                            }.orEmpty()
                        }
                } else (element.value("Target Image") as? ElementValue.Text)?.value.orEmpty()
                if (uriText.isBlank()) null else runCatching {
                    context.contentResolver.openInputStream(Uri.parse(uriText))?.use { stream -> BitmapFactory.decodeStream(stream)?.asImageBitmap() }
                }.getOrNull()?.let { element.id to it }
            }.toMap()
        }
    }
    val elementImages by produceState(
        initialValue = emptyMap<String, androidx.compose.ui.graphics.ImageBitmap>(),
        key1 = elements.map { element -> element.id to element.properties }
    ) {
        value = withContext(Dispatchers.IO) {
            elements.mapNotNull { element ->
                val uriText = listOf("Image File / Source", "Import Image", "Custom Bar Image", "Texture / Image", "Custom Image", "Image Source / File", "Target Image")
                    .firstNotNullOfOrNull { key -> (element.value(key) as? ElementValue.Text)?.value?.takeIf { it.isNotBlank() } }.orEmpty()
                if (uriText.isBlank()) null else {
                    val bitmap = runCatching {
                        context.contentResolver.openInputStream(Uri.parse(uriText))?.use { stream ->
                            val decoded = BitmapFactory.decodeStream(stream) ?: return@use null
                            val maskUri = (element.value("Mask Image") as? ElementValue.Text)?.value.orEmpty()
                            val maskBitmap = if ((element.type == StudioElementType.IMAGE || element.type == StudioElementType.MOTION_BLUR_EFFECT || element.type == StudioElementType.RGB_SPLIT) && maskUri.isNotBlank()) {
                                context.contentResolver.openInputStream(Uri.parse(maskUri))?.use { BitmapFactory.decodeStream(it) }
                            } else null
                            val processed = when (element.type) {
                                StudioElementType.IMAGE -> processImageForPreview(decoded, element, maskBitmap)
                                StudioElementType.BLUR_EFFECT -> processBlurEffectForPreview(decoded, element)
                                StudioElementType.MOTION_BLUR_EFFECT, StudioElementType.RGB_SPLIT -> processImageForPreview(decoded, element, maskBitmap)
                                StudioElementType.BACKGROUND -> BackgroundImageProcessor.process(decoded, element)
                                else -> decoded
                            }
                            val image = processed.asImageBitmap()
                            if (processed !== decoded) decoded.recycle()
                            maskBitmap?.recycle()
                            image
                        }
                    }.getOrNull()
                    bitmap?.let { element.id to it }
                }
            }.toMap()
        }
    }
    val layerSmoothing = remember { mutableMapOf<Long, FloatArray>() }
    val audioReactivityRuntimes = remember { mutableMapOf<String, AudioReactivityRuntime>() }
    val barRuntimes = remember { mutableMapOf<String, SpectrumBarsRuntime>() }
    val barLastUpdateMs = remember { mutableMapOf<String, Long>() }
    Canvas(modifier.background(Color.Black)) {
        clipRect {
        val cx = size.width / 2f
        val cy = size.height / 2f
        // Render dedicated backgrounds before all visual elements.
        elements.filter { it.visible && it.type == StudioElementType.BACKGROUND }.forEach { background ->
            val properties = background.properties
            fun bgNumber(name: String, fallback: Float): Float = when (val value = properties[name]) {
                is ElementValue.Number -> value.value.toFloat()
                is ElementValue.Integer -> value.value.toFloat()
                else -> fallback
            }
            fun bgText(name: String, fallback: String = ""): String =
                (properties[name] as? ElementValue.Text)?.value ?: fallback
            fun bgBool(name: String): Boolean = (properties[name] as? ElementValue.Bool)?.value == true
            val sourceType = bgText("Source Type", "Solid Color")
            val opacity = (bgNumber("Opacity", 100f) / 100f *
                bgNumber("Opacity / Alpha", 1f)).coerceIn(0f, 1f)
            val blend = when (bgText("Blend Mode Content", "Alpha")) {
                "Add" -> androidx.compose.ui.graphics.BlendMode.Plus
                "Screen" -> androidx.compose.ui.graphics.BlendMode.Screen
                "Multiply", "Subtractive" -> androidx.compose.ui.graphics.BlendMode.Multiply
                "Darken" -> androidx.compose.ui.graphics.BlendMode.Darken
                "Lighten" -> androidx.compose.ui.graphics.BlendMode.Lighten
                "Exclusion" -> androidx.compose.ui.graphics.BlendMode.Difference
                "Copy" -> androidx.compose.ui.graphics.BlendMode.Src
                else -> androidx.compose.ui.graphics.BlendMode.SrcOver
            }
            when (sourceType) {
                "Solid Color" -> {
                    val color = runCatching {
                        Color(android.graphics.Color.parseColor(bgText("Color", "#FF000000")))
                    }.getOrDefault(Color.Black).copy(alpha = opacity)
                    drawRect(color, blendMode = blend)
                }
                "Gradient" -> {
                    val start = runCatching { Color(android.graphics.Color.parseColor(bgText("Background Gradient Start", "#FF000000"))) }.getOrDefault(Color.Black).copy(alpha = opacity)
                    val end = runCatching { Color(android.graphics.Color.parseColor(bgText("Background Gradient End", "#FFFFFFFF"))) }.getOrDefault(Color.White).copy(alpha = opacity)
                    val angle = Math.toRadians(bgNumber("Background Gradient Angle", 0f).toDouble())
                    val radius = (kotlin.math.sqrt(size.width * size.width + size.height * size.height) * 0.5f).coerceAtLeast(1f)
                    val dx = kotlin.math.cos(angle).toFloat() * radius
                    val dy = kotlin.math.sin(angle).toFloat() * radius
                    val brush = if (bgText("Background Gradient Type", "Linear") == "Radial") {
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(start, end), center = Offset(cx, cy), radius = radius
                        )
                    } else {
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(start, end),
                            start = Offset(cx - dx, cy - dy),
                            end = Offset(cx + dx, cy + dy)
                        )
                    }
                    drawRect(brush, blendMode = blend)
                }
                "Image" -> {
                    val image = elementImages[background.id]
                    if (image != null) {
                        val imageRatio = image.width.toFloat() / image.height.coerceAtLeast(1)
                        val frameRatio = size.width / size.height.coerceAtLeast(1f)
                        val fit = bgText("Fit Mode (Scale Type)", "Fill (Cover)")
                        val destWidth: Float
                        val destHeight: Float
                        when {
                            fit == "Stretch" -> { destWidth = size.width; destHeight = size.height }
                            fit == "Fit (Contain)" -> {
                                destWidth = if (imageRatio > frameRatio) size.width else size.height * imageRatio
                                destHeight = if (imageRatio > frameRatio) size.width / imageRatio else size.height
                            }
                            else -> {
                                destWidth = if (imageRatio > frameRatio) size.height * imageRatio else size.width
                                destHeight = if (imageRatio > frameRatio) size.height else size.width / imageRatio
                            }
                        }
                        val alignment = bgText("Background Fit Alignment", "Center")
                        val left = when (alignment) {
                            "Left" -> 0f
                            "Right" -> size.width - destWidth
                            else -> (size.width - destWidth) * 0.5f
                        } + bgNumber("Background Image Offset X", 0f) * size.width / 1080f
                        val top = when (alignment) {
                            "Top" -> 0f
                            "Bottom" -> size.height - destHeight
                            else -> (size.height - destHeight) * 0.5f
                        } + bgNumber("Background Image Offset Y", 0f) * size.height / 1080f
                        val dst = androidx.compose.ui.geometry.Rect(left, top, left + destWidth, top + destHeight)
                        if (bgBool("Background Tile")) {
                            drawIntoCanvas { target ->
                                val tileScale = bgNumber("Background Tile Scale", 1f).coerceIn(0.01f, 100f)
                                val shader = android.graphics.BitmapShader(
                                    image.asAndroidBitmap(),
                                    android.graphics.Shader.TileMode.REPEAT,
                                    android.graphics.Shader.TileMode.REPEAT
                                )
                                val matrix = android.graphics.Matrix().apply {
                                    setScale(tileScale, tileScale)
                                    postTranslate(left, top)
                                }
                                shader.setLocalMatrix(matrix)
                                val tilePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                                    this.shader = shader
                                    alpha = (opacity * 255f).toInt().coerceIn(0, 255)
                                    xfermode = android.graphics.PorterDuffXfermode(when (blend) {
                                        androidx.compose.ui.graphics.BlendMode.Plus -> android.graphics.PorterDuff.Mode.ADD
                                        androidx.compose.ui.graphics.BlendMode.Screen -> android.graphics.PorterDuff.Mode.SCREEN
                                        androidx.compose.ui.graphics.BlendMode.Multiply -> android.graphics.PorterDuff.Mode.MULTIPLY
                                        androidx.compose.ui.graphics.BlendMode.Darken -> android.graphics.PorterDuff.Mode.DARKEN
                                        androidx.compose.ui.graphics.BlendMode.Lighten -> android.graphics.PorterDuff.Mode.LIGHTEN
                                        androidx.compose.ui.graphics.BlendMode.Src -> android.graphics.PorterDuff.Mode.SRC
                                        else -> android.graphics.PorterDuff.Mode.SRC_OVER
                                    })
                                }
                                target.nativeCanvas.drawRect(0f, 0f, size.width, size.height, tilePaint)
                            }
                        } else {
                            drawImage(
                                image,
                                dstOffset = androidx.compose.ui.unit.IntOffset(dst.left.toInt(), dst.top.toInt()),
                                dstSize = androidx.compose.ui.unit.IntSize(dst.width.toInt().coerceAtLeast(1), dst.height.toInt().coerceAtLeast(1)),
                                alpha = opacity,
                                blendMode = blend
                            )
                        }
                        val overlay = runCatching { Color(android.graphics.Color.parseColor(bgText("Color Overlay", "#00000000"))) }.getOrDefault(Color.Transparent)
                        if (overlay.alpha > 0f) drawRect(overlay.copy(alpha = overlay.alpha * opacity), blendMode = blend)
                    }
                }
                else -> Unit // Video backgrounds need a video-frame source pipeline.
            }
        }
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
                runtimeSeconds = playbackSeconds,
                trackPositionSeconds = (frame?.timeSec ?: 0.0).toFloat(),
                playing = playing,
                beatPulse = providerFrame?.beatValue ?: frame?.beat?.pulse ?: 0f,
                bass = bassLevel,
                treble = trebleLevel,
                bpm = frame?.beat?.bpm ?: 120f,
                seed = layer.id,
                playingSeconds = playbackSeconds
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
                            val a = i * 2f * PI.toFloat() / n + playbackSeconds * (if (layer.kind == LayerKind.PARTICLES) 1f else 0f)
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
            val overallLevel = frame?.rms ?: 0f
            val waveformLevel = frame?.waveform?.let { wave ->
                if (wave.isEmpty()) overallLevel else kotlin.math.sqrt(
                    wave.sumOf { sample -> sample.toDouble() * sample.toDouble() } / wave.size
                ).toFloat()
            } ?: overallLevel
            val reactivitySources = audioReactivitySources(
                samples = elementSamples,
                overall = overallLevel,
                beat = providerFrame?.beatValue ?: frame?.beat?.pulse ?: 0f,
                peak = frame?.peak ?: overallLevel,
                rms = frame?.rms ?: overallLevel,
                waveform = waveformLevel,
                left = frame?.channel(ChannelSelect.LEFT)?.rms ?: overallLevel,
                right = frame?.channel(ChannelSelect.RIGHT)?.rms ?: overallLevel
            )
            val reactivityValue = audioReactivityRuntimes.getOrPut(element.id) { AudioReactivityRuntime() }
                .update(props, reactivitySources, 1f / 60f)
            val reactivity = audioReactivityTransform(props, reactivityValue)
            val bandSize = (elementSamples.size / 5).coerceAtLeast(1)
            val bass = elementSamples.take(bandSize).average().toFloat().coerceIn(0f, 1f)
            val treble = elementSamples.takeLast(bandSize).average().toFloat().coerceIn(0f, 1f)
            fun motionFor(property: String) = MeasureWhatEngine.evaluate(
                mode = textValue(property, "Nothing"),
                runtimeSeconds = playbackSeconds,
                trackPositionSeconds = (frame?.timeSec ?: 0.0).toFloat(),
                playing = playing,
                beatPulse = providerFrame?.beatValue ?: frame?.beat?.pulse ?: 0f,
                bass = bass, treble = treble, bpm = frame?.beat?.bpm ?: 120f,
                seed = element.id.hashCode().toLong(), playingSeconds = playbackSeconds
            )
            val posMotion = motionFor("Measure Pos (Measure What)")
            val scaleMotion = motionFor("Measure Scale (Measure What)")
            val rotMotion = motionFor("Measure Rot (Measure What)")
            val center = Offset(
                cx + ((position.x.toFloat() + reactivity.xOffset) / 200f + posMotion.offsetX) * size.width,
                cy + ((position.y.toFloat() + reactivity.yOffset) / 200f + posMotion.offsetY) * size.height
            )
            val scale = (number("Scale", 100f) / 100f * scaleMotion.scale * reactivity.scaleMultiplier).coerceIn(0f, 10f)
            val imageAnimationMode = textValue("Measure Animation Speed (Measure What)", "Nothing")
            val imageAnimationMotion = if (element.type == StudioElementType.IMAGE) motionFor("Measure Animation Speed (Measure What)") else null
            val imageAnimationResponse = imageAnimationMotion?.let { motion ->
                if (imageAnimationMode == "Nothing") 0f else maxOf(
                    kotlin.math.abs(motion.scale - 1f) / 0.32f,
                    kotlin.math.abs(motion.rotation) / 8f,
                    kotlin.math.abs(motion.offsetX) / 0.05f,
                    kotlin.math.abs(motion.offsetY) / 0.05f
                ).coerceIn(0f, 1f)
            } ?: 0f
            val imageAnimationRate = number("Measure Animation Speed", 0f) *
                (number("Measure Animation Speed A (Speed)", 1f) +
                    imageAnimationResponse * number("Measure Animation Speed B (Beat Amount)", 0f))
            val imageAnimationRotation = if (element.type == StudioElementType.IMAGE) playbackSeconds * imageAnimationRate * 360f else 0f
            val rotation = number("Rotation", 0f) + posMotion.rotation + scaleMotion.rotation + rotMotion.rotation + reactivity.rotationOffset + imageAnimationRotation
            val opacityMode = textValue("Measure Opacity (Measure What)", "Nothing")
            val opacityMotion = motionFor("Measure Opacity (Measure What)")
            val measuredOpacity = if (opacityMode == "Nothing") 1f else maxOf(
                kotlin.math.abs(opacityMotion.scale - 1f) / 0.32f,
                kotlin.math.abs(opacityMotion.rotation) / 8f,
                kotlin.math.abs(opacityMotion.offsetX) / 0.05f,
                kotlin.math.abs(opacityMotion.offsetY) / 0.05f
            ).coerceIn(0f, 1f)
            val opacity = (number("Opacity", 100f) / 100f * measuredOpacity * reactivity.opacityMultiplier).coerceIn(0f, 1f)
            val colorText = textValue("Color", "#FFFFFFFF")
            val baseColor = runCatching { android.graphics.Color.parseColor(colorText) }.getOrDefault(android.graphics.Color.WHITE)
            val elementColor = Color(audioReactivityColor(baseColor, reactivity.colorMultiplier)).copy(alpha = opacity)
            val label = textValue("Text", element.tag).take(120)
            rotate(rotation, center) {
                when (element.type) {
                    StudioElementType.TEXT -> drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        val caseMode = textValue("Case", "Original")
                        val rawText = textValue("Text", label).take(2000)
                        val displayText = when (caseMode) {
                            "Uppercase" -> rawText.uppercase()
                            "Lowercase" -> rawText.lowercase()
                            "Capitalize" -> rawText.split(" ").joinToString(" ") { word ->
                                word.replaceFirstChar { char -> char.uppercase() }
                            }
                            else -> rawText
                        }
                        val isRtl = when (textValue("Text Direction", "Auto")) {
                            "Left To Right" -> false
                            "Right To Left" -> true
                            else -> android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(displayText, 0, displayText.length)
                        }
                        val fontStyle = textValue("Font Style", "Bold")
                        val typefaceStyle = when (fontStyle) {
                            "Regular" -> android.graphics.Typeface.NORMAL
                            "Medium", "SemiBold", "Bold" -> android.graphics.Typeface.BOLD
                            "Italic" -> android.graphics.Typeface.ITALIC
                            "Bold Italic" -> android.graphics.Typeface.BOLD_ITALIC
                            else -> android.graphics.Typeface.BOLD
                        }
                        val fontFamily = textValue("Font Family", "Poppins").ifBlank { "Poppins" }
                        val fallbackFamily = when (textValue("Font Fallback", "Sans Serif")) { "Serif" -> "serif"; "Monospace" -> "monospace"; "Cursive" -> "cursive"; else -> "sans-serif" }
                        val importedTypeface = if (textValue("Font Asset", "System Font") == "Imported Font") elementFonts[element.id] else null
                        val baseTypeface = importedTypeface?.let { android.graphics.Typeface.create(it, typefaceStyle) }
                            ?: if (fontFamily.equals("Poppins", ignoreCase = true)) {
                                poppinsTypeface?.let { android.graphics.Typeface.create(it, typefaceStyle) }
                                    ?: android.graphics.Typeface.create(fallbackFamily, typefaceStyle)
                            } else {
                                android.graphics.Typeface.create(fontFamily, typefaceStyle)
                            }
                        val measureFontMode = textValue("Measure Font Size (Measure What)", "Nothing")
                        val measureFontMotion = motionFor("Measure Font Size (Measure What)")
                        val measureFontResponse = if (measureFontMode == "Nothing" || measureFontMode == "None") 0f else maxOf(
                            kotlin.math.abs(measureFontMotion.scale - 1f) / 0.32f,
                            kotlin.math.abs(measureFontMotion.rotation) / 8f,
                            kotlin.math.abs(measureFontMotion.offsetX) / 0.05f,
                            kotlin.math.abs(measureFontMotion.offsetY) / 0.05f
                        ).coerceIn(0f, 1f)
                        val resolvedTextSize = ((number("Font Size", 48f) +
                            number("Measure Font Size", 0f) * measureFontResponse) * scale).coerceAtLeast(6f).sp.toPx()
                        val shadowEnabled = props["Shadow Enabled"] == ElementValue.Bool(true)
                        val shadowColor = runCatching {
                            android.graphics.Color.parseColor(textValue("Shadow Color", "#99000000"))
                        }.getOrDefault(android.graphics.Color.BLACK)
                        val shadowBlur = (number("Shadow Blur", 4f) + number("Shadow Spread", 0f)).dp.toPx().coerceAtLeast(0f)
                        val shadowAngle = Math.toRadians(number("Shadow Angle", 45f).toDouble())
                        val shadowDistance = number("Shadow Distance", 3f).dp.toPx()
                        val shadowX = number("Shadow Offset X", 2f).dp.toPx() + kotlin.math.cos(shadowAngle).toFloat() * shadowDistance
                        val shadowY = number("Shadow Offset Y", 2f).dp.toPx() + kotlin.math.sin(shadowAngle).toFloat() * shadowDistance
                        val strokeEnabled = props["Stroke Enabled"] == ElementValue.Bool(true)
                        val strokeColor = runCatching {
                            Color(android.graphics.Color.parseColor(textValue("Stroke Color", "#FF000000")))
                        }.getOrDefault(Color.Black).copy(alpha = opacity)
                        val strokeWidth = number("Stroke Thickness", 0f).dp.toPx().coerceAtLeast(0f)
                        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = elementColor.toArgb()
                            textSize = resolvedTextSize
                            textAlign = when (textValue("Alignment", "Center")) {
                                "Left" -> android.graphics.Paint.Align.LEFT
                                "Right" -> android.graphics.Paint.Align.RIGHT
                                "Justified" -> android.graphics.Paint.Align.LEFT
                                else -> android.graphics.Paint.Align.CENTER
                            }
                            typeface = baseTypeface
                            letterSpacing = number("Letter Spacing", 0f) / 100f
                            isUnderlineText = props["Underline"] == ElementValue.Bool(true)
                            if (shadowEnabled) setShadowLayer(shadowBlur, shadowX, shadowY, shadowColor)
                        }
                        val maxTextWidth = number("Max Width", 1000f).dp.toPx().coerceAtLeast(1f)
                        val wrapText = props["Wrap Text"] != ElementValue.Bool(false)
                        val textLines = if (!wrapText) listOf(displayText) else buildList {
                            displayText.split("\n").forEach { paragraph ->
                                var line = ""
                                paragraph.split(Regex("\\s+")).forEach { word ->
                                    val candidate = if (line.isEmpty()) word else "$line $word"
                                    if (line.isNotEmpty() && textPaint.measureText(candidate) > maxTextWidth) {
                                        add(line)
                                        line = word
                                    } else line = candidate
                                }
                                add(line)
                            }
                        }.ifEmpty { listOf("") }
                        if (props["Auto-Scale"] == ElementValue.Bool(true)) {
                            val widest = textLines.maxOfOrNull { textPaint.measureText(it) } ?: 0f
                            if (widest > maxTextWidth) textPaint.textScaleX = maxTextWidth / widest
                        }
                        val maximumLines = number("Maximum Lines", 0f).toInt().coerceAtLeast(0)
                        val visibleLines = (if (maximumLines > 0) textLines.take(maximumLines) else textLines).toMutableList()
                        if (textValue("Overflow Mode", "Visible") == "Ellipsis" && visibleLines.size < textLines.size && visibleLines.isNotEmpty()) visibleLines[visibleLines.lastIndex] = android.text.TextUtils.ellipsize(visibleLines.last(), android.text.TextPaint(textPaint), maxTextWidth, android.text.TextUtils.TruncateAt.END).toString()
                        val lineHeight = if (textValue("Line Height Mode", "Multiplier") == "Fixed" && number("Fixed Line Height", 0f) > 0f) number("Fixed Line Height", 0f).dp.toPx() else textPaint.textSize * number("Line Spacing", 1f).coerceIn(0.5f, 4f)
                        val paragraphSpacing = number("Paragraph Spacing", 0f).dp.toPx()
                        val textBlockHeight = (visibleLines.size - 1) * (lineHeight + paragraphSpacing)
                        val firstBaseline = when (textValue("Vertical Alignment", "Middle")) { "Top" -> center.y - textBlockHeight * 0.5f + textPaint.textSize * 0.5f; "Bottom" -> center.y + textBlockHeight * 0.5f - textPaint.textSize * 0.5f; else -> center.y - textBlockHeight * 0.5f }
                        if (textValue("Overflow Mode", "Visible") == "Scale Down") { val widest = visibleLines.maxOfOrNull { textPaint.measureText(it) } ?: 0f; if (widest > maxTextWidth && widest > 0f) textPaint.textScaleX *= maxTextWidth / widest }
                        val textX = when (textValue("Alignment", "Center")) {
                            "Left" -> center.x - maxTextWidth * 0.5f
                            "Right" -> center.x + maxTextWidth * 0.5f
                            "Justified" -> center.x - maxTextWidth * 0.5f
                            else -> center.x
                        }
                        fun drawTextLine(targetPaint: android.graphics.Paint, line: String, baseline: Float, index: Int) {
                            if (textValue("Alignment", "Center") == "Justified" && index < visibleLines.lastIndex) {
                                val words = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                                if (words.size > 1) {
                                    val naturalWidth = words.sumOf { targetPaint.measureText(it).toDouble() }.toFloat()
                                    val gap = ((maxTextWidth - naturalWidth) / (words.size - 1)).coerceAtLeast(0f)
                                    if (isRtl) {
                                        var x = textX + maxTextWidth
                                        words.asReversed().forEach { word ->
                                            val wordWidth = targetPaint.measureText(word)
                                            x -= wordWidth
                                            native.drawTextRun(word, 0, word.length, 0, word.length, x, baseline, true, targetPaint)
                                            x -= gap
                                        }
                                    } else {
                                        var x = textX
                                        words.forEach { word ->
                                            native.drawTextRun(word, 0, word.length, 0, word.length, x, baseline, false, targetPaint)
                                            x += targetPaint.measureText(word) + gap
                                        }
                                    }
                                    return
                                }
                            }
                            native.drawTextRun(line, 0, line.length, 0, line.length, textX, baseline, isRtl, targetPaint)
                        }
                        if (strokeEnabled && strokeWidth > 0f) {
                            val outlinePaint = android.graphics.Paint(textPaint).apply {
                                color = strokeColor.toArgb()
                                style = android.graphics.Paint.Style.STROKE
                                strokeJoin = when (textValue("Stroke Join", "Round")) { "Miter" -> android.graphics.Paint.Join.MITER; "Bevel" -> android.graphics.Paint.Join.BEVEL; else -> android.graphics.Paint.Join.ROUND }
                                this.strokeWidth = strokeWidth
                                clearShadowLayer()
                            }
                            visibleLines.forEachIndexed { index, line ->
                                drawTextLine(outlinePaint, line, firstBaseline + index * (lineHeight + paragraphSpacing), index)
                            }
                        }
                        if (props["Text Background Enabled"] == ElementValue.Bool(true)) { val bg = runCatching { android.graphics.Color.parseColor(textValue("Text Background Color", "#00000000")) }.getOrDefault(android.graphics.Color.TRANSPARENT); val px = number("Text Background Padding X", 0f).dp.toPx(); val py = number("Text Background Padding Y", 0f).dp.toPx(); val top = firstBaseline - textPaint.textSize * 0.8f - py; val bottom = firstBaseline + (visibleLines.size - 1) * (lineHeight + paragraphSpacing) + textPaint.textSize * 0.25f + py; val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = bg }; native.drawRoundRect(center.x - maxTextWidth * 0.5f - px, top, center.x + maxTextWidth * 0.5f + px, bottom, number("Text Background Corner Radius", 0f).dp.toPx(), number("Text Background Corner Radius", 0f).dp.toPx(), bgPaint) }
                        val gradientStart = runCatching {
                            android.graphics.Color.parseColor(textValue("Text Gradient Start", "#FFFFFFFF"))
                        }.getOrDefault(android.graphics.Color.WHITE)
                        val gradientEnd = runCatching {
                            android.graphics.Color.parseColor(textValue("Text Gradient End", "#FFFFFFFF"))
                        }.getOrDefault(android.graphics.Color.WHITE)
                        if (gradientStart != gradientEnd) {
                            val widest = visibleLines.maxOfOrNull { textPaint.measureText(it) } ?: textPaint.textSize
                            val angle = Math.toRadians(number("Text Gradient Angle", 0f).toDouble())
                            val dx = kotlin.math.cos(angle).toFloat() * widest * 0.5f; val dy = kotlin.math.sin(angle).toFloat() * widest * 0.5f
                            if (textValue("Text Gradient Type", "Linear") == "Radial") textPaint.shader = android.graphics.RadialGradient(center.x, center.y, widest.coerceAtLeast(1f) * 0.5f, gradientStart, gradientEnd, android.graphics.Shader.TileMode.CLAMP) else textPaint.shader = android.graphics.LinearGradient(center.x - dx, center.y - dy, center.x + dx, center.y + dy, intArrayOf(gradientStart, android.graphics.Color.argb((android.graphics.Color.alpha(gradientStart) + (android.graphics.Color.alpha(gradientEnd) - android.graphics.Color.alpha(gradientStart)) * number("Text Gradient Midpoint", 0.5f)).toInt(), (android.graphics.Color.red(gradientStart) + (android.graphics.Color.red(gradientEnd) - android.graphics.Color.red(gradientStart)) * number("Text Gradient Midpoint", 0.5f)).toInt(), (android.graphics.Color.green(gradientStart) + (android.graphics.Color.green(gradientEnd) - android.graphics.Color.green(gradientStart)) * number("Text Gradient Midpoint", 0.5f)).toInt(), (android.graphics.Color.blue(gradientStart) + (android.graphics.Color.blue(gradientEnd) - android.graphics.Color.blue(gradientStart)) * number("Text Gradient Midpoint", 0.5f)).toInt()), gradientEnd), floatArrayOf(0f, number("Text Gradient Midpoint", 0.5f).coerceIn(0.001f, 0.999f), 1f), android.graphics.Shader.TileMode.CLAMP)
                        }
                        visibleLines.forEachIndexed { index, line ->
                            drawTextLine(textPaint, line, firstBaseline + index * (lineHeight + paragraphSpacing), index)
                        }
                        textPaint.clearShadowLayer()
                        textPaint.shader = null
                    }
                    StudioElementType.BARS_SEGMENTS -> {
                        val providerIndex = number("Audio Provider Index", 0f).toInt()
                            .coerceIn(0, (providerFrames.size - 1).coerceAtLeast(0))
                        val selectedProviderFrame = providerFrames.getOrNull(providerIndex) ?: providerFrame
                        val selectedChannel = textValue("Audio Channel", "Mixed")
                        val frequencyStartHz = number("Frequency Range Start", 20f).coerceAtLeast(0f)
                        val frequencyEndHz = number("Frequency Range End", 18000f).coerceAtLeast(frequencyStartHz + 1f)
                        val customFrequencyRange = frequencyStartHz > 20.01f || frequencyEndHz < 17999.99f
                        val selectedSpectrum = when (selectedChannel) {
                            "Left" -> frame?.channel(ChannelSelect.LEFT)?.spectrum
                            "Right" -> frame?.channel(ChannelSelect.RIGHT)?.spectrum
                            "Mid" -> frame?.channel(ChannelSelect.MID)?.spectrum
                            "Side" -> frame?.channel(ChannelSelect.SIDE)?.spectrum
                            else -> frame?.channel(ChannelSelect.MIX)?.spectrum
                        }
                        val useProviderSmoothing = props["Use Audio Provider Smoothing"] != ElementValue.Bool(false)
                        val sourceSamples: FloatArray = if (selectedChannel == "Mixed" && !customFrequencyRange && useProviderSmoothing) {
                            selectedProviderFrame?.samples ?: frame?.spectrum ?: FloatArray(64)
                        } else selectedSpectrum ?: frame?.spectrum ?: FloatArray(64)
                        val rawSamples = if (customFrequencyRange && frame != null) SpectrumMapper.cropFrequencyRange(
                            sourceSamples, frame!!.sampleRate, frequencyStartHz, frequencyEndHz
                        ) else sourceSamples
                        val count = number("Bar Count", 64f).toInt().coerceIn(1, 512)
                        val input = processBarSpectrum(
                            samples = rawSamples,
                            amplitudeScale = number("Amplitude Scale", 1f) * number("Sensitivity", 1f),
                            gamma = number("Amplitude Gamma", 1f),
                            amplitudeMode = textValue("Amplitude Mode", "Magnitude"),
                            threshold = number("Amplitude Threshold", 0f),
                            offset = number("Amplitude Offset", 0f),
                            mappingCurve = number("Frequency Mapping Curve", 1f),
                            mappingBias = number("Frequency Mapping Bias", 0f),
                            bandOffset = number("Frequency Band Offset", 0f).toInt()
                        )
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
                            logarithmicInput = (selectedChannel != "Mixed" || customFrequencyRange || selectedProviderFrame == null) && textValue("Frequency Scale", "Logarithmic") == "Logarithmic",
                            reactionDelayMs = number("Reaction Delay", 0f),
                            accumulatedDelayMs = maxOf(number("Accumulated Delay", 0f), number("Reaction Accumulated Delay", 0f)),
                            attackSmoothing = number("Attack Smoothing", 0.12f),
                            releaseSmoothing = number("Release Smoothing", number("Smoothing / Damping", 0.72f)),
                            peakHoldEnabled = props["Peak Hold Enabled"] != ElementValue.Bool(false),
                            peakResetThreshold = number("Peak Reset Threshold", 0f),
                            peakFallDelay = number("Peak Fall Delay", 0f)
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
                        val heightScale = number("Height Scale", 1f).coerceIn(0f, 10f)
                        val maxHeightScale = number("Max Height Scale", 1f).coerceIn(0f, 1f)
                        val maxHeight = (number("Max Height", size.height * 0.35f) * scaleY * heightScale * maxHeightScale).coerceIn(1f, size.height * 0.95f)
                        val minHeightScale = number("Min Height Scale", 0f).coerceIn(0f, 1f)
                        val minHeight = maxOf(number("Min Height", 0f), maxHeight * minHeightScale).coerceIn(0f, maxHeight)
                        val design = textValue("Bar Design", "Rounded Rect")
                        val orientation = textValue("Orientation", "Vertical")
                        val alignment = textValue("Alignment", "Bottom")
                        val mirrorMode = textValue("Mirror Mode", if (props["Mirror"] == ElementValue.Bool(true)) "Horizontal" else "Off")
                        val horizontalMirror = orientation == "Mirror" || mirrorMode == "Horizontal" || mirrorMode == "Both" || props["Mirror"] == ElementValue.Bool(true)
                        val verticalMirror = mirrorMode == "Vertical" || mirrorMode == "Both"
                        val centerSymmetry = props["Center Symmetry"] == ElementValue.Bool(true)
                        val outline = props["Outline / Stroke"] == ElementValue.Bool(true)
                        val strokeWidth = number("Stroke Width", 1.5f).dp.toPx().coerceAtLeast(0.5f)
                        val strokeColor = runCatching { Color(audioReactivityColor(android.graphics.Color.parseColor(textValue("Stroke Color", colorText)), reactivity.colorMultiplier)) }.getOrDefault(elementColor)
                        val colorA = runCatching { Color(audioReactivityColor(android.graphics.Color.parseColor(textValue("Bar Color A", "#FF56D8FF")), reactivity.colorMultiplier)) }.getOrDefault(elementColor)
                        val colorB = runCatching { Color(audioReactivityColor(android.graphics.Color.parseColor(textValue("Bar Color B", "#FF9B5CFF")), reactivity.colorMultiplier)) }.getOrDefault(elementColor)
                        val peakColor = runCatching { Color(audioReactivityColor(android.graphics.Color.parseColor(textValue("Peak Indicator Color", textValue("Peak Color", "#FFFFFFFF"))), reactivity.colorMultiplier)) }.getOrDefault(strokeColor)
                        val capColor = runCatching { Color(audioReactivityColor(android.graphics.Color.parseColor(textValue("Cap Color", "#FFFFFFFF")), reactivity.colorMultiplier)) }.getOrDefault(strokeColor)
                        val colorMode = if (props["Gradient Fill"] == ElementValue.Bool(true)) "Gradient" else textValue("Color Mode", "Alternating")
                        val capMode = textValue("Top/Bottom Cap", "None")
                        val capPlacement = textValue("Cap Placement", "Top")
                        val capSize = number("Cap Size", 4f).dp.toPx().coerceAtLeast(1f)
                        val capThickness = number("Cap Thickness", 3f).dp.toPx().coerceAtLeast(0.5f)
                        val customImage = elementImages[element.id]
                        val corner = when (design) {
                            "Capsule / Pill" -> barWidth / 2f
                            "Rounded Rect" -> number("Corner Radius", 4f).dp.toPx().coerceIn(0f, barWidth / 2f)
                            else -> 0f
                        }
                        val dashEffect = when (textValue("Stroke Style", "Solid")) {
                            "Dashed" -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(strokeWidth * 3f, strokeWidth * 2f))
                            "Dotted" -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(strokeWidth, strokeWidth * 1.8f))
                            else -> null
                        }
                        val shapeStyle = if (outline) Stroke(strokeWidth, pathEffect = dashEffect) else androidx.compose.ui.graphics.drawscope.Fill
                        val barOffset = number("Bar Offset", 0f).dp.toPx()
                        val extrusion = props["3D / Extrusion"] == ElementValue.Bool(true)
                        val extrusionDepth = number("Extrusion Depth", 5f).dp.toPx()
                        val glowEnabled = props["Glow / Shadow"] == ElementValue.Bool(true)
                        val glowAlpha = number("Glow Strength", 0.35f).coerceIn(0f, 1f)
                        val barGlowRadius = number("Bar Glow Radius", 8f).dp.toPx().coerceIn(0f, 256.dp.toPx())
                        val barGlowColorStrength = number("Bar Glow Color Strength", 1f).coerceIn(0f, 1f)
                        val barSoftnessRadius = number("Softness", 0f).coerceIn(0f, 20f).dp.toPx()
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
                                    "Pentagon" -> { for (point in 0 until 5) { val angle = (point * 72f - 90f) * PI.toFloat() / 180f; val px = left + width * (0.5f + cos(angle) * 0.5f); val py = top + height * (0.5f + sin(angle) * 0.5f); if (point == 0) moveTo(px, py) else lineTo(px, py) }; close() }
                                    "Octagon" -> { for (point in 0 until 8) { val angle = (point * 45f - 22.5f) * PI.toFloat() / 180f; val px = left + width * (0.5f + cos(angle) * 0.5f); val py = top + height * (0.5f + sin(angle) * 0.5f); if (point == 0) moveTo(px, py) else lineTo(px, py) }; close() }
                                    "Trapezoid" -> { moveTo(left + width * 0.2f, top); lineTo(left + width * 0.8f, top); lineTo(left + width, top + height); lineTo(left, top + height); close() }
                                    "Parallelogram" -> { moveTo(left + width * 0.25f, top); lineTo(left + width, top); lineTo(left + width * 0.75f, top + height); lineTo(left, top + height); close() }
                                    "Lightning" -> { moveTo(left + width * 0.58f, top); lineTo(left + width * 0.18f, top + height * 0.56f); lineTo(left + width * 0.46f, top + height * 0.56f); lineTo(left + width * 0.34f, top + height); lineTo(left + width * 0.84f, top + height * 0.38f); lineTo(left + width * 0.56f, top + height * 0.38f); close() }
                                    "Star" -> { for (point in 0 until 10) { val angle = (point * 36f - 90f) * PI.toFloat() / 180f; val radius = if (point % 2 == 0) 0.5f else 0.22f; val px = left + width * (0.5f + cos(angle) * radius); val py = top + height * (0.5f + sin(angle) * radius); if (point == 0) moveTo(px, py) else lineTo(px, py) }; close() }
                                    "Chevron" -> { moveTo(left, top + height * 0.28f); lineTo(left + width * 0.5f, top); lineTo(left + width, top + height * 0.28f); lineTo(left + width, top + height * 0.72f); lineTo(left + width * 0.5f, top + height); lineTo(left, top + height * 0.72f); close() }
                                    "Arrow" -> { moveTo(left + width * 0.35f, top); lineTo(left + width, top + height * 0.5f); lineTo(left + width * 0.35f, top + height); lineTo(left + width * 0.35f, top + height * 0.68f); lineTo(left, top + height * 0.68f); lineTo(left, top + height * 0.32f); lineTo(left + width * 0.35f, top + height * 0.32f); close() }
                                    "Cross" -> { moveTo(left + width * 0.35f, top); lineTo(left + width * 0.65f, top); lineTo(left + width * 0.65f, top + height * 0.35f); lineTo(left + width, top + height * 0.35f); lineTo(left + width, top + height * 0.65f); lineTo(left + width * 0.65f, top + height * 0.65f); lineTo(left + width * 0.65f, top + height); lineTo(left + width * 0.35f, top + height); lineTo(left + width * 0.35f, top + height * 0.65f); lineTo(left, top + height * 0.65f); lineTo(left, top + height * 0.35f); lineTo(left + width * 0.35f, top + height * 0.35f); close() }
                                    "Wave" -> { moveTo(left, top + height * 0.2f); lineTo(left + width * 0.5f, top); lineTo(left + width, top + height * 0.2f); lineTo(left + width, top + height); lineTo(left + width * 0.5f, top + height * 0.8f); lineTo(left, top + height); close() }
                                    else -> { moveTo(left, top); lineTo(left + width, top); lineTo(left + width, top + height); lineTo(left, top + height); close() }
                                }
                            }
                            if (barSoftnessRadius > 0f) {
                                val softnessBands = listOf(
                                    2f to 0.035f,
                                    1.5f to 0.06f,
                                    1f to 0.10f,
                                    0.5f to 0.16f
                                )
                                softnessBands.forEach { (widthFactor, alphaFactor) ->
                                    val softnessColor = fill.copy(alpha = (opacity * alphaFactor).coerceIn(0f, 1f))
                                    val featherStyle = Stroke(width = (barSoftnessRadius * widthFactor).coerceAtLeast(0.5f))
                                    if (design == "Circle / Dot") {
                                        drawCircle(
                                            color = softnessColor,
                                            radius = (height * 0.5f).coerceAtLeast(1f),
                                            center = Offset(left + width / 2f, top + height / 2f),
                                            style = featherStyle
                                        )
                                    } else {
                                        drawPath(path, softnessColor, style = featherStyle)
                                    }
                                }
                            }
                            if (glowEnabled && barGlowRadius > 0f && barGlowColorStrength > 0f) {
                                drawPath(path, fill.copy(alpha = glowAlpha * barGlowColorStrength * 0.2f),
                                    style = Stroke(width = barGlowRadius * 2f + strokeWidth))
                            }
                            if (extrusion) {
                                val shifted = Path().apply { addPath(path, Offset(extrusionDepth, extrusionDepth)) }
                                drawPath(shifted, fill.copy(alpha = opacity * 0.32f), style = shapeStyle)
                            }
                            val modeBrush = when (colorMode) {
                                "Gradient", "By Amplitude" -> androidx.compose.ui.graphics.SolidColor(fill)
                                "Alternating" -> androidx.compose.ui.graphics.SolidColor(if (index % 2 == 0) colorA else colorB)
                                "Per Bar" -> androidx.compose.ui.graphics.SolidColor(runCatching { Color(android.graphics.Color.parseColor(textValue("Per-Bar Color", "#FFFFFFFF"))) }.getOrDefault(elementColor))
                                else -> androidx.compose.ui.graphics.SolidColor(elementColor)
                            }
                            if (design == "Custom Image" && customImage != null) {
                                drawImage(customImage, dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                                    dstSize = androidx.compose.ui.unit.IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)),
                                    alpha = opacity)
                            } else {
                                when (design) {
                                    "Circle / Dot" -> drawCircle(fill, (height * 0.5f).coerceAtLeast(1f), Offset(left + width / 2f, top + height / 2f))
                                    "Line" -> drawLine(fill, Offset(left + width / 2f, top), Offset(left + width / 2f, top + height), width.coerceAtLeast(1f), cap = StrokeCap.Round)
                                    "Rounded Rect", "Capsule / Pill" -> drawRoundRect(brush = modeBrush, topLeft = rectTop, size = rectSize, cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner))
                                    else -> drawPath(path, modeBrush)
                                }
                            }
                            if (outline) {
                                when (design) {
                                    "Circle / Dot" -> drawCircle(strokeColor.copy(alpha = opacity), width * 0.5f, Offset(left + width / 2f, top + height / 2f), style = shapeStyle)
                                    "Line" -> {
                                        val linePath = Path().apply {
                                            moveTo(left + width / 2f, top)
                                            lineTo(left + width / 2f, top + height)
                                        }
                                        drawPath(linePath, strokeColor.copy(alpha = opacity), style = shapeStyle)
                                    }
                                    "Rounded Rect", "Capsule / Pill" -> drawRoundRect(strokeColor.copy(alpha = opacity), rectTop, rectSize, androidx.compose.ui.geometry.CornerRadius(corner), style = shapeStyle)
                                    else -> drawPath(path, strokeColor.copy(alpha = opacity), style = shapeStyle)
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
                                        "Square" -> drawRect(capColor.copy(alpha = opacity), Offset(cx - capThickness / 2f, y - capSize), androidx.compose.ui.geometry.Size(capThickness, capSize * 2f))
                                        "Diamond" -> {
                                            val capPath = Path().apply { moveTo(cx, y - capSize); lineTo(cx + capThickness / 2f, y); lineTo(cx, y + capSize); lineTo(cx - capThickness / 2f, y); close() }
                                            drawPath(capPath, capColor.copy(alpha = opacity))
                                        }
                                    }
                                }
                                if (capPlacement != "Bottom") capAt(capYTop)
                                if (capPlacement != "Top") capAt(capYBottom)
                            }
                        }
                        val segmentsPerBar = number("Segments Per Bar", 1f).toInt().coerceIn(1, 64)
                        val segmentGap = (gap * 0.55f).coerceAtMost(6.dp.toPx())
                        val radialLayout = orientation == "Radial" || textValue("Shape Path", "Horizontal Line") == "Circle"
                        if (radialLayout) {
                            val innerRadius = (size.minDimension * 0.16f + barOffset).coerceIn(0f, size.minDimension * 0.42f)
                            val radialMax = minOf(maxHeight, size.minDimension * 0.42f)
                            repeat(count) { index ->
                                val layoutRandom = kotlin.math.abs(sin((index + 1f) * 12.9898f) * 43758.5453f) % 1f
                                val widthRandom = number("Bar Width Random", 0f).coerceIn(0f, 1f)
                                val barWidth = (requestedWidth * (1f + (layoutRandom * 2f - 1f) * widthRandom)).coerceIn(0.5f, (slot - gap).coerceAtLeast(0.5f))
                                val sourceIndex = if (horizontalMirror) minOf(index, count - 1 - index) else index
                                var level = levels[sourceIndex].coerceIn(0f, 1f)
                                if (props["Flip Input"] == ElementValue.Bool(true)) level = 1f - level
                                val heightRandom = number("Bar Height Random", 0f).coerceIn(0f, 1f)
                                val barHeight = ((minHeight + level * radialMax) * (1f + (layoutRandom * 2f - 1f) * heightRandom)).coerceIn(0f, radialMax)
                                val angle = index * (360f / count)
                                val radialColorBlendOffset = number("Color Blend Offset", 0f).coerceIn(-1f, 1f)
                                val radialColorBlendPosition = (if (colorMode == "Gradient" && textValue("Gradient Direction", "Vertical") == "Horizontal")
                                    index.toFloat() / (count - 1).coerceAtLeast(1) + radialColorBlendOffset
                                else level + radialColorBlendOffset).coerceIn(0f, 1f)
                                val radialColor = when (colorMode) {
                                    "Alternating" -> if (index % 2 == 0) colorA else colorB
                                    "Per Bar" -> runCatching { Color(android.graphics.Color.parseColor(textValue("Per-Bar Color", "#FFFFFFFF"))) }.getOrDefault(elementColor)
                                    "Gradient", "By Amplitude" -> Color(
                                        colorA.red + (colorB.red - colorA.red) * radialColorBlendPosition,
                                        colorA.green + (colorB.green - colorA.green) * radialColorBlendPosition,
                                        colorA.blue + (colorB.blue - colorA.blue) * radialColorBlendPosition,
                                        opacity
                                    )
                                    else -> elementColor
                                }
                                val radialTop = center.y - innerRadius - barHeight
                                rotate(angle, center) {
                                    val segmentHeight = ((barHeight - segmentGap * (segmentsPerBar - 1)) / segmentsPerBar).coerceAtLeast(0.5f)
                                    repeat(segmentsPerBar) { segment ->
                                        val segmentNumber = segment + 1
                                        val segmentPrefix = if (segmentNumber <= 2) "Segment $segmentNumber " else ""
                                        val fixedHeight = segmentPrefix.isNotEmpty() && props["$segmentPrefix" + "Fixed Height"] == ElementValue.Bool(true)
                                        val segmentMultiplier = if (segmentPrefix.isNotEmpty()) number("$segmentPrefix" + "Bar Height Multiplier", 1f).coerceIn(0f, 10f) else 1f
                                        val renderedSegmentHeight = (if (fixedHeight) radialMax / segmentsPerBar else segmentHeight)
                                            .times(segmentMultiplier).coerceAtLeast(0.5f)
                                        val from = if (segmentPrefix.isNotEmpty()) runCatching { Color(android.graphics.Color.parseColor(textValue("$segmentPrefix" + "Color From", "#FF56D8FF"))) }.getOrDefault(radialColor) else radialColor
                                        val to = if (segmentPrefix.isNotEmpty()) runCatching { Color(android.graphics.Color.parseColor(textValue("$segmentPrefix" + "Color To", "#FF9B5CFF"))) }.getOrDefault(radialColor) else radialColor
                                        val segmentFill = if (segmentPrefix.isNotEmpty()) Color(
                                            from.red + (to.red - from.red) * level,
                                            from.green + (to.green - from.green) * level,
                                            from.blue + (to.blue - from.blue) * level,
                                            opacity
                                        ) else radialColor
                                        val segmentTop = radialTop + segment * (renderedSegmentHeight + segmentGap)
                                        val flipAlternate = segmentPrefix.isNotEmpty() && props["$segmentPrefix" + "Flip Every Other"] == ElementValue.Bool(true) && index % 2 == 1
                                        val mirrorSegment = segmentPrefix.isNotEmpty() && props["$segmentPrefix" + "Mirror"] == ElementValue.Bool(true)
                                        rotate(number("Bar Rotation", 0f) + if (flipAlternate) 180f else 0f, Offset(center.x, center.y - innerRadius)) {
                                            drawBarShape(center.x - barWidth / 2f, segmentTop, barWidth, renderedSegmentHeight, segmentFill, 0f, index)
                                            if (verticalMirror || centerSymmetry || mirrorSegment) {
                                                val mirrorTop = center.y - innerRadius + segment * (renderedSegmentHeight + segmentGap)
                                                drawBarShape(center.x - barWidth / 2f, mirrorTop, barWidth, renderedSegmentHeight, segmentFill, 0f, index)
                                            }
                                        }
                                    }
                                    val peakStyle = textValue("Peak Indicator Style", "Line")
                                    if (peakEnabled && peakStyle != "None") {
                                        val peakY = center.y - innerRadius - peaks[sourceIndex].coerceIn(0f, 1f) * radialMax
                                        when (peakStyle) {
                                            "Dot" -> drawCircle(peakColor.copy(alpha = opacity), (peakWidth * 1.5f).coerceAtLeast(1f), Offset(center.x, peakY))
                                            "Bar" -> drawRect(peakColor.copy(alpha = opacity), Offset(center.x - barWidth / 2f, peakY - peakWidth), androidx.compose.ui.geometry.Size(barWidth, peakWidth * 2f))
                                            "Line" -> drawLine(peakColor.copy(alpha = opacity), Offset(center.x - barWidth / 2f - peakWidth, peakY), Offset(center.x + barWidth / 2f + peakWidth, peakY), peakWidth)
                                        }
                                    }
                                }
                            }
                        } else {
                            rotate(if (orientation == "Horizontal" || textValue("Shape Path", "Horizontal Line") == "Vertical Line") 90f else 0f, center) {
                                val customPathMode = textValue("Shape Path", "Horizontal Line") == "Custom Path"
                                val customPoints = if (customPathMode) textValue("Custom Path Points", "0,0.5;0.25,0.25;0.5,0.75;0.75,0.25;1,0.5")
                                    .split(";").mapNotNull { token ->
                                        val parts = token.trim().split(",")
                                        if (parts.size != 2) null else runCatching {
                                            parts[0].trim().toFloat().coerceIn(0f, 1f) to parts[1].trim().toFloat().coerceIn(0f, 1f)
                                        }.getOrNull()
                                    }.take(128) else emptyList()
                                fun pathPoint(t: Float): Pair<Float, Float> {
                                    if (customPoints.size < 2) return (center.x - totalWidth / 2f + t * totalWidth) to (center.y + size.height * 0.25f)
                                    val scaled = t.coerceIn(0f, 1f) * (customPoints.size - 1)
                                    val segmentIndex = scaled.toInt().coerceAtMost(customPoints.size - 2)
                                    val fraction = scaled - segmentIndex
                                    val a = customPoints[segmentIndex]
                                    val b = customPoints[segmentIndex + 1]
                                    return (center.x - totalWidth / 2f + (a.first + (b.first - a.first) * fraction) * totalWidth) to
                                        (center.y - size.height * 0.4f + (a.second + (b.second - a.second) * fraction) * size.height * 0.8f)
                                }
                                repeat(count) { index ->
                            val sourceIndex = if (horizontalMirror) minOf(index, count - 1 - index) else index
                            var level = levels[sourceIndex].coerceIn(0f, 1f)
                            if (props["Flip Input"] == ElementValue.Bool(true)) level = 1f - level
                            val layoutRandom = kotlin.math.abs(sin((index + 1f) * 12.9898f) * 43758.5453f) % 1f
                            val heightRandom = number("Bar Height Random", 0f).coerceIn(0f, 1f)
                            val barHeight = ((minHeight + level * maxHeight) * (1f + (layoutRandom * 2f - 1f) * heightRandom)).coerceIn(0f, size.height * 0.95f)
                            val widthRandom = number("Bar Width Random", 0f).coerceIn(0f, 1f)
                            val spacingRandom = number("Bar Spacing Random", 0f).coerceIn(0f, 1f)
                            val barWidth = (requestedWidth * (1f + (layoutRandom * 2f - 1f) * widthRandom)).coerceIn(0.5f, (slot - gap).coerceAtLeast(0.5f))
                            val spacingJitter = (layoutRandom * 2f - 1f) * slot * 0.45f * spacingRandom
                            val verticalSymmetry = verticalMirror || centerSymmetry
                            val drawHeight = if (verticalSymmetry) barHeight / 2f else barHeight
                            val customPathMode = textValue("Shape Path", "Horizontal Line") == "Custom Path"
                            val customPoints = if (customPathMode) textValue("Custom Path Points", "0,0.5;0.25,0.25;0.5,0.75;0.75,0.25;1,0.5")
                                .split(";").mapNotNull { token ->
                                    val parts = token.trim().split(",")
                                    if (parts.size != 2) null else runCatching {
                                        parts[0].trim().toFloat().coerceIn(0f, 1f) to parts[1].trim().toFloat().coerceIn(0f, 1f)
                                    }.getOrNull()
                                }.take(128) else emptyList()
                            fun pathPoint(t: Float): Pair<Float, Float> {
                                if (customPoints.size < 2) return (center.x - totalWidth / 2f + t * totalWidth) to (center.y + size.height * 0.25f)
                                val scaled = t.coerceIn(0f, 1f) * (customPoints.size - 1)
                                val segmentIndex = scaled.toInt().coerceAtMost(customPoints.size - 2)
                                val fraction = scaled - segmentIndex
                                val a = customPoints[segmentIndex]
                                val b = customPoints[segmentIndex + 1]
                                return (center.x - totalWidth / 2f + (a.first + (b.first - a.first) * fraction) * totalWidth) to
                                    (center.y - size.height * 0.4f + (a.second + (b.second - a.second) * fraction) * size.height * 0.8f)
                            }
                            val point = if (customPathMode) pathPoint(if (count <= 1) 0f else index.toFloat() / (count - 1)) else 0f to 0f
                            val beforePoint = if (customPathMode) pathPoint((index.toFloat() / count - 0.01f).coerceAtLeast(0f)) else point
                            val afterPoint = if (customPathMode) pathPoint((index.toFloat() / (count - 1).coerceAtLeast(1) + 0.01f).coerceAtMost(1f)) else point
                            val customAngle = if (customPathMode) Math.toDegrees(kotlin.math.atan2((afterPoint.second - beforePoint.second).toDouble(), (afterPoint.first - beforePoint.first).toDouble())).toFloat() else 0f
                            val x = if (customPathMode) point.first - barWidth / 2f + barOffset else center.x - totalWidth / 2f + index * slot + (slot - barWidth) / 2f + spacingJitter + barOffset
                            val alternateDirection = props["Alternate Bar Direction"] == ElementValue.Bool(true) &&
                                !customPathMode && !verticalSymmetry
                            val alternateFromTop = alternateDirection && index % 2 == 1
                            val baseline = if (customPathMode) point.second else if (alternateDirection) {
                                if (alternateFromTop) center.y - size.height * 0.38f else center.y + size.height * 0.38f
                            } else when (alignment) {
                                "Top" -> center.y - size.height * 0.38f
                                "Center" -> center.y
                                "Stretch" -> center.y + size.height * 0.44f
                                else -> center.y + size.height * 0.38f
                            }
                            val top = if (verticalSymmetry) baseline - drawHeight else if (alternateFromTop) baseline
                                else if (alternateDirection) baseline - barHeight
                                else if (alignment == "Center") baseline - barHeight / 2f
                                else if (alignment == "Top") baseline else baseline - barHeight
                            val height = if (alignment == "Stretch") (drawHeight * 1.25f).coerceAtMost(size.height * 0.95f) else drawHeight
                            val colorBlendOffset = number("Color Blend Offset", 0f).coerceIn(-1f, 1f)
                            val blendPosition = (if (colorMode == "Gradient" && textValue("Gradient Direction", "Vertical") == "Horizontal")
                                index.toFloat() / (count - 1).coerceAtLeast(1) + colorBlendOffset
                            else level + colorBlendOffset).coerceIn(0f, 1f)
                            val colorForBar = when (colorMode) {
                                "Alternating" -> if (index % 2 == 0) colorA else colorB
                                "Per Bar" -> runCatching { Color(android.graphics.Color.parseColor(textValue("Per-Bar Color", "#FFFFFFFF"))) }.getOrDefault(elementColor)
                                "Gradient", "By Amplitude" -> Color(
                                    colorA.red + (colorB.red - colorA.red) * blendPosition,
                                    colorA.green + (colorB.green - colorA.green) * blendPosition,
                                    colorA.blue + (colorB.blue - colorA.blue) * blendPosition,
                                    opacity
                                )
                                else -> elementColor
                            }
                            rotate(barRotation + customAngle, Offset(x + barWidth / 2f, baseline)) {
                                val segmentHeight = ((height - segmentGap * (segmentsPerBar - 1)) / segmentsPerBar).coerceAtLeast(0.5f)
                                repeat(segmentsPerBar) { segment ->
                                    val segmentNumber = segment + 1
                                    val segmentPrefix = if (segmentNumber <= 2) "Segment $segmentNumber " else ""
                                    val fixedHeight = segmentPrefix.isNotEmpty() && props["$segmentPrefix" + "Fixed Height"] == ElementValue.Bool(true)
                                    val segmentMultiplier = if (segmentPrefix.isNotEmpty()) number("$segmentPrefix" + "Bar Height Multiplier", 1f).coerceIn(0f, 10f) else 1f
                                    val renderedSegmentHeight = (if (fixedHeight) maxHeight / segmentsPerBar else segmentHeight)
                                        .times(segmentMultiplier).coerceAtLeast(0.5f)
                                    val from = if (segmentPrefix.isNotEmpty()) runCatching { Color(android.graphics.Color.parseColor(textValue("$segmentPrefix" + "Color From", "#FF56D8FF"))) }.getOrDefault(colorForBar) else colorForBar
                                    val to = if (segmentPrefix.isNotEmpty()) runCatching { Color(android.graphics.Color.parseColor(textValue("$segmentPrefix" + "Color To", "#FF9B5CFF"))) }.getOrDefault(colorForBar) else colorForBar
                                    val segmentFill = if (segmentPrefix.isNotEmpty()) Color(
                                        from.red + (to.red - from.red) * level,
                                        from.green + (to.green - from.green) * level,
                                        from.blue + (to.blue - from.blue) * level,
                                        opacity
                                    ) else colorForBar
                                    val segmentTop = top + segment * (renderedSegmentHeight + segmentGap)
                                    val flipAlternate = segmentPrefix.isNotEmpty() && props["$segmentPrefix" + "Flip Every Other"] == ElementValue.Bool(true) && index % 2 == 1
                                    val mirrorSegment = segmentPrefix.isNotEmpty() && props["$segmentPrefix" + "Mirror"] == ElementValue.Bool(true)
                                    rotate(if (flipAlternate) 180f else 0f, Offset(x + barWidth / 2f, baseline)) {
                                        drawBarShape(x, segmentTop, barWidth, renderedSegmentHeight, segmentFill, 0f, index)
                                        if (verticalMirror || centerSymmetry || mirrorSegment) {
                                            val mirrorTop = baseline + segment * (renderedSegmentHeight + segmentGap)
                                            drawBarShape(x, mirrorTop, barWidth, renderedSegmentHeight, segmentFill, 0f, index)
                                        }
                                    }
                                }
                                val peakStyle = textValue("Peak Indicator Style", "Line")
                                if (peakEnabled && peakStyle != "None") {
                                    val peakLevel = peaks[sourceIndex].coerceIn(0f, 1f)
                                    val peakTravel = peakLevel * maxHeight * if (verticalMirror || centerSymmetry) 0.5f else 1f
                                    val peakY = if (alignment == "Top" && !customPathMode) baseline + peakTravel else baseline - peakTravel
                                    when (peakStyle) {
                                        "Dot" -> drawCircle(peakColor.copy(alpha = opacity), (peakWidth * 1.5f).coerceAtLeast(1f), Offset(x + barWidth / 2f, peakY))
                                        "Bar" -> drawRect(peakColor.copy(alpha = opacity), Offset(x, peakY - peakWidth), androidx.compose.ui.geometry.Size(barWidth, peakWidth * 2f))
                                        "Line" -> drawLine(peakColor.copy(alpha = opacity), Offset(x - peakWidth, peakY), Offset(x + barWidth + peakWidth, peakY), peakWidth)
                                    }
                                }
                            }
                                }
                            }
                        }
                    }
                    StudioElementType.PARTICLES -> {
                        fun numberAlias(primary: String, primaryDefault: Float, legacy: String, legacyDefault: Float = primaryDefault): Float {
                            val preferred = number(primary, primaryDefault)
                            val fallback = number(legacy, legacyDefault)
                            return if (preferred != primaryDefault) preferred else fallback
                        }
                        fun textAlias(primary: String, primaryDefault: String, legacy: String, legacyDefault: String = primaryDefault): String {
                            val preferred = textValue(primary, primaryDefault)
                            val fallback = textValue(legacy, legacyDefault)
                            return if (preferred != primaryDefault) preferred else fallback
                        }
                        val requestedCount = number("Particle Count", 256f).toInt().coerceIn(1, 10000)
                        val countLimit = number("Count Limit", 128f).toInt().coerceIn(1, 10000)
                        val count = minOf(requestedCount, countLimit)
                        val lifetime = numberAlias("Life Span", 3f, "Lifetime").coerceIn(0.01f, 3600f)
                        val emissionRate = number("Emission Rate", 0f).coerceAtLeast(0f)
                        val spawnInterval = if (emissionRate > 0f) 1f / emissionRate.coerceAtLeast(0.001f) else number("Spawn Time", 0.02f).coerceIn(0f, 10f)
                        val spawnWidth = number("Spawn Area Width", 1f).coerceIn(0f, 10000f) * size.width * 0.25f
                        val spawnHeight = number("Spawn Area Height", 1f).coerceIn(0f, 10000f) * size.height * 0.25f
                        val spawnArea = textAlias("Particle Emitter Shape", "Point", "Spawn Area")
                        val emitterPosition = props["Emitter Position"] as? ElementValue.Vector2 ?: ElementValue.Vector2(0.5, 0.5)
                        val emitterX = center.x + (emitterPosition.x.toFloat() - 0.5f) * size.width
                        val emitterY = center.y + (emitterPosition.y.toFloat() - 0.5f) * size.height
                        val emitterRotation = number("Emitter Rotation", 0f) * PI.toFloat() / 180f
                        val emitterRadius = number("Emitter Radius", 100f).coerceAtLeast(0f) * size.width / 1080f
                        val emitterInnerRadius = number("Emitter Inner Radius", 0f).coerceIn(0f, number("Emitter Radius", 100f)) * size.width / 1080f
                        val emitterArcStart = number("Emitter Arc Start", 0f) * PI.toFloat() / 180f
                        val emitterArcSweep = number("Emitter Arc Sweep", 360f).coerceIn(-360f, 360f) * PI.toFloat() / 180f
                        val emitterBurstCount = number("Emitter Burst Count", 0f).toInt().coerceAtLeast(0)
                        val emitterBurstInterval = number("Emitter Burst Interval", 1f).coerceAtLeast(0.001f)
                        val emitterLoop = props["Emitter Loop"] != ElementValue.Bool(false)
                        val particleMass = number("Particle Mass", 1f).coerceAtLeast(0.001f)
                        val particleDamping = number("Particle Damping", 0f).coerceIn(0f, 1f)
                        val angularVelocity = numberAlias("Particle Angular Velocity", 0f, "Rotation Speed")
                        val angularVelocityRandom = number("Particle Angular Velocity Random", 0f).coerceAtLeast(0f)
                        val relativeToCenter = props["Vector Relative To Center"] == ElementValue.Bool(true)
                        val randomVector = props["Use Random Vector Instead"] == ElementValue.Bool(true)
                        val baseAngle = Math.toRadians(numberAlias("Vector Angle", -90f, "Direction / Angle").toDouble()).toFloat()
                        val spreadAngle = (number("Spread", 360f) + number("Direction Random", 0f)).coerceIn(0f, 720f) * PI.toFloat() / 180f
                        val gravity = props["Gravity"] as? ElementValue.Vector2 ?: ElementValue.Vector2(0.0, 0.0)
                        val forcePosition = props["Force Field Position"] as? ElementValue.Vector2 ?: ElementValue.Vector2(0.5, 0.5)
                        val forceMode = textValue("Force Field", "None")
                        val forceStrength = number("Force Field Strength", 0f)
                        val speedSource = when (textValue("Measure Overall Speed (Measure What)", "Nothing")) {
                            "Beat" -> providerFrame?.beatValue ?: frame?.beat?.pulse ?: 0f
                            "Bass" -> bass
                            "Treble" -> treble
                            "RMS", "Overall Audio Level" -> frame?.rms ?: 0f
                            "Peak" -> frame?.peak ?: 0f
                            else -> 0f
                        }.coerceIn(0f, 1f)
                        val baseSpeed = numberAlias("Speed", 80f, "Start Velocity") +
                            speedSource * (number("Measure Overall Speed A (X Amount)", 0f) + number("Measure Overall Speed B (Y Amount)", 0f))
                        val speedRandom = numberAlias("Speed Random", 0f, "Velocity Random").coerceIn(0f, 1f)
                        val endVelocity = number("End Velocity", number("Speed", number("Start Velocity", 80f)))
                        val friction = number("Friction / Drag", 0f).coerceIn(0f, 100f) / 100f
                        val turbulence = number("Turbulence / Noise", 0f).coerceAtLeast(0f)
                        val lifetimeRandom = number("Lifetime Random", 0f).coerceIn(0f, 1f)
                        val wind = props["Wind"] as? ElementValue.Vector2 ?: ElementValue.Vector2(0.0, 0.0)
                        val collisionEnabled = props["Collision"] == ElementValue.Bool(true)
                        val bounce = number("Bounce", 0.5f).coerceIn(0f, 1f)
                        val trailWidth = number("Particle Trail Width", 1f).coerceAtLeast(0f)
                        val trailFade = number("Particle Trail Fade", 0.8f).coerceIn(0f, 1f)
                        val opacityRandom = number("Particle Opacity Random", 0f).coerceIn(0f, 1f)
                        val colorRandom = number("Particle Color Random", 0f).coerceIn(0f, 1f)
                        val particleShape = textValue("Particle Shape", "Circle")
                        val screenBlend = props["Particle Screen Blend"] == ElementValue.Bool(true)
                        val additiveBlend = props["Particle Additive Blend"] == ElementValue.Bool(true)
                        val particleBlendMode = if (screenBlend) androidx.compose.ui.graphics.BlendMode.Screen else if (additiveBlend) androidx.compose.ui.graphics.BlendMode.Plus else androidx.compose.ui.graphics.BlendMode.SrcOver
                        val startSize = number("Start Size", 8f).coerceAtLeast(0f)
                        val endSize = number("End Size", 2f).coerceAtLeast(0f)
                        val endRandom = numberAlias("End Size Random", 0f, "Particle Size Random").coerceIn(0f, 1f)
                        val fadeTime = number("Fade In And Out Time", 0.25f).coerceIn(0f, lifetime * 0.5f)
                        val trailLength = number("Trail Length", 0f).coerceIn(0f, 256f)
                        val particleScale = number("Particle Scale", 100f).coerceAtLeast(0f) / 100f * scale
                        val aspect = number("Size Aspect Ratio", 1f).coerceIn(0.01f, 20f)
                        val perspectiveDepth = number("Perspective Depth", 0f).coerceAtLeast(0f)
                        val perspectiveFov = number("Perspective Fov", 60f).coerceIn(1f, 179f)
                        val nearFadeDistance = number("Near Camera Fade Out Distance", 0f).coerceAtLeast(0f)
                        val sineFrequency = number("Side Sine Wave Freq", 0f)
                        val sineFrequencyRandom = number("Side Sine Wave Freq Random", 0f).coerceIn(0f, 1f)
                        val sineMagnitude = number("Side Sine Wave Mag", 0f)
                        val sineMagnitudeRandom = number("Side Sine Wave Mag Random", 0f).coerceIn(0f, 1f)
                        val colorFrom = runCatching { Color(audioReactivityColor(android.graphics.Color.parseColor(textValue("Color From", textValue("Color", colorText))), reactivity.colorMultiplier)) }.getOrDefault(elementColor)
                        val colorTo = runCatching { Color(audioReactivityColor(android.graphics.Color.parseColor(textValue("Color To", colorText)), reactivity.colorMultiplier)) }.getOrDefault(elementColor)
                        val colorOverLifeMode = textValue("Color Over Life", "Gradient")
                        val alphaOverLifeMode = textValue("Alpha Over Life", "Fade In Out")
                        val colorOverLifeStart = runCatching {
                            Color(audioReactivityColor(android.graphics.Color.parseColor(
                                textAlias("Color Over Life Start", "#FFFFFFFF", "Color From", textValue("Color", colorText))
                            ), reactivity.colorMultiplier))
                        }.getOrDefault(colorFrom)
                        val colorOverLifeEnd = runCatching {
                            Color(audioReactivityColor(android.graphics.Color.parseColor(
                                textAlias("Color Over Life End", "#00FFFFFF", "Color To", colorText)
                            ), reactivity.colorMultiplier))
                        }.getOrDefault(colorTo)
                        val customParticle = elementImages[element.id]
                        val mirrorX = props["Mirror X"] == ElementValue.Bool(true)
                        val mirrorY = props["Mirror Y"] == ElementValue.Bool(true)
                        val velocityAngle = props["Velocity Angle"] == ElementValue.Bool(true)
                        val gravityX = gravity.x.toFloat()
                        val gravityY = gravity.y.toFloat()
                        repeat(count) { index ->
                            val seed = index + 1f
                            val randomA = kotlin.math.abs(sin(seed * 12.9898f) * 43758.5453f) % 1f
                            val randomB = kotlin.math.abs(sin(seed * 78.233f + 4.17f) * 24634.6345f) % 1f
                            val randomC = kotlin.math.abs(sin(seed * 39.425f + 9.31f) * 56445.234f) % 1f
                            val particleLifetime = (lifetime * (1f + (randomC * 2f - 1f) * lifetimeRandom)).coerceAtLeast(0.01f)
                            val age = ((playbackSeconds + index * spawnInterval) % particleLifetime).coerceIn(0f, particleLifetime)
                            val lifeT = (age / particleLifetime).coerceIn(0f, 1f)
                            val emitterAngle = emitterArcStart + randomA * emitterArcSweep
                            val radialDistance = emitterInnerRadius + (emitterRadius - emitterInnerRadius) * randomB
                            val localX: Float
                            val localY: Float
                            when (spawnArea) {
                                "Rectangle" -> { localX = (randomA * 2f - 1f) * spawnWidth; localY = (randomB * 2f - 1f) * spawnHeight }
                                "Ellipse", "Circle" -> { localX = cos(emitterAngle) * radialDistance.coerceAtLeast(spawnWidth * randomB); localY = sin(emitterAngle) * (if (spawnArea == "Circle") radialDistance else (emitterRadius * spawnHeight / spawnWidth.coerceAtLeast(1f))) }
                                "Ring" -> { localX = cos(emitterAngle) * radialDistance; localY = sin(emitterAngle) * radialDistance }
                                "Line" -> { localX = (randomA * 2f - 1f) * spawnWidth; localY = 0f }
                                "Edge" -> { localX = if (randomA < 0.5f) -spawnWidth else spawnWidth; localY = (randomB * 2f - 1f) * spawnHeight }
                                else -> { localX = 0f; localY = 0f }
                            }
                            val spawnX = emitterX + localX * cos(emitterRotation) - localY * sin(emitterRotation)
                            val spawnY = emitterY + localX * sin(emitterRotation) + localY * cos(emitterRotation)
                            val radialAngle = kotlin.math.atan2(spawnY - center.y, spawnX - center.x)
                            val angle = when {
                                randomVector -> randomA * 2f * PI.toFloat()
                                relativeToCenter -> radialAngle + number("Vector Angle Z", 0f) * PI.toFloat() / 180f + (randomA - 0.5f) * spreadAngle
                                else -> baseAngle + (randomA - 0.5f) * spreadAngle
                            }
                            val burstIndex = if (emitterBurstCount > 0) (playbackSeconds / emitterBurstInterval).toInt() else 0
                            val inBurst = emitterBurstCount <= 0 || (index % count.coerceAtLeast(1)) < emitterBurstCount
                            val particleSpeed = baseSpeed + (endVelocity - baseSpeed) * lifeT
                            val velocity = particleSpeed * (1f + (randomC * 2f - 1f) * speedRandom) / particleMass
                            var vx = cos(angle) * velocity
                            var vy = sin(angle) * velocity
                            if (emitterBurstCount > 0 && (!inBurst || (!emitterLoop && burstIndex > 0))) { vx = 0f; vy = 0f }
                            val fieldX = center.x + forcePosition.x.toFloat() * size.width - center.x
                            val fieldY = center.y + forcePosition.y.toFloat() * size.height - center.y
                            val dx = fieldX - spawnX
                            val dy = fieldY - spawnY
                            val distance = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                            when (forceMode) {
                                "Attract", "Radial" -> { vx += dx / distance * forceStrength * age; vy += dy / distance * forceStrength * age }
                                "Repel" -> { vx -= dx / distance * forceStrength * age; vy -= dy / distance * forceStrength * age }
                                "Directional" -> { vx += cos(baseAngle) * forceStrength * age; vy += sin(baseAngle) * forceStrength * age }
                            }
                            val frequency = sineFrequency * (1f + randomB * sineFrequencyRandom)
                            val wave = sin(age * frequency * 2f * PI.toFloat() + randomC * 2f * PI.toFloat()) *
                                sineMagnitude * (1f + randomA * sineMagnitudeRandom)
                            val dampingFactor = (1f - particleDamping).coerceAtLeast(0f)
                            val totalDrag = (1f - (1f - particleDamping) * (1f - friction)).coerceIn(0f, 0.999f)
                            val travelTime = if (totalDrag > 0f) (1f - kotlin.math.exp(-totalDrag * age)) / totalDrag else age
                            var px = spawnX + vx * travelTime * 0.01f + gravityX * age * age * 0.005f + wind.x.toFloat() * age * age * 0.005f + wave * dampingFactor + sin(age * 7f + randomA * 19f) * turbulence * age * 0.01f
                            var py = spawnY + vy * travelTime * 0.01f + gravityY * age * age * 0.005f + wind.y.toFloat() * age * age * 0.005f + cos(age * 6f + randomB * 17f) * turbulence * age * 0.01f
                            if (collisionEnabled) { if (px < 0f || px > size.width) vx = -vx * bounce; if (py < 0f || py > size.height) vy = -vy * bounce; px = px.coerceIn(0f, size.width); py = py.coerceIn(0f, size.height) }
                            val depth = randomB * perspectiveDepth
                            val perspective = (perspectiveFov / (perspectiveFov + depth * 0.1f)).coerceIn(0.05f, 1f)
                            val depthScale = (1f + number("End Size ZFactor", 0f) * depth / 1000f).coerceAtLeast(0.05f)
                            val endVariation = 1f + (randomC * 2f - 1f) * endRandom
                            val fadeInScale = if (fadeTime > 0f) (age / fadeTime).coerceIn(0f, 1f) else 1f
                            val fadeInSize = number("Fade In Size", 1f).coerceIn(0f, 10f)
                            val sizeT = ((startSize + (endSize * endVariation - startSize) * lifeT) *
                                (1f + (fadeInScale - 1f) * fadeInSize)).coerceAtLeast(0f)
                            val fadeOut = if (fadeTime > 0f) ((particleLifetime - age) / fadeTime).coerceIn(0f, 1f) else 1f
                            val nearFade = if (nearFadeDistance > 0f) (1f - depth / nearFadeDistance).coerceIn(0f, 1f) else 1f
                            val alphaOverLife = when (alphaOverLifeMode) {
                                "Constant" -> 1f
                                "Fade In" -> lifeT
                                "Fade Out" -> 1f - lifeT
                                else -> (1f - kotlin.math.abs(lifeT * 2f - 1f)).coerceIn(0f, 1f)
                            }
                            val randomizedColorT = (lifeT + (randomC - 0.5f) * colorRandom).coerceIn(0f, 1f)
                            val colorT = when (colorOverLifeMode) {
                                "Constant", "Fade To Transparent" -> 0f
                                else -> randomizedColorT
                            }
                            val colorFadeAlpha = if (colorOverLifeMode == "Fade To Transparent") 1f - lifeT else 1f
                            val colorAlpha = (colorOverLifeStart.alpha +
                                (colorOverLifeEnd.alpha - colorOverLifeStart.alpha) * colorT).coerceIn(0f, 1f)
                            val alpha = (opacity * fadeInScale * fadeOut * nearFade *
                                (1f - randomA * opacityRandom) * alphaOverLife * colorFadeAlpha * colorAlpha)
                                .coerceIn(0f, 1f)
                            val particleColor = Color(
                                colorOverLifeStart.red + (colorOverLifeEnd.red - colorOverLifeStart.red) * colorT,
                                colorOverLifeStart.green + (colorOverLifeEnd.green - colorOverLifeStart.green) * colorT,
                                colorOverLifeStart.blue + (colorOverLifeEnd.blue - colorOverLifeStart.blue) * colorT,
                                alpha
                            )
                            val particleWidth = (sizeT * particleScale * perspective * depthScale * aspect).dp.toPx().coerceAtLeast(0.5f)
                            val particleHeight = (sizeT * particleScale * perspective * depthScale).dp.toPx().coerceAtLeast(0.5f)
                            val randomAngularVelocity = angularVelocity + (randomB * 2f - 1f) * angularVelocityRandom
                            val visualAngle = if (velocityAngle) Math.toDegrees(kotlin.math.atan2(vy.toDouble(), vx.toDouble())).toFloat() else number("Particle Rotation Random", 0f) * randomC + randomAngularVelocity * age
                            val trailX = vx * trailLength * 0.001f
                            val trailY = vy * trailLength * 0.001f
                            fun drawParticleAt(x: Float, y: Float) {
                                if (trailLength > 0f) {
                                    drawLine(particleColor.copy(alpha = alpha * trailFade), Offset(x - trailX, y - trailY), Offset(x, y),
                                        (particleHeight * 0.35f * trailWidth).coerceAtLeast(0.5f), cap = androidx.compose.ui.graphics.StrokeCap.Round, blendMode = particleBlendMode)
                                }
                                if (customParticle != null) {
                                    rotate(visualAngle, Offset(x, y)) {
                                        drawImage(customParticle,
                                            dstOffset = androidx.compose.ui.unit.IntOffset((x - particleWidth / 2f).toInt(), (y - particleHeight / 2f).toInt()),
                                            dstSize = androidx.compose.ui.unit.IntSize(particleWidth.toInt().coerceAtLeast(1), particleHeight.toInt().coerceAtLeast(1)),
                                            alpha = alpha,
                                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(particleColor.copy(alpha = 1f), androidx.compose.ui.graphics.BlendMode.SrcIn),
                                            blendMode = particleBlendMode)
                                    }
                                } else {
                                    rotate(visualAngle, Offset(x, y)) {
                                        when (particleShape) {
                                            "Square" -> drawRect(particleColor, Offset(x - particleWidth / 2f, y - particleHeight / 2f), androidx.compose.ui.geometry.Size(particleWidth, particleHeight), blendMode = particleBlendMode)
                                            "Triangle" -> { val p = Path().apply { moveTo(x, y - particleHeight / 2f); lineTo(x + particleWidth / 2f, y + particleHeight / 2f); lineTo(x - particleWidth / 2f, y + particleHeight / 2f); close() }; drawPath(p, particleColor, blendMode = particleBlendMode) }
                                            "Star" -> { val p = Path(); for (v in 0 until 10) { val a = (v * 36f - 90f) * PI.toFloat() / 180f; val r = if (v % 2 == 0) 0.5f else 0.22f; val px2 = x + cos(a) * particleWidth * r; val py2 = y + sin(a) * particleHeight * r; if (v == 0) p.moveTo(px2, py2) else p.lineTo(px2, py2) }; p.close(); drawPath(p, particleColor, blendMode = particleBlendMode) }
                                            "Line" -> drawLine(particleColor, Offset(x - particleWidth / 2f, y), Offset(x + particleWidth / 2f, y), particleHeight.coerceAtLeast(1f), blendMode = particleBlendMode)
                                            else -> drawCircle(particleColor, particleWidth.coerceAtLeast(1f) * 0.5f, Offset(x, y), blendMode = particleBlendMode)
                                        }
                                    }
                                }
                            }
                            if (mirrorX) drawParticleAt(size.width - px, py)
                            if (mirrorY) drawParticleAt(px, size.height - py)
                            if (mirrorX && mirrorY) drawParticleAt(size.width - px, size.height - py)
                            drawParticleAt(px, py)
                        }
                    }
                    StudioElementType.IMAGE -> {
                        val bitmap = elementImages[element.id]
                        if (bitmap != null) {
                            val imageScale = props["Scale"] as? ElementValue.Vector2 ?: ElementValue.Vector2(1.0, 1.0)
                            val imageScaleX = imageScale.x.toFloat().coerceIn(0f, 10f)
                            val imageScaleY = (if (props["Scale Is Uniform"] == ElementValue.Bool(true)) imageScaleX else imageScale.y.toFloat()).coerceIn(0f, 10f)
                            val frameWidth = (size.width * 0.45f * scale * imageScaleX).coerceAtLeast(1f)
                            val frameHeight = (size.height * 0.45f * scale * imageScaleY).coerceAtLeast(1f)
                            val fitMode = if (props["Keep Aspect Ratio And Crop To Fit"] == ElementValue.Bool(true)) "Fill (Cover)" else textValue("Fit Mode (Scale Type)", "Fit (Contain)")
                            val keepRatio = props["Keep Aspect Ratio"] != ElementValue.Bool(false)
                            val sourceLeft = (number("Crop Left", 0f).coerceIn(0f, 0.95f) * bitmap.width).toInt()
                            val sourceTop = (number("Crop Top", 0f).coerceIn(0f, 0.95f) * bitmap.height).toInt()
                            val sourceRight = (number("Crop Right", 0f).coerceIn(0f, 0.95f) * bitmap.width).toInt()
                            val sourceBottom = (number("Crop Bottom", 0f).coerceIn(0f, 0.95f) * bitmap.height).toInt()
                            val sourceWidth = (bitmap.width - sourceLeft - sourceRight).coerceAtLeast(1)
                            val sourceHeight = (bitmap.height - sourceTop - sourceBottom).coerceAtLeast(1)
                            val imageRatio = sourceWidth.toFloat() / sourceHeight
                            val frameRatio = frameWidth / frameHeight
                            val destination = when {
                                fitMode == "Stretch" || !keepRatio -> androidx.compose.ui.geometry.Rect(center.x - frameWidth / 2f, center.y - frameHeight / 2f, center.x + frameWidth / 2f, center.y + frameHeight / 2f)
                                fitMode == "Fill (Cover)" -> {
                                    val w = if (imageRatio > frameRatio) frameHeight * imageRatio else frameWidth
                                    val h = if (imageRatio > frameRatio) frameHeight else frameWidth / imageRatio
                                    androidx.compose.ui.geometry.Rect(center.x - w / 2f, center.y - h / 2f, center.x + w / 2f, center.y + h / 2f)
                                }
                                else -> {
                                    val w = if (imageRatio > frameRatio) frameWidth else frameHeight * imageRatio
                                    val h = if (imageRatio > frameRatio) frameWidth / imageRatio else frameHeight
                                    androidx.compose.ui.geometry.Rect(center.x - w / 2f, center.y - h / 2f, center.x + w / 2f, center.y + h / 2f)
                                }
                            }
                            val parallaxX = number("Image Parallax X", 0f).coerceIn(-1f, 1f) * frameWidth
                            val parallaxY = number("Image Parallax Y", 0f).coerceIn(-1f, 1f) * frameHeight
                            val imageDestination = androidx.compose.ui.geometry.Rect(destination.left + parallaxX, destination.top + parallaxY, destination.right + parallaxX, destination.bottom + parallaxY)
                            val flipX = props["Image Flip Horizontal"] == ElementValue.Bool(true)
                            val flipY = props["Image Flip Vertical"] == ElementValue.Bool(true)
                            val shapeName = textValue("Shape", "Rectangle").takeIf { it != "Rectangle" } ?: textValue("Mask / Shape", "Rectangle")
                            val customShape = buildNormalizedImageShapePath(
                                textValue("Image Custom Shape Path", ""),
                                android.graphics.RectF(imageDestination.left, imageDestination.top, imageDestination.right, imageDestination.bottom)
                            )
                            val clip = Path().apply {
                                if (customShape != null) addPath(customShape.asComposePath()) else when (shapeName) {
                                    "Circle", "Ellipse", "Circle/Oval" -> addOval(imageDestination)
                                    "Square" -> {
                                        val side = minOf(imageDestination.width, imageDestination.height)
                                        addRect(androidx.compose.ui.geometry.Rect(imageDestination.center.x - side / 2f, imageDestination.center.y - side / 2f, imageDestination.center.x + side / 2f, imageDestination.center.y + side / 2f))
                                    }
                                    "Triangle" -> { moveTo(imageDestination.center.x, imageDestination.top); lineTo(imageDestination.right, imageDestination.bottom); lineTo(imageDestination.left, imageDestination.bottom); close() }
                                    "Rounded Rectangle", "Rounded Rect" -> addRoundRect(androidx.compose.ui.geometry.RoundRect(imageDestination, androidx.compose.ui.geometry.CornerRadius((number("Corner Radius", 0f) * size.width / 1080f).coerceAtLeast(0f))))
                                    else -> addRect(imageDestination)
                                }
                            }
                            val overlay = runCatching { Color(android.graphics.Color.parseColor(textValue("Color Overlay", "#00FFFFFF"))) }.getOrDefault(Color.Transparent)
                            val imageAlpha = (opacity * number("Opacity / Alpha", 1f).coerceIn(0f, 1f) * number("Opacity Strength", 1f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
                            val layerBlend = when (textValue("Blend Mode", "Alpha")) {
                                "Add" -> androidx.compose.ui.graphics.BlendMode.Plus
                                "Screen" -> androidx.compose.ui.graphics.BlendMode.Screen
                                "Multiply", "Subtractive" -> androidx.compose.ui.graphics.BlendMode.Multiply
                                "Darken" -> androidx.compose.ui.graphics.BlendMode.Darken
                                "Lighten" -> androidx.compose.ui.graphics.BlendMode.Lighten
                                "Exclusion" -> androidx.compose.ui.graphics.BlendMode.Difference
                                "Copy" -> androidx.compose.ui.graphics.BlendMode.Src
                                else -> androidx.compose.ui.graphics.BlendMode.SrcOver
                            }
                            val contentBlend = when (textValue("Blend Mode Content", "Alpha")) {
                                "Add" -> androidx.compose.ui.graphics.BlendMode.Plus
                                "Screen" -> androidx.compose.ui.graphics.BlendMode.Screen
                                "Multiply", "Subtractive" -> androidx.compose.ui.graphics.BlendMode.Multiply
                                "Darken" -> androidx.compose.ui.graphics.BlendMode.Darken
                                "Lighten" -> androidx.compose.ui.graphics.BlendMode.Lighten
                                "Exclusion" -> androidx.compose.ui.graphics.BlendMode.Difference
                                "Copy" -> androidx.compose.ui.graphics.BlendMode.Src
                                else -> layerBlend
                            }
                            val saturation = number("Saturation", 1f).coerceIn(0f, 2f)
                            val imageColorMatrix = ColorMatrix().apply {
                                setToSaturation(saturation)

                                val layerTint = runCatching { Color(android.graphics.Color.parseColor(textValue("Color", "#FFFFFFFF"))) }.getOrDefault(Color.White)
                                val tintValues = values
                                val tintChannels = floatArrayOf(layerTint.red, layerTint.green, layerTint.blue)
                                for (row in 0..2) {
                                    val base = row * 5
                                    for (column in 0..3) tintValues[base + column] *= tintChannels[row]
                                    tintValues[base + 4] *= tintChannels[row]
                                }
                                if (overlay.alpha > 0f) {
                                    val a = overlay.alpha
                                    val v = values
                                    for (row in 0..2) {
                                        val base = row * 5
                                        for (column in 0..3) v[base + column] *= (1f - a)
                                        v[base + 4] = v[base + 4] * (1f - a) + (if (row == 0) overlay.red else if (row == 1) overlay.green else overlay.blue) * a
                                    }
                                }
                                val blendMode = textValue("Measure Color Blend (Measure What)", "Nothing")
                                val blendMotion = motionFor("Measure Color Blend (Measure What)")
                                val blendResponse = maxOf(
                                    kotlin.math.abs(blendMotion.scale - 1f) / 0.32f,
                                    kotlin.math.abs(blendMotion.rotation) / 8f,
                                    kotlin.math.abs(blendMotion.offsetX) / 0.05f,
                                    kotlin.math.abs(blendMotion.offsetY) / 0.05f
                                ).coerceIn(0f, 1f)
                                val measuredBlend = if (blendMode == "Nothing") 0f else {
                                    val minBlend = number("Measure Color Blend Min", 0f).coerceIn(0f, 1f)
                                    val maxBlend = number("Measure Color Blend Max", 1f).coerceIn(0f, 1f)
                                    minBlend + (maxBlend - minBlend) * blendResponse
                                }
                                val blendAmount = maxOf(number("Measure Color Blend", 0f).coerceIn(0f, 1f), measuredBlend)
                                if (blendAmount > 0f) {
                                    val target = runCatching { Color(android.graphics.Color.parseColor(textValue("Color To", "#FFFFFFFF"))) }.getOrDefault(Color.White)
                                    val v = values
                                    val targetChannels = floatArrayOf(target.red, target.green, target.blue)
                                    for (row in 0..2) {
                                        val base = row * 5
                                        for (column in 0..3) v[base + column] *= (1f - blendAmount)
                                        v[base + 4] = v[base + 4] * (1f - blendAmount) + targetChannels[row] * 255f * blendAmount
                                    }
                                }
                            }
                            val showUnblurredUnder = props["Show Unblurred Content Under"] == ElementValue.Bool(true)
                            val showUnblurredOver = props["Show Unblurred Content"] == ElementValue.Bool(true)
                            val originalBitmap = elementOriginalImages[element.id]
                            if (flipX || flipY) drawContext.transform.scale(if (flipX) -1f else 1f, if (flipY) -1f else 1f, pivot = Offset(imageDestination.center.x, imageDestination.center.y))
                            clipPath(clip) {
                                if (showUnblurredUnder && originalBitmap != null) {
                                    drawImage(originalBitmap,
                                        srcOffset = androidx.compose.ui.unit.IntOffset(sourceLeft, sourceTop),
                                        srcSize = androidx.compose.ui.unit.IntSize(sourceWidth, sourceHeight),
                                        dstOffset = androidx.compose.ui.unit.IntOffset(imageDestination.left.toInt(), imageDestination.top.toInt()),
                                        dstSize = androidx.compose.ui.unit.IntSize(imageDestination.width.toInt().coerceAtLeast(1), imageDestination.height.toInt().coerceAtLeast(1)),
                                        alpha = imageAlpha,
                                        blendMode = layerBlend
                                    )
                                }
                                val tileX = textValue("Image Tile Mode X", "Clamp")
                                val tileY = textValue("Image Tile Mode Y", "Clamp")
                                val tileScaleX = number("Image Tile Scale X", 1f).coerceIn(0.1f, 4f)
                                val tileScaleY = number("Image Tile Scale Y", 1f).coerceIn(0.1f, 4f)
                                if (tileX == "Clamp" && tileY == "Clamp" && tileScaleX == 1f && tileScaleY == 1f) {
                                    drawImage(bitmap,
                                        srcOffset = androidx.compose.ui.unit.IntOffset(sourceLeft, sourceTop),
                                        srcSize = androidx.compose.ui.unit.IntSize(sourceWidth, sourceHeight),
                                        dstOffset = androidx.compose.ui.unit.IntOffset(imageDestination.left.toInt(), imageDestination.top.toInt()),
                                        dstSize = androidx.compose.ui.unit.IntSize(imageDestination.width.toInt().coerceAtLeast(1), imageDestination.height.toInt().coerceAtLeast(1)),
                                        alpha = imageAlpha,
                                        colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(imageColorMatrix),
                                        blendMode = contentBlend,
                                        filterQuality = when (textValue("Image Sampling", "Linear")) {
                                            "Nearest" -> androidx.compose.ui.graphics.FilterQuality.None
                                            "Cubic" -> androidx.compose.ui.graphics.FilterQuality.High
                                            else -> androidx.compose.ui.graphics.FilterQuality.Medium
                                        }
                                    )
                                } else {
                                    drawIntoCanvas { composeCanvas ->
                                        val nativePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                            alpha = (imageAlpha * 255f).toInt().coerceIn(0, 255)
                                            isFilterBitmap = textValue("Image Sampling", "Linear") != "Nearest"
                                            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(imageColorMatrix.values))
                                            xfermode = android.graphics.PorterDuffXfermode(when (contentBlend) {
                                                androidx.compose.ui.graphics.BlendMode.Plus -> android.graphics.PorterDuff.Mode.ADD
                                                androidx.compose.ui.graphics.BlendMode.Screen -> android.graphics.PorterDuff.Mode.SCREEN
                                                androidx.compose.ui.graphics.BlendMode.Multiply -> android.graphics.PorterDuff.Mode.MULTIPLY
                                                androidx.compose.ui.graphics.BlendMode.Darken -> android.graphics.PorterDuff.Mode.DARKEN
                                                androidx.compose.ui.graphics.BlendMode.Lighten -> android.graphics.PorterDuff.Mode.LIGHTEN
                                                androidx.compose.ui.graphics.BlendMode.Difference -> android.graphics.PorterDuff.Mode.XOR
                                                androidx.compose.ui.graphics.BlendMode.Src -> android.graphics.PorterDuff.Mode.SRC
                                                else -> android.graphics.PorterDuff.Mode.SRC_OVER
                                            })
                                        }
                                        val shader = android.graphics.BitmapShader(bitmap.asAndroidBitmap(),
                                            when (tileX) {
                                                "Repeat" -> android.graphics.Shader.TileMode.REPEAT
                                                "Mirror" -> android.graphics.Shader.TileMode.MIRROR
                                                else -> android.graphics.Shader.TileMode.CLAMP
                                            },
                                            when (tileY) {
                                                "Repeat" -> android.graphics.Shader.TileMode.REPEAT
                                                "Mirror" -> android.graphics.Shader.TileMode.MIRROR
                                                else -> android.graphics.Shader.TileMode.CLAMP
                                            })
                                        val tileMatrix = android.graphics.Matrix().apply {
                                            setRectToRect(
                                                android.graphics.RectF(sourceLeft.toFloat(), sourceTop.toFloat(), (sourceLeft + sourceWidth).toFloat(), (sourceTop + sourceHeight).toFloat()),
                                                android.graphics.RectF(imageDestination.left, imageDestination.top,
                                                    imageDestination.left + imageDestination.width * tileScaleX,
                                                    imageDestination.top + imageDestination.height * tileScaleY),
                                                android.graphics.Matrix.ScaleToFit.FILL
                                            )
                                        }
                                        shader.setLocalMatrix(tileMatrix)
                                        nativePaint.shader = shader
                                        composeCanvas.nativeCanvas.drawRect(
                                            android.graphics.RectF(imageDestination.left, imageDestination.top, imageDestination.right, imageDestination.bottom),
                                            nativePaint
                                        )
                                    }
                                }
                                if (showUnblurredOver && originalBitmap != null) {
                                    drawImage(originalBitmap,
                                        srcOffset = androidx.compose.ui.unit.IntOffset(sourceLeft, sourceTop),
                                        srcSize = androidx.compose.ui.unit.IntSize(sourceWidth, sourceHeight),
                                        dstOffset = androidx.compose.ui.unit.IntOffset(imageDestination.left.toInt(), imageDestination.top.toInt()),
                                        dstSize = androidx.compose.ui.unit.IntSize(imageDestination.width.toInt().coerceAtLeast(1), imageDestination.height.toInt().coerceAtLeast(1)),
                                        alpha = imageAlpha,
                                        blendMode = layerBlend
                                    )
                                }
                            }
                            if (flipX || flipY) drawContext.transform.scale(if (flipX) -1f else 1f, if (flipY) -1f else 1f, pivot = Offset(imageDestination.center.x, imageDestination.center.y))
                            val borderWidth = number("Image Border Width", 0f).coerceIn(0f, 256f) * size.width / 1080f
                            if (borderWidth > 0f) {
                                val borderColor = runCatching { Color(android.graphics.Color.parseColor(textValue("Image Border Color", "#00000000"))) }.getOrDefault(Color.Transparent)
                                drawPath(clip, borderColor, style = Stroke(width = borderWidth))
                            }
                        }
                    }
                    StudioElementType.RGB_SPLIT -> {
                        val bitmap = elementImages[element.id]
                        if (bitmap != null) {
                            val original = elementOriginalImages[element.id]
                            val showUnder = props["Show Unblurred Content Under"] == ElementValue.Bool(true)
                            val showOver = props["Show Unblurred Content"] == ElementValue.Bool(true)
                            val frameWidth = size.width * 0.45f * scale
                            val frameHeight = frameWidth * bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
                            val originalDestination = androidx.compose.ui.unit.IntOffset((center.x - frameWidth / 2f).toInt(), (center.y - frameHeight / 2f).toInt())
                            val originalSize = androidx.compose.ui.unit.IntSize(frameWidth.toInt().coerceAtLeast(1), frameHeight.toInt().coerceAtLeast(1))
                            if (showUnder && original != null) drawImage(original, dstOffset = originalDestination, dstSize = originalSize, alpha = opacity)
                            val amount = number("Split Amount", number("Split Amount / Distance", 4f)) * number("Split Multiplier", 1f) * scale * reactivity.effectStrengthMultiplier
                            val angle = (number("Angle / Direction", 0f) + playbackSeconds * number("Speed / Frequency", 0f)) * PI.toFloat() / 180f
                            val focus = props["Center / Focus Point"] as? ElementValue.Vector2 ?: ElementValue.Vector2(0.5, 0.5)
                            val falloff = number("Falloff / Radial Split", 0f).coerceAtLeast(0f)
                            val contentColor = runCatching {
                                Color(audioReactivityColor(android.graphics.Color.parseColor(textValue("Color", "#FFFFFFFF")), reactivity.colorMultiplier))
                            }.getOrDefault(Color.White)
                            val colors = listOf("Split Color 0", "Split Color 1", "Split Color 2").mapIndexed { index, key ->
                                val channel = runCatching { Color(android.graphics.Color.parseColor(textValue(key, listOf("#FFFF0000", "#FF00FF00", "#FF0000FF")[index]))) }.getOrDefault(listOf(Color.Red, Color.Green, Color.Blue)[index])
                                Color(channel.red * contentColor.red, channel.green * contentColor.green, channel.blue * contentColor.blue, channel.alpha * contentColor.alpha)
                            }
                            val offsets = listOf(
                                Offset(number("Red Offset X", -4f), number("Red Offset Y", 0f)),
                                Offset(number("Green Offset X", 0f), number("Green Offset Y", 0f)),
                                Offset(number("Blue Offset X", 4f), number("Blue Offset Y", 0f))
                            )
                            val destination = Offset(center.x - frameWidth / 2f, center.y - frameHeight / 2f)
                            val contentBlend = when (textValue("Blend Mode Content", "Alpha")) {
                                "Add" -> androidx.compose.ui.graphics.BlendMode.Plus
                                "Screen" -> androidx.compose.ui.graphics.BlendMode.Screen
                                "Multiply", "Subtractive" -> androidx.compose.ui.graphics.BlendMode.Multiply
                                "Darken" -> androidx.compose.ui.graphics.BlendMode.Darken
                                "Lighten" -> androidx.compose.ui.graphics.BlendMode.Lighten
                                "Exclusion", "Difference" -> androidx.compose.ui.graphics.BlendMode.Difference
                                "Copy" -> androidx.compose.ui.graphics.BlendMode.Src
                                else -> androidx.compose.ui.graphics.BlendMode.SrcOver
                            }
                            colors.forEachIndexed { index, tint ->
                                val base = offsets[index]
                                val radialScale = 1f + falloff * kotlin.math.sqrt((focus.x.toFloat() - 0.5f).pow(2) + (focus.y.toFloat() - 0.5f).pow(2))
                                val dx = (base.x * scale + cos(angle) * amount * (index - 1)) * radialScale
                                val dy = (base.y * scale + sin(angle) * amount * (index - 1)) * radialScale
                                drawImage(bitmap,
                                    dstOffset = androidx.compose.ui.unit.IntOffset((destination.x + dx).toInt(), (destination.y + dy).toInt()),
                                    dstSize = androidx.compose.ui.unit.IntSize(frameWidth.toInt().coerceAtLeast(1), frameHeight.toInt().coerceAtLeast(1)),
                                    alpha = opacity * 0.72f,
                                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint, androidx.compose.ui.graphics.BlendMode.Screen),
                                    blendMode = contentBlend
                                )
                            }
                            if (showOver && original != null) drawImage(original, dstOffset = originalDestination, dstSize = originalSize, alpha = opacity)
                        }
                    }
                    StudioElementType.BLUR_EFFECT -> {
                        val bitmap = elementImages[element.id]
                        if (bitmap != null) {
                            val frameWidth = (size.width * 0.45f * scale).coerceAtLeast(1f)
                            val frameHeight = frameWidth * bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
                            val destination = androidx.compose.ui.unit.IntOffset((center.x - frameWidth / 2f).toInt(), (center.y - frameHeight / 2f).toInt())
                            val destinationSize = androidx.compose.ui.unit.IntSize(frameWidth.toInt().coerceAtLeast(1), frameHeight.toInt().coerceAtLeast(1))
                            val original = elementOriginalImages[element.id]
                            if (props["Show Unblurred Content Under"] == ElementValue.Bool(true) && original != null) {
                                drawImage(original, dstOffset = destination, dstSize = destinationSize, alpha = opacity)
                            }
                            drawImage(bitmap, dstOffset = destination, dstSize = destinationSize, alpha = opacity * reactivity.effectStrengthMultiplier.coerceIn(0f, 1f))
                            if (props["Show Unblurred Content"] == ElementValue.Bool(true) && original != null) {
                                drawImage(original, dstOffset = destination, dstSize = destinationSize, alpha = opacity)
                            }
                        }
                    }
                    StudioElementType.MOTION_BLUR_EFFECT -> {
                        val bitmap = elementImages[element.id]
                        if (bitmap != null) {
                            val frameWidth = (size.width * 0.45f * scale).coerceAtLeast(1f)
                            val frameHeight = frameWidth * bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
                            val destinationSize = androidx.compose.ui.unit.IntSize(frameWidth.toInt().coerceAtLeast(1), frameHeight.toInt().coerceAtLeast(1))
                            val samples = number("Samples / Iterations", 8f).toInt().coerceIn(1, 64)
                            val strength = number("Blur Amount / Strength", 1f).coerceIn(0f, 64f)
                            val lengthBase = number("Blur Length", 24f) * number("Blur Amount Multiplier", 1f) * strength * scale * reactivity.effectStrengthMultiplier
                            val focus = props["Center / Focus Point"] as? ElementValue.Vector2 ?: ElementValue.Vector2(0.5, 0.5)
                            val focusX = focus.x.toFloat() * size.width
                            val focusY = focus.y.toFloat() * size.height
                            val radialAngle = atan2(center.y - focusY, center.x - focusX)
                            val motionSource = textValue("Motion Source", "Transform Movement")
                            val relativeMode = textValue("Relative Motion Mode", "Composition")
                            val radial = props["Radial Motion"] == ElementValue.Bool(true) || motionSource == "Target Motion" || relativeMode == "Target Relative"
                            val measuredAngle = number("Blur Direction / Angle", 0f) + if (motionSource == "Measured Movement") sin(playbackSeconds * 2.4f) * 45f else 0f
                            val resolvedAngle = if (radial) radialAngle else (measuredAngle + if (relativeMode == "Relative") number("Rotation", 0f) else 0f) * PI.toFloat() / 180f
                            val velocity = if (props["Velocity Influence"] == ElementValue.Bool(false)) 1f else (0.25f + (frame?.rms ?: 0f).coerceIn(0f, 1f) * 0.75f)
                            val length = lengthBase * velocity
                            val angle = resolvedAngle
                            val trails = number("Trails / Echo", 0f).toInt().coerceIn(0, 32)
                            val fade = number("Trail Opacity / Fade", 0.75f).coerceIn(0f, 1f)
                            val centerOffset = androidx.compose.ui.unit.IntOffset((center.x - frameWidth / 2f).toInt(), (center.y - frameHeight / 2f).toInt())
                            val showUnder = props["Show Unblurred Content Under"] == ElementValue.Bool(true)
                            val showOver = props["Show Unblurred Content"] == ElementValue.Bool(true)
                            val original = elementOriginalImages[element.id]
                            if (showUnder && original != null) {
                                drawImage(original, dstOffset = centerOffset, dstSize = destinationSize, alpha = opacity)
                            }
                            val contentColor = runCatching {
                                Color(audioReactivityColor(android.graphics.Color.parseColor(textValue("Color", "#FFFFFFFF")), reactivity.colorMultiplier))
                            }.getOrDefault(Color.White)
                            val contentBlend = when (textValue("Blend Mode Content", "Alpha")) {
                                "Add" -> androidx.compose.ui.graphics.BlendMode.Plus
                                "Screen" -> androidx.compose.ui.graphics.BlendMode.Screen
                                "Multiply" -> androidx.compose.ui.graphics.BlendMode.Multiply
                                "Darken" -> androidx.compose.ui.graphics.BlendMode.Darken
                                "Lighten" -> androidx.compose.ui.graphics.BlendMode.Lighten
                                "Exclusion" -> androidx.compose.ui.graphics.BlendMode.Exclusion
                                "Copy" -> androidx.compose.ui.graphics.BlendMode.Src
                                else -> androidx.compose.ui.graphics.BlendMode.SrcOver
                            }
                            val contentFilter = if (contentColor != Color.White)
                                androidx.compose.ui.graphics.ColorFilter.tint(contentColor, androidx.compose.ui.graphics.BlendMode.Modulate)
                                else null
                            for (sample in samples downTo 1) {
                                val t = sample.toFloat() / samples
                                val dx = cos(angle) * length * t
                                val dy = sin(angle) * length * t
                                val destination = androidx.compose.ui.unit.IntOffset((center.x - frameWidth / 2f + dx).toInt(), (center.y - frameHeight / 2f + dy).toInt())
                                drawImage(bitmap, dstOffset = destination, dstSize = destinationSize, alpha = opacity * fade * (1f - t * 0.55f), colorFilter = contentFilter, blendMode = contentBlend)
                            }
                            repeat(trails) { index ->
                                val t = (index + 1f) / (trails + 1f)
                                val dx = -cos(angle) * length * t
                                val dy = -sin(angle) * length * t
                                val destination = androidx.compose.ui.unit.IntOffset((center.x - frameWidth / 2f + dx).toInt(), (center.y - frameHeight / 2f + dy).toInt())
                                drawImage(bitmap, dstOffset = destination, dstSize = destinationSize, alpha = opacity * fade * (1f - t), colorFilter = contentFilter, blendMode = contentBlend)
                            }
                            if (showOver) {
                                if (original != null) drawImage(original, dstOffset = centerOffset, dstSize = destinationSize, alpha = opacity)
                                else drawImage(bitmap, dstOffset = centerOffset, dstSize = destinationSize, alpha = opacity, colorFilter = contentFilter, blendMode = contentBlend)
                            } else {
                                drawImage(bitmap, dstOffset = centerOffset, dstSize = destinationSize, alpha = opacity, colorFilter = contentFilter, blendMode = contentBlend)
                            }
                        }
                    }
                    StudioElementType.SHAPE -> {
                        val w = (number("Width", 300f).dp.toPx() * scale).coerceAtLeast(0f)
                        val h = (number("Height", 300f).dp.toPx() * scale).coerceAtLeast(0f)
                        val left = center.x - w / 2f
                        val top = center.y - h / 2f
                        val shapeType = textValue("Shape Type", "Rectangle")
                        val fillAlpha = (opacity * number("Opacity / Alpha", 1f)).coerceIn(0f, 1f)
                        val fillColor = runCatching {
                            Color(audioReactivityColor(android.graphics.Color.parseColor(textValue("Fill / Color", colorText)), reactivity.colorMultiplier))
                        }.getOrDefault(elementColor).copy(alpha = fillAlpha)
                        val gradientEnabled = props["Gradient Fill"] == ElementValue.Bool(true)
                        val gradientStart = runCatching {
                            Color(audioReactivityColor(android.graphics.Color.parseColor(textValue("Gradient Start Color", textValue("Fill / Color", colorText))), reactivity.colorMultiplier))
                        }.getOrDefault(fillColor).copy(alpha = fillAlpha)
                        val gradientEnd = runCatching {
                            Color(audioReactivityColor(android.graphics.Color.parseColor(textValue("Gradient End Color", "#FF000000")), reactivity.colorMultiplier))
                        }.getOrDefault(Color.Black).copy(alpha = fillAlpha)
                        val gradientAngle = number("Gradient Angle", 0f) * PI.toFloat() / 180f
                        val gradientCenterValue = props["Gradient Center"] as? ElementValue.Vector2 ?: ElementValue.Vector2(0.5, 0.5)
                        val gradientCenter = Offset(left + w * gradientCenterValue.x.toFloat(), top + h * gradientCenterValue.y.toFloat())
                        val gradientRadius = (maxOf(w, h, 1f) * number("Gradient Radius", 0.5f)).coerceAtLeast(1f)
                        val gradientBrush = if (gradientEnabled) {
                            if (textValue("Gradient Type", "Linear") == "Radial") androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(gradientStart, gradientEnd), center = gradientCenter, radius = gradientRadius,
                                tileMode = when (textValue("Gradient Spread", "Pad")) { "Repeat" -> androidx.compose.ui.graphics.TileMode.Repeated; "Reflect" -> androidx.compose.ui.graphics.TileMode.Mirror; else -> androidx.compose.ui.graphics.TileMode.Clamp }
                            ) else {
                                val dx = cos(gradientAngle) * gradientRadius
                                val dy = sin(gradientAngle) * gradientRadius
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    colors = listOf(gradientStart, gradientEnd), start = Offset(gradientCenter.x - dx, gradientCenter.y - dy),
                                    end = Offset(gradientCenter.x + dx, gradientCenter.y + dy),
                                    tileMode = when (textValue("Gradient Spread", "Pad")) { "Repeat" -> androidx.compose.ui.graphics.TileMode.Repeated; "Reflect" -> androidx.compose.ui.graphics.TileMode.Mirror; else -> androidx.compose.ui.graphics.TileMode.Clamp }
                                )
                            }
                        } else androidx.compose.ui.graphics.SolidColor(fillColor)
                        val baseShapePath = Path().apply {
                            when (shapeType) {
                                "Circle/Oval" -> {
                                    val oval = androidx.compose.ui.geometry.Rect(left, top, left + w, top + h)
                                    val trimStart = number("Trim Start", 0f).coerceIn(0f, 1f)
                                    val trimEnd = number("Trim End", 1f).coerceIn(trimStart, 1f)
                                    val sweep = number("Ellipse Sweep Angle", 360f) * (trimEnd - trimStart) * number("Trim Paths / Progress", 1f).coerceIn(0f, 1f)
                                    arcTo(oval, number("Ellipse Start Angle", 0f) + (trimStart + number("Trim Offset", 0f)) * 360f, sweep, true)
                                    if (kotlin.math.abs(sweep) >= 359.9f) close()
                                }
                                "Line" -> { val progress = number("Trim Paths / Progress", 1f).coerceIn(0f, 1f); val a = number("Trim Start", 0f).coerceIn(0f, 1f); val z = number("Trim End", 1f).coerceIn(a, 1f); moveTo(left + w * a, center.y); lineTo(left + w * (a + (z - a) * progress), center.y) }
                                "Triangle" -> { moveTo(center.x, top); lineTo(left + w, top + h); lineTo(left, top + h); close() }
                                "Polygon", "Star" -> {
                                    val count = if (shapeType == "Star") number("Star Points", 5f).toInt().coerceIn(3, 128) * 2 else number("Polygon Sides", 6f).toInt().coerceIn(3, 128)
                                    for (point in 0 until count) {
                                        val angle = (point.toFloat() / count * 360f - 90f) * PI.toFloat() / 180f
                                        val radius = if (shapeType == "Star" && point % 2 == 1) number("Star Inner Radius", 0.5f).coerceIn(0.01f, 0.99f) else 1f
                                        val px = center.x + cos(angle) * w * 0.5f * radius
                                        val py = center.y + sin(angle) * h * 0.5f * radius
                                        if (point == 0) moveTo(px, py) else lineTo(px, py)
                                    }
                                    close()
                                }
                                "Custom" -> {
                                    val points = textValue("Custom Path Data").split(';').mapNotNull { token ->
                                        val pair = token.trim().split(',', ' ').filter { it.isNotBlank() }
                                        if (pair.size < 2) null else runCatching { pair[0].toFloat() to pair[1].toFloat() }.getOrNull()
                                    }.take(512)
                                    points.forEachIndexed { index, point ->
                                        val px = left + point.first.coerceIn(0f, 1f) * w
                                        val py = top + point.second.coerceIn(0f, 1f) * h
                                        if (index == 0) moveTo(px, py) else lineTo(px, py)
                                    }
                                    if (points.size >= 3) close()
                                }
                                else -> addRoundRect(androidx.compose.ui.geometry.RoundRect(left, top, left + w, top + h,
                                    androidx.compose.ui.geometry.CornerRadius(number("Corner Radius", 0f).dp.toPx())))
                            }
                            fillType = if (textValue("Fill Rule", "Non Zero") == "Even Odd") androidx.compose.ui.graphics.PathFillType.EvenOdd else androidx.compose.ui.graphics.PathFillType.NonZero
                        }
                        val skewX = kotlin.math.tan(Math.toRadians(number("Skew X", 0f).coerceIn(-89f, 89f).toDouble())).toFloat()
                        val skewY = kotlin.math.tan(Math.toRadians(number("Skew Y", 0f).coerceIn(-89f, 89f).toDouble())).toFloat()
                        val shapeMatrix = android.graphics.Matrix().apply { setTranslate(-center.x, -center.y); postSkew(skewX, skewY); postTranslate(center.x, center.y) }
                        val shapePath = baseShapePath.asAndroidPath().apply { transform(shapeMatrix) }.asComposePath()
                        val shadowEnabled = props["Shape Shadow Enabled"] == ElementValue.Bool(true)
                        val shapeBlur = number("Shape Blur", 0f).dp.toPx().coerceIn(0f, 256f)
                        if (shadowEnabled || shapeBlur > 0f) {
                            val shadowColor = runCatching { android.graphics.Color.parseColor(textValue("Shape Shadow Color", "#99000000")) }.getOrDefault(android.graphics.Color.BLACK)
                            val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                color = if (shadowEnabled) shadowColor else android.graphics.Color.TRANSPARENT
                                alpha = (255f * fillAlpha).toInt().coerceIn(0, 255)
                                val blurRadius = (number("Shape Shadow Blur", 8f).dp.toPx() + shapeBlur).coerceAtLeast(0f)
                                if (blurRadius > 0f) setShadowLayer(blurRadius, number("Shape Shadow Offset X", 0f).dp.toPx(), number("Shape Shadow Offset Y", 4f).dp.toPx(), shadowColor)
                            }
                            drawIntoCanvas { canvas ->
                                canvas.nativeCanvas.drawPath(shapePath.asAndroidPath(), shadowPaint)
                            }
                        }
                        val shapeBlendMode = when (textValue("Blend Mode Content", "Alpha")) {
                            "Add" -> androidx.compose.ui.graphics.BlendMode.Plus
                            "Screen" -> androidx.compose.ui.graphics.BlendMode.Screen
                            "Multiply", "Subtractive" -> androidx.compose.ui.graphics.BlendMode.Multiply
                            "Darken" -> androidx.compose.ui.graphics.BlendMode.Darken
                            "Lighten" -> androidx.compose.ui.graphics.BlendMode.Lighten
                            "Exclusion" -> androidx.compose.ui.graphics.BlendMode.Difference
                            "Copy" -> androidx.compose.ui.graphics.BlendMode.Src
                            else -> androidx.compose.ui.graphics.BlendMode.SrcOver
                        }
                        if (shapeType == "Line") {
                            val lineWidth = number("Stroke / Border Width", 3f).dp.toPx().coerceAtLeast(1f)
                            drawPath(shapePath, fillColor, blendMode = shapeBlendMode, style = Stroke(width = lineWidth, cap = when (textValue("Stroke Cap", "Round")) { "Butt" -> androidx.compose.ui.graphics.StrokeCap.Butt; "Square" -> androidx.compose.ui.graphics.StrokeCap.Square; else -> androidx.compose.ui.graphics.StrokeCap.Round }, join = when (textValue("Stroke Join", "Round")) { "Miter" -> androidx.compose.ui.graphics.StrokeJoin.Miter; "Bevel" -> androidx.compose.ui.graphics.StrokeJoin.Bevel; else -> androidx.compose.ui.graphics.StrokeJoin.Round }))
                        } else {
                            drawPath(shapePath, brush = gradientBrush, blendMode = shapeBlendMode)
                        }
                        val strokeWidth = number("Stroke / Border Width", 0f).dp.toPx()
                        if (strokeWidth > 0f && shapeType != "Line") {
                            val strokeStyle = textValue("Stroke Style", "Solid")
                            val customDash = textValue("Stroke Dash Pattern").split(',', ' ').mapNotNull { it.trim().toFloatOrNull()?.takeIf { value -> value > 0f } }.toFloatArray()
                            val dash = when (strokeStyle) {
                                "Dashed" -> floatArrayOf(strokeWidth * 4f, strokeWidth * 2f)
                                "Dotted" -> floatArrayOf(strokeWidth, strokeWidth * 1.8f)
                                else -> customDash.takeIf { it.size >= 2 }
                            }
                            val pathEffect = dash?.let { androidx.compose.ui.graphics.PathEffect.dashPathEffect(it, number("Stroke Dash Offset", 0f).dp.toPx()) }
                            val strokeColor = runCatching {
                                Color(android.graphics.Color.parseColor(textValue("Stroke Color", "#FFFFFFFF")))
                            }.getOrDefault(Color.White).copy(alpha = opacity)
                            val strokeAlignment = textValue("Stroke Alignment", "Center")
                            val resolvedStrokeWidth = if (strokeAlignment == "Center") strokeWidth else strokeWidth * 2f
                            val drawStroke: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
                                drawPath(shapePath, strokeColor, blendMode = shapeBlendMode, style = Stroke(width = resolvedStrokeWidth, miter = number("Stroke Miter Limit", 4f).coerceIn(1f, 100f), pathEffect = pathEffect, cap = when (textValue("Stroke Cap", "Round")) { "Butt" -> androidx.compose.ui.graphics.StrokeCap.Butt; "Square" -> androidx.compose.ui.graphics.StrokeCap.Square; else -> androidx.compose.ui.graphics.StrokeCap.Round }, join = when (textValue("Stroke Join", "Round")) { "Miter" -> androidx.compose.ui.graphics.StrokeJoin.Miter; "Bevel" -> androidx.compose.ui.graphics.StrokeJoin.Bevel; else -> androidx.compose.ui.graphics.StrokeJoin.Round }))
                            }
                            when (strokeAlignment) {
                                "Inside" -> clipPath(shapePath) { drawStroke() }
                                "Outside" -> clipPath(shapePath, androidx.compose.ui.graphics.ClipOp.Difference) { drawStroke() }
                                else -> drawStroke()
                            }
                        }
                    }
                    StudioElementType.BACKGROUND -> Unit
                    StudioElementType.AUDIO_PROVIDER,
                    StudioElementType.MIRROR_EFFECT,
                    StudioElementType.CUSTOM_SHADER,
                    StudioElementType.COMPOSITION -> Unit
                }
            }
        }
        }
    }
}

@Composable
private fun ColorWheelField(label: String, value: String, onColorChange: (String) -> Unit) {
    var showWheel by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Box(Modifier.width(42.dp).height(30.dp).background(
            runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(Color.White),
            RectangleShape
        ).border(1.dp, Color(0xFF69705F), RectangleShape))
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { showWheel = true }, modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 10.dp), shape = RectangleShape) {
            Text("PICK COLOR", color = Lime, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (showWheel) {
        var chosen by remember(value) { mutableStateOf(runCatching { android.graphics.Color.parseColor(value) }.getOrDefault(android.graphics.Color.WHITE)) }
        AlertDialog(
            onDismissRequest = { showWheel = false },
            containerColor = Panel,
            title = { Text("COLOR WHEEL", color = Color.White) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Canvas(Modifier.size(232.dp).pointerInput(Unit) {
                        detectTapGestures { point ->
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val dx = point.x - cx
                            val dy = point.y - cy
                            val radius = kotlin.math.sqrt(dx * dx + dy * dy)
                            val maxRadius = minOf(size.width, size.height).toFloat() * 0.48f
                            val hue = ((Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()) + 360.0) % 360.0).toFloat()
                            val saturation = (radius / maxRadius).coerceIn(0f, 1f)
                            val hsv = floatArrayOf(hue, saturation, 1f)
                            chosen = android.graphics.Color.HSVToColor(hsv)
                        }
                    }) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val radius = size.minDimension * 0.48f
                        for (degree in 0 until 360) {
                            val angle = degree * PI.toFloat() / 180f
                            val hueColor = android.graphics.Color.HSVToColor(floatArrayOf(degree.toFloat(), 1f, 1f))
                            drawLine(Color.White, Offset(cx, cy), Offset(cx + cos(angle) * radius, cy + sin(angle) * radius), 2.2.dp.toPx())
                            drawLine(Color(hueColor), Offset(cx + cos(angle) * radius * 0.08f, cy + sin(angle) * radius * 0.08f),
                                Offset(cx + cos(angle) * radius, cy + sin(angle) * radius), 2.2.dp.toPx())
                        }
                        drawCircle(Color.White, radius * 0.075f, Offset(cx, cy))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).background(Color(chosen), RectangleShape).border(1.dp, Color.White))
                        Spacer(Modifier.width(10.dp))
                        Text(String.format("#%08X", chosen.toLong() and 0xFFFFFFFFL), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Text("Tap inside the wheel: centre = white, outer edge = full saturation.", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onColorChange(String.format("#%08X", chosen.toLong() and 0xFFFFFFFFL))
                    showWheel = false
                }) { Text("APPLY", color = Lime) }
            },
            dismissButton = { TextButton(onClick = { showWheel = false }) { Text("CANCEL", color = Muted) } }
        )
    }
}

@Composable
private fun CompactTextInput(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label.uppercase(), color = Muted, style = MaterialTheme.typography.labelSmall)
        BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = true,
            textStyle = MaterialTheme.typography.labelMedium.copy(color = Color.White),
            modifier = Modifier.fillMaxWidth().height(40.dp)
                .border(1.dp, Color(0xFF69705F), RectangleShape)
                .padding(horizontal = 10.dp),
            decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { inner() } }
        )
    }
}

@Composable
private fun MeasureWhatDropdown(value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("MEASURE WHAT", color = Muted, style = MaterialTheme.typography.labelSmall)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().height(40.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), shape = RectangleShape) {
                Text(value.uppercase(), modifier = Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("▾", color = Lime)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(Panel)) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(option.uppercase(), style = MaterialTheme.typography.labelMedium, color = Color.White)
                        } },
                        onClick = { onSelect(option); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionDropdown(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label.uppercase(), color = Muted, style = MaterialTheme.typography.labelSmall)
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
            Text(title.uppercase(), color = Color.White, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp)
            Text(if (expanded == title) "−" else "+", color = Lime)
        }
        if (expanded == title) Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), content = content)
    }
}

@Composable
private fun LayerSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    defaultValue: Float = defaultSliderValue(label, value),
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(value.toString().let { if (value % 1f == 0f) value.toInt().toString() else String.format("%.2f", value) }, color = Lime, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        ThinSlider(
            label = label,
            value = value,
            valueRange = range,
            defaultValue = defaultValue,
            onValueChange = onChange
        )
    }
}

private fun defaultSliderValue(label: String, current: Float): Float = when (label.uppercase()) {
    "POSITION X", "POSITION Y", "ROTATION", "X", "Y", "ROT", "OFFSET X", "OFFSET Y" -> 0f
    "SCALE" -> 100f
    "OPACITY" -> 100f
    "AUDIO SENSITIVITY", "SENSITIVITY", "GAIN" -> 100f
    "SMOOTHING" -> 45f
    "RED" -> 198f
    "GREEN" -> 243f
    "BLUE" -> 106f
    "MIN FREQUENCY HZ" -> 30f
    "MAX FREQUENCY HZ" -> 16000f
    "BAR / SAMPLE COUNT" -> 64f
    "THICKNESS" -> 2f
    "SPACING" -> 1f
    "RADIUS" -> 18f
    "PARTICLE COUNT" -> 48f
    "BEAT SENSITIVITY" -> 60f
    "MANUAL BPM (0 = AUTO)" -> 0f
    "BEAT COOLDOWN" -> 150f
    "BEAT THRESHOLD" -> 1.5f
    "TEMPORAL SMOOTHING" -> 50f
    "ATTACK" -> 20f
    "RELEASE" -> 180f
    "RING BUFFER" -> 8192f
    "WAVEFORM SAMPLES" -> 2048f
    "DB FLOOR" -> -70f
    "GAIN" -> 1f
    "ATTACK MS" -> 20f
    "RELEASE MS" -> 150f
    else -> current
}

@Composable
private fun ThinSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    defaultValue: Float = valueRange.start,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val safeValue = value.coerceIn(valueRange)
    val safeDefault = defaultValue.coerceIn(valueRange)
    val currentValue by rememberUpdatedState(safeValue)
    val updateValue by rememberUpdatedState(onValueChange)
    val finishChange by rememberUpdatedState(onValueChangeFinished)
    var draggingThumb by remember { mutableStateOf(false) }
    val updateDefault by rememberUpdatedState(safeDefault)
    Canvas(
        modifier.fillMaxWidth().height(24.dp)
            .pointerInput(valueRange, safeDefault) {
                val inset = 6.dp.toPx()
                val usable = (size.width - inset * 2f).coerceAtLeast(1f)
                val thumbHitRadius = 18.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { down ->
                        val thumbX = inset + ((currentValue - valueRange.start) /
                            (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.00001f)) * usable
                        // Only the thumb starts a slider drag. Touches beside it remain ordinary
                        // scroll/tap gestures and can never change the numeric value.
                        draggingThumb = kotlin.math.abs(down.x - thumbX) <= thumbHitRadius
                    },
                    onHorizontalDrag = { change, _ ->
                        if (draggingThumb) {
                            change.consume()
                            val fraction = ((change.position.x - inset) / usable).coerceIn(0f, 1f)
                            val raw = valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
                            val snapDistance = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.00001f) * 0.0125f
                            val snapped = if (kotlin.math.abs(raw - updateDefault) <= snapDistance) updateDefault else raw
                            updateValue(snapped.coerceIn(valueRange))
                        }
                    },
                    onDragEnd = { if (draggingThumb) finishChange?.invoke(); draggingThumb = false },
                    onDragCancel = { draggingThumb = false }
                )
            }
            .pointerInput(valueRange, safeDefault) {
                detectTapGestures(onDoubleTap = { point ->
                    val inset = 6.dp.toPx()
                    val usable = (size.width - inset * 2f).coerceAtLeast(1f)
                    val thumbX = inset + ((currentValue - valueRange.start) /
                        (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.00001f)) * usable
                    if (kotlin.math.abs(point.x - thumbX) <= 18.dp.toPx()) {
                        updateValue(safeDefault)
                        finishChange?.invoke()
                    }
                })
            }
            .semantics {
                contentDescription = label.uppercase()
                stateDescription = safeValue.toString()
            }
    ) {
        val inset = 6.dp.toPx()
        val centerY = size.height / 2f
        val startX = inset
        val endX = (size.width - inset).coerceAtLeast(startX)
        val fraction = ((safeValue - valueRange.start) / (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.00001f)).coerceIn(0f, 1f)
        val thumbX = startX + (endX - startX) * fraction
        drawLine(Color(0xFF3D4436), Offset(startX, centerY), Offset(endX, centerY), 1.25.dp.toPx(), cap = StrokeCap.Round)
        if (fraction > 0f) drawLine(Lime, Offset(startX, centerY), Offset(thumbX, centerY), 1.6.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Ink, 5.5.dp.toPx(), Offset(thumbX, centerY))
        drawCircle(Lime, 4.5.dp.toPx(), Offset(thumbX, centerY))
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
            defaultValue = 0f,
            onValueChange = { seek = it },
            onValueChangeFinished = { player.seekTo(seek.toLong()) },
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
                defaultValue = 1f,
                steps = 5,
                onValueChange = { speed = it; player.setPlaybackParameters(androidx.media3.common.PlaybackParameters(it, 1f)) },
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

@Composable
private fun ExportScreen(project: StudioProject, onProjectChange: (StudioProject) -> Unit, modifier: Modifier = Modifier) {
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

private fun formatTime(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}