package com.vooc.visualizer.studio

import android.graphics.Path
import android.graphics.RectF

/**
 * Parses a normalized SVG path (M/L/H/V/Q/C/Z, absolute or relative)
 * and maps its 0..1 coordinates into the supplied destination rectangle.
 */
internal fun isSupportedNormalizedImageShapePath(data: String): Boolean {
    if (data.isBlank() || data.length > 16384) return false
    // Whitelist the complete input, not only recognized tokens: unsupported SVG
    // commands (for example A/S/T) must never be silently skipped.
    if (data.any { !(it.isWhitespace() || it == ',' || it in "MmLlHhVvQqCcZz0123456789.+-eE") }) return false
    val matches = Regex("[MmLlHhVvQqCcZz]|[-+]?(?:\\d*\\.)?\\d+(?:[eE][-+]?\\d+)?").findAll(data).toList()
    return matches.isNotEmpty() && matches.size <= 2048 &&
        data.none { it.isLetter() && it !in "MmLlHhVvQqCcZzEe" }
}

fun buildNormalizedImageShapePath(data: String, bounds: RectF): Path? {
    if (!isSupportedNormalizedImageShapePath(data)) return null
    val tokens = Regex("[MmLlHhVvQqCcZz]|[-+]?(?:\\d*\\.)?\\d+(?:[eE][-+]?\\d+)?").findAll(data).map { it.value }.toList()
    val path = Path()
    var i = 0
    var command = ' '
    var x = 0f
    var y = 0f
    var startX = 0f
    var startY = 0f
    var hasMove = false
    fun number(): Float? = tokens.getOrNull(i++)?.toFloatOrNull()
    fun px(v: Float) = bounds.left + v * bounds.width()
    fun py(v: Float) = bounds.top + v * bounds.height()
    fun point(relative: Boolean): Pair<Float, Float>? {
        val a = number() ?: return null
        val b = number() ?: return null
        return if (relative) (x + a) to (y + b) else a to b
    }
    return runCatching {
        while (i < tokens.size) {
            if (tokens[i].length == 1 && tokens[i][0].isLetter()) command = tokens[i++][0]
            if (command == ' ') return null
            val relative = command.isLowerCase()
            when (command.uppercaseChar()) {
                'M', 'L' -> {
                    val p = point(relative) ?: return null
                    x = p.first; y = p.second
                    if (command.uppercaseChar() == 'M' && !hasMove) {
                        path.moveTo(px(x), py(y)); startX = x; startY = y; hasMove = true
                        command = if (relative) 'l' else 'L'
                    } else path.lineTo(px(x), py(y))
                }
                'H' -> { val v = number() ?: return null; x = if (relative) x + v else v; path.lineTo(px(x), py(y)) }
                'V' -> { val v = number() ?: return null; y = if (relative) y + v else v; path.lineTo(px(x), py(y)) }
                'Q' -> {
                    val c = point(relative) ?: return null
                    val p = point(relative) ?: return null
                    path.quadTo(px(c.first), py(c.second), px(p.first), py(p.second)); x = p.first; y = p.second
                }
                'C' -> {
                    val c1 = point(relative) ?: return null
                    val c2 = point(relative) ?: return null
                    val p = point(relative) ?: return null
                    path.cubicTo(px(c1.first), py(c1.second), px(c2.first), py(c2.second), px(p.first), py(p.second)); x = p.first; y = p.second
                }
                'Z' -> { path.close(); x = startX; y = startY; command = ' ' }
                else -> return null
            }
            if (i < tokens.size && tokens[i].length == 1 && tokens[i][0].isLetter() && tokens[i][0] !in "MmLlHhVvQqCcZz") return null
        }
        if (!hasMove) null else { path.close(); path }
    }.getOrNull()
}
