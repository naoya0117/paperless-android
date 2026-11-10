package com.naoya0117.paperless.feature.labels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naoya0117.paperless.core.model.MatchingLabel
import com.naoya0117.paperless.core.model.StoragePath
import com.naoya0117.paperless.core.model.Tag

/**
 * The labels of one kind (tags, correspondents, ...): searchable, refreshed from the server when opened.
 *
 * @param onOpen opens a label for editing; `null` creates a new one
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelListScreen(
    onBack: () -> Unit,
    onOpen: (kind: LabelKind, id: Long?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LabelListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(viewModel.kind.title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.labels_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpen(viewModel.kind, null) }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(viewModel.kind.newTitle))
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val items = state.items
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp), modifier = Modifier.fillMaxSize()) {
                item(key = "search") {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::search,
                        placeholder = { Text(stringResource(R.string.labels_search)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (state.refreshFailed) {
                    item(key = "offline") {
                        Text(
                            stringResource(R.string.labels_refresh_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                when {
                    items == null ->
                        item(key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    items.isEmpty() ->
                        item(key = "empty") {
                            Text(
                                stringResource(
                                    if (state.query.isBlank()) R.string.labels_empty else R.string.labels_no_match,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                            )
                        }
                    else ->
                        items(items, key = { it.id }) { label ->
                            LabelRow(label, onClick = { onOpen(viewModel.kind, label.id) })
                            HorizontalDivider()
                        }
                }
            }
        }
    }
}

@Composable
private fun LabelRow(
    label: MatchingLabel,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = (label as? Tag)?.let { tag -> { ColorDot(tag.color) } },
        headlineContent = { Text(label.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (label is StoragePath) {
                    Text(
                        label.path,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                label.documentCount?.let {
                    Text(
                        pluralStringResource(R.plurals.labels_document_count, it, it),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if ((label as? Tag)?.isInboxTag == true) {
                    Icon(Icons.Outlined.Inbox, contentDescription = stringResource(R.string.labels_inbox_tag))
                }
                if (!label.userCanChange) {
                    Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.labels_read_only))
                }
            }
        },
    )
}

@Composable
internal fun ColorDot(
    color: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(24.dp)
            .background(parseColor(color) ?: MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

/** `#rrggbb` → a color; `null` if malformed. */
internal fun parseColor(hex: String): Color? =
    hex
        .removePrefix("#")
        .takeIf { it.length == 6 }
        ?.toLongOrNull(16)
        ?.let { Color(0xFF000000 or it) }
