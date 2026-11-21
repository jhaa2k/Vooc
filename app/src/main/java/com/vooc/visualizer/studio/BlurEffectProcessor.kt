package com.vooc.visualizer.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared CPU blur renderer used by both the live studio preview and video export. */
object BlurEffectProcessor {
    private fun number(element: ElementDocument, name: String, fallback: Float): Float =
        when (val value = element.value(name)) {
            is ElementValue.Number -> value.value.toFloat()
            is ElementValue.Integer -> value.value.toFloat()
            else -> fallback
        }.let { if (it.isFinite()) it else fallback }

    private fun text(element: ElementDocument, name: String, fallback: String): String =
        (element.value(name) as? ElementValue.Text)?.value ?: fallback

    private fun enabled(element: ElementDocument, name: String): Boolean =
        (element.value(name) as? ElementValue.Bool)?.value == true

    private fun vector(element: ElementDocument, name: String, x: Float, y: Float): Pair<Float, Float> {
        val v = element.value(name) as? ElementValue.Vector2 ?: return x to y
        return v.x.toFloat().let { if (it.isFinite()) it else x } to
            v.y.toFloat().let { if (it.isFinite()) it else y }
    }

    fun process(source: Bitmap, element: ElementDocument, maskSource: Bitmap? = null): Bitmap {
        val radius = (number(element, "Blur Radius", 2f) *
            number(element, "Blur Multiplier", 4.1f) +
            number(element, "Blur Spread", 0f)).coerceIn(0f, 256f)
        val quality = number(element, "Blur Quality", 1f).coerceIn(0.25f, 2f)
        val downScale = (1f / (1f + radius / (28f * quality))).coerceIn(0.12f, 1f)
        val workW = max(1, (source.width * downScale).toInt())
        val workH = max(1, (source.height * downScale).toInt())
        val working = if (workW != source.width || workH != source.height)
            Bitmap.createScaledBitmap(source, workW, workH, true) else source
        val original = IntArray(workW * workH)
        working.getPixels(original, 0, workW, 0, 0, workW, workH)
        val workRadius = (radius * downScale).coerceIn(0f, 48f)
        val passes = number(element, "Blur Passes", 2f).toInt().coerceIn(1, 8)
        val algorithm = text(element, "Blur Algorithm", "Gaussian")
        val direction = Math.toRadians(number(element, "Blur Direction", 0f).toDouble())
        val center = vector(element, "Blur Center", 0.5f, 0.5f)
        val zoom = number(element, "Blur Zoom Amount", 0f).coerceIn(0f, 4f)
        var blurred = original
        if (workRadius > 0.05f) {
            repeat(passes) { pass ->
                blurred = blurPass(blurred, workW, workH, workRadius / sqrt(passes.toFloat()),
                    algorithm, direction, center, zoom, pass)
            }
        }
        val alphaOnly = enabled(element, "Blur Alpha Only")
        val preserveEdges = enabled(element, "Blur Preserve Edges")
        val threshold = number(element, "Blur Threshold", 0f).coerceIn(0f, 1f)
        val scaledMask = maskSource?.let {
            if (it.width != workW || it.height != workH) Bitmap.createScaledBitmap(it, workW, workH, true) else it
        }
        val mask = scaledMask?.let { bitmap ->
            IntArray(workW * workH).also { pixels -> bitmap.getPixels(pixels, 0, workW, 0, 0, workW, workH) }
        }
        if (scaledMask != null && scaledMask !== maskSource) scaledMask.recycle()
        val maskMode = text(element, "Blur Mask Mode", "None")
        val maskStrength = number(element, "Blur Mask Strength", 1f).coerceIn(0f, 1f)
        val strength = number(element, "Blur Blend Strength", 1f).coerceIn(0f, 1f)
        val finalPixels = IntArray(original.size)
        for (i in original.indices) {
            val src = original[i]
            val blur = blurred[i]
            val x = i % workW
            val y = i / workW
            var amount = strength
            if (preserveEdges) {
                val left = luminance(original[y * workW + max(0, x - 1)])
                val right = luminance(original[y * workW + min(workW - 1, x + 1)])
                val up = luminance(original[max(0, y - 1) * workW + x])
                val down = luminance(original[min(workH - 1, y + 1) * workW + x])
                val edge = (abs(left - right) + abs(up - down)) / 510f
                val edgeLimit = if (threshold > 0f) threshold else 0.12f
                amount *= (1f - edge / edgeLimit).coerceIn(0f, 1f)
            }
            if (threshold > 0f && !preserveEdges) {
                val delta = abs(luminance(src) - luminance(blur)) / 255f
                amount *= (delta / threshold).coerceIn(0f, 1f)
            }
            if (mask != null && maskMode != "None") {
                val mp = mask[i]
                val alpha = Color.alpha(mp) / 255f
                val lum = luminance(mp) / 255f
                val maskValue = when (maskMode) {
                    "Alpha" -> alpha
                    "Luminance" -> lum
                    "Inverted Alpha" -> 1f - alpha
                    "Inverted Luminance" -> 1f - lum
                    else -> 1f
                }
                amount *= 1f - (1f - maskValue) * maskStrength
            }
            val a = mix(Color.alpha(src), Color.alpha(blur), amount)
            val r = if (alphaOnly) Color.red(src) else mix(Color.red(src), Color.red(blur), amount)
            val g = if (alphaOnly) Color.green(src) else mix(Color.green(src), Color.green(blur), amount)
            val b = if (alphaOnly) Color.blue(src) else mix(Color.blue(src), Color.blue(blur), amount)
            finalPixels[i] = Color.argb(a, r, g, b)
        }
        val processedSmall = Bitmap.createBitmap(workW, workH, Bitmap.Config.ARGB_8888)
        processedSmall.setPixels(finalPixels, 0, workW, 0, 0, workW, workH)
        val processed = if (workW != source.width || workH != source.height)
            Bitmap.createScaledBitmap(processedSmall, source.width, source.height, true) else processedSmall
        if (processedSmall !== processed) processedSmall.recycle()
        if (working !== source) working.recycle()

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val blend = when (text(element, "Blend Mode Content", "Alpha")) {
            "Add" -> PorterDuff.Mode.ADD
            "Screen" -> PorterDuff.Mode.SCREEN
            "Multiply", "Subtractive" -> PorterDuff.Mode.MULTIPLY
            "Darken" -> PorterDuff.Mode.DARKEN
            "Lighten" -> PorterDuff.Mode.LIGHTEN
            "Exclusion" -> PorterDuff.Mode.XOR
            "Copy" -> PorterDuff.Mode.SRC
            else -> PorterDuff.Mode.SRC_OVER
        }
        if (enabled(element, "Show Unblurred Content Under")) canvas.drawBitmap(source, 0f, 0f, null)
        val baseTint = parseColor(text(element, "Color", "#FFFFFFFF"), Color.WHITE)
        val layerOpacity = (number(element, "Opacity", 100f).coerceIn(0f, 100f) *
            number(element, "Blur Blend Strength", 1f).coerceIn(0f, 1f) * 2.55f).toInt()
        for (index in 1..3) {
            val (sxRaw, syRaw) = vector(element, "${index}layer Scale", if (index == 1) 1f else 0f, if (index == 1) 1f else 0f)
            val sx = sxRaw.coerceIn(0f, 10f)
            val sy = syRaw.coerceIn(0f, 10f)
            if (sx <= 0f || sy <= 0f) continue
            val tint = parseColor(text(element, "${index}layer Color", "#FFFFFFFF"), Color.WHITE)
            val combinedTint = Color.argb(
                Color.alpha(baseTint) * Color.alpha(tint) / 255,
                Color.red(baseTint) * Color.red(tint) / 255,
                Color.green(baseTint) * Color.green(tint) / 255,
                Color.blue(baseTint) * Color.blue(tint) / 255
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = layerOpacity.coerceIn(0, 255)
                if (combinedTint != Color.WHITE) colorFilter = PorterDuffColorFilter(combinedTint, PorterDuff.Mode.MULTIPLY)
                if (blend != PorterDuff.Mode.SRC_OVER) xfermode = PorterDuffXfermode(blend)
            }
            val halfW = source.width * sx / 2f
            val halfH = source.height * sy / 2f
            canvas.drawBitmap(processed, null, RectF(source.width / 2f - halfW, source.height / 2f - halfH,
                source.width / 2f + halfW, source.height / 2f + halfH), paint)
        }
        if (enabled(element, "Show Unblurred Content")) canvas.drawBitmap(source, 0f, 0f, null)
        applyBloom(canvas, processed, element, radius)
        if (processed !== source) processed.recycle()
        return output
    }

    private fun blurPass(input: IntArray, w: Int, h: Int, radius: Float, algorithm: String,
        direction: Double, center: Pair<Float, Float>, zoom: Float, pass: Int): IntArray {
        val output = IntArray(input.size)
        val step = radius.coerceAtLeast(0.5f) * (1f + pass * 0.18f)
        val cx = center.first * (w - 1)
        val cy = center.second * (h - 1)
        val cosA = cos(direction).toFloat()
        val sinA = sin(direction).toFloat()
        val taps = when (algorithm) {
            "Kawase" -> 5
            "Directional", "Radial" -> 7
            "Zoom" -> 9
            else -> 9
        }
        for (y in 0 until h) for (x in 0 until w) {
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            var totalWeight = 0
            for (tap in 0 until taps) {
                val t = when (algorithm) {
                    "Directional", "Radial" -> (tap - 3) / 3f
                    "Zoom" -> (tap - 4) / 4f
                    else -> 0f
                }
                val sx: Float
                val sy: Float
                when (algorithm) {
                    "Directional" -> {
                        sx = x + cosA * step * t
                        sy = y + sinA * step * t
                    }
                    "Radial" -> {
                        sx = x + (x - cx) * step * t / max(w, h)
                        sy = y + (y - cy) * step * t / max(w, h)
                    }
                    "Zoom" -> {
                        sx = x + (x - cx) * t * step * zoom / max(w, h)
                        sy = y + (y - cy) * t * step * zoom / max(w, h)
                    }
                    "Kawase" -> {
                        val dx = if (tap == 0 || tap == 2) -step else if (tap == 1 || tap == 3) step else 0f
                        val dy = if (tap == 0 || tap == 1) -step else if (tap == 2 || tap == 3) step else 0f
                        sx = x + dx
                        sy = y + dy
                    }
                    else -> {
                        val dx = when (tap) {
                            0 -> -1f; 1 -> 1f; 5, 6 -> if (tap == 5) -0.7f else 0.7f
                            7, 8 -> if (tap == 7) -0.7f else 0.7f
                            else -> 0f
                        }
                        val dy = when (tap) {
                            2 -> -1f; 3 -> 1f; 5, 7 -> -0.7f; 6, 8 -> 0.7f
                            else -> 0f
                        }
                        sx = x + dx * step
                        sy = y + dy * step
                    }
                }
                val pixel = if (algorithm == "Kawase" && tap == 4) input[y * w + x]
                    else sample(input, w, h, sx, sy)
                val weight = if (algorithm == "Gaussian") when (tap) { 0, 1, 2, 3 -> 2; 4 -> 4; else -> 1 } else 1
                sumA += Color.alpha(pixel) * weight
                sumR += Color.red(pixel) * weight
                sumG += Color.green(pixel) * weight
                sumB += Color.blue(pixel) * weight
                totalWeight += weight
            }
            output[y * w + x] = Color.argb(sumA / totalWeight, sumR / totalWeight,
                sumG / totalWeight, sumB / totalWeight)
        }
        return output
    }

    private fun sample(pixels: IntArray, w: Int, h: Int, x: Float, y: Float): Int =
        pixels[y.toInt().coerceIn(0, h - 1) * w + x.toInt().coerceIn(0, w - 1)]

    private fun luminance(color: Int): Float =
        0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)

    private fun mix(a: Int, b: Int, amount: Float): Int =
        (a + (b - a) * amount).toInt().coerceIn(0, 255)

    private fun parseColor(value: String, fallback: Int): Int =
        runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    private fun applyBloom(canvas: Canvas, bitmap: Bitmap, element: ElementDocument, blurRadius: Float) {
        val strength = number(element, "Blur Bloom Strength", 0f).coerceIn(0f, 2f)
        if (strength <= 0f) return
        val tint = parseColor(text(element, "Blur Glow Color", "#FFFFFFFF"), Color.WHITE)
        val threshold = number(element, "Blur Bloom Threshold", 0.72f).coerceIn(0f, 1f)
        val scale = min(1f, 512f / max(bitmap.width, bitmap.height))
        val bloomW = max(1, (bitmap.width * scale).toInt())
        val bloomH = max(1, (bitmap.height * scale).toInt())
        val small = if (bloomW != bitmap.width || bloomH != bitmap.height) Bitmap.createScaledBitmap(bitmap, bloomW, bloomH, true) else bitmap
        val pixels = IntArray(bloomW * bloomH)
        small.getPixels(pixels, 0, bloomW, 0, 0, bloomW, bloomH)
        if (small !== bitmap) small.recycle()
        for (i in pixels.indices) {
            val source = pixels[i]
            val lum = luminance(source) / 255f
            val highlight = ((lum - threshold) / (1f - threshold).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
            val alpha = (Color.alpha(source) * highlight).toInt().coerceIn(0, 255)
            pixels[i] = Color.argb(alpha, Color.red(source), Color.green(source), Color.blue(source))
        }
        var glowPixels = pixels
        val glowRadius = (blurRadius * scale * 0.65f).coerceIn(2f, 18f)
        repeat(2) { pass -> glowPixels = blurPass(glowPixels, bloomW, bloomH, glowRadius / sqrt(2f), "Gaussian", 0.0, 0.5f to 0.5f, 0f, pass) }
        val glow = Bitmap.createBitmap(bloomW, bloomH, Bitmap.Config.ARGB_8888)
        glow.setPixels(glowPixels, 0, bloomW, 0, 0, bloomW, bloomH)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (strength * 120f).toInt().coerceIn(0, 240)
            colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
        canvas.drawBitmap(glow, null, RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), paint)
        glow.recycle()
    }
}
