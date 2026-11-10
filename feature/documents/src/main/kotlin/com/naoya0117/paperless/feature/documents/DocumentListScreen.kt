package com.naoya0117.paperless.feature.documents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.naoya0117.paperless.core.model.Document
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A list of documents with large thumbnails, so that their content can be recognised at a glance. The search
 * list has a search field on top.
 *
 * A long press on a document opens a menu (edit, share, open in another app, download, delete).
 *
 * @param onOpenDocument the user tapped a document
 * @param onEditDocument the user chose to edit a document (from its menu)
 * @param snackbar where the results of the menu's actions are shown
 * @param onRefresh the user pulled to refresh (e.g. to update the inbox count too)
 */
@Composable
fun DocumentListScreen(
    onOpenDocument: (Document) -> Unit,
    modifier: Modifier = Modifier,
    onEditDocument: (Long) -> Unit = {},
    snackbar: SnackbarHostState,
    onRefresh: () -> Unit = {},
    viewModel: DocumentListViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle, snackbar) {
        // Only while visible: another app cannot be started from the background (the event waits).
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                val message =
                    when (event) {
                        // Not long after it was asked for (e.g. back on the list minutes later).
                        is ViewerEvent.SendFile ->
                            if (event.stale ||
                                context.sendFile(event)
                            ) {
                                null
                            } else {
                                resources.getString(R.string.viewer_no_app)
                            }
                        is ViewerEvent.Saved -> resources.getString(R.string.viewer_saved, event.fileName)
                        ViewerEvent.Deleted -> resources.getString(R.string.documents_moved_to_trash)
                        is ViewerEvent.Failed -> resources.getString(event.message())
                        else -> null
                    }
                // Not waiting for it: the next event (e.g. a chooser to open) comes at once.
                if (message != null) launch { snackbar.showSnackbar(message) }
            }
        }
    }
    val pages = viewModel.documentPages.collectAsLazyPagingItems()
    val actionRunning by viewModel.actionRunning.collectAsStateWithLifecycle()

    /** The document to delete (after confirming), by ID: kept when the screen is recreated. */
    var confirmDelete by rememberSaveable { mutableStateOf<Long?>(null) }
    val menu =
        remember(viewModel, actionRunning, onEditDocument) {
            DocumentMenuActions(
                enabled = !actionRunning,
                onEdit = { onEditDocument(it.id) },
                onShare = viewModel::share,
                onOpen = viewModel::openExternally,
                onDownload = viewModel::download,
                onDelete = { confirmDelete = it.id },
            )
        }
    // Only for a document still listed: the user must see what goes.
    val toDelete = confirmDelete?.let { id -> pages.itemSnapshotList.items.firstOrNull { it.id == id } }
    // Gone from the loaded list: forgotten, so that the dialog does not come up on its own later.
    LaunchedEffect(confirmDelete, toDelete, pages.loadState.refresh) {
        if (confirmDelete != null &&
            toDelete == null &&
            pages.loadState.refresh is LoadState.NotLoading
        ) {
            confirmDelete = null
        }
    }
    toDelete?.let { document ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.viewer_delete_title)) },
            text = { Text(stringResource(R.string.viewer_delete_message, document.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        viewModel.delete(document)
                    },
                ) { Text(stringResource(R.string.viewer_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.viewer_cancel)) }
            },
        )
    }
    val activeSearch by viewModel.activeSearch.collectAsStateWithLifecycle()
    val labels by viewModel.labelNames.collectAsStateWithLifecycle()
    val searchText by viewModel.searchText.collectAsStateWithLifecycle()
    val accountId by viewModel.accountId.collectAsStateWithLifecycle()
    val viewMissing by viewModel.savedViewMissing.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        if (viewModel.mode == DocumentListMode.SEARCH) {
            OutlinedTextField(
                value = searchText,
                onValueChange = viewModel::setSearchText,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.documents_search_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        DocumentList(
            pages = pages,
            labels = labels,
            mode = viewModel.mode,
            typedSearch = searchText,
            activeSearch = activeSearch,
            accountId = accountId,
            viewMissing = viewMissing,
            onRefresh = {
                viewModel.refreshLabels()
                pages.refresh()
                onRefresh()
            },
            onRetry = {
                viewModel.refreshLabels()
                pages.retry()
            },
            onOpenDocument = onOpenDocument,
            menu = menu,
        )
    }
}

/** The long-press menu's actions on a document; [enabled] is `false` while one runs. */
private class DocumentMenuActions(
    val enabled: Boolean,
    val onEdit: (Document) -> Unit,
    val onShare: (Document) -> Unit,
    val onOpen: (Document) -> Unit,
    val onDownload: (Document) -> Unit,
    val onDelete: (Document) -> Unit,
)

@Composable
private fun DocumentMenu(
    document: Document,
    actions: DocumentMenuActions,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        @Composable
        fun item(
            text: Int,
            icon: ImageVector,
            onClick: (Document) -> Unit,
            enabled: Boolean = actions.enabled,
        ) = DropdownMenuItem(
            text = { Text(stringResource(text)) },
            leadingIcon = { Icon(icon, contentDescription = null) },
            enabled = enabled,
            onClick = {
                onDismiss()
                onClick(document)
            },
        )
        // Editing and deleting need permission to change the document.
        // Editing does not wait for a file action.
        if (document.userCanChange) item(R.string.viewer_edit, Icons.Outlined.Edit, actions.onEdit, enabled = true)
        item(R.string.viewer_share, Icons.Outlined.Share, actions.onShare)
        item(R.string.viewer_open_with, Icons.AutoMirrored.Outlined.OpenInNew, actions.onOpen)
        item(R.string.viewer_download, Icons.Outlined.Download, actions.onDownload)
        if (document.userCanChange) {
            HorizontalDivider()
            item(R.string.viewer_delete, Icons.Outlined.Delete, actions.onDelete)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentList(
    pages: LazyPagingItems<Document>,
    labels: LabelNames,
    mode: DocumentListMode,
    typedSearch: String,
    activeSearch: String,
    accountId: String?,
    viewMissing: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpenDocument: (Document) -> Unit,
    menu: DocumentMenuActions,
) {
    val refresh = pages.loadState.refresh

    /** The document whose menu is open. */
    var menuFor by rememberSaveable { mutableStateOf<Long?>(null) }
    // Text typed but not searched yet (typing pauses first): show progress, not the prompt.
    val searchPending =
        mode == DocumentListMode.SEARCH && typedSearch.isNotBlank() && typedSearch.trim() != activeSearch
    // Nothing to refresh on the search tab before a search.
    val canRefresh = mode != DocumentListMode.SEARCH || activeSearch.isNotEmpty()
    PullToRefreshBox(
        isRefreshing = refresh is LoadState.Loading && pages.itemCount > 0,
        onRefresh = { if (canRefresh) onRefresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            pages.itemCount > 0 ->
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
                    if (refresh is LoadState.Error) item { ErrorBanner(onRetry = onRetry) }
                    items(count = pages.itemCount, key = pages.itemKey { it.id }) { index ->
                        pages[index]?.let { document ->
                            Box {
                                DocumentRow(
                                    document,
                                    labels,
                                    accountId,
                                    onClick = { onOpenDocument(document) },
                                    // combinedClickable gives the long-press feedback itself.
                                    onLongClick = { menuFor = document.id },
                                )
                                // Gone from the list (e.g. refreshed away) with its menu open: it does not reopen later.
                                DisposableEffect(document.id) {
                                    onDispose {
                                        if (menuFor ==
                                            document.id
                                        ) {
                                            menuFor = null
                                        }
                                    }
                                }
                                DocumentMenu(
                                    document = document,
                                    actions = menu,
                                    expanded = menuFor == document.id,
                                    onDismiss = { menuFor = null },
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 136.dp))
                        }
                    }
                    when (pages.loadState.append) {
                        is LoadState.Loading -> item { LoadingRow() }
                        is LoadState.Error -> item { ErrorBanner(onRetry = onRetry) }
                        else -> Unit
                    }
                }
            refresh is LoadState.Loading || searchPending -> Centered { CircularProgressIndicator() }
            // Scrollable like the empty states, so that pull to refresh works too.
            refresh is LoadState.Error -> Scrollable { ErrorBanner(onRetry = onRetry) }
            viewMissing -> EmptyState(Icons.Outlined.SearchOff, stringResource(R.string.documents_view_missing))
            mode == DocumentListMode.SEARCH && typedSearch.isBlank() ->
                EmptyState(Icons.Outlined.Search, stringResource(R.string.documents_search_prompt))
            mode == DocumentListMode.SEARCH ->
                EmptyState(
                    Icons.Outlined.SearchOff,
                    stringResource(R.string.documents_no_results),
                )
            mode == DocumentListMode.INBOX ->
                EmptyState(
                    Icons.Outlined.Inbox,
                    stringResource(R.string.documents_inbox_empty),
                )
            else -> EmptyState(Icons.Outlined.Description, stringResource(R.string.documents_empty))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentRow(
    document: Document,
    labels: LabelNames,
    accountId: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = stringResource(R.string.documents_open),
                    onLongClickLabel = stringResource(R.string.documents_menu),
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ThumbnailImage(accountId, document)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = document.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = document.created.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            document.correspondentId?.let(labels.correspondents::get)?.let { name ->
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            document.documentTypeId?.let(labels.documentTypes::get)?.let { name ->
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val tags = document.tagIds.mapNotNull(labels.tags::get)
            if (tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tags.forEach { TagChip(it) }
                }
            }
        }
    }
}

/** Large enough to recognise the document (thumbnails are a page preview). */
@Composable
private fun ThumbnailImage(
    accountId: String?,
    document: Document,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier =
            Modifier
                .size(width = THUMBNAIL_WIDTH.dp, height = THUMBNAIL_HEIGHT.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        // Shown while loading and when the thumbnail cannot be loaded; the image covers it.
        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        if (accountId != null) {
            AsyncImage(
                model = DocumentThumbnail(accountId, document),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun TagChip(tag: TagLabel) {
    val background = parseColor(tag.color) ?: MaterialTheme.colorScheme.secondaryContainer
    val text = parseColor(tag.textColor) ?: MaterialTheme.colorScheme.onSecondaryContainer
    Text(
        text = tag.name,
        style = MaterialTheme.typography.labelSmall,
        color = text,
        maxLines = 1,
        modifier =
            Modifier
                .clip(
                    RoundedCornerShape(50),
                ).background(background)
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun parseColor(hex: String): Color? =
    hex
        .removePrefix("#")
        .takeIf { it.length == 6 }
        ?.toLongOrNull(16)
        ?.let { Color(0xFF000000 or it) }

@Composable
private fun ErrorBanner(onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.documents_load_error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.documents_retry)) }
    }
}

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Scrollable(content: @Composable () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) { item { content() } }
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    message: String,
) {
    // Scrollable, so that pull to refresh works on an empty list too.
    LazyColumn(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Column(
                modifier = Modifier.padding(top = 96.dp, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(48.dp),
                )
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val THUMBNAIL_WIDTH = 104
private const val THUMBNAIL_HEIGHT = 140
