package com.vooc.visualizer.studio

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Multi-project repository. Each project is an independent JSON document written atomically.
 * The repository never trusts a display name as a file path.
 */
data class ProjectEntry(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val modifiedAt: Long,
    val fileSizeBytes: Long
) {
    val aspectRatioLabel: String
        get() = when {
            height == 0 -> "CUSTOM"
            width.toFloat() / height.toFloat() > 1.70f -> "LANDSCAPE"
            width.toFloat() / height.toFloat() < 0.80f -> "PORTRAIT"
            else -> "SQUARE / PORTRAIT"
        }
}

object ProjectRepository {
    private const val DIRECTORY = "projects"
    private const val EXTENSION = ".vooc.json"
    private val idPattern = Regex("^[a-f0-9-]{36}$")

    private fun directory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { if (!exists()) mkdirs() }

    private fun fileFor(context: Context, id: String): File? {
        if (!idPattern.matches(id)) return null
        return File(directory(context), "$id$EXTENSION")
    }

    /** Lists valid project documents, newest modified first. Invalid files are ignored, not deleted. */
    fun list(context: Context): List<ProjectEntry> = directory(context).listFiles()
        ?.asSequence()
        ?.filter { it.isFile && it.name.endsWith(EXTENSION) && idPattern.matches(it.name.removeSuffix(EXTENSION)) }
        ?.mapNotNull { file ->
            runCatching {
                val project = ProjectStore.decode(file.readText(Charsets.UTF_8)) ?: return@runCatching null
                ProjectEntry(
                    id = file.name.removeSuffix(EXTENSION),
                    name = project.name,
                    width = project.width,
                    height = project.height,
                    fps = project.fps,
                    modifiedAt = file.lastModified(),
                    fileSizeBytes = file.length()
                )
            }.getOrNull()
        }
        ?.sortedByDescending { it.modifiedAt }
        ?.toList()
        ?: emptyList()

    /** Creates a new independent project. Existing project files are never overwritten. */
    fun create(context: Context, project: StudioProject): ProjectEntry? {
        val id = UUID.randomUUID().toString()
        return write(context, id, project)
    }

    /** Saves an existing project and preserves its stable repository ID. */
    fun save(context: Context, id: String, project: StudioProject): ProjectEntry? =
        write(context, id, project)

    fun load(context: Context, id: String): StudioProject? {
        val file = fileFor(context, id) ?: return null
        if (!file.isFile) return null
        return runCatching { ProjectStore.decode(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    fun rename(context: Context, id: String, newName: String): ProjectEntry? {
        val name = newName.trim().take(80)
        if (name.isBlank()) return null
        val current = load(context, id) ?: return null
        return write(context, id, current.copy(name = name))
    }

    /** Duplicate creates a new ID and a separate file; edits never mutate the source project. */
    fun duplicate(context: Context, id: String, requestedName: String? = null): ProjectEntry? {
        val source = load(context, id) ?: return null
        val duplicateName = requestedName?.trim()?.take(80)
            ?.takeIf { it.isNotBlank() } ?: "${source.name} Copy"
        return create(context, source.copy(name = duplicateName))
    }

    fun delete(context: Context, id: String): Boolean {
        val file = fileFor(context, id) ?: return false
        return !file.exists() || file.delete()
    }

    private fun write(context: Context, id: String, project: StudioProject): ProjectEntry? {
        val target = fileFor(context, id) ?: return null
        val safeProject = project.copy(
            name = project.name.trim().take(80).ifBlank { "Untitled Project" },
            width = project.width.coerceIn(160, 7680),
            height = project.height.coerceIn(160, 7680),
            fps = project.fps.coerceIn(1, 120)
        )
        val temporary = File(target.parentFile, "$id.tmp")
        return runCatching {
            temporary.writeText(ProjectStore.encode(safeProject), Charsets.UTF_8)
            // Parse the exact bytes before replacing the previous valid project.
            check(ProjectStore.decode(temporary.readText(Charsets.UTF_8)) != null) {
                "Project validation failed before save"
            }
            if (target.exists() && !target.delete()) error("Could not replace existing project")
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            ProjectEntry(
                id = id,
                name = safeProject.name,
                width = safeProject.width,
                height = safeProject.height,
                fps = safeProject.fps,
                modifiedAt = target.lastModified(),
                fileSizeBytes = target.length()
            )
        }.getOrElse {
            temporary.delete()
            null
        }
    }
}
