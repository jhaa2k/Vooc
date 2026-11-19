package com.vooc.visualizer.studio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class LayerKind(val title: String) {
    BARS("Bars"), BARSH("Mirrored bars"), CIRCLE("Circle spectrum"), MULTIRING("Multi ring"),
    CONCENTRIC("Concentric"), RING("Ring"), PULSE("Pulse"), WAVE("Wave"), WAVEC("Waveform"),
    SCOPE("Scope"), DOTS("Dots"), LINES("Lines"), BLOCKS("Blocks"), GRID("Grid"),
    PARTICLES("Particles"), SHAPE("Shape"), TEXT("Text"), IMAGE("Image"), BACKGROUND("Background")
}

data class VisualLayer(
    val id: Long,
    val kind: LayerKind,
    val name: String = kind.title,
    val enabled: Boolean = true,
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 100f,
    val rotation: Float = 0f,
    val opacity: Float = 100f,
    val color: Long = 0xFFC6F36A,
    val sensitivity: Float = 100f,
    val smoothing: Float = 45f,
    val measureWhat: String = "Spectrum",
    val channel: String = "MIX",
    val barCount: Int = 64,
    val thickness: Float = 2f,
    val spacing: Float = 1f,
    val radius: Float = 18f,
    val particleCount: Int = 48,
    val textContent: String = "VOOC",
    val imageUri: String = "",
    val gradientColor: Long = 0xFF55D6FF
)

data class StudioProject(
    val name: String = "Untitled Project",
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val layers: List<VisualLayer> = listOf(
        VisualLayer(1, LayerKind.BACKGROUND),
        VisualLayer(2, LayerKind.BARS)
    ),
    val document: ProjectDocument = ProjectDocument()
)

object ProjectStore {
    private const val FILE_NAME = "vooc-project.json"

    fun save(context: Context, project: StudioProject) {
        val root = JSONObject().put("name", project.name).put("width", project.width)
            .put("height", project.height).put("fps", project.fps)
        val array = JSONArray()
        project.layers.forEach { layer ->
            array.put(JSONObject().put("id", layer.id).put("kind", layer.kind.name)
                .put("name", layer.name).put("enabled", layer.enabled).put("x", layer.x)
                .put("y", layer.y).put("scale", layer.scale).put("rotation", layer.rotation)
                .put("opacity", layer.opacity).put("color", layer.color)
                .put("sensitivity", layer.sensitivity).put("smoothing", layer.smoothing)
                .put("measureWhat", layer.measureWhat).put("channel", layer.channel)
                .put("barCount", layer.barCount).put("thickness", layer.thickness)
                .put("spacing", layer.spacing).put("radius", layer.radius)
                .put("particleCount", layer.particleCount).put("textContent", layer.textContent)
                .put("imageUri", layer.imageUri).put("gradientColor", layer.gradientColor))
        }
        root.put("layers", array).put("document", project.document.toJson())
        context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { it.write(root.toString().toByteArray()) }
    }

    fun load(context: Context): StudioProject? = runCatching {
        val root = JSONObject(context.openFileInput(FILE_NAME).bufferedReader().use { it.readText() })
        val array = root.getJSONArray("layers")
        val layers = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            VisualLayer(
                id = o.getLong("id"),
                kind = LayerKind.valueOf(o.getString("kind")),
                name = o.optString("name", "Layer"),
                enabled = o.optBoolean("enabled", true),
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                scale = o.optDouble("scale", 100.0).toFloat(),
                rotation = o.optDouble("rotation", 0.0).toFloat(),
                opacity = o.optDouble("opacity", 100.0).toFloat(),
                color = o.optLong("color", 0xFFC6F36A),
                sensitivity = o.optDouble("sensitivity", 100.0).toFloat(),
                smoothing = o.optDouble("smoothing", 45.0).toFloat(),
                measureWhat = o.optString("measureWhat", "Spectrum"),
                channel = o.optString("channel", "MIX"),
                barCount = o.optInt("barCount", 64).coerceIn(8, 256),
                thickness = o.optDouble("thickness", 2.0).toFloat(),
                spacing = o.optDouble("spacing", 1.0).toFloat(),
                radius = o.optDouble("radius", 18.0).toFloat(),
                particleCount = o.optInt("particleCount", 48).coerceIn(4, 256),
                textContent = o.optString("textContent", "VOOC"),
                imageUri = o.optString("imageUri", ""),
                gradientColor = o.optLong("gradientColor", 0xFF55D6FF)
            )
        }
        StudioProject(root.optString("name", "Untitled Project"), root.optInt("width", 1920),
            root.optInt("height", 1080), root.optInt("fps", 30), layers,
            projectDocumentFromJson(root.optJSONObject("document") ?: JSONObject()))
    }.getOrNull()
    fun encode(project: StudioProject): String {
        val root = JSONObject().put("name", project.name).put("width", project.width)
            .put("height", project.height).put("fps", project.fps)
        val array = JSONArray()
        project.layers.forEach { layer ->
            array.put(JSONObject().put("id", layer.id).put("kind", layer.kind.name)
                .put("name", layer.name).put("enabled", layer.enabled).put("x", layer.x)
                .put("y", layer.y).put("scale", layer.scale).put("rotation", layer.rotation)
                .put("opacity", layer.opacity).put("color", layer.color)
                .put("sensitivity", layer.sensitivity).put("smoothing", layer.smoothing)
                .put("measureWhat", layer.measureWhat).put("channel", layer.channel)
                .put("barCount", layer.barCount).put("thickness", layer.thickness)
                .put("spacing", layer.spacing).put("radius", layer.radius)
                .put("particleCount", layer.particleCount).put("textContent", layer.textContent)
                .put("imageUri", layer.imageUri).put("gradientColor", layer.gradientColor))
        }
        return root.put("layers", array).put("document", project.document.toJson()).toString(2)
    }

    fun decode(json: String): StudioProject? = runCatching {
        val root = JSONObject(json)
        val array = root.getJSONArray("layers")
        val layers = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            VisualLayer(
                id = o.getLong("id"),
                kind = LayerKind.valueOf(o.getString("kind")),
                name = o.optString("name", "Layer"),
                enabled = o.optBoolean("enabled", true),
                x = o.optDouble("x", 0.0).toFloat(),
                y = o.optDouble("y", 0.0).toFloat(),
                scale = o.optDouble("scale", 100.0).toFloat(),
                rotation = o.optDouble("rotation", 0.0).toFloat(),
                opacity = o.optDouble("opacity", 100.0).toFloat(),
                color = o.optLong("color", 0xFFC6F36A),
                sensitivity = o.optDouble("sensitivity", 100.0).toFloat(),
                smoothing = o.optDouble("smoothing", 45.0).toFloat(),
                measureWhat = o.optString("measureWhat", "Spectrum"),
                channel = o.optString("channel", "MIX"),
                barCount = o.optInt("barCount", 64).coerceIn(8, 256),
                thickness = o.optDouble("thickness", 2.0).toFloat(),
                spacing = o.optDouble("spacing", 1.0).toFloat(),
                radius = o.optDouble("radius", 18.0).toFloat(),
                particleCount = o.optInt("particleCount", 48).coerceIn(4, 256),
                textContent = o.optString("textContent", "VOOC"),
                imageUri = o.optString("imageUri", ""),
                gradientColor = o.optLong("gradientColor", 0xFF55D6FF)
            )
        }
        StudioProject(root.optString("name", "Untitled Project"), root.optInt("width", 1920),
            root.optInt("height", 1080), root.optInt("fps", 30), layers,
            projectDocumentFromJson(root.optJSONObject("document") ?: JSONObject()))
    }.getOrNull()

}


/** Persistent user template library. Templates are stored as versioned project JSON in app-private preferences. */
object TemplateStore {
    private const val PREFS = "vooc_template_library"
    private const val KEY = "templates"

    fun load(context: Context): List<StudioProject> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            ProjectStore.decode(array.getJSONObject(index).toString())
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, project: StudioProject): List<StudioProject> {
        val name = project.name.trim().ifBlank { "Untitled Template" }
        val next = load(context).filterNot { it.name.equals(name, ignoreCase = true) } + project.copy(name = name)
        persist(context, next)
        return next
    }

    fun delete(context: Context, name: String): List<StudioProject> {
        val next = load(context).filterNot { it.name.equals(name, ignoreCase = true) }
        persist(context, next)
        return next
    }

    private fun persist(context: Context, templates: List<StudioProject>) {
        val array = JSONArray()
        templates.forEach { template -> array.put(JSONObject(ProjectStore.encode(template))) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString()).apply()
    }
}
