package com.vooc.visualizer.studio

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Vooc preset interchange and best-effort Avee VIZ reader.
 *
 * Vooc files are complete ProjectStore JSON documents with a .voocpreset extension.
 * Avee's VIZ schema is proprietary/undocumented; this reader accepts JSON VIZ files and
 * ZIP-based VIZ packages containing scene.json or another JSON scene, then maps the
 * supported visual objects to Vooc layers. Unsupported Avee effects are reported.
 */
data class PresetImportResult(
    val project: StudioProject,
    val sourceFormat: String,
    val warnings: List<String>
)

object PresetFileCodec {
    private const val MAX_SCENE_BYTES = 24 * 1024 * 1024

    fun exportVooc(project: StudioProject): ByteArray =
        ProjectStore.encode(project).toByteArray(Charsets.UTF_8)

    fun import(input: InputStream, fileName: String): PresetImportResult {
        val bytes = input.use { it.readBytes() }
        require(bytes.isNotEmpty()) { "The selected preset file is empty." }
        require(bytes.size <= MAX_SCENE_BYTES * 2) { "Preset file is too large to import safely." }

        val lowerName = fileName.lowercase(Locale.ROOT)
        val jsonBytes = if (looksLikeZip(bytes)) extractSceneJson(bytes) else bytes
        val json = jsonBytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        require(json.startsWith("{")) {
            "This .viz file is not a readable JSON/ZIP Avee template. Keep the original file; this format cannot be decoded by Vooc yet."
        }
        val root = JSONObject(json)

        // Native Vooc documents are lossless: preserve all supported properties and assets.
        ProjectStore.decode(root.toString())?.let { native ->
            return PresetImportResult(native, "VOOC", emptyList())
        }

        require(lowerName.endsWith(".viz") || root.has("composition") || root.has("scene")) {
            "This JSON is not a recognized Vooc preset or Avee VIZ scene."
        }

        val layers = mutableListOf<VisualLayer>()
        val warnings = linkedSetOf<String>()
        collectAveeObjects(root, layers, warnings)
        val unique = layers.distinctBy { it.id }.take(256)
        require(unique.isNotEmpty()) {
            "Avee scene data was found, but Vooc could not identify supported visual elements. The template may use unsupported Avee object types."
        }
        val projectName = root.optString("name", root.optString("title", fileName.substringBeforeLast('.')))
            .ifBlank { "Imported Avee Template" }.take(80)
        val project = StudioProject(
            name = projectName,
            width = root.optInt("width", 1920).coerceIn(160, 7680),
            height = root.optInt("height", 1080).coerceIn(160, 7680),
            fps = root.optInt("fps", 30).coerceIn(1, 120),
            layers = listOf(VisualLayer(1L, LayerKind.BACKGROUND)) + unique.mapIndexed { index, layer ->
                layer.copy(id = index + 2L)
            }
        )
        warnings += "Avee VIZ compatibility is best-effort. Unsupported shaders, modifiers, animation bindings and some asset references may not be reproduced exactly."
        return PresetImportResult(project, "AVEE_VIZ", warnings.toList())
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    private fun extractSceneJson(bytes: ByteArray): ByteArray {
        var fallback: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !entry.name.lowercase(Locale.ROOT).endsWith(".json")) continue
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_SCENE_BYTES) { "Avee scene JSON is larger than the safe import limit." }
                    output.write(buffer, 0, count)
                }
                val data = output.toByteArray()
                val entryName = entry.name.lowercase(Locale.ROOT)
                if (entryName.endsWith("scene.json") || entryName == "scene.json") return data
                if (fallback == null && data.isNotEmpty() && data[0].toInt().toChar() == '{') fallback = data
            }
        }
        return fallback ?: throw IllegalArgumentException("No scene.json or readable JSON scene was found inside this .viz package.")
    }

    private fun collectAveeObjects(value: Any?, output: MutableList<VisualLayer>, warnings: MutableSet<String>) {
        when (value) {
            is JSONObject -> {
                val mapped = mapAveeObject(value)
                if (mapped != null) output += mapped
                else {
                    val type = sequenceOf("type", "elementType", "class", "typeName", "name")
                        .map { value.optString(it, "") }.firstOrNull { it.isNotBlank() }.orEmpty()
                    if (type.isNotBlank() && value.length() > 2) warnings += "Skipped unsupported Avee object: $type"
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (child is JSONObject || child is JSONArray) collectAveeObjects(child, output, warnings)
                }
            }
            is JSONArray -> for (i in 0 until value.length()) collectAveeObjects(value.opt(i), output, warnings)
        }
    }

    private fun mapAveeObject(obj: JSONObject): VisualLayer? {
        val typeText = sequenceOf("type", "elementType", "class", "typeName", "name", "element")
            .map { obj.optString(it, "") }.firstOrNull {
                it.contains("bar", true) || it.contains("wave", true) || it.contains("circle", true) ||
                it.contains("ring", true) || it.contains("particle", true) || it.contains("text", true) ||
                it.contains("image", true) || it.contains("background", true) || it.contains("spectrum", true) ||
                it.contains("scope", true) || it.contains("shape", true)
            } ?: return null
        val t = typeText.lowercase(Locale.ROOT)
        val kind = when {
            "particle" in t -> LayerKind.PARTICLES
            "background" in t -> LayerKind.BACKGROUND
            "image" in t -> LayerKind.IMAGE
            "text" in t -> LayerKind.TEXT
            "wave" in t || "oscillo" in t -> LayerKind.WAVE
            "circle" in t || "radial" in t -> LayerKind.CIRCLE
            "ring" in t -> LayerKind.RING
            "scope" in t -> LayerKind.SCOPE
            "shape" in t -> LayerKind.SHAPE
            "spectrum" in t || "bar" in t || "equalizer" in t -> LayerKind.BARS
            else -> return null
        }
        val props = obj.optJSONObject("properties") ?: obj.optJSONObject("props") ?: obj
        fun number(vararg names: String, default: Float): Float {
            for (name in names) {
                if (props.has(name) && !props.isNull(name)) {
                    val raw = props.opt(name)
                    val n = when (raw) {
                        is Number -> raw.toFloat()
                        is String -> raw.toFloatOrNull()
                        else -> null
                    }
                    if (n != null && n.isFinite()) return n
                }
            }
            return default
        }
        val rawColor = props.opt("color") ?: props.opt("Color")
        val color = when (rawColor) {
            is Number -> rawColor.toLong()
            is String -> runCatching {
                val cleaned = rawColor.removePrefix("#").removePrefix("0x")
                cleaned.toLong(16).let { if (cleaned.length <= 6) it or 0xFF000000L else it }
            }.getOrDefault(0xFFC6F36AL)
            else -> 0xFFC6F36AL
        }
        val text = props.optString("text", props.optString("textContent", "VOOC"))
        val image = props.optString("imageUri", props.optString("image", ""))
        return VisualLayer(
            id = (obj.optLong("id", 0L).takeIf { it > 0L } ?: (outputSafeId(typeText))),
            kind = kind,
            name = obj.optString("name", typeText).take(80),
            enabled = props.optBoolean("enabled", props.optBoolean("visible", true)),
            x = number("x", "posX", "positionX", default = 0f).coerceIn(-10000f, 10000f),
            y = number("y", "posY", "positionY", default = 0f).coerceIn(-10000f, 10000f),
            scale = number("scale", "scaleX", default = 100f).coerceIn(1f, 1000f),
            rotation = number("rotation", "angle", default = 0f).coerceIn(-3600f, 3600f),
            opacity = number("opacity", "alpha", default = 100f).coerceIn(0f, 100f),
            color = color,
            sensitivity = number("sensitivity", "audioSensitivity", default = 100f).coerceIn(0f, 500f),
            smoothing = number("smoothing", "smooth", default = 45f).coerceIn(0f, 100f),
            measureWhat = props.optString("measureWhat", props.optString("measure", "Spectrum")),
            barCount = number("barCount", "count", "samples", default = 64f).toInt().coerceIn(8, 256),
            thickness = number("thickness", "strokeWidth", default = 2f).coerceIn(0.1f, 100f),
            spacing = number("spacing", "gap", default = 1f).coerceIn(0f, 100f),
            radius = number("radius", "size", default = 18f).coerceIn(1f, 1000f),
            particleCount = number("particleCount", "count", default = 48f).toInt().coerceIn(4, 256),
            textContent = text,
            imageUri = image,
            gradientColor = color
        )
    }

    private fun outputSafeId(seed: String): Long = (seed.hashCode().toLong() and 0x7FFFFFFF).coerceAtLeast(2L)
}
