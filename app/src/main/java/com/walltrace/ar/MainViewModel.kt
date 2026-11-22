package com.walltrace.ar

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.ArCoreApk
import com.walltrace.ar.data.Project
import com.walltrace.ar.data.ProjectRepository
import com.walltrace.ar.image.ImageImportException
import com.walltrace.ar.image.ImageLoader
import com.walltrace.ar.model.SketchAdjustments
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

enum class Screen { HOME, AR, OVERLAY }

/** Whether this phone can run ARCore wall tracking. */
enum class ArSupport { CHECKING, SUPPORTED, UNSUPPORTED, UNKNOWN }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)

    val projects: StateFlow<List<Project>> = repository.projects

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _currentProject = MutableStateFlow<Project?>(null)
    val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()

    private val _sketchBitmap = MutableStateFlow<Bitmap?>(null)
    val sketchBitmap: StateFlow<Bitmap?> = _sketchBitmap.asStateFlow()

    private val _adjustments = MutableStateFlow(SketchAdjustments())
    val adjustments: StateFlow<SketchAdjustments> = _adjustments.asStateFlow()

    private val _arSupport = MutableStateFlow(ArSupport.CHECKING)
    val arSupport: StateFlow<ArSupport> = _arSupport.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val thumbnails = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private var saveJob: Job? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch { checkArSupport() }
        viewModelScope.launch {
            try {
                repository.load()
            } catch (e: IOException) {
                Log.e(TAG, "Loading projects failed", e)
                _messages.tryEmit("Couldn't load saved projects.")
            }
        }
    }

    /** Imports a picked image. Opens the AR screen, or replaces the image if AR is already open. */
    fun importImage(uri: Uri) {
        if (_busy.value) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _busy.value = true
            try {
                flushPendingSave()
                val project = repository.importImage(uri)
                val bitmap = repository.loadBitmap(project, ImageLoader.MAX_TEXTURE_SIDE)
                // Keep the user's current opacity when swapping images inside AR / Overlay.
                val keepOpacity = _screen.value != Screen.HOME && _sketchBitmap.value != null
                val adjustments = if (keepOpacity) SketchAdjustments(opacity = _adjustments.value.opacity) else project.adjustments
                _currentProject.value = project
                _adjustments.value = adjustments
                _sketchBitmap.value = bitmap
                if (keepOpacity) repository.updateAdjustments(project.id, adjustments)
                _screen.value = sketchScreen()
            } catch (e: ImageImportException) {
                Log.w(TAG, "Import failed", e)
                _messages.tryEmit(e.message ?: "Couldn't import that image.")
            } catch (e: IOException) {
                Log.e(TAG, "Import failed", e)
                _messages.tryEmit("Couldn't save the image. Check free storage space.")
            } finally {
                _busy.value = false
            }
        }
    }

    fun openProject(project: Project) {
        if (_busy.value) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _busy.value = true
            try {
                flushPendingSave()
                val bitmap = repository.loadBitmap(project, ImageLoader.MAX_TEXTURE_SIDE)
                _currentProject.value = project
                _adjustments.value = project.adjustments
                _sketchBitmap.value = bitmap
                _screen.value = sketchScreen()
            } catch (e: ImageImportException) {
                Log.w(TAG, "Open failed", e)
                _messages.tryEmit(e.message ?: "Couldn't open this project.")
            } finally {
                _busy.value = false
            }
        }
    }

    fun deleteProject(project: Project) {
        viewModelScope.launch {
            try {
                if (_currentProject.value?.id == project.id) {
                    saveJob?.cancel()
                    _currentProject.value = null
                    _sketchBitmap.value = null
                }
                repository.delete(project.id)
                thumbnails.remove(project.id)
            } catch (e: IOException) {
                Log.e(TAG, "Delete failed", e)
                _messages.tryEmit("Couldn't delete the project.")
            }
        }
    }

    fun updateAdjustments(adjustments: SketchAdjustments) {
        val sanitized = adjustments.sanitized()
        _adjustments.value = sanitized
        val projectId = _currentProject.value?.id ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            save(projectId, sanitized)
        }
    }

    /** Switches to Overlay mode (camera + image without AR tracking). */
    fun useOverlayMode() {
        _screen.value = Screen.OVERLAY
    }

    /** Switches to AR mode when this phone supports ARCore. */
    fun useArMode() {
        if (_arSupport.value != ArSupport.UNSUPPORTED) _screen.value = Screen.AR
    }

    fun closeAr() {
        _screen.value = Screen.HOME
        viewModelScope.launch { flushPendingSave() }
    }

    suspend fun thumbnail(project: Project): Bitmap? {
        thumbnails.get(project.id)?.let { return it }
        return try {
            repository.loadBitmap(project, ImageLoader.THUMBNAIL_SIDE).also { thumbnails.put(project.id, it) }
        } catch (e: ImageImportException) {
            Log.w(TAG, "Thumbnail failed for ${project.id}", e)
            null
        }
    }

    private fun sketchScreen(): Screen = when {
        _screen.value == Screen.OVERLAY -> Screen.OVERLAY
        _arSupport.value == ArSupport.UNSUPPORTED -> Screen.OVERLAY
        else -> Screen.AR
    }

    private suspend fun checkArSupport() {
        val arCore = ArCoreApk.getInstance()
        repeat(AR_CHECK_ATTEMPTS) {
            val availability = arCore.checkAvailability(getApplication<Application>())
            if (!availability.isTransient) {
                _arSupport.value = when {
                    availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ArSupport.UNSUPPORTED
                    availability.isSupported -> ArSupport.SUPPORTED
                    else -> ArSupport.UNKNOWN
                }
                return
            }
            delay(AR_CHECK_INTERVAL_MS)
        }
        _arSupport.value = ArSupport.UNKNOWN
    }

    private suspend fun flushPendingSave() {
        val pending = saveJob
        if (pending != null && pending.isActive) {
            pending.cancel()
            val projectId = _currentProject.value?.id ?: return
            save(projectId, _adjustments.value)
        }
    }

    private suspend fun save(projectId: String, adjustments: SketchAdjustments) {
        withContext(NonCancellable) {
            try {
                repository.updateAdjustments(projectId, adjustments)
            } catch (e: IOException) {
                Log.e(TAG, "Saving settings failed", e)
                _messages.tryEmit("Couldn't save sketch settings.")
            }
        }
    }

    private companion object {
        const val TAG = "WallTraceVM"
        const val SAVE_DEBOUNCE_MS = 600L
        const val THUMBNAIL_CACHE_BYTES = 16 * 1024 * 1024
        const val AR_CHECK_ATTEMPTS = 25
        const val AR_CHECK_INTERVAL_MS = 200L
    }
}
