package com.walltrace.ar.ui

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ar.core.ArCoreApk
import com.walltrace.ar.MainViewModel
import com.walltrace.ar.data.Project
import com.walltrace.ar.ui.theme.WtColors
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(viewModel: MainViewModel) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Project?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    val availability by produceState<ArCoreApk.Availability?>(initialValue = null) {
        repeat(25) {
            val result = ArCoreApk.getInstance().checkAvailability(context)
            if (!result.isTransient) {
                value = result
                return@produceState
            }
            delay(200)
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.importImage(uri)
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importImage(uri)
    }

    Scaffold(
        containerColor = WtColors.Background,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("WallTrace AR", style = MaterialTheme.typography.headlineSmall, color = WtColors.Text)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Project any image onto a real wall and trace it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WtColors.TextMuted
                    )
                }
            }

            if (availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Banner("This device doesn't support ARCore, so wall tracking can't run here.")
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                ImportCard(
                    busy = busy,
                    onGallery = {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onFiles = { openDocument.launch(arrayOf("image/*")) }
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recent projects", style = MaterialTheme.typography.titleMedium, color = WtColors.Text)
                    Spacer(Modifier.weight(1f))
                    if (projects.isNotEmpty()) {
                        Text("${projects.size}", style = MaterialTheme.typography.labelMedium, color = WtColors.TextMuted)
                    }
                }
            }

            if (projects.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Imported images appear here with their settings, so you can continue later.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WtColors.TextMuted
                    )
                }
            } else {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        viewModel = viewModel,
                        enabled = !busy,
                        onOpen = { viewModel.openProject(project) },
                        onDelete = { pendingDelete = project }
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Projects restore your image and settings. Place the sketch on the wall again after reopening.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WtColors.TextMuted
                    )
                }
            }
        }
    }

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete project?") },
            text = { Text("\"${project.name}\" and its saved image will be removed from WallTrace.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProject(project)
                    pendingDelete = null
                }) { Text("Delete", color = WtColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
            containerColor = WtColors.Surface
        )
    }
}

@Composable
private fun ImportCard(busy: Boolean, onGallery: () -> Unit, onFiles: () -> Unit) {
    Surface(
        color = WtColors.Surface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(WtColors.SurfaceHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Image, contentDescription = null, tint = WtColors.Teal)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Start AR drawing", style = MaterialTheme.typography.titleMedium, color = WtColors.Text)
                    Text(
                        "Import an image (JPG, PNG, WEBP, HEIC)",
                        style = MaterialTheme.typography.bodySmall,
                        color = WtColors.TextMuted
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onGallery,
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WtColors.Teal, contentColor = WtColors.OnTeal)
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Gallery")
                }
                OutlinedButton(
                    onClick = onFiles,
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp), tint = WtColors.Text)
                    Spacer(Modifier.width(8.dp))
                    Text("Files", color = WtColors.Text)
                }
            }
            if (busy) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = WtColors.Teal,
                    trackColor = WtColors.SurfaceHigh
                )
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    viewModel: MainViewModel,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val thumbnail by produceState<Bitmap?>(initialValue = null, project.id, project.imageFileName) {
        value = viewModel.thumbnail(project)
    }
    Surface(
        color = WtColors.Surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onOpen)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(WtColors.SurfaceHigh),
                contentAlignment = Alignment.Center
            ) {
                val bmp = thumbnail
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = project.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Outlined.Image, contentDescription = null, tint = WtColors.TextMuted)
                }
            }
            Row(
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = WtColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        DateUtils.getRelativeTimeSpanString(project.updatedAt).toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = WtColors.TextMuted,
                        maxLines = 1
                    )
                }
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete ${project.name}", tint = WtColors.TextMuted)
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(WtColors.Surface)
            .border(1.dp, WtColors.Warning.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = WtColors.Warning)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = WtColors.Text)
    }
}
