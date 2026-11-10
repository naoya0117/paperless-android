package com.naoya0117.paperless.feature.documents

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.Note
import kotlinx.coroutines.CancellationException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The document's pages, scrolled vertically. Tapping the document switches to full screen (and back); the
 * info button shows its metadata with an edit button, the search button searches within it.
 *
 * @param onEdit opens the editor for the document
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val infoVisible by viewModel.infoVisible.collectAsStateWithLifecycle()
    val fullscreen by viewModel.fullscreen.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    SystemBarsHidden(fullscreen)
    // Back closes the search; otherwise it leaves the viewer (also in full screen, which a scroll turns on).
    BackHandler(enabled = search.open, onBack = viewModel::closeSearch)
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val currentOnBack by rememberUpdatedState(onBack)

    /** The note being written (kept while the details are hidden, cleared once added). */
    var noteText by rememberSaveable { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        // Only while visible: another app cannot be started from the background (the event waits).
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is ViewerEvent.SendFile ->
                        // Not long after it was asked for (e.g. back from the editor minutes later).
                        if (!event.stale && !context.sendFile(event)) {
                            snackbar.showSnackbar(resources.getString(R.string.viewer_no_app))
                        }
                    is ViewerEvent.Saved ->
                        snackbar.showSnackbar(
                            resources.getString(R.string.viewer_saved, event.fileName),
                        )
                    ViewerEvent.Deleted -> currentOnBack()
                    is ViewerEvent.NoteAdded -> if (noteText.trim() == event.text) noteText = ""
                    is ViewerEvent.Failed -> snackbar.showSnackbar(resources.getString(event.message()))
                }
            }
        }
    }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // Scrolling down hides the bars (full screen), scrolling up shows them again.
    val threshold = with(LocalDensity.current) { BARS_SCROLL_THRESHOLD.toPx() }
    val hideBarsOnScroll = remember(viewModel, threshold) { HideBarsOnScroll(threshold, viewModel::onScrolled) }
    Scaffold(
        modifier = modifier,
        // Above the navigation bar, unless the text match card (which clears it) is below; above the keyboard.
        snackbarHost = {
            val card = search.currentMatch is SearchMatch.InText
            SnackbarHost(snackbar, if (card) Modifier.imePadding() else Modifier.navigationBarsPadding().imePadding())
        },
        // The document goes behind the bars, so that it stays put when they hide.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            (search.currentMatch as? SearchMatch.InText)?.let { TextMatchCard(it) }
        },
    ) { padding ->
        @OptIn(ExperimentalLayoutApi::class)
        val barSpace =
            // As tall as the bar with its insets (e.g. a cutout taller than the status bar).
            viewerBarInsets().asPaddingValues().calculateTopPadding() + TopAppBarDefaults.TopAppBarExpandedHeight

        @OptIn(ExperimentalLayoutApi::class)
        val bottomSpace =
            maxOf(
                padding.calculateBottomPadding(),
                WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding(),
            )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .nestedScroll(hideBarsOnScroll),
            contentAlignment = Alignment.Center,
        ) {
            // Pinch to zoom; reset for other content.
            val zoom = remember(state.content) { ZoomState() }

            // Landscape: clear of a navigation bar or cutout at the side.
            @OptIn(ExperimentalLayoutApi::class)
            val sides =
                WindowInsets.systemBarsIgnoringVisibility
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal)
                    .asPaddingValues()
            val direction = LocalLayoutDirection.current
            val space =
                PaddingValues(
                    start = sides.calculateStartPadding(direction),
                    end = sides.calculateEndPadding(direction),
                    top = barSpace,
                    bottom = bottomSpace,
                )
            when (val content = state.content) {
                ViewerContent.Loading -> CircularProgressIndicator()
                is ViewerContent.Pdf ->
                    PdfPagesView(
                        pages = content.pages,
                        onTap = viewModel::toggleFullscreen,
                        search = search,
                        space = space,
                        modifier = Modifier.zoomable(zoom),
                    )
                is ViewerContent.Image ->
                    ImageView(content, viewModel::toggleFullscreen, space, Modifier.zoomable(zoom))
                is ViewerContent.Unsupported ->
                    Message(stringResource(R.string.viewer_unsupported, content.fileName.orEmpty()))
                ViewerContent.Failed ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Message(stringResource(R.string.viewer_failed))
                        TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.documents_retry)) }
                    }
            }
            AnimatedVisibility(
                visible = !fullscreen,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                if (search.open) {
                    SearchBar(
                        search = search,
                        onQueryChange = viewModel::setSearchQuery,
                        onPrevious = viewModel::previousMatch,
                        onNext = viewModel::nextMatch,
                        onClose = viewModel::closeSearch,
                    )
                } else {
                    TopAppBar(
                        // Not shrinking while the status bar hides with it.
                        windowInsets = viewerBarInsets(),
                        title = {
                            Text(
                                state.document?.title.orEmpty(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = stringResource(R.string.viewer_back),
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = viewModel::toggleInfo, enabled = state.document != null) {
                                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.viewer_info))
                            }
                            if (state.busy) {
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                }
                            }
                            // Always there: searching works while a file action runs (those wait).
                            run {
                                Box {
                                    IconButton(onClick = { menuOpen = true }, enabled = state.document != null) {
                                        Icon(
                                            Icons.Outlined.MoreVert,
                                            contentDescription = stringResource(R.string.viewer_more),
                                        )
                                    }
                                    ViewerMenu(
                                        expanded = menuOpen,
                                        onDismiss = { menuOpen = false },
                                        onSearch = viewModel::openSearch.takeIf { state.content.searchable },
                                        onShare = viewModel::share,
                                        onOpen = viewModel::openExternally,
                                        onDownload = viewModel::download,
                                        onDelete =
                                            { confirmDelete = true }.takeIf {
                                                state.document?.userCanChange ==
                                                    true
                                            },
                                        actionsEnabled = !state.busy,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
    val document = state.document
    if (confirmDelete && document != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.viewer_delete_title)) },
            text = { Text(stringResource(R.string.viewer_delete_message, document.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete()
                    },
                ) { Text(stringResource(R.string.viewer_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.viewer_cancel)) }
            },
        )
    }
    if (infoVisible && document != null) {
        ModalBottomSheet(onDismissRequest = viewModel::hideInfo) {
            DocumentInfo(
                document = document,
                labels = state.labels,
                onEdit = {
                    viewModel.hideInfo()
                    onEdit(document.id)
                },
                notes =
                    NoteActions(
                        text = noteText,
                        onTextChange = { noteText = it },
                        busy = state.busy,
                        onAdd = { viewModel.addNote(noteText) },
                        onDelete = viewModel::deleteNote,
                    ),
            )
        }
    }
}

@Composable
private fun ViewerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    /** `null` if the content cannot be searched. */
    onSearch: (() -> Unit)?,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDelete: (() -> Unit)?,
    /** File actions and deleting wait while one runs; the search does not. */
    actionsEnabled: Boolean,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        @Composable
        fun item(
            text: Int,
            icon: ImageVector,
            onClick: () -> Unit,
            enabled: Boolean = actionsEnabled,
        ) = DropdownMenuItem(
            text = { Text(stringResource(text)) },
            leadingIcon = { Icon(icon, contentDescription = null) },
            enabled = enabled,
            onClick = {
                onDismiss()
                onClick()
            },
        )
        onSearch?.let { item(R.string.viewer_search, Icons.Outlined.Search, it, enabled = true) }
        item(R.string.viewer_share, Icons.Outlined.Share, onShare)
        item(R.string.viewer_open_with, Icons.AutoMirrored.Outlined.OpenInNew, onOpen)
        item(R.string.viewer_download, Icons.Outlined.Download, onDownload)
        // Only with permission to change the document (like in the lists).
        if (onDelete != null) {
            HorizontalDivider()
            item(R.string.viewer_delete, Icons.Outlined.Delete, onDelete)
        }
    }
}

internal fun ViewerEvent.Failed.message(): Int =
    when (action) {
        FileAction.SHARE, FileAction.OPEN ->
            if (reason == FailureReason.STORAGE) R.string.viewer_file_storage_failed else R.string.viewer_file_failed
        FileAction.DOWNLOAD ->
            if (reason == FailureReason.STORAGE) R.string.viewer_save_failed else R.string.viewer_file_failed
        FileAction.DELETE -> R.string.viewer_delete_failed
        FileAction.ADD_NOTE -> R.string.viewer_note_add_failed
        FileAction.DELETE_NOTE -> R.string.viewer_note_delete_failed
    }

/** Starts an app for the file (a chooser to share it); `false` if there is none. */
internal fun Context.sendFile(event: ViewerEvent.SendFile): Boolean {
    val intent =
        if (event.open) {
            Intent(Intent.ACTION_VIEW).setDataAndType(event.uri, event.mimeType)
        } else {
            val send =
                Intent(Intent.ACTION_SEND)
                    .setType(event.mimeType)
                    .putExtra(Intent.EXTRA_STREAM, event.uri)
                    // Set before the chooser is made: it passes the grant on only if the shared intent has it.
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(null, event.uri)
            Intent.createChooser(send, null)
        }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** Pages one below the other, rendered when they come into view. */
@Composable
private fun PdfPagesView(
    pages: PdfPages,
    onTap: () -> Unit,
    search: SearchState,
    /** Kept free above and below (behind the bars). */
    space: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val pageMatches =
        remember(search.matches) { search.matches.filterIsInstance<SearchMatch.InPage>().groupBy { it.page } }
    val current = search.currentMatch as? SearchMatch.InPage
    val padding = with(LocalDensity.current) { PAGE_PADDING.toPx() }
    // The current match comes into view: its page, scrolled so that the match is in the upper third.
    LaunchedEffect(current, search.moves) {
        val match = current ?: return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize
        val aspect = pages.knownAspectRatio(match.page) ?: PdfPages.A4_ASPECT
        val pageWidth = viewport.width - 2 * padding
        val top = (match.bounds.minOfOrNull { it.top } ?: 0f) * pageWidth * aspect
        listState.animateScrollToItem(match.page, (top - viewport.height / 3).toInt().coerceAtLeast(0))
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().tapWhenNotScrolling({ listState.isScrollInProgress }, onTap),
        contentPadding =
            PaddingValues(
                start = space.calculateStartPadding(LocalLayoutDirection.current) + PAGE_PADDING,
                end = space.calculateEndPadding(LocalLayoutDirection.current) + PAGE_PADDING,
                top = space.calculateTopPadding() + PAGE_PADDING,
                bottom = space.calculateBottomPadding() + PAGE_PADDING,
            ),
        verticalArrangement = Arrangement.spacedBy(PAGE_PADDING),
    ) {
        items(count = pages.pageCount, key = { it }) { index ->
            PdfPage(pages, index, highlights = pageMatches[index].orEmpty(), current = current)
        }
    }
}

@Composable
private fun PdfPage(
    pages: PdfPages,
    index: Int,
    highlights: List<SearchMatch.InPage>,
    current: SearchMatch.InPage?,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bitmap by produceState<ImageBitmap?>(null, pages, index, widthPx) {
            value = safely { pages.render(index, widthPx).asImageBitmap() }
        }
        // Known once rendered; until then the page takes the space of an A4 page.
        val aspect =
            bitmap?.let { it.height.toFloat() / it.width } ?: pages.knownAspectRatio(index) ?: PdfPages.A4_ASPECT
        val description = stringResource(R.string.viewer_page, index + 1, pages.pageCount)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1 / aspect)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            if (image == null) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Image(
                    image,
                    contentDescription = description,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (highlights.isNotEmpty()) Highlights(highlights, current)
        }
    }
}

private val PAGE_PADDING = 12.dp

/** The top bar's insets, as if the system bars were shown (they hide and show with it). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun viewerBarInsets(): WindowInsets =
    WindowInsets.systemBarsIgnoringVisibility
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

/** How far a scroll goes before the bars hide or show, so that small moves do not toggle them. */
private val BARS_SCROLL_THRESHOLD = 32.dp

/** Reports scrolling by the user: [onScrolled] with `true` for down (towards the end), `false` for up. */
private class HideBarsOnScroll(
    private val threshold: Float,
    private val onScrolled: (down: Boolean) -> Unit,
) : NestedScrollConnection {
    private var distance = 0f

    override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (source == NestedScrollSource.UserInput && available.y != 0f) {
            // A change of direction starts counting again.
            if (distance != 0f && (distance < 0) != (available.y < 0)) distance = 0f
            distance += available.y
            if (kotlin.math.abs(distance) > threshold) {
                onScrolled(distance < 0)
                distance = 0f
            }
        }
        return Offset.Zero
    }
}

/** The search matches on a page; the current one stands out. */
@Composable
private fun Highlights(
    matches: List<SearchMatch.InPage>,
    current: SearchMatch.InPage?,
) {
    val other = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
    val selected = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    Canvas(Modifier.fillMaxSize()) {
        matches.forEach { match ->
            val color = if (match == current) selected else other
            match.bounds.forEach { rect ->
                drawRect(
                    color = color,
                    topLeft = Offset(rect.left * size.width, rect.top * size.height),
                    size = Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height),
                )
            }
        }
    }
}

/** Search field with the number of matches and buttons to the previous and next one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    search: SearchState,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        windowInsets = viewerBarInsets(),
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.viewer_search_close),
                )
            }
        },
        title = {
            TextField(
                value = search.query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.viewer_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            val count =
                when {
                    search.searching -> null
                    search.query.isBlank() -> null
                    search.matches.isEmpty() -> stringResource(R.string.viewer_search_none)
                    else -> stringResource(R.string.viewer_search_position, search.current + 1, search.matches.size)
                }
            if (search.searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            // Always there (a live region is announced when it changes, not when it appears).
            Text(
                if (search.searching) "" else count.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            // A single match on a page: the buttons bring it back into view.
            val enabled = search.matches.size > 1 || search.currentMatch is SearchMatch.InPage
            IconButton(onClick = onPrevious, enabled = enabled) {
                Icon(
                    Icons.Outlined.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.viewer_search_previous),
                )
            }
            IconButton(onClick = onNext, enabled = enabled) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.viewer_search_next))
            }
        },
    )
}

/** A match in the document's text (where its position on a page is not known), with the text around it. */
@Composable
private fun TextMatchCard(match: SearchMatch.InText) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(16.dp)) {
            Text(
                stringResource(R.string.viewer_search_in_text),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                buildAnnotatedString {
                    if (match.cutBefore) append("…")
                    append(match.before)
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            background = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        append(match.match)
                    }
                    append(match.after)
                    if (match.cutAfter) append("…")
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Whether the content can be searched (in its pages or its text). */
private val ViewerContent.searchable: Boolean
    get() = this is ViewerContent.Pdf || this is ViewerContent.Image || this is ViewerContent.Unsupported

/** Hides the status and navigation bars while [hidden] (a swipe shows them for a moment). */
@Composable
private fun SystemBarsHidden(hidden: Boolean) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    DisposableEffect(hidden, activity, view) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) {
            controller.hide(
                WindowInsetsCompat.Type.systemBars(),
            )
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Rendering stops (returns `null`) when the pages were closed meanwhile or the page cannot be rendered. */
private suspend fun <T> safely(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") _: Exception,
    ) {
        null
    }

@Composable
private fun ImageView(
    content: ViewerContent.Image,
    onTap: () -> Unit,
    space: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .tapWhenNotScrolling({ false }, onTap)
                .padding(space)
                .padding(12.dp),
    ) {
        AsyncImage(
            model = content.file,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Message(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp))
}

/** The metadata of the document, with the edit button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentInfo(
    document: Document,
    labels: LabelNames,
    onEdit: () -> Unit,
    notes: NoteActions,
) {
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val dateTime =
        DateTimeFormatter
            .ofLocalizedDateTime(
                FormatStyle.MEDIUM,
                FormatStyle.SHORT,
            ).withZone(ZoneId.systemDefault())
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(
                    rememberScrollState(),
                ).padding(horizontal = 24.dp)
                .navigationBarsPadding()
                // The note field stays above the keyboard.
                .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(document.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (document.userCanChange) {
                Button(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.viewer_edit), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        val tags = document.tagIds.mapNotNull(labels.tags::get)
        if (tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.forEach { TagChip(it) }
            }
        }
        InfoRow(R.string.info_created, document.created.format(date))
        InfoRow(R.string.info_correspondent, document.correspondentId?.let(labels.correspondents::get))
        InfoRow(R.string.info_document_type, document.documentTypeId?.let(labels.documentTypes::get))
        InfoRow(R.string.info_storage_path, document.storagePathId?.let(labels.storagePaths::get))
        InfoRow(R.string.info_archive_serial_number, document.archiveSerialNumber?.toString())
        InfoRow(R.string.info_pages, document.pageCount?.toString())
        InfoRow(R.string.info_original_file_name, document.originalFileName)
        InfoRow(R.string.info_added, dateTime.format(document.added))
        InfoRow(R.string.info_modified, dateTime.format(document.modified))
        CustomFieldRows(document, labels)
        // Without notes (not allowed to see them, or not loaded) there is no notes section.
        document.notes?.let { NotesSection(it, notes, canChange = document.userCanChange, dateTime = dateTime) }
        androidx.compose.foundation.layout
            .Spacer(Modifier.size(16.dp))
    }
}

/** What the notes section can do; [onAdd] adds [text]. */
private class NoteActions(
    val text: String,
    val onTextChange: (String) -> Unit,
    val busy: Boolean,
    val onAdd: () -> Unit,
    val onDelete: (Long) -> Unit,
)

@Composable
private fun CustomFieldRows(
    document: Document,
    labels: LabelNames,
) {
    if (document.customFields.isEmpty()) return
    HorizontalDivider()
    Text(stringResource(R.string.info_custom_fields), style = MaterialTheme.typography.titleSmall)
    document.customFields.forEach { value ->
        val field = labels.customFields[value.fieldId]
        InfoRow(
            label = field?.name ?: stringResource(R.string.info_custom_field_unknown, value.fieldId),
            value = formatFieldValue(field, value.value),
        )
    }
}

@Composable
private fun NotesSection(
    notes: List<Note>,
    actions: NoteActions,
    canChange: Boolean,
    dateTime: DateTimeFormatter,
) {
    var confirmDelete by rememberSaveable { mutableStateOf<Long?>(null) }
    confirmDelete?.let { noteId ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            text = { Text(stringResource(R.string.info_note_delete_message)) },
            confirmButton = {
                TextButton(
                    // Not while another action runs (it would be ignored).
                    enabled = !actions.busy,
                    onClick = {
                        confirmDelete = null
                        actions.onDelete(noteId)
                    },
                ) { Text(stringResource(R.string.viewer_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.viewer_cancel)) }
            },
        )
    }
    HorizontalDivider()
    Text(stringResource(R.string.info_notes), style = MaterialTheme.typography.titleSmall)
    notes.forEach { note ->
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(note.text, style = MaterialTheme.typography.bodyMedium)
                val author =
                    note.user?.let { user ->
                        listOfNotNull(user.firstName, user.lastName)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .ifBlank { user.username }
                    }
                Text(
                    listOfNotNull(author, dateTime.format(note.created)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Only with permission to change the document (the server requires it).
            if (canChange) {
                IconButton(onClick = { confirmDelete = note.id }, enabled = !actions.busy) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription =
                            stringResource(
                                R.string.info_note_delete_named,
                                note.text.take(NOTE_LABEL_LENGTH),
                            ),
                    )
                }
            }
        }
    }
    if (canChange) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = actions.text,
                onValueChange = actions.onTextChange,
                placeholder = { Text(stringResource(R.string.info_note_hint)) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = actions.onAdd, enabled = !actions.busy && actions.text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.info_note_add))
            }
        }
    }
}

/** How much of a note names its delete button (for screen readers). */
private const val NOTE_LABEL_LENGTH = 40

@Composable
private fun InfoRow(
    label: String,
    value: String?,
) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp),
        )
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoRow(
    label: Int,
    value: String?,
) = InfoRow(stringResource(label), value)

/**
 * Taps switch to full screen, but not a tap that stops a fling (it was scrolling when the finger went down).
 */
private fun Modifier.tapWhenNotScrolling(
    scrolling: () -> Boolean,
    onTap: () -> Unit,
): Modifier =
    pointerInput(Unit) {
        var wasScrolling = false
        detectTapGestures(
            onPress = { wasScrolling = scrolling() },
            onTap = { if (!wasScrolling) onTap() },
        )
    }
