package com.vooc.visualizer.studio

import android.graphics.Bitmap
import kotlin.math.min
import kotlin.math.pow

/**
 * Shared background-image processing for preview and export.
 * Keeps pixel adjustments deterministic across both renderers.
 */
object BackgroundImageProcessor {
    fun process(source: Bitmap, element: ElementDocument, maskSource: Bitmap? = null): Bitmap {
        fun number(name: String, fallback: Float): Float = when (val value = element.value(name)) {
            is ElementValue.Number -> value.value.toFloat()
            is ElementValue.Integer -> value.value.toFloat()
            else -> fallback
        }
        fun enabled(name: String): Boolean = (element.value(name) as? ElementValue.Bool)?.value == true

        val blurRadius = number("Blur Radius", 24f).coerceIn(0f, 256f)
        val blurQuality = number("Background Blur Quality", 1f).coerceIn(0.1f, 4f)
        val edgeBlur = enabled("Edge Blur (Image Fill)")
        val brightness = number("Background Brightness", 1f).coerceIn(0f, 4f)
        val contrast = number("Background Contrast", 1f).coerceIn(0f, 4f)
        val saturation = number("Background Saturation", 1f).coerceIn(0f, 4f)
        val grayscale = enabled("Background Grayscale")
        val invert = enabled("Background Invert")
        val pixelAdjustments = brightness != 1f || contrast != 1f || saturation != 1f || grayscale || invert

        val outputMask = maskSource?.let {
            if (it.width == source.width && it.height == source.height) it
            else Bitmap.createScaledBitmap(it, source.width, source.height, true)
        }
        val maskPixels = outputMask?.let { mask ->
            IntArray(mask.width * mask.height).also { values ->
                mask.getPixels(values, 0, mask.width, 0, 0, mask.width, mask.height)
            }
        }
        if (blurRadius <= 0f && !edgeBlur && !pixelAdjustments && maskPixels == null) {
            if (outputMask != null && outputMask !== maskSource) outputMask.recycle()
            return source
        }

        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val width = output.width
        val height = output.height
        if (width <= 0 || height <= 0) return output

        val pixels = IntArray(width * height)
        output.getPixels(pixels, 0, width, 0, 0, width, height)
        val edgeWidth = if (edgeBlur) {
            val maximumEdgeWidth = (min(width, height) * 0.45f).coerceAtLeast(1f)
            (blurRadius * 2f * blurQuality).coerceIn(1f, maximumEdgeWidth)
        } else 0f

        if (pixelAdjustments || edgeBlur || maskPixels != null) {
            val maskStrength = number("Mask Strength", 1f).coerceIn(0f, 1f)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val pixel = pixels[index]
                    var alpha = android.graphics.Color.alpha(pixel).toFloat()
                    if (maskPixels != null) {
                        val maskAlpha = android.graphics.Color.alpha(maskPixels[index]).toFloat() / 255f
                        alpha *= 1f - (1f - maskAlpha) * maskStrength
                    }
                    var red = android.graphics.Color.red(pixel).toFloat() / 255f
                    var green = android.graphics.Color.green(pixel).toFloat() / 255f
                    var blue = android.graphics.Color.blue(pixel).toFloat() / 255f

                    if (edgeBlur && edgeWidth > 0f) {
                        val distance = min(
                            min(x + 1f, width - x.toFloat()),
                            min(y + 1f, height - y.toFloat())
                        )
                        val edge = (distance / edgeWidth).coerceIn(0f, 1f)
                        val smoothEdge = edge * edge * (3f - 2f * edge)
                        alpha *= smoothEdge
                    }

                    if (pixelAdjustments) {
                        red = ((red - 0.5f) * contrast + 0.5f) * brightness
                        green = ((green - 0.5f) * contrast + 0.5f) * brightness
                        blue = ((blue - 0.5f) * contrast + 0.5f) * brightness
                        if (grayscale || saturation != 1f) {
                            val luminance = (0.2126f * red + 0.7152f * green + 0.0722f * blue).coerceIn(0f, 1f)
                            val amount = if (grayscale) 0f else saturation
                            red = luminance + (red - luminance) * amount
                            green = luminance + (green - luminance) * amount
                            blue = luminance + (blue - luminance) * amount
                        }
                        if (invert) {
                            red = 1f - red
                            green = 1f - green
                            blue = 1f - blue
                        }
                    }

                    pixels[index] = android.graphics.Color.argb(
                        alpha.toInt().coerceIn(0, 255),
                        (red.coerceIn(0f, 1f) * 255f).toInt(),
                        (green.coerceIn(0f, 1f) * 255f).toInt(),
                        (blue.coerceIn(0f, 1f) * 255f).toInt()
                    )
                }
            }
            output.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        if (outputMask != null && outputMask !== maskSource && !outputMask.isRecycled) outputMask.recycle()

        if (blurRadius <= 0f) return output

        // Higher quality preserves more source detail at the same blur radius.
        val reduction = (1f / (1f + blurRadius / (12f * blurQuality))).coerceIn(0.06f, 1f)
        val reducedWidth = (width * reduction).toInt().coerceAtLeast(1)
        val reducedHeight = (height * reduction).toInt().coerceAtLeast(1)
        if (reducedWidth >= width && reducedHeight >= height) return output
        val reduced = Bitmap.createScaledBitmap(output, reducedWidth, reducedHeight, true)
        val blurred = Bitmap.createScaledBitmap(reduced, width, height, true)
        if (reduced !== output && reduced !== blurred && !reduced.isRecycled) reduced.recycle()
        if (output !== blurred && !output.isRecycled) output.recycle()
        return blurred
    }
}
