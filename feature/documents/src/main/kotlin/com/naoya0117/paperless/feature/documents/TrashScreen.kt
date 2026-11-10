package com.naoya0117.paperless.feature.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naoya0117.paperless.core.model.Document
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Deleted documents: restore them, or delete them (or all) permanently after a confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message =
                when (event) {
                    is TrashEvent.Done ->
                        when (event.action) {
                            TrashAction.RESTORE -> resources.getString(R.string.trash_restored)
                            TrashAction.DELETE, TrashAction.EMPTY ->
                                resources.getQuantityString(R.plurals.trash_deleted, event.count, event.count)
                        }
                    is TrashEvent.Failed ->
                        resources.getString(
                            when (event.reason) {
                                TrashFailure.REFUSED -> R.string.trash_refused
                                TrashFailure.GONE -> R.string.trash_gone
                                TrashFailure.FAILED -> R.string.trash_failed
                            },
                        )
                }
            snackbar.showSnackbar(message)
        }
    }
    /** The document to delete permanently (after confirming); `EMPTY_ALL` for the whole trash. */
    var confirm by rememberSaveable { mutableStateOf<Long?>(null) }
    val dateTime =
        remember {
            DateTimeFormatter
                .ofLocalizedDateTime(
                    FormatStyle.MEDIUM,
                    FormatStyle.SHORT,
                ).withZone(ZoneId.systemDefault())
        }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.viewer_back),
                        )
                    }
                },
                actions = {
                    if (!state.documents.isNullOrEmpty()) {
                        IconButton(onClick = { confirm = EMPTY_ALL }, enabled = !state.busy) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.trash_empty))
                        }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.documents != null,
            onRefresh = viewModel::load,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val documents = state.documents
            when {
                documents == null && state.loadFailed ->
                    Message(stringResource(R.string.trash_load_failed)) {
                        TextButton(onClick = viewModel::load) { Text(stringResource(R.string.documents_retry)) }
                    }
                documents == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                documents.isEmpty() -> Message(stringResource(R.string.trash_none))
                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (state.total > documents.size) {
                            item(key = "partial") {
                                Text(
                                    stringResource(R.string.trash_partial, documents.size, state.total),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                        if (state.loadFailed) {
                            item(key = "failed") {
                                Text(
                                    stringResource(R.string.trash_load_failed),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                        items(documents, key = { it.id }) { document ->
                            TrashRow(
                                document = document,
                                dateTime = dateTime,
                                enabled = !state.busy,
                                onRestore = { viewModel.restore(document.id) },
                                onDelete = { confirm = document.id },
                            )
                            HorizontalDivider()
                        }
                    }
            }
        }
    }
    confirm?.let { target ->
        val title = state.documents?.firstOrNull { it.id == target }?.title
        // Only for a document still listed (e.g. not after a reload without it): the user must see what goes.
        if (target != EMPTY_ALL && title == null) {
            LaunchedEffect(target, state.documents) { if (state.documents != null) confirm = null }
            return@let
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    stringResource(
                        if (target ==
                            EMPTY_ALL
                        ) {
                            R.string.trash_empty_title
                        } else {
                            R.string.trash_delete_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    if (target == EMPTY_ALL) {
                        pluralStringResource(R.plurals.trash_empty_message, state.total, state.total)
                    } else {
                        stringResource(R.string.trash_delete_message, title.orEmpty())
                    },
                )
            },
            confirmButton = {
                TextButton(
                    // Not while another action runs (it would be ignored).
                    enabled = !state.busy,
                    onClick = {
                        confirm = null
                        if (target == EMPTY_ALL) viewModel.empty() else viewModel.delete(target)
                    },
                ) { Text(stringResource(R.string.trash_delete_permanently), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirm = null },
                ) { Text(stringResource(R.string.viewer_cancel)) }
            },
        )
    }
}

/** Not a document ID (those are positive). */
private const val EMPTY_ALL = -1L

@Composable
private fun TrashRow(
    document: Document,
    dateTime: DateTimeFormatter,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(document.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            document.deletedAt?.let { Text(stringResource(R.string.trash_deleted_at, dateTime.format(it))) }
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onRestore, enabled = enabled) {
                    Icon(
                        Icons.Outlined.RestoreFromTrash,
                        contentDescription = stringResource(R.string.trash_restore_named, document.title),
                    )
                }
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(
                        Icons.Outlined.DeleteForever,
                        contentDescription = stringResource(R.string.trash_delete_named, document.title),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

/** Scrollable, so that pull to refresh works. */
@Composable
private fun Message(
    text: String,
    action: @Composable () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // At least as high as the screen, so that the text is centered (and still scrollable).
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action()
        }
    }
}
