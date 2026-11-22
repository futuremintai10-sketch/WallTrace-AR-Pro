package com.walltrace.ar.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.walltrace.ar.image.ImageImportException
import com.walltrace.ar.image.ImageLoader
import com.walltrace.ar.model.SketchAdjustments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Stores projects in app-private storage: one JSON index plus a copy of each imported image.
 * Works fully offline; no account or cloud storage.
 */
class ProjectRepository(context: Context) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "projects")
    private val imagesDir = File(root, "images")
    private val indexFile = File(root, "index.json")
    private val mutex = Mutex()

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _projects.value = readIndex()
                .filter { imageFile(it).exists() }
                .sortedByDescending { it.updatedAt }
        }
    }

    fun imageFile(project: Project): File = File(imagesDir, project.imageFileName)

    /** Imports an image picked by the user and creates a new project for it. */
    @Throws(ImageImportException::class)
    suspend fun importImage(uri: Uri): Project = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val fileName = "$id.img"
        val destination = File(imagesDir, fileName)
        ImageLoader.copyToFile(appContext.contentResolver, uri, destination)
        // Validate that it actually decodes before creating the project.
        try {
            ImageLoader.decode(destination, ImageLoader.THUMBNAIL_SIDE).recycle()
        } catch (e: ImageImportException) {
            destination.delete()
            throw e
        }
        val now = System.currentTimeMillis()
        val project = Project(
            id = id,
            name = displayName(uri) ?: "Sketch",
            imageFileName = fileName,
            createdAt = now,
            updatedAt = now,
            adjustments = SketchAdjustments()
        )
        mutex.withLock {
            val updated = listOf(project) + _projects.value
            writeIndex(updated)
            _projects.value = updated
        }
        project
    }

    suspend fun updateAdjustments(projectId: String, adjustments: SketchAdjustments) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val updated = _projects.value
                .map { if (it.id == projectId) it.copy(adjustments = adjustments.sanitized(), updatedAt = now) else it }
                .sortedByDescending { it.updatedAt }
            writeIndex(updated)
            _projects.value = updated
        }
    }

    suspend fun delete(projectId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val project = _projects.value.firstOrNull { it.id == projectId } ?: return@withLock
            val updated = _projects.value.filterNot { it.id == projectId }
            writeIndex(updated)
            _projects.value = updated
            val file = imageFile(project)
            if (file.exists() && !file.delete()) Log.w(TAG, "Couldn't delete ${file.name}")
        }
    }

    suspend fun loadBitmap(project: Project, maxSide: Int): Bitmap = withContext(Dispatchers.IO) {
        ImageLoader.decode(imageFile(project), maxSide)
    }

    private fun displayName(uri: Uri): String? = try {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.substringBeforeLast('.')?.take(60) else null
                } else null
            }
            ?.takeIf { it.isNotBlank() }
    } catch (e: SecurityException) {
        Log.w(TAG, "No permission to read display name", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Provider doesn't support display name", e)
        null
    }

    private fun readIndex(): List<Project> {
        if (!indexFile.exists()) return emptyList()
        return try {
            Project.listFromJson(indexFile.readText())
        } catch (e: IOException) {
            Log.e(TAG, "Couldn't read project index", e)
            emptyList()
        } catch (e: JSONException) {
            Log.e(TAG, "Project index is corrupted; starting fresh", e)
            emptyList()
        }
    }

    private fun writeIndex(projects: List<Project>) {
        root.mkdirs()
        val temp = File(root, "index.json.tmp")
        try {
            temp.writeText(Project.listToJson(projects))
            if (indexFile.exists() && !indexFile.delete()) {
                throw IOException("Couldn't replace project index")
            }
            if (!temp.renameTo(indexFile)) throw IOException("Couldn't save project index")
        } catch (e: IOException) {
            Log.e(TAG, "Saving projects failed", e)
            throw e
        }
    }

    private companion object {
        const val TAG = "WallTraceProjects"
    }
}
