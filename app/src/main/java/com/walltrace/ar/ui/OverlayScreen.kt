package com.walltrace.ar.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walltrace.ar.ArSupport
import com.walltrace.ar.MainViewModel
import com.walltrace.ar.image.SketchProcessor
import com.walltrace.ar.model.SketchAdjustments
import com.walltrace.ar.ui.theme.WtColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import kotlin.math.min

/**
 * Overlay mode: for phones without ARCore. Shows the processed image over the live camera preview.
 * The image is fixed to the screen (not to the wall), so the phone must stay still while tracing;
 * the UI says so explicitly.
 */
@Composable
fun OverlayScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    CameraPermissionGate(onBack = onBack) {
        OverlayContent(viewModel, onBack)
    }
}

@Composable
private fun OverlayContent(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            keepScreenOn = true
        }
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraAttempt by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner, cameraAttempt) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                cameraError = null
            } catch (e: ExecutionException) {
                Log.e(TAG, "Camera provider failed", e)
                cameraError = "The camera couldn't be started."
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "No back camera", e)
                cameraError = "No rear camera was found on this device."
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Camera bind failed", e)
                cameraError = "The camera is busy. Close other camera apps and try again."
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            provider?.unbindAll()
        }
    }

    val source by viewModel.sketchBitmap.collectAsStateWithLifecycle()
    val adjustments by viewModel.adjustments.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val arSupport by viewModel.arSupport.collectAsStateWithLifecycle()

    // Opacity, flips and grid are applied live by the view; everything else is processed off the main thread.
    val processingKey = adjustments.copy(opacity = 1f, flipHorizontal = false, flipVertical = false, showGrid = false)
    var processed by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(source, processingKey) {
        val src = source ?: return@LaunchedEffect
        val job = coroutineContext[Job]
        val result = withContext(Dispatchers.Default) {
            processForOverlay(src, processingKey) { job?.isActive != false }
        }
        processed = result.asImageBitmap()
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    var offset by remember { mutableStateOf(Offset.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var drawingMode by rememberSaveable { mutableStateOf(false) }
    var showAdjust by rememberSaveable { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val gridStroke = with(LocalDensity.current) { 1.5.dp.toPx() }
    val borderStroke = with(LocalDensity.current) { 2.dp.toPx() }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.importImage(uri)
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importImage(uri)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // ---- Sketch overlay ----
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (!locked && !drawingMode) {
                        Modifier.pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, rotate ->
                                offset += pan
                                scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                rotation += rotate
                            }
                        }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            val image = processed
            if (image != null) {
                val ratio = image.width.toFloat() / image.height.toFloat()
                val maxW = maxWidth * 0.85f
                val maxH = maxHeight * 0.7f
                val width = min(maxW.value, maxH.value * ratio).dp
                val height = (width.value / ratio).dp
                Image(
                    bitmap = image,
                    contentDescription = "Sketch overlay",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .size(width, height)
                        .graphicsLayer {
                            translationX = offset.x
                            translationY = offset.y
                            scaleX = scale * if (adjustments.flipHorizontal) -1f else 1f
                            scaleY = scale * if (adjustments.flipVertical) -1f else 1f
                            rotationZ = rotation
                            alpha = adjustments.opacity
                        }
                        .drawWithContent {
                            drawContent()
                            if (adjustments.showGrid) {
                                val cells = 8
                                for (i in 1 until cells) {
                                    val x = size.width * i / cells
                                    val y = size.height * i / cells
                                    drawLine(WtColors.Teal, Offset(x, 0f), Offset(x, size.height), gridStroke)
                                    drawLine(WtColors.Teal, Offset(0f, y), Offset(size.width, y), gridStroke)
                                }
                            }
                            if (!locked) {
                                drawRect(WtColors.Teal, style = Stroke(width = borderStroke))
                            }
                        }
                )
            }
        }

        // ---- Top bar ----
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
                OverlayStatusChip(locked = locked, modifier = Modifier.weight(1f))
                if (!drawingMode && arSupport == ArSupport.SUPPORTED) {
                    Spacer(Modifier.width(8.dp))
                    RoundIconButton(Icons.Outlined.ViewInAr, "Switch to AR mode", onClick = viewModel::useArMode)
                }
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
            } else {
                Text(
                    if (locked) "Locked on screen. Keep the phone still while you trace."
                    else "Drag to move · Pinch to resize · Twist to rotate",
                    style = MaterialTheme.typography.bodySmall,
                    color = WtColors.Text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(WtColors.Overlay)
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
                OverlayControlPanel(
                    adjustments = adjustments,
                    locked = locked,
                    showAdjust = showAdjust,
                    busy = busy,
                    onToggleAdjust = { showAdjust = !showAdjust },
                    onAdjustmentsChange = viewModel::updateAdjustments,
                    onToggleLock = {
                        locked = !locked
                        if (locked) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onReset = {
                        offset = Offset.Zero
                        scale = 1f
                        rotation = 0f
                        locked = false
                    },
                    onPickGallery = {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onPickFiles = { openDocument.launch(arrayOf("image/*")) }
                )
            }
        }

        cameraError?.let { message ->
            CenterCard {
                Text(message, color = WtColors.Text, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onBack) { Text("Back", color = WtColors.Text) }
                    Button(
                        onClick = { cameraAttempt++ },
                        colors = ButtonDefaults.buttonColors(containerColor = WtColors.Teal, contentColor = WtColors.OnTeal)
                    ) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun OverlayStatusChip(locked: Boolean, modifier: Modifier = Modifier) {
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
                .background(WtColors.Warning)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Overlay mode · no wall tracking",
            style = MaterialTheme.typography.labelLarge,
            color = WtColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (locked) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.Lock, contentDescription = "Locked", tint = WtColors.Teal, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun OverlayControlPanel(
    adjustments: SketchAdjustments,
    locked: Boolean,
    showAdjust: Boolean,
    busy: Boolean,
    onToggleAdjust: () -> Unit,
    onAdjustmentsChange: (SketchAdjustments) -> Unit,
    onToggleLock: () -> Unit,
    onReset: () -> Unit,
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
            if (locked) {
                OutlinedButton(
                    onClick = onToggleLock,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Outlined.LockOpen, contentDescription = null, tint = WtColors.Text)
                    Spacer(Modifier.width(8.dp))
                    Text("UNLOCK TO REPOSITION", color = WtColors.Text)
                }
            } else {
                Button(
                    onClick = onToggleLock,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WtColors.Teal, contentColor = WtColors.OnTeal)
                ) {
                    Icon(Icons.Outlined.Lock, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("LOCK POSITION")
                }
            }
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
                        enabled = !busy && !locked,
                        onClick = { menuOpen = true }
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
                ToolButton(icon = Icons.Outlined.Tune, label = "Adjust", selected = showAdjust, onClick = onToggleAdjust)
                ToolButton(
                    icon = Icons.Outlined.GridOn,
                    label = "Grid",
                    selected = adjustments.showGrid,
                    onClick = { onAdjustmentsChange(adjustments.copy(showGrid = !adjustments.showGrid)) }
                )
                ToolButton(icon = Icons.Outlined.RestartAlt, label = "Reset", onClick = onReset)
            }
        }
    }
}

/** Downscales (for speed) and applies the CPU sketch pipeline. Runs on a background dispatcher. */
private fun processForOverlay(source: Bitmap, adjustments: SketchAdjustments, isActive: () -> Boolean): Bitmap {
    val longest = maxOf(source.width, source.height)
    val scaled = if (longest > OVERLAY_MAX_SIDE) {
        val factor = OVERLAY_MAX_SIDE.toFloat() / longest
        Bitmap.createScaledBitmap(
            source,
            maxOf(1, (source.width * factor).toInt()),
            maxOf(1, (source.height * factor).toInt()),
            true
        )
    } else {
        source
    }
    val w = scaled.width
    val h = scaled.height
    val pixels = IntArray(w * h)
    scaled.getPixels(pixels, 0, w, 0, 0, w, h)
    val out = SketchProcessor.process(pixels, w, h, adjustments, isActive)
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}

private const val TAG = "WallTraceOverlay"
private const val OVERLAY_MAX_SIDE = 1280
private const val MIN_SCALE = 0.1f
private const val MAX_SCALE = 10f
