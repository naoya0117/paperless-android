package com.naoya0117.paperless.feature.documents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.SavedView

/**
 * The home screen: the saved views of the dashboard (as set in the web UI), each with its first documents.
 *
 * @param onOpenDocument the user tapped a document
 * @param onOpenView the user wants all documents of a view
 * @param onRefresh the user pulled to refresh (e.g. to update the inbox count too)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDocument: (Document) -> Unit,
    onOpenView: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accountId by viewModel.accountId.collectAsStateWithLifecycle()
    val refreshCount by viewModel.refreshCount.collectAsStateWithLifecycle()
    PullToRefreshBox(
        isRefreshing = state.refreshing && state.views != null,
        onRefresh = {
            viewModel.refresh()
            onRefresh()
        },
        modifier = modifier.fillMaxSize(),
    ) {
        val views = state.views
        when {
            views == null ->
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            views.isEmpty() -> NoViews()
            else ->
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(views, key = { it.id }) { view ->
                        ViewSection(
                            view = view,
                            viewModel = viewModel,
                            accountId = accountId,
                            refreshCount = refreshCount,
                            onOpenDocument = onOpenDocument,
                            onOpenView = { onOpenView(view.id) },
                        )
                    }
                }
        }
    }
}

/** A view's name with its first documents side by side, and a way to all of them. */
@Composable
private fun ViewSection(
    view: SavedView,
    viewModel: HomeViewModel,
    accountId: String?,
    refreshCount: Int,
    onOpenDocument: (Document) -> Unit,
    onOpenView: () -> Unit,
) {
    val pages = remember(view) { viewModel.pages(view) }.collectAsLazyPagingItems()
    // A pull to refresh refreshes the views' documents too (not when first shown: they load anyway).
    LaunchedEffect(refreshCount) { if (refreshCount > 0) pages.refresh() }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        ) {
            Text(
                view.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenView) { Text(stringResource(R.string.home_show_all)) }
        }
        val count = minOf(pages.itemCount, DOCUMENTS_PER_VIEW)
        when {
            count > 0 ->
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(count = count, key = { index -> pages.peek(index)?.id ?: -index.toLong() }) { index ->
                        pages[index]?.let { document ->
                            DocumentCard(document, accountId, onClick = { onOpenDocument(document) })
                        }
                    }
                }
            pages.loadState.refresh is LoadState.Loading ->
                Box(Modifier.fillMaxWidth().height(CARD_IMAGE_HEIGHT), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            pages.loadState.refresh is LoadState.Error ->
                SectionMessage(stringResource(R.string.home_view_failed))
            else -> SectionMessage(stringResource(R.string.home_view_empty))
        }
    }
}

@Composable
private fun DocumentCard(
    document: Document,
    accountId: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(
                    CARD_WIDTH,
                ).clickable(onClickLabel = stringResource(R.string.documents_open), onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ThumbnailImage(accountId, document, Modifier.size(width = CARD_WIDTH, height = CARD_IMAGE_HEIGHT))
        Text(
            document.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** No view on the dashboard: how to add some (scrollable, so that pull to refresh works). */
@Composable
private fun NoViews() {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            Icons.Outlined.Dashboard,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 48.dp).size(48.dp),
        )
        Text(
            stringResource(R.string.home_no_views),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** How many documents of a view are shown (the rest: "show all"). */
private const val DOCUMENTS_PER_VIEW = 10
private val CARD_WIDTH = 128.dp
private val CARD_IMAGE_HEIGHT = 172.dp
