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
    data class Error(val message: String, val canRetry: Boolean, val offerOverlay: Boolean = false) : ArSetupState
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
        if (ArCoreApk.getInstance().checkAvailability(activity) ==
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE
        ) {
            _state.value = ArSetupState.Error(UNSUPPORTED_MESSAGE, canRetry = false, offerOverlay = true)
            return null
        }
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
            fail(NOT_INSTALLED_MESSAGE, canRetry = true, e = e, offerOverlay = true)
        } catch (e: UnavailableArcoreNotInstalledException) {
            fail(NOT_INSTALLED_MESSAGE, canRetry = true, e = e, offerOverlay = true)
        } catch (e: UnavailableApkTooOldException) {
            fail("Please update Google Play Services for AR from the Play Store.", canRetry = true, e = e, offerOverlay = true)
        } catch (e: UnavailableSdkTooOldException) {
            fail("This app needs an update to work with your ARCore version.", canRetry = false, e = e, offerOverlay = true)
        } catch (e: UnavailableDeviceNotCompatibleException) {
            fail(UNSUPPORTED_MESSAGE, canRetry = false, e = e, offerOverlay = true)
        } catch (e: UnavailableException) {
            fail("AR is not available on this device.", canRetry = false, e = e, offerOverlay = true)
        } catch (e: FatalException) {
            fail("ARCore failed to start. Please try again.", canRetry = true, e = e, offerOverlay = true)
        } catch (e: SecurityException) {
            fail("Camera permission is required.", canRetry = true, e = e)
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

    private fun fail(message: String, canRetry: Boolean, e: Exception, offerOverlay: Boolean = false): Session? {
        Log.e(TAG, message, e)
        _state.value = ArSetupState.Error(message, canRetry, offerOverlay)
        return null
    }

    private companion object {
        const val TAG = "WallTraceSession"
        const val UNSUPPORTED_MESSAGE =
            "This phone doesn't support ARCore, so wall tracking can't run here. Use Overlay mode instead."
        const val NOT_INSTALLED_MESSAGE =
            "Google Play Services for AR isn't installed. If the Play Store says your device isn't " +
                "compatible, this phone can't run AR: use Overlay mode instead."
    }
}
