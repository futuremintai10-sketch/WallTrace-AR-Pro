package com.walltrace.ar.ar

import android.app.Activity
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ArSetupState {
    data object Starting : ArSetupState
    data object Installing : ArSetupState
    data object Running : ArSetupState
    data class Error(val message: String, val canRetry: Boolean) : ArSetupState
}

/**
 * Owns the ARCore [Session] and ties it to the Activity / GLSurfaceView lifecycle:
 * ARCore install checks, session creation and configuration, resume/pause and release.
 * All methods must be called on the main thread.
 */
class ArSessionController(
    private val activity: Activity,
    private val renderer: ArRenderer
) {
    private var session: Session? = null
    private var installRequested = false
    private var resumed = false

    private val _state = MutableStateFlow<ArSetupState>(ArSetupState.Starting)
    val state: StateFlow<ArSetupState> = _state.asStateFlow()

    fun resume(surfaceView: GLSurfaceView) {
        if (resumed) return
        val s = session ?: createSession() ?: return
        session = s
        try {
            s.resume()
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "Camera not available on resume", e)
            _state.value = ArSetupState.Error(
                "The camera is in use by another app. Close it and tap Retry.",
                canRetry = true
            )
            return
        } catch (e: SecurityException) {
            Log.w(TAG, "Camera permission revoked", e)
            _state.value = ArSetupState.Error("Camera permission is required.", canRetry = true)
            return
        }
        renderer.session = s
        surfaceView.onResume()
        resumed = true
        _state.value = ArSetupState.Running
    }

    fun pause(surfaceView: GLSurfaceView) {
        if (!resumed) return
        // Stop the GL thread first so it no longer calls into the session.
        surfaceView.onPause()
        session?.pause()
        resumed = false
    }

    /** Pauses and resumes the session to recover from a lost camera. */
    fun retry(surfaceView: GLSurfaceView) {
        pause(surfaceView)
        if (_state.value is ArSetupState.Error) _state.value = ArSetupState.Starting
        resume(surfaceView)
    }

    fun release(surfaceView: GLSurfaceView) {
        pause(surfaceView)
        renderer.session = null
        session?.close()
        session = null
    }

    private fun createSession(): Session? {
        return try {
            when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    _state.value = ArSetupState.Installing
                    return null
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            Session(activity).also { configure(it) }
        } catch (e: UnavailableUserDeclinedInstallationException) {
            fail("Google Play Services for AR is required. Please install it to continue.", true, e)
        } catch (e: UnavailableArcoreNotInstalledException) {
            fail("Google Play Services for AR is not installed.", true, e)
        } catch (e: UnavailableApkTooOldException) {
            fail("Please update Google Play Services for AR from the Play Store.", true, e)
        } catch (e: UnavailableSdkTooOldException) {
            fail("This app needs an update to work with your ARCore version.", false, e)
        } catch (e: UnavailableDeviceNotCompatibleException) {
            fail("This device does not support ARCore (Google Play Services for AR).", false, e)
        } catch (e: UnavailableException) {
            fail("AR is not available on this device.", false, e)
        } catch (e: FatalException) {
            fail("ARCore failed to start. Please try again.", true, e)
        } catch (e: SecurityException) {
            fail("Camera permission is required.", true, e)
        }
    }

    private fun configure(session: Session) {
        val config = Config(session)
        config.setPlaneFindingMode(Config.PlaneFindingMode.VERTICAL)
        config.setFocusMode(Config.FocusMode.AUTO)
        config.setUpdateMode(Config.UpdateMode.BLOCKING)
        config.setLightEstimationMode(Config.LightEstimationMode.DISABLED)
        val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        config.setDepthMode(if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED)
        session.configure(config)
        renderer.depthEnabled = depthSupported
    }

    private fun fail(message: String, canRetry: Boolean, e: Exception): Session? {
        Log.e(TAG, message, e)
        _state.value = ArSetupState.Error(message, canRetry)
        return null
    }

    private companion object {
        const val TAG = "WallTraceSession"
    }
}
