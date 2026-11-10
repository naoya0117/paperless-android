package com.naoya0117.paperless.feature.uploads

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage

/**
 * Scanning a paper document: pages taken with the camera ([startWithCamera]) or picked images, checked
 * (turned, reordered, removed), then uploaded as one PDF.
 *
 * @param onClose the scan was uploaded or given up
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    startWithCamera: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Picked images to start with (taken over once, then [onImagesTaken]). */
    initialImages: List<android.net.Uri> = emptyList(),
    onImagesTaken: () -> Unit = {},
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(initialImages) {
        if (initialImages.isNotEmpty()) {
            viewModel.addImages(initialImages)
            onImagesTaken()
        }
    }
    var camera by rememberSaveable { mutableStateOf(startWithCamera) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val currentOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                // Closed when done (below), also if the app was in the background then.
                ScanEvent.Queued -> Unit
                ScanEvent.NotSignedIn -> snackbar.showSnackbar(resources.getString(R.string.uploads_not_signed_in_scan))
                ScanEvent.Failed -> snackbar.showSnackbar(resources.getString(R.string.scan_failed))
            }
        }
    }
    LifecycleResumeEffect(state.done) {
        if (state.done) currentOnClose()
        onPauseOrDispose {}
    }
    val leave = {
        when {
            camera && state.pages.isNotEmpty() -> camera = false
            state.pages.isNotEmpty() -> confirmDiscard = true
            else -> {
                viewModel.discard()
                onClose()
            }
        }
    }
    BackHandler(enabled = !state.working, onBack = leave)
    if (camera) {
        CameraCapture(
            pages = state.pages,
            newFile = viewModel::newPageFile,
            onTaken = viewModel::addPage,
            onDone = { camera = false },
            onClose = leave,
            modifier = modifier,
        )
    } else {
        Scaffold(
            modifier = modifier,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.scan_title)) },
                    navigationIcon = {
                        IconButton(onClick = leave, enabled = !state.working) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.scan_close))
                        }
                    },
                    actions = {
                        IconButton(onClick = { camera = true }, enabled = !state.working) {
                            Icon(Icons.Outlined.AddAPhoto, contentDescription = stringResource(R.string.scan_add_pages))
                        }
                    },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = viewModel::upload,
                        enabled = state.pages.isNotEmpty() && !state.working,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    ) {
                        if (state.working) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.scan_upload, state.pages.size))
                        }
                    }
                }
            },
        ) { padding ->
            PageReview(state, viewModel, Modifier.padding(padding))
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            text = { Text(stringResource(R.string.scan_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        viewModel.discard()
                        onClose()
                    },
                ) { Text(stringResource(R.string.scan_discard)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDiscard = false },
                ) { Text(stringResource(R.string.scan_keep)) }
            },
        )
    }
}

/** The pages to check: title, the document filter, and per page turn, move and remove. */
@Composable
private fun PageReview(
    state: ScanState,
    viewModel: ScanViewModel,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        item(span = {
            androidx.compose.foundation.lazy.grid
                .GridItemSpan(maxLineSpan)
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::setTitle,
                    enabled = !state.working,
                    label = { Text(stringResource(R.string.scan_name)) },
                    placeholder = { Text(stringResource(R.string.scan_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.scan_enhance), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.scan_enhance_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.enhance, onCheckedChange = viewModel::setEnhance, enabled = !state.working)
                }
                if (state.pages.isEmpty() && !state.working) {
                    Text(
                        stringResource(R.string.scan_no_pages),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
        itemsIndexed(state.pages, key = { _, page -> page.file.name }) { index, page ->
            PageCard(
                page = page,
                number = index + 1,
                enhance = state.enhance,
                first = index == 0,
                last = index == state.pages.lastIndex,
                enabled = !state.working,
                onRotate = { viewModel.rotate(index) },
                onEarlier = { viewModel.move(index, -1) },
                onLater = { viewModel.move(index, 1) },
                onRemove = { viewModel.remove(index) },
            )
        }
    }
}

@Composable
private fun PageCard(
    page: ScanPage,
    number: Int,
    enhance: Boolean,
    first: Boolean,
    last: Boolean,
    enabled: Boolean,
    onRotate: () -> Unit,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(PAGE_ASPECT)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = page.file,
                contentDescription = stringResource(R.string.scan_page, number),
                contentScale = ContentScale.Fit,
                // A preview of the filter the PDF gets.
                colorFilter =
                    if (enhance) {
                        ColorFilter.colorMatrix(
                            ColorMatrix(ScanViewModel.documentFilter().array),
                        )
                    } else {
                        null
                    },
                // Sideways, the page is wider than the (portrait) box: smaller, so that nothing is cut off.
                modifier =
                    Modifier
                        .fillMaxSize()
                        .rotate(page.rotation.toFloat())
                        .scale(if (page.rotation % 180 == 0) 1f else PAGE_ASPECT),
            )
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onEarlier, enabled = enabled && !first) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.scan_move_earlier, number),
                )
            }
            IconButton(onClick = onRotate, enabled = enabled) {
                Icon(
                    Icons.AutoMirrored.Outlined.RotateRight,
                    contentDescription = stringResource(R.string.scan_rotate, number),
                )
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.scan_remove, number))
            }
            IconButton(onClick = onLater, enabled = enabled && !last) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = stringResource(R.string.scan_move_later, number),
                )
            }
        }
    }
}

/** The camera: a preview, a shutter, and the pages taken so far. */
@Composable
private fun CameraCapture(
    pages: List<ScanPage>,
    newFile: () -> java.io.File,
    onTaken: (java.io.File) -> Unit,
    onDone: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }

    /** Refused once already: asking again may not show anything (then the app's settings help). */
    var refused by rememberSaveable { mutableStateOf(false) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
            if (!it) refused = true
        }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    var taking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraPreview(capture, Modifier.fillMaxSize())
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            ) {
                Text(stringResource(R.string.scan_camera_permission), color = Color.White)
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.scan_allow_camera))
                }
                if (refused) {
                    TextButton(
                        onClick = {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        },
                    ) { Text(stringResource(R.string.scan_open_settings), color = Color.White) }
                }
            }
        }
        IconButton(
            onClick = onClose,
            enabled = !taking,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 8.dp),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.scan_close), tint = Color.White)
        }
        Column(
            modifier =
                Modifier
                    .align(
                        Alignment.BottomCenter,
                    ).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .navigationBarsPadding(),
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(THUMB_HEIGHT + 16.dp),
            ) {
                itemsIndexed(pages, key = { _, page -> page.file.name }) { index, page ->
                    AsyncImage(
                        model = page.file,
                        contentDescription = stringResource(R.string.scan_page, index + 1),
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .width(
                                    THUMB_HEIGHT * PAGE_ASPECT,
                                ).height(THUMB_HEIGHT)
                                .clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    if (failed) Text(stringResource(R.string.scan_capture_failed), color = Color.White)
                }
                FilledIconButton(
                    onClick = {
                        taking = true
                        failed = false
                        val file = newFile()
                        capture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    taking = false
                                    onTaken(file)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    taking = false
                                    failed = true
                                    file.delete()
                                }
                            },
                        )
                    },
                    enabled = granted && !taking,
                    modifier = Modifier.size(72.dp),
                ) {
                    if (taking) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    } else {
                        Icon(
                            Icons.Outlined.AddAPhoto,
                            contentDescription = stringResource(R.string.scan_shutter),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = onDone, enabled = pages.isNotEmpty() && !taking) {
                        Text(
                            stringResource(R.string.scan_done, pages.size),
                            color = if (pages.isNotEmpty()) Color.White else Color.Gray,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    capture: ImageCapture,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var unavailable by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, capture) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener(
            {
                // Not bound any more once the screen is gone (the provider can come late).
                if (disposed) return@addListener
                try {
                    val cameras = future.get()
                    // The back camera, else any (e.g. a tablet with a front camera only).
                    val selector =
                        if (cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        } else {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        }
                    val preview = Preview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
                    cameras.unbindAll()
                    cameras.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                    provider = cameras
                } catch (
                    @Suppress("TooGenericExceptionCaught") _: Exception,
                ) {
                    // No camera, or it cannot be opened.
                    unavailable = true
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed = true
            provider?.unbindAll()
        }
    }
    if (unavailable) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.scan_camera_unavailable),
                color = Color.White,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/** A4 portrait (width / height), for page previews. */
private const val PAGE_ASPECT = 0.707f
private val THUMB_HEIGHT = 56.dp
