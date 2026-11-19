package com.vooc.visualizer.viz

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Typed representation of the verified Avee VIZ container. Unknown properties remain lossless. */
data class VizProperty(
    val name: String,
    val rawValue: Any?,
    val typeCode: String,
    val order: Int?,
    val tag: String?,
    val decodedValue: Any?
)

data class VizNode(
    val type: String,
    val properties: Map<String, VizProperty>,
    val children: List<VizNode>
) {
    fun property(name: String): VizProperty? = properties[name]
    fun value(name: String): Any? = properties[name]?.decodedValue
}

data class VizScene(
    val version: String,
    val template: Int,
    val compositions: List<VizNode>,
    /** Embedded assets keyed by the numeric ZIP entry name, without decoding or recompression. */
    val assets: Map<String, ByteArray>,
    val unknownElementTypes: Set<String>
)

object VizLoader {
    private const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 32 * 1024 * 1024

    fun load(input: InputStream): VizScene {
        val extracted = input.use { source ->
            val all = source.readBytes()
            require(all.isNotEmpty() && all.size <= MAX_ARCHIVE_BYTES) { "VIZ archive is empty or exceeds 64 MB." }
            extract(all)
        }
        val root = JSONObject(extracted.first.toString(Charsets.UTF_8).trimStart('\uFEFF'))
        require(root.optString("objType") == "Root") { "Unsupported VIZ scene root. Expected objType=Root." }
        val compositionsJson = root.optJSONArray("compositions")
            ?: throw IllegalArgumentException("VIZ scene has no compositions array.")
        val unknown = linkedSetOf<String>()
        val compositions = buildList {
            for (i in 0 until compositionsJson.length()) {
                val item = compositionsJson.optJSONObject(i) ?: continue
                add(parseNode(item, unknown))
            }
        }
        require(compositions.isNotEmpty()) { "VIZ scene contains no readable compositions." }
        return VizScene(
            version = root.optString("ver", ""),
            template = root.optInt("template", 0),
            compositions = compositions,
            assets = extracted.second,
            unknownElementTypes = unknown
        )
    }

    private fun extract(archive: ByteArray): Pair<ByteArray, Map<String, ByteArray>> {
        var scene: ByteArray? = null
        val assets = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var size = 0
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= MAX_ENTRY_BYTES) { "VIZ entry exceeds 32 MB: ${entry.name}" }
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                if (entry.name.substringAfterLast('/') == "scene.json") {
                    scene = bytes
                } else if (entry.name.substringAfterLast('/').matches(Regex("[0-9]+"))) {
                    assets[entry.name.substringAfterLast('/')] = bytes
                }
            }
        }
        return (scene ?: throw IllegalArgumentException("VIZ archive does not contain scene.json.")) to assets
    }

    private fun parseNode(json: JSONObject, unknown: MutableSet<String>): VizNode {
        val type = json.optString("objType", "Unknown")
        if (type !in setOf("Composition", "AudioProvider", "Image", "Bars", "Particles", "BlurEffect", "RgbSplitEffect")) {
            unknown += type
        }
        val properties = linkedMapOf<String, VizProperty>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val p = json.optJSONObject(name) ?: continue
            if (!p.has("v") || !p.has("t")) continue
            val typeCode = p.optString("t")
            val raw = p.opt("v")
            val decoded = if (typeCode.startsWith("_child")) {
                val nested = linkedMapOf<String, Any?>()
                val nestedKeys = p.keys()
                while (nestedKeys.hasNext()) {
                    val nestedName = nestedKeys.next()
                    val nestedProp = p.optJSONObject(nestedName) ?: continue
                    if (nestedProp.has("v") && nestedProp.has("t")) {
                        nested[nestedName] = decode(nestedProp.opt("v"), nestedProp.optString("t"))
                    }
                }
                mapOf("choice" to raw, "nested" to nested)
            } else decode(raw, typeCode)
            properties[name] = VizProperty(
                name = name,
                rawValue = raw,
                typeCode = typeCode,
                order = if (p.has("o")) p.optInt("o") else null,
                tag = p.optString("tag").takeIf { it.isNotEmpty() },
                decodedValue = decoded
            )
        }
        val childrenJson = json.optJSONArray("elements") ?: JSONArray()
        val children = buildList {
            for (i in 0 until childrenJson.length()) {
                val child = childrenJson.optJSONObject(i) ?: continue
                add(parseNode(child, unknown))
            }
        }
        return VizNode(type, properties, children)
    }

    private fun decode(raw: Any?, typeString: String): Any? {
        val code = typeString.substringBefore(' ').trim()
        return when (code) {
            "f" -> raw.toString().toFloatOrNull()
            "i", "ih" -> raw.toString().toIntOrNull() ?: raw
            "b" -> raw == true || raw.toString() == "1"
            "f2" -> raw.toString().trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }.take(2)
            "crgba", "crgb", "crgb_hl" -> raw.toString().toLongOrNull()?.let(::argbToRgba)
            "chsla4f" -> raw.toString().trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }.take(4)
            "mvarf", "mvarf2" -> {
                val parts = raw.toString().trim().split(Regex("\\s+"))
                mapOf("mode" to parts.firstOrNull().orEmpty(), "args" to parts.drop(1).mapNotNull { it.toFloatOrNull() })
            }
            "img", "pimg" -> {
                val (kind, _, id) = raw.toString().partition(":")
                mapOf("kind" to kind, "id" to id)
            }
            else -> raw // Enum/text/id and future types are retained exactly.
        }
    }

    /** Avee stores signed 32-bit ARGB; mask before shifting to avoid sign extension. */
    private fun argbToRgba(value: Long): List<Float> {
        val n = value.toInt()
        return listOf(
            ((n ushr 16) and 255) / 255f,
            ((n ushr 8) and 255) / 255f,
            (n and 255) / 255f,
            ((n ushr 24) and 255) / 255f
        )
    }
}
