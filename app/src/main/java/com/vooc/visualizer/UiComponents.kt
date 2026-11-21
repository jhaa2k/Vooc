package com.vooc.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun ColorWheelField(label: String, value: String, onColorChange: (String) -> Unit) {
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
internal fun CompactTextInput(label: String, value: String, onValueChange: (String) -> Unit) {
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
internal fun MeasureWhatDropdown(value: String, options: List<String>, onSelect: (String) -> Unit) {
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
internal fun OptionDropdown(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
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
internal fun EditorSection(title: String, expanded: String, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title.uppercase(), color = Color.White, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp)
            Text(if (expanded == title) "−" else "+", color = Lime)
        }
        if (expanded == title) Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), content = content)
    }
}

internal fun defaultSliderResetValue(label: String, range: ClosedFloatingPointRange<Float>): Float =
    when (label.uppercase()) {
        "BEAT SENSITIVITY" -> 60f
        "MANUAL BPM (0 = AUTO)" -> 0f
        "POSITION X", "POSITION Y", "ROTATION" -> 0f
        "SCALE", "OPACITY", "AUDIO SENSITIVITY" -> 100f
        "SMOOTHING" -> 45f
        "RED" -> 198f
        "GREEN" -> 243f
        "BLUE" -> 106f
        "BAR / SAMPLE COUNT" -> 64f
        "THICKNESS" -> 2f
        "SPACING" -> 1f
        "RADIUS" -> 18f
        "PARTICLE COUNT" -> 48f
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
        else -> range.start
    }.coerceIn(range)

@Composable
internal fun LayerSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    resetValue: Float = defaultSliderResetValue(label, range),
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(value.toInt().toString(), color = Lime, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        ThinSlider(
            label = label,
            value = value,
            valueRange = range,
            resetValue = resetValue,
            onValueChange = onChange
        )
    }
}

@Composable
internal fun ThinSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    resetValue: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onDoubleTapReset: (() -> Unit)? = null,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val safeValue = value.coerceIn(valueRange)
    val safeReset = resetValue.coerceIn(valueRange)
    val updateValue by rememberUpdatedState(onValueChange)
    val resetAction by rememberUpdatedState(onDoubleTapReset ?: { onValueChange(safeReset) })
    Canvas(
        modifier.fillMaxWidth().height(24.dp)
            .pointerInput(valueRange, safeReset) {
                fun valueAt(x: Float): Float {
                    val inset = 6.dp.toPx()
                    val usable = (size.width - inset * 2f).coerceAtLeast(1f)
                    val fraction = ((x - inset) / usable).coerceIn(0f, 1f)
                    val raw = valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
                    if (steps <= 0) return raw.coerceIn(valueRange)
                    val count = steps + 1
                    val step = (valueRange.endInclusive - valueRange.start) / count
                    return (valueRange.start + kotlin.math.round((raw - valueRange.start) / step) * step).coerceIn(valueRange)
                }
                detectTapGestures(onTap = { updateValue(valueAt(it.x)) }, onDoubleTap = { resetAction() })
            }
            .pointerInput(valueRange) {
                fun update(x: Float) {
                    val inset = 6.dp.toPx()
                    val usable = (size.width - inset * 2f).coerceAtLeast(1f)
                    val fraction = ((x - inset) / usable).coerceIn(0f, 1f)
                    updateValue((valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)).coerceIn(valueRange))
                }
                detectDragGestures(
                    onDragStart = { update(it.x) },
                    onDrag = { change, _ -> change.consume(); update(change.position.x) },
                    onDragEnd = { onValueChangeFinished?.invoke() }
                )
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
