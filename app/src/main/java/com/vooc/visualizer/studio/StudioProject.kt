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
    val smoothing: Float = 45f
)

data class StudioProject(
    val name: String = "Untitled Project",
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val layers: List<VisualLayer> = listOf(
        VisualLayer(1, LayerKind.BACKGROUND),
        VisualLayer(2, LayerKind.BARS)
    )
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
                .put("sensitivity", layer.sensitivity).put("smoothing", layer.smoothing))
        }
        root.put("layers", array)
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
                smoothing = o.optDouble("smoothing", 45.0).toFloat()
            )
        }
        StudioProject(root.optString("name", "Untitled Project"), root.optInt("width", 1920),
            root.optInt("height", 1080), root.optInt("fps", 30), layers)
    }.getOrNull()
}
