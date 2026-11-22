@file:OptIn(ExperimentalMaterial3Api::class)

package com.walltrace.ar.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walltrace.ar.MainViewModel
import com.walltrace.ar.ar.ArCommand
import com.walltrace.ar.ar.ArRenderer
import com.walltrace.ar.ar.ArSessionController
import com.walltrace.ar.ar.ArSetupState
import com.walltrace.ar.ar.ArStatus
import com.walltrace.ar.ar.CameraTracking
import com.walltrace.ar.ar.PlacementKind
import com.walltrace.ar.ar.PlacementState
import com.walltrace.ar.ar.TrackingIssue
import com.walltrace.ar.model.SketchAdjustments
import com.walltrace.ar.ui.theme.WtColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ArScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        askedOnce = true
    }

    // Re-check on resume: the user may have granted the permission in system settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasCameraPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!granted && !askedOnce) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (granted && activity != null) {
        ArCameraContent(viewModel, activity, onBack)
    } else {
        PermissionRequest(
            onAllow = {
                val permanentlyDenied = askedOnce && activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
                if (permanentlyDenied) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onBack = onBack
        )
    }
}

@Composable
private fun ArCameraContent(viewModel: MainViewModel, activity: Activity, onBack: () -> Unit) {
    val density = LocalDensity.current.density
    val renderer = remember {
        ArRenderer(density) { ContextCompat.getDisplayOrDefault(activity).rotation }
    }
    val glView = remember {
        GLSurfaceView(activity).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setWillNotDraw(false)
            keepScreenOn = true
        }
    }
    val controller = remember { ArSessionController(activity, renderer) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.resume(glView)
                Lifecycle.Event.ON_PAUSE -> controller.pause(glView)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release(glView)
        }
    }

    val status by renderer.status.collectAsStateWithLifecycle()
    val setup by controller.state.collectAsStateWithLifecycle()
    val bitmap by viewModel.sketchBitmap.collectAsStateWithLifecycle()
    val adjustments by viewModel.adjustments.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    LaunchedEffect(bitmap) { bitmap?.let(renderer::setImage) }
    LaunchedEffect(adjustments) { renderer.adjustments = adjustments }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(renderer) {
        launch {
            renderer.messages.collect { msg ->
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(msg)
            }
        }
        viewModel.messages.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    val haptics = LocalHapticFeedback.current
    LaunchedEffect(status.placement) {
        if (status.placement == PlacementState.LOCKED) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    var drawingMode by rememberSaveable { mutableStateOf(false) }
    var showAdjust by rememberSaveable { mutableStateOf(false) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.importImage(uri)
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importImage(uri)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())

        val interactive = setup == ArSetupState.Running && status.sessionRunning && !drawingMode
        if (interactive) {
            GestureLayer(onCommand = renderer::submit)
        }

        // ---- Top: navigation + status ----
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!drawingMode) {
                    RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack)
                    Spacer(Modifier.width(8.dp))
                }
                StatusChip(status = status, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                RoundIconButton(
                    icon = if (drawingMode) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    contentDescription = if (drawingMode) "Show controls" else "Drawing mode: hide controls",
                    onClick = {
                        drawingMode = !drawingMode
                        showAdjust = false
                    }
                )
            }
            if (!drawingMode) {
                ScanningProgress(status)
            }
            SnackbarHost(snackbar, modifier = Modifier.padding(top = 4.dp))
        }

        // ---- Bottom controls ----
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (drawingMode) {
                DrawingModeBar(adjustments, onChange = viewModel::updateAdjustments)
            } else if (setup == ArSetupState.Running) {
                PlacementHint(status)
                if (shouldOfferApproximate(status)) {
                    OutlinedButton(
                        onClick = { renderer.submit(ArCommand.PlaceApproximate) },
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text("Place approximately (no wall detected)", color = WtColors.Text)
                    }
                }
                ControlPanel(
                    status = status,
                    adjustments = adjustments,
                    showAdjust = showAdjust,
                    busy = busy,
                    onToggleAdjust = { showAdjust = !showAdjust },
                    onAdjustmentsChange = viewModel::updateAdjustments,
                    onCommand = renderer::submit,
                    onPickGallery = {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onPickFiles = { openDocument.launch(arrayOf("image/*")) }
                )
            }
        }

        SetupOverlay(
            setup = setup,
            status = status,
            onRetry = { controller.retry(glView) },
            onBack = onBack
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Gestures
// -------------------------------------------------------------------------------------------------

@Composable
private fun GestureLayer(onCommand: (ArCommand) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    var dragging = false
                    var multiTouch = false
                    var moved = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            if (dragging) {
                                onCommand(ArCommand.DragEnd)
                                dragging = false
                            }
                            multiTouch = true
                            val zoom = event.calculateZoom()
                            val rotation = event.calculateRotation()
                            if (zoom.isFinite() && zoom > 0f && zoom != 1f) onCommand(ArCommand.Scale(zoom))
                            if (rotation.isFinite() && rotation != 0f) {
                                onCommand(ArCommand.Rotate(Math.toRadians(rotation.toDouble()).toFloat()))
                            }
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1 && !multiTouch) {
                            val change = pressed[0]
                            if (!moved && (change.position - start).getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                                dragging = true
                                onCommand(ArCommand.DragStart(start.x, start.y))
                            }
                            if (dragging) onCommand(ArCommand.DragMove(change.position.x, change.position.y))
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    if (dragging) onCommand(ArCommand.DragEnd)
                    if (!moved && !multiTouch) onCommand(ArCommand.Tap(start.x, start.y))
                }
            }
    )
}

// -------------------------------------------------------------------------------------------------
// Status
// -------------------------------------------------------------------------------------------------

private enum class Tone { GOOD, INFO, WARN, BAD }

private fun statusLine(s: ArStatus): Pair<String, Tone> {
    if (!s.sessionRunning) return "Starting camera…" to Tone.INFO
    return when (s.cameraTracking) {
        CameraTracking.INITIALIZING -> "Initializing tracking… move the phone slowly" to Tone.INFO
        CameraTracking.LIMITED, CameraTracking.STOPPED -> {
            val base = when (s.issue) {
                TrackingIssue.INSUFFICIENT_LIGHT -> "Too dark: add more light"
                TrackingIssue.EXCESSIVE_MOTION -> "Moving too fast: slow down"
                TrackingIssue.INSUFFICIENT_FEATURES -> "Surface too plain: aim at edges or texture"
                TrackingIssue.CAMERA_UNAVAILABLE -> "Camera unavailable"
                else -> "Tracking lost: point back at the wall"
            }
            val suffix = if (s.placement != PlacementState.NONE) " · sketch kept" else ""
            (base + suffix) to Tone.WARN
        }
        CameraTracking.TRACKING -> when (s.placement) {
            PlacementState.LOCKED ->
                if (s.anchorTracking) "Locked to wall" to Tone.GOOD
                else "Locked · re-aim at the wall to restore accuracy" to Tone.WARN
            PlacementState.PLACED -> when {
                !s.anchorTracking -> "Position uncertain: aim at the wall" to Tone.WARN
                s.placementKind == PlacementKind.APPROXIMATE -> "Approximate placement (not on a detected wall)" to Tone.WARN
                else -> "Placed · adjust, then lock" to Tone.INFO
            }
            PlacementState.NONE -> when {
                !s.hasImage -> "Import an image to begin" to Tone.INFO
                s.wallPlanes > 0 -> "Wall detected · tap to place" to Tone.GOOD
                else -> "Scanning wall · ${s.featurePoints} points" to Tone.INFO
            }
        }
    }
}

@Composable
private fun StatusChip(status: ArStatus, modifier: Modifier = Modifier) {
    val (text, tone) = statusLine(status)
    val dot = when (tone) {
        Tone.GOOD -> WtColors.Teal
        Tone.INFO -> WtColors.Text
        Tone.WARN -> WtColors.Warning
        Tone.BAD -> WtColors.Error
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(WtColors.Overlay)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = WtColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (status.placement == PlacementState.LOCKED) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.Lock, contentDescription = "Locked", tint = WtColors.Teal, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ScanningProgress(status: ArStatus) {
    val scanning = status.sessionRunning &&
        status.cameraTracking == CameraTracking.TRACKING &&
        status.placement == PlacementState.NONE &&
        status.wallPlanes == 0
    AnimatedVisibility(visible = scanning) {
        Column(Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
            LinearProgressIndicator(
                progress = { (status.featurePoints / 150f).coerceIn(0.02f, 0.95f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = WtColors.Teal,
                trackColor = WtColors.Overlay
            )
            if (status.scanningSeconds >= 5) {
                Text(
                    "Tip: hold the phone 0.5–2 m from the wall and move slowly sideways." +
                        if (status.depthEnabled) "" else " Good light helps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WtColors.Text,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(WtColors.Overlay)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

private fun shouldOfferApproximate(s: ArStatus): Boolean =
    s.sessionRunning &&
        s.hasImage &&
        s.cameraTracking == CameraTracking.TRACKING &&
        s.placement == PlacementState.NONE &&
        s.wallPlanes == 0 &&
        s.scanningSeconds >= APPROXIMATE_AFTER_SECONDS

private const val APPROXIMATE_AFTER_SECONDS = 8

@Composable
private fun PlacementHint(status: ArStatus) {
    val text = when {
        !status.sessionRunning || status.cameraTracking != CameraTracking.TRACKING -> null
        status.placement == PlacementState.NONE && status.hasImage && status.wallPlanes > 0 ->
            "Tap the wall where the image should go"
        status.placement == PlacementState.PLACED ->
            "Drag to move · Pinch to resize · Twist to rotate · ${status.sketchWidthCm} × ${status.sketchHeightCm} cm"
        else -> null
    } ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = WtColors.Text,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(WtColors.Overlay)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

// -------------------------------------------------------------------------------------------------
// Controls
// -------------------------------------------------------------------------------------------------

@Composable
private fun ControlPanel(
    status: ArStatus,
    adjustments: SketchAdjustments,
    showAdjust: Boolean,
    busy: Boolean,
    onToggleAdjust: () -> Unit,
    onAdjustmentsChange: (SketchAdjustments) -> Unit,
    onCommand: (ArCommand) -> Unit,
    onPickGallery: () -> Unit,
    onPickFiles: () -> Unit
) {
    Surface(
        color = WtColors.Overlay,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            OpacityRow(adjustments, onAdjustmentsChange)

            AnimatedVisibility(visible = showAdjust) {
                AdjustPanel(adjustments, onAdjustmentsChange)
            }

            Spacer(Modifier.height(8.dp))
            LockButton(status, onCommand)
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    ToolButton(
                        icon = Icons.Outlined.Collections,
                        label = if (busy) "Loading…" else "Image",
                        enabled = !busy && status.placement != PlacementState.LOCKED,
                        onClick = { menuOpen = true }
                    )
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("From gallery") },
                            leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                            onClick = { menuOpen = false; onPickGallery() }
                        )
                        DropdownMenuItem(
                            text = { Text("From files") },
                            leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                            onClick = { menuOpen = false; onPickFiles() }
                        )
                    }
                }
                ToolButton(
                    icon = Icons.Outlined.Tune,
                    label = "Adjust",
                    selected = showAdjust,
                    onClick = onToggleAdjust
                )
                ToolButton(
                    icon = Icons.Outlined.GridOn,
                    label = "Grid",
                    selected = adjustments.showGrid,
                    onClick = { onAdjustmentsChange(adjustments.copy(showGrid = !adjustments.showGrid)) }
                )
                ToolButton(
                    icon = Icons.Outlined.RestartAlt,
                    label = "Reset",
                    enabled = status.placement != PlacementState.NONE,
                    onClick = { onCommand(ArCommand.ResetPlacement) }
                )
            }
        }
    }
}

@Composable
private fun LockButton(status: ArStatus, onCommand: (ArCommand) -> Unit) {
    when (status.placement) {
        PlacementState.LOCKED -> OutlinedButton(
            onClick = { onCommand(ArCommand.Unlock) },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Outlined.LockOpen, contentDescription = null, tint = WtColors.Text)
            Spacer(Modifier.width(8.dp))
            Text("UNLOCK TO REPOSITION", color = WtColors.Text)
        }
        else -> Button(
            onClick = { onCommand(ArCommand.Lock) },
            enabled = status.placement == PlacementState.PLACED && status.anchorTracking,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = WtColors.Teal,
                contentColor = WtColors.OnTeal,
                disabledContainerColor = WtColors.SurfaceHigh,
                disabledContentColor = WtColors.TextMuted
            )
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("LOCK SKETCH")
        }
    }
}

@Composable
private fun OpacityRow(adjustments: SketchAdjustments, onChange: (SketchAdjustments) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Opacity, contentDescription = "Opacity", tint = WtColors.TextMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Slider(
            value = adjustments.opacity,
            onValueChange = { onChange(adjustments.copy(opacity = it)) },
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f),
            colors = sliderColors()
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "${(adjustments.opacity * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelLarge,
            color = WtColors.Text,
            modifier = Modifier.width(44.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun AdjustPanel(a: SketchAdjustments, onChange: (SketchAdjustments) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ToggleChip("Outline", a.outline) { onChange(a.copy(outline = it)) }
            ToggleChip("Grayscale", a.grayscale) { onChange(a.copy(grayscale = it)) }
            ToggleChip("B & W", a.blackAndWhite) { onChange(a.copy(blackAndWhite = it)) }
            ToggleChip("Invert", a.invert) { onChange(a.copy(invert = it)) }
            ToggleChip("Remove white", a.removeWhiteBackground) { onChange(a.copy(removeWhiteBackground = it)) }
            ToggleChip("Flip ↔", a.flipHorizontal) { onChange(a.copy(flipHorizontal = it)) }
            ToggleChip("Flip ↕", a.flipVertical) { onChange(a.copy(flipVertical = it)) }
        }
        if (a.outline) {
            LabeledSlider("Line strength", a.outlineStrength, 0f..1f, "${(a.outlineStrength * 100).roundToInt()}%") {
                onChange(a.copy(outlineStrength = it))
            }
            LabeledSlider("Line thickness", a.outlineThickness, 1f..4f, String.format(java.util.Locale.US, "%.1f", a.outlineThickness)) {
                onChange(a.copy(outlineThickness = it))
            }
            Text(
                "Tip: turn on Invert for white lines on dark walls.",
                style = MaterialTheme.typography.bodySmall,
                color = WtColors.TextMuted,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        } else {
            LabeledSlider("Brightness", a.brightness, -0.5f..0.5f, signedPercent(a.brightness * 2f)) {
                onChange(a.copy(brightness = it))
            }
            LabeledSlider("Contrast", a.contrast, 0.2f..2f, "${(a.contrast * 100).roundToInt()}%") {
                onChange(a.copy(contrast = it))
            }
            LabeledSlider("Saturation", a.saturation, 0f..2f, "${(a.saturation * 100).roundToInt()}%") {
                onChange(a.copy(saturation = it))
            }
        }
        TextButton(onClick = { onChange(a.resetImageAdjustments()) }) {
            Text("Reset adjustments", color = WtColors.Teal)
        }
    }
}

private fun signedPercent(v: Float): String {
    val p = (v * 100).roundToInt()
    return if (p > 0) "+$p%" else "$p%"
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(top = 6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium, color = WtColors.TextMuted)
            Spacer(Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = WtColors.Text)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = sliderColors(),
            modifier = Modifier.height(32.dp)
        )
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onToggle: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(!selected) },
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = WtColors.SurfaceHigh,
            labelColor = WtColors.Text,
            selectedContainerColor = WtColors.Teal,
            selectedLabelColor = WtColors.OnTeal
        ),
        border = null
    )
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tint = when {
        !enabled -> WtColors.TextMuted.copy(alpha = 0.5f)
        selected -> WtColors.Teal
        else -> WtColors.Text
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .padding(horizontal = 4.dp)
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label, tint = tint)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(WtColors.Overlay),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription, tint = WtColors.Text)
        }
    }
}

@Composable
private fun DrawingModeBar(adjustments: SketchAdjustments, onChange: (SketchAdjustments) -> Unit) {
    Surface(
        color = WtColors.Overlay,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            OpacityRow(adjustments, onChange)
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = WtColors.Teal,
    activeTrackColor = WtColors.Teal,
    inactiveTrackColor = WtColors.SurfaceHigh
)

// -------------------------------------------------------------------------------------------------
// Setup / permission / error overlays
// -------------------------------------------------------------------------------------------------

@Composable
private fun SetupOverlay(setup: ArSetupState, status: ArStatus, onRetry: () -> Unit, onBack: () -> Unit) {
    when (setup) {
        ArSetupState.Starting -> CenterCard {
            CircularProgressIndicator(color = WtColors.Teal, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(12.dp))
            Text("Starting camera…", color = WtColors.Text)
        }
        ArSetupState.Installing -> CenterCard {
            CircularProgressIndicator(color = WtColors.Teal, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                "Waiting for Google Play Services for AR to install…",
                color = WtColors.Text,
                textAlign = TextAlign.Center
            )
        }
        is ArSetupState.Error -> CenterCard {
            Text(setup.message, color = WtColors.Text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back", color = WtColors.Text) }
                if (setup.canRetry) {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = WtColors.Teal, contentColor = WtColors.OnTeal)
                    ) { Text("Retry") }
                }
            }
        }
        ArSetupState.Running -> if (!status.sessionRunning && status.issue == TrackingIssue.CAMERA_UNAVAILABLE) {
            CenterCard {
                Text(
                    "The camera was interrupted by another app or the system.",
                    color = WtColors.Text,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = WtColors.Teal, contentColor = WtColors.OnTeal)
                ) { Text("Restart camera") }
            }
        }
    }
}

@Composable
private fun CenterCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = WtColors.Surface,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .padding(32.dp)
                .widthIn(max = 360.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) { content() }
        }
    }
}

@Composable
private fun PermissionRequest(onAllow: () -> Unit, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(WtColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Box(Modifier.padding(12.dp)) {
            RoundIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack)
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(32.dp)
                .widthIn(max = 380.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Camera access needed", style = MaterialTheme.typography.titleMedium, color = WtColors.Text)
            Spacer(Modifier.height(8.dp))
            Text(
                "WallTrace uses the camera to find your wall and show the image on it. Nothing is recorded or uploaded.",
                style = MaterialTheme.typography.bodyMedium,
                color = WtColors.TextMuted,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onAllow,
                colors = ButtonDefaults.buttonColors(containerColor = WtColors.Teal, contentColor = WtColors.OnTeal)
            ) { Text("Allow camera") }
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
