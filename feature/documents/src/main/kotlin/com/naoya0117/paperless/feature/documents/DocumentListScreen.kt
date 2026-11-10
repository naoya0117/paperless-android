package com.naoya0117.paperless.feature.documents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.naoya0117.paperless.core.model.Document
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A list of documents with large thumbnails, so that their content can be recognised at a glance.
 *
 * @param onOpenDocument the user tapped a document
 */
@Composable
fun DocumentListScreen(
    onOpenDocument: (Document) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentListViewModel = hiltViewModel(),
) {
    val pages = viewModel.documentPages.collectAsLazyPagingItems()
    val labels by viewModel.labelNames.collectAsStateWithLifecycle()
    val searchText by viewModel.searchText.collectAsStateWithLifecycle()
    val accountId by viewModel.accountId.collectAsStateWithLifecycle()
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
            searchText = searchText,
            accountId = accountId,
            onRefresh = {
                viewModel.refreshLabels()
                pages.refresh()
            },
            onRetry = {
                viewModel.refreshLabels()
                pages.retry()
            },
            onOpenDocument = onOpenDocument,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentList(
    pages: LazyPagingItems<Document>,
    labels: LabelNames,
    mode: DocumentListMode,
    searchText: String,
    accountId: String?,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpenDocument: (Document) -> Unit,
) {
    val refresh = pages.loadState.refresh
    PullToRefreshBox(
        isRefreshing = refresh is LoadState.Loading && pages.itemCount > 0,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            pages.itemCount > 0 ->
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
                    if (refresh is LoadState.Error) item { ErrorBanner(onRetry = onRetry) }
                    items(count = pages.itemCount, key = pages.itemKey { it.id }) { index ->
                        pages[index]?.let { document ->
                            DocumentRow(document, labels, accountId, onClick = { onOpenDocument(document) })
                            HorizontalDivider(modifier = Modifier.padding(start = 136.dp))
                        }
                    }
                    when (pages.loadState.append) {
                        is LoadState.Loading -> item { LoadingRow() }
                        is LoadState.Error -> item { ErrorBanner(onRetry = onRetry) }
                        else -> Unit
                    }
                }
            refresh is LoadState.Loading -> Centered { CircularProgressIndicator() }
            // Scrollable like the empty states, so that pull to refresh works too.
            refresh is LoadState.Error -> Scrollable { ErrorBanner(onRetry = onRetry) }
            mode == DocumentListMode.SEARCH && searchText.isBlank() ->
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
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
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
