package com.walltrace.ar.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walltrace.ar.ArSupport
import com.walltrace.ar.MainViewModel
import com.walltrace.ar.image.SketchProcessor
import com.walltrace.ar.model.SketchAdjustments
import com.walltrace.ar.tracking.QuadMath
import com.walltrace.ar.tracking.WallTracker
import com.walltrace.ar.ui.theme.WtColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Overlay mode, for phones without ARCore. The processed image is drawn over the live camera preview.
 * After LOCK, an OpenCV planar tracker (optical flow + homography, ORB relocalisation) keeps the
 * image attached to the wall as the phone moves. If OpenCV can't load, the lock falls back to the
 * screen and the UI says so.
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
    val tracker = remember { WallTracker() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraAttempt by remember { mutableIntStateOf(0) }

    // Declared before the camera effect so it is disposed after the camera is unbound.
    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.execute { tracker.release() }
            analysisExecutor.shutdown()
        }
    }

    DisposableEffect(lifecycleOwner, cameraAttempt) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get()
                provider = p
                previewView.doOnLayout {
                    if (disposed) return@doOnLayout
                    analysis = bindCamera(p, previewView, lifecycleOwner, tracker, analysisExecutor)
                    cameraError = null
                }
            } catch (e: ExecutionException) {
                Log.e(TAG, "Camera provider failed", e)
                cameraError = "The camera couldn't be started."
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            analysis?.clearAnalyzer()
            provider?.unbindAll()
        }
    }

    val trackerState by tracker.state.collectAsStateWithLifecycle()
    val source by viewModel.sketchBitmap.collectAsStateWithLifecycle()
    val adjustments by viewModel.adjustments.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val arSupport by viewModel.arSupport.collectAsStateWithLifecycle()

    // Opacity, flips and grid are applied while drawing; everything else is processed off the main thread.
    val processingKey = adjustments.copy(opacity = 1f, flipHorizontal = false, flipVertical = false, showGrid = false)
    var processed by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source, processingKey) {
        val src = source ?: return@LaunchedEffect
        val job = coroutineContext[Job]
        processed = withContext(Dispatchers.Default) {
            processForOverlay(src, processingKey) { job?.isActive != false }
        }
    }
    val processedImage = remember(processed) { processed?.asImageBitmap() }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var staticCorners by remember { mutableStateOf<FloatArray?>(null) }
    var drawingMode by rememberSaveable { mutableStateOf(false) }
    var showAdjust by rememberSaveable { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val gridStroke = with(density) { 1.5.dp.toPx() }
    val borderStroke = with(density) { 2.dp.toPx() }
    val dotRadius = with(density) { 2.5.dp.toPx() }

    // Size (px) of the sketch box before lock; must match the layout of the positioning Box below.
    fun sketchBoxPx(bitmap: Bitmap): Pair<Float, Float> {
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val maxW = viewSize.width * 0.85f
        val maxH = viewSize.height * 0.7f
        val w = min(maxW, maxH * ratio)
        return w to w / ratio
    }

    // Tell the tracker where the sketch is so it rates (and shows dots for) that part of the wall.
    LaunchedEffect(offset, scale, rotation, viewSize, processed, locked) {
        val bitmap = processed
        if (locked || bitmap == null || viewSize.width == 0 || viewSize.height == 0) {
            tracker.setScanRegion(null)
        } else {
            val (w, h) = sketchBoxPx(bitmap)
            tracker.setScanRegion(
                QuadMath.imageCorners(
                    viewSize.width.toFloat(), viewSize.height.toFloat(), w, h,
                    offset.x, offset.y, scale, rotation
                )
            )
        }
    }

    fun lockNow() {
        val bitmap = processed ?: return
        if (viewSize.width == 0 || viewSize.height == 0) return
        val (w, h) = sketchBoxPx(bitmap)
        val corners = QuadMath.imageCorners(
            viewSize.width.toFloat(), viewSize.height.toFloat(), w, h,
            offset.x, offset.y, scale, rotation
        )
        staticCorners = corners
        tracker.lock(corners)
        locked = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun unlockNow() {
        tracker.unlock()
        locked = false
        staticCorners = null
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.importImage(uri)
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importImage(uri)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged {
                viewSize = it
                tracker.setViewSize(it.width, it.height)
            }
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // ---- Feature dots (genuine detected corners) before locking ----
        if (!locked && !drawingMode && trackerState.points.isNotEmpty()) {
            val pts = trackerState.points
            Canvas(Modifier.fillMaxSize()) {
                var i = 0
                while (i < pts.size) {
                    drawCircle(WtColors.Teal.copy(alpha = 0.85f), dotRadius, Offset(pts[i], pts[i + 1]))
                    i += 2
                }
            }
        }

        if (locked) {
            // ---- Locked: draw the sketch where the tracker says the wall area is ----
            val bitmap = processed
            val trackedCorners = trackerState.corners
            val corners = trackedCorners ?: staticCorners
            if (bitmap != null && corners != null) {
                val lostFactor = if (trackerState.status == WallTracker.Status.LOST) 0.35f else 1f
                val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
                val matrix = remember { Matrix() }
                Canvas(Modifier.fillMaxSize()) {
                    val bw = bitmap.width.toFloat()
                    val bh = bitmap.height.toFloat()
                    val l = if (adjustments.flipHorizontal) bw else 0f
                    val r = if (adjustments.flipHorizontal) 0f else bw
                    val t = if (adjustments.flipVertical) bh else 0f
                    val b = if (adjustments.flipVertical) 0f else bh
                    val src = floatArrayOf(l, t, r, t, r, b, l, b)
                    if (matrix.setPolyToPoly(src, 0, corners, 0, 4)) {
                        paint.alpha = (adjustments.opacity * lostFactor * 255f).toInt().coerceIn(0, 255)
                        drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, matrix, paint) }
                        if (adjustments.showGrid) {
                            val cells = 8
                            val line = FloatArray(4)
                            for (k in 1 until cells) {
                                val fx = bw * k / cells
                                val fy = bh * k / cells
                                floatArrayOf(fx, 0f, fx, bh).copyInto(line)
                                matrix.mapPoints(line)
                                drawLine(WtColors.Teal, Offset(line[0], line[1]), Offset(line[2], line[3]), gridStroke)
                                floatArrayOf(0f, fy, bw, fy).copyInto(line)
                                matrix.mapPoints(line)
                                drawLine(WtColors.Teal, Offset(line[0], line[1]), Offset(line[2], line[3]), gridStroke)
                            }
                        }
                    }
                }
            }
        } else {
            // ---- Positioning: gestures move / scale / rotate the sketch on screen ----
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (!drawingMode) {
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
                val bitmap = processed
                val image = processedImage
                if (bitmap != null && image != null && viewSize.width > 0) {
                    val (wPx, hPx) = sketchBoxPx(bitmap)
                    Image(
                        bitmap = image,
                        contentDescription = "Sketch overlay",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
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
                                drawRect(WtColors.Teal, style = Stroke(width = borderStroke))
                            }
                    )
                }
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
                TrackingStatusChip(locked = locked, state = trackerState, modifier = Modifier.weight(1f))
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
                    hintText(locked, trackerState),
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
                    canLock = processed != null,
                    showAdjust = showAdjust,
                    busy = busy,
                    onToggleAdjust = { showAdjust = !showAdjust },
                    onAdjustmentsChange = viewModel::updateAdjustments,
                    onToggleLock = { if (locked) unlockNow() else lockNow() },
                    onReset = {
                        unlockNow()
                        offset = Offset.Zero
                        scale = 1f
                        rotation = 0f
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

/**
 * Binds Preview + ImageAnalysis with the PreviewView's ViewPort so the analysed crop matches exactly
 * what is on screen. Returns the analysis use case, or null when binding failed.
 */
private fun bindCamera(
    provider: ProcessCameraProvider,
    previewView: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    tracker: WallTracker,
    executor: java.util.concurrent.Executor
): ImageAnalysis? {
    val aspect = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    val preview = Preview.Builder()
        .setResolutionSelector(aspect.build())
        .build()
    preview.setSurfaceProvider(previewView.surfaceProvider)
    val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .build()
    analysis.setAnalyzer(executor, tracker)

    val viewPort = previewView.viewPort
    val group = UseCaseGroup.Builder().apply {
        if (viewPort != null) setViewPort(viewPort)
        addUseCase(preview)
        addUseCase(analysis)
    }.build()
    return try {
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
        analysis
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "Camera bind failed", e)
        analysis.clearAnalyzer()
        null
    } catch (e: IllegalStateException) {
        Log.e(TAG, "Camera bind failed", e)
        analysis.clearAnalyzer()
        null
    }
}

private fun hintText(locked: Boolean, state: WallTracker.State): String = when {
    !locked -> "Place the sketch, aim so the wall around it shows dots, then LOCK"
    state.status == WallTracker.Status.UNAVAILABLE -> "Locked on screen. Keep the phone still while you trace."
    state.status == WallTracker.Status.LOST -> "Point the camera back at the locked area to restore the sketch."
    else -> "The sketch follows the wall. Move slowly and keep the area in view."
}

@Composable
private fun TrackingStatusChip(locked: Boolean, state: WallTracker.State, modifier: Modifier = Modifier) {
    val (text, color) = when {
        !locked -> when {
            state.status == WallTracker.Status.UNAVAILABLE -> "Wall tracking unavailable" to WtColors.Warning
            state.featureCount >= GOOD_TEXTURE_POINTS -> "Wall texture: good · ${state.featureCount} points" to WtColors.Teal
            state.featureCount >= FAIR_TEXTURE_POINTS -> "Wall texture: fair · ${state.featureCount} points" to WtColors.Warning
            else -> "Wall texture: low · add light or marks" to WtColors.Error
        }
        state.status == WallTracker.Status.TRACKING -> "Locked to wall · ${state.featureCount} points" to WtColors.Teal
        state.status == WallTracker.Status.WEAK -> "Locked · weak tracking, move slowly" to WtColors.Warning
        state.status == WallTracker.Status.LOST -> "Tracking lost · aim at the locked area" to WtColors.Error
        state.status == WallTracker.Status.UNAVAILABLE -> "Locked on screen (no tracking)" to WtColors.Warning
        else -> "Locking…" to WtColors.Text
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
                .background(color)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
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
    canLock: Boolean,
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
                    enabled = canLock,
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
                    Text("LOCK TO WALL")
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
        source.scale(maxOf(1, (source.width * factor).toInt()), maxOf(1, (source.height * factor).toInt()))
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
private const val GOOD_TEXTURE_POINTS = 80
private const val FAIR_TEXTURE_POINTS = 30
