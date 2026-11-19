package com.vooc.visualizer.studio

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, Android-first project document.
 *
 * This is the persistent contract for the editor. Optional values deliberately have safe defaults so
 * projects created by earlier Vooc builds remain readable as the document evolves.
 */
data class ProjectDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val aspectRatio: String = "16:9",
    val durationMs: Long = 0L,
    val backgroundArgb: Long = 0xFF080A08,
    val audioUri: String = "",
    val audioOffsetMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val compositions: List<CompositionDocument> = listOf(CompositionDocument()),
    val assets: List<ProjectAsset> = emptyList(),
    val keyframes: List<ProjectKeyframe> = emptyList(),
    val export: ExportDocument = ExportDocument(),
    val editor: EditorDocument = EditorDocument()
) {
    companion object { const val CURRENT_SCHEMA_VERSION = 2 }
}

data class CompositionDocument(
    val id: String = "final",
    val name: String = "Final Composition",
    val layerIds: List<Long> = emptyList(),
    val isFinal: Boolean = true,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val blendMode: String = "NORMAL",
    val introFadeMs: Long = 0L,
    val outroFadeMs: Long = 0L,
    val introFadeColor: Long = 0xFF000000
)

data class ProjectAsset(
    val id: String,
    val type: String,
    val uri: String,
    val displayName: String = "",
    val mimeType: String = "",
    val copiedIntoProject: Boolean = false,
    val missing: Boolean = false
)

data class ProjectKeyframe(
    val id: String,
    val layerId: Long,
    val property: String,
    val timeMs: Long,
    val value: Float,
    val interpolation: String = "LINEAR",
    val control1: Float? = null,
    val control2: Float? = null
)

data class ExportDocument(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val videoCodec: String = "video/avc",
    val videoBitrate: Int = 8_000_000,
    val audioBitrate: Int = 192_000,
    val includeAudio: Boolean = true,
    val outputName: String = "vooc-export",
    val format: String = "MP4"
)

data class EditorDocument(
    val mode: String = "BEGINNER",
    val previewQuality: String = "BALANCED",
    val showGrid: Boolean = false,
    val snapToGuides: Boolean = true,
    val autosaveEnabled: Boolean = true,
    val autosaveIntervalSeconds: Int = 30
)

internal fun ProjectDocument.toJson(): JSONObject {
    val compositionArray = JSONArray()
    compositions.forEach { c ->
        compositionArray.put(JSONObject()
            .put("id", c.id).put("name", c.name).put("layerIds", JSONArray(c.layerIds))
            .put("isFinal", c.isFinal).put("visible", c.visible).put("opacity", c.opacity)
            .put("blendMode", c.blendMode).put("introFadeMs", c.introFadeMs)
            .put("outroFadeMs", c.outroFadeMs).put("introFadeColor", c.introFadeColor))
    }
    val assetArray = JSONArray()
    assets.forEach { a ->
        assetArray.put(JSONObject().put("id", a.id).put("type", a.type).put("uri", a.uri)
            .put("displayName", a.displayName).put("mimeType", a.mimeType)
            .put("copiedIntoProject", a.copiedIntoProject).put("missing", a.missing))
    }
    val keyframeArray = JSONArray()
    keyframes.forEach { k ->
        val item = JSONObject().put("id", k.id).put("layerId", k.layerId)
            .put("property", k.property).put("timeMs", k.timeMs).put("value", k.value)
            .put("interpolation", k.interpolation)
        k.control1?.let { item.put("control1", it) }
        k.control2?.let { item.put("control2", it) }
        keyframeArray.put(item)
    }
    return JSONObject().put("schemaVersion", schemaVersion).put("aspectRatio", aspectRatio)
        .put("durationMs", durationMs).put("backgroundArgb", backgroundArgb)
        .put("audioUri", audioUri).put("audioOffsetMs", audioOffsetMs)
        .put("trimStartMs", trimStartMs).put("trimEndMs", trimEndMs)
        .put("compositions", compositionArray).put("assets", assetArray).put("keyframes", keyframeArray)
        .put("export", JSONObject().put("width", export.width).put("height", export.height)
            .put("fps", export.fps).put("videoCodec", export.videoCodec)
            .put("videoBitrate", export.videoBitrate).put("audioBitrate", export.audioBitrate)
            .put("includeAudio", export.includeAudio).put("outputName", export.outputName)
            .put("format", export.format))
        .put("editor", JSONObject().put("mode", editor.mode).put("previewQuality", editor.previewQuality)
            .put("showGrid", editor.showGrid).put("snapToGuides", editor.snapToGuides)
            .put("autosaveEnabled", editor.autosaveEnabled)
            .put("autosaveIntervalSeconds", editor.autosaveIntervalSeconds))
}

internal fun projectDocumentFromJson(root: JSONObject): ProjectDocument {
    val version = root.optInt("schemaVersion", 1)
    val compositionsJson = root.optJSONArray("compositions") ?: JSONArray()
    val compositions = (0 until compositionsJson.length()).mapNotNull { index ->
        val c = compositionsJson.optJSONObject(index) ?: return@mapNotNull null
        val ids = c.optJSONArray("layerIds") ?: JSONArray()
        CompositionDocument(
            id = c.optString("id", "composition-$index"),
            name = c.optString("name", "Composition"),
            layerIds = (0 until ids.length()).mapNotNull { ids.optLong(it).takeIf { id -> id > 0L } },
            isFinal = c.optBoolean("isFinal", false),
            visible = c.optBoolean("visible", true),
            opacity = c.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
            blendMode = c.optString("blendMode", "NORMAL"),
            introFadeMs = c.optLong("introFadeMs", 0L).coerceAtLeast(0L),
            outroFadeMs = c.optLong("outroFadeMs", 0L).coerceAtLeast(0L),
            introFadeColor = c.optLong("introFadeColor", 0xFF000000)
        )
    }.ifEmpty { listOf(CompositionDocument()) }
    val assetsJson = root.optJSONArray("assets") ?: JSONArray()
    val assets = (0 until assetsJson.length()).mapNotNull { index ->
        val a = assetsJson.optJSONObject(index) ?: return@mapNotNull null
        ProjectAsset(a.optString("id", "asset-$index"), a.optString("type", "UNKNOWN"),
            a.optString("uri", ""), a.optString("displayName", ""), a.optString("mimeType", ""),
            a.optBoolean("copiedIntoProject", false), a.optBoolean("missing", false))
    }
    val keyframesJson = root.optJSONArray("keyframes") ?: JSONArray()
    val keyframes = (0 until keyframesJson.length()).mapNotNull { index ->
        val k = keyframesJson.optJSONObject(index) ?: return@mapNotNull null
        ProjectKeyframe(k.optString("id", "keyframe-$index"), k.optLong("layerId"),
            k.optString("property", ""), k.optLong("timeMs").coerceAtLeast(0L),
            k.optDouble("value", 0.0).toFloat(), k.optString("interpolation", "LINEAR"),
            if (k.has("control1")) k.optDouble("control1").toFloat() else null,
            if (k.has("control2")) k.optDouble("control2").toFloat() else null)
    }
    val e = root.optJSONObject("export") ?: JSONObject()
    val editorJson = root.optJSONObject("editor") ?: JSONObject()
    return ProjectDocument(
        schemaVersion = ProjectDocument.CURRENT_SCHEMA_VERSION,
        aspectRatio = root.optString("aspectRatio", "16:9"),
        durationMs = root.optLong("durationMs", 0L).coerceAtLeast(0L),
        backgroundArgb = root.optLong("backgroundArgb", 0xFF080A08),
        audioUri = root.optString("audioUri", ""),
        audioOffsetMs = root.optLong("audioOffsetMs", 0L),
        trimStartMs = root.optLong("trimStartMs", 0L).coerceAtLeast(0L),
        trimEndMs = root.optLong("trimEndMs", 0L).coerceAtLeast(0L),
        compositions = compositions,
        assets = assets,
        keyframes = keyframes,
        export = ExportDocument(
            width = e.optInt("width", 1920).coerceIn(160, 7680),
            height = e.optInt("height", 1080).coerceIn(160, 7680),
            fps = e.optInt("fps", 30).coerceIn(1, 120),
            videoCodec = e.optString("videoCodec", "video/avc"),
            videoBitrate = e.optInt("videoBitrate", 8_000_000).coerceIn(250_000, 100_000_000),
            audioBitrate = e.optInt("audioBitrate", 192_000).coerceIn(32_000, 512_000),
            includeAudio = e.optBoolean("includeAudio", true),
            outputName = e.optString("outputName", "vooc-export"),
            format = e.optString("format", "MP4")
        ),
        editor = EditorDocument(
            mode = editorJson.optString("mode", "BEGINNER"),
            previewQuality = editorJson.optString("previewQuality", "BALANCED"),
            showGrid = editorJson.optBoolean("showGrid", false),
            snapToGuides = editorJson.optBoolean("snapToGuides", true),
            autosaveEnabled = editorJson.optBoolean("autosaveEnabled", true),
            autosaveIntervalSeconds = editorJson.optInt("autosaveIntervalSeconds", 30).coerceIn(5, 300)
        )
    )
}
