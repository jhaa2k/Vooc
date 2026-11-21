package com.vooc.visualizer.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

internal fun resolveMirrorAxisPosition(origin: Float, normalizedPosition: Float, extent: Float): Float {
    val safeExtent = extent.takeIf { it.isFinite() && it > 0f } ?: 1f
    val safeOrigin = origin.takeIf { it.isFinite() } ?: safeExtent * 0.5f
    val safePosition = normalizedPosition.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
    return (safeOrigin + (safePosition - 0.5f) * safeExtent).coerceIn(-safeExtent, safeExtent * 2f)
}

/** Shared bitmap mirror/reflection renderer for preview and export. */
object MirrorEffectProcessor {
    fun process(source: Bitmap, element: ElementDocument, targetWidth: Int = source.width, targetHeight: Int = source.height): Bitmap {
        fun number(name: String, fallback: Float): Float = when (val value = element.value(name)) {
            is ElementValue.Number -> value.value.toFloat()
            is ElementValue.Integer -> value.value.toFloat()
            else -> fallback
        }
        fun text(name: String, fallback: String = ""): String =
            (element.value(name) as? ElementValue.Text)?.value ?: fallback
        fun bool(name: String, fallback: Boolean = false): Boolean =
            (element.value(name) as? ElementValue.Bool)?.value ?: fallback

        if (!bool("Mirror Enabled", true)) return source
        val width = targetWidth.coerceAtLeast(1)
        val height = targetHeight.coerceAtLeast(1)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val origin = text("Mirror Origin", "Center")
        val customCenter = element.value("Mirror Center") as? ElementValue.Vector2
        val baseAxisX = when (origin) {
            "Left" -> 0f
            "Right" -> width.toFloat()
            "Custom" -> (((customCenter?.x ?: 0.5).toFloat()).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f) * width
            else -> width * 0.5f
        }
        val baseAxisY = when (origin) {
            "Top" -> 0f
            "Bottom" -> height.toFloat()
            "Custom" -> (((customCenter?.y ?: 0.5).toFloat()).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f) * height
            else -> height * 0.5f
        }
        val axis = text("Mirror Axis", "Horizontal")
        val position = number("Mirror Position", 0.5f)
        // Position offsets the selected origin; neutral 0.5 preserves edge/custom anchors.
        var axisX = if (axis == "Horizontal" || axis == "Both")
            resolveMirrorAxisPosition(baseAxisX, position, width.toFloat()) else baseAxisX
        var axisY = if (axis == "Vertical")
            resolveMirrorAxisPosition(baseAxisY, position, height.toFloat()) else baseAxisY
        axisX = (axisX + number("Mirror Offset X", 0f) * width).coerceIn(-width.toFloat(), width * 2f)
        axisY = (axisY + number("Mirror Offset Y", 0f) * height).coerceIn(-height.toFloat(), height * 2f)

        val mirrorGap = number("Mirror Gap", 0f).coerceIn(0f, 512f) * width / 1080f
        val mirrorSpacing = number("Mirror Spacing", 0f).coerceIn(-1024f, 1024f) * width / 1080f
        val reflectionCount = number("Reflection Count", 2f).toInt().coerceIn(1, 16)
        val reflectionOpacity = number("Reflection Opacity", 1f).coerceIn(0f, 1f)
        val mirrorFlip = bool("Mirror Flip")
        val mirrorRotation = number("Mirror Rotation", 0f)
        val mirrorScale = number("Mirror Scale", 1f).coerceIn(0f, 10f)
        val tintStrength = number("Mirror Color Tint", 0f).coerceIn(0f, 1f)
        val tintColor = runCatching { Color.parseColor(text("Mirror Tint Color", "#FFFFFFFF")) }
            .getOrDefault(Color.WHITE)
        val blend = when (text("Mirror Blend Mode", "Alpha")) {
            "Add" -> PorterDuff.Mode.ADD
            "Screen" -> PorterDuff.Mode.SCREEN
            "Multiply" -> PorterDuff.Mode.MULTIPLY
            "Overlay" -> PorterDuff.Mode.OVERLAY
            else -> PorterDuff.Mode.SRC_OVER
        }
        val workingSource = if (bool("Mirror Use Source Alpha", true)) source else {
            source.copy(Bitmap.Config.ARGB_8888, true).also { opaque ->
                val pixels = IntArray(opaque.width * opaque.height)
                opaque.getPixels(pixels, 0, opaque.width, 0, 0, opaque.width, opaque.height)
                for (i in pixels.indices) pixels[i] = pixels[i] or (0xFF shl 24)
                opaque.setPixels(pixels, 0, opaque.width, 0, 0, opaque.width, opaque.height)
            }
        }
        val originalPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (bool("Mirror Original", true)) {
            val originalMatrix = Matrix().apply {
                setScale(width.toFloat() / workingSource.width, height.toFloat() / workingSource.height)
            }
            canvas.drawBitmap(workingSource, originalMatrix, originalPaint)
        }

        fun drawReflection(horizontal: Boolean, vertical: Boolean, diagonal: Boolean, copyIndex: Int) {
            val copyAlpha = (255f * reflectionOpacity.pow(copyIndex + 1)).toInt().coerceIn(0, 255)
            if (copyAlpha <= 0) return
            val direction = if (mirrorFlip) -1f else 1f
            canvas.save()
            when {
                diagonal -> {
                    val clip = Path().apply {
                        if (mirrorFlip) {
                            moveTo(0f, 0f); lineTo(0f, height.toFloat()); lineTo(width.toFloat(), height.toFloat()); close()
                        } else {
                            moveTo(0f, 0f); lineTo(width.toFloat(), 0f); lineTo(width.toFloat(), height.toFloat()); close()
                        }
                    }
                    canvas.clipPath(clip)
                }
                horizontal && !vertical -> {
                    if (mirrorFlip) canvas.clipRect(0f, 0f, axisX - mirrorGap * 0.5f, height.toFloat())
                    else canvas.clipRect(axisX + mirrorGap * 0.5f, 0f, width.toFloat(), height.toFloat())
                }
                vertical && !horizontal -> {
                    if (mirrorFlip) canvas.clipRect(0f, 0f, width.toFloat(), axisY - mirrorGap * 0.5f)
                    else canvas.clipRect(0f, axisY + mirrorGap * 0.5f, width.toFloat(), height.toFloat())
                }
                else -> {
                    if (mirrorFlip) canvas.clipRect(0f, 0f, axisX - mirrorGap * 0.5f, axisY - mirrorGap * 0.5f)
                    else canvas.clipRect(axisX + mirrorGap * 0.5f, axisY + mirrorGap * 0.5f, width.toFloat(), height.toFloat())
                }
            }

            val shift = (mirrorGap + mirrorSpacing) * copyIndex
            val matrix = Matrix()
            when {
                diagonal -> {
                    matrix.setRotate(-45f, axisX, axisY)
                    matrix.postScale(-mirrorScale, mirrorScale, axisX, axisY)
                    matrix.postRotate(45f + mirrorRotation, axisX, axisY)
                }
                else -> {
                    matrix.setScale(if (horizontal) -mirrorScale else mirrorScale, if (vertical) -mirrorScale else mirrorScale, axisX, axisY)
                    matrix.postRotate(mirrorRotation, axisX, axisY)
                }
            }
            when {
                horizontal && !vertical -> matrix.postTranslate(direction * shift, 0f)
                vertical && !horizontal -> matrix.postTranslate(0f, direction * shift)
                else -> matrix.postTranslate(direction * shift, direction * shift)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = copyAlpha
                xfermode = if (blend == PorterDuff.Mode.SRC_OVER) null else PorterDuffXfermode(blend)
                if (tintStrength > 0f) {
                    val tintAlpha = (tintStrength * 255f).toInt().coerceIn(0, 255)
                    val tint = (tintColor and 0x00FFFFFF) or (tintAlpha shl 24)
                    colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_ATOP)
                }
            }
            canvas.drawBitmap(workingSource, matrix, paint)
            canvas.restore()
        }

        for (copy in 0 until reflectionCount) {
            when (axis) {
                "Vertical" -> drawReflection(horizontal = false, vertical = true, diagonal = false, copyIndex = copy)
                "Both" -> {
                    drawReflection(horizontal = true, vertical = false, diagonal = false, copyIndex = copy)
                    drawReflection(horizontal = false, vertical = true, diagonal = false, copyIndex = copy)
                }
                "Diagonal" -> drawReflection(horizontal = true, vertical = false, diagonal = true, copyIndex = copy)
                else -> drawReflection(horizontal = true, vertical = false, diagonal = false, copyIndex = copy)
            }
        }

        if (bool("Mirror Seamless Edge", true)) {
            val feather = number("Mirror Feather", 0f).coerceAtLeast(0f) * width / 1080f
            val softness = number("Mirror Edge Softness", 0f).coerceIn(0f, 1f)
            val featherWidth = maxOf(feather, min(width, height) * 0.08f * softness)
            if (featherWidth > 0f) {
                val pixels = IntArray(width * height)
                output.getPixels(pixels, 0, width, 0, 0, width, height)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val distance = when (axis) {
                            "Vertical" -> abs(y - axisY)
                            "Diagonal" -> abs(y - x - (axisY - axisX)) * 0.7071f
                            "Both" -> min(abs(x - axisX), abs(y - axisY))
                            else -> abs(x - axisX)
                        }
                        val t = (distance / featherWidth).coerceIn(0f, 1f)
                        val smooth = t * t * (3f - 2f * t)
                        val index = y * width + x
                        val pixel = pixels[index]
                        val alpha = (Color.alpha(pixel) * smooth).toInt().coerceIn(0, 255)
                        pixels[index] = (pixel and 0x00FFFFFF) or (alpha shl 24)
                    }
                }
                output.setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }

        if (workingSource !== source && !workingSource.isRecycled) workingSource.recycle()
        return output
    }
}
