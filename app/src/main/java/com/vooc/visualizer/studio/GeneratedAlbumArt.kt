package com.vooc.visualizer.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deterministic, offline procedural artwork fallback for Image layers.
 * This is deliberately not described as AI generation: it creates a reusable
 * abstract cover from the layer's color and hue hints.
 */
object GeneratedAlbumArt {
    fun isForced(element: ElementDocument): Boolean =
        (element.value("Generated Album Art Hint Force Gen") as? ElementValue.Bool)?.value == true

    fun create(element: ElementDocument, size: Int = 512): Bitmap {
        val dimension = size.coerceIn(128, 1024)
        val bitmap = Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val base = runCatching {
            Color.parseColor((element.value("Generated Album Art Color") as? ElementValue.Text)?.value ?: "#FFFFFFFF")
        }.getOrDefault(Color.WHITE)
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[0] = (hsv[0] + ((element.value("Generated Album Art Hint Shift Hue") as? ElementValue.Number)?.value?.toFloat() ?: 0f) + 360f) % 360f
        hsv[1] = (hsv[1] * 0.82f + 0.18f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * 0.82f + 0.12f).coerceIn(0f, 1f)
        val primary = Color.HSVToColor(hsv)
        hsv[0] = (hsv[0] + 42f) % 360f
        hsv[1] = (hsv[1] * 0.92f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * 0.72f).coerceIn(0f, 1f)
        val secondary = Color.HSVToColor(hsv)
        val dark = Color.rgb(
            (Color.red(primary) * 0.12f).toInt(),
            (Color.green(primary) * 0.12f).toInt(),
            (Color.blue(primary) * 0.12f).toInt()
        )
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, dimension.toFloat(), dimension.toFloat(), intArrayOf(dark, primary, secondary), null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, dimension.toFloat(), dimension.toFloat(), background)
        val center = dimension * 0.5f
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 42
            style = Paint.Style.STROKE
            strokeWidth = dimension * 0.012f
        }
        for (i in 0..6) {
            val radius = dimension * (0.12f + i * 0.075f)
            canvas.drawCircle(center, center, radius, accent)
        }
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(
                center * 0.78f, center * 0.72f, dimension * 0.46f,
                intArrayOf(Color.argb(110, 255, 255, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(center * 0.78f, center * 0.72f, dimension * 0.46f, glow)
        val wave = Path()
        for (i in 0..180) {
            val x = dimension * i / 180f
            val y = center + sin(i * 0.105) .toFloat() * dimension * 0.11f +
                sin(i * 0.027 + 1.2).toFloat() * dimension * 0.065f
            if (i == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
        }
        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 190
            style = Paint.Style.STROKE
            strokeWidth = dimension * 0.009f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(wave, wavePaint)
        val noText = (element.value("Generated Album Art Hint No Text") as? ElementValue.Bool)?.value == true
        if (!noText) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                alpha = 210
                textSize = dimension * 0.075f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                }
            canvas.drawText("VOOC", dimension * 0.09f, dimension * 0.88f, textPaint)
        }
        background.shader = null
        glow.shader = null
        return bitmap
    }
}
