package com.naoya0117.paperless.feature.uploads

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naoya0117.paperless.core.model.Upload
import com.naoya0117.paperless.core.model.UploadError
import com.naoya0117.paperless.core.model.UploadState

/**
 * The uploads of the active account, newest first: their state and progress, why they failed, and retry
 * or removal. Finished ones open their document.
 */
@Composable
fun UploadsScreen(
    viewModel: UploadsViewModel,
    onOpenDocument: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()
    if (uploads.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Outlined.CloudUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(48.dp),
                )
                Text(stringResource(R.string.uploads_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(uploads, key = { it.id }) { upload ->
            UploadRow(
                upload = upload,
                onOpen = upload.documentId?.let { id -> { onOpenDocument(id) } },
                onRetry = { viewModel.retry(upload.id) },
                onRemove = { viewModel.remove(upload.id) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun UploadRow(
    upload: Upload,
    onOpen: (() -> Unit)?,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val (icon, tint) = upload.state.icon()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .let { if (onOpen != null && upload.state == UploadState.DONE) it.clickable(onClick = onOpen) else it }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                upload.fileName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stateText(upload),
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (upload.state ==
                        UploadState.FAILED
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            upload.error?.takeIf { upload.state == UploadState.FAILED }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
            when (upload.state) {
                UploadState.UPLOADING ->
                    LinearProgressIndicator(
                        progress = {
                            if (upload.size >
                                0
                            ) {
                                (upload.sentBytes.toFloat() / upload.size).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                UploadState.PROCESSING -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else -> Unit
            }
        }
        if (upload.state == UploadState.FAILED) {
            IconButton(onClick = onRetry) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.uploads_retry, upload.fileName),
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.uploads_remove, upload.fileName))
        }
    }
}

private fun UploadState.icon(): Pair<ImageVector, @Composable () -> androidx.compose.ui.graphics.Color> =
    when (this) {
        UploadState.QUEUED -> Icons.Outlined.HourglassEmpty to { MaterialTheme.colorScheme.outline }
        UploadState.UPLOADING -> Icons.Outlined.CloudUpload to { MaterialTheme.colorScheme.primary }
        UploadState.PROCESSING -> Icons.Outlined.Sync to { MaterialTheme.colorScheme.primary }
        UploadState.DONE -> Icons.Outlined.CheckCircle to { MaterialTheme.colorScheme.primary }
        UploadState.FAILED -> Icons.Outlined.ErrorOutline to { MaterialTheme.colorScheme.error }
    }

@Composable
private fun stateText(upload: Upload): String =
    when (upload.state) {
        UploadState.QUEUED -> stringResource(R.string.uploads_state_queued)
        UploadState.UPLOADING -> stringResource(R.string.uploads_state_uploading)
        UploadState.PROCESSING -> stringResource(R.string.uploads_state_processing)
        UploadState.DONE ->
            if (upload.documentId !=
                null
            ) {
                stringResource(R.string.uploads_state_done)
            } else {
                stringResource(R.string.uploads_state_done_no_document)
            }
        UploadState.FAILED ->
            when (upload.errorKind) {
                UploadError.SIGN_IN_REQUIRED -> stringResource(R.string.uploads_error_sign_in)
                UploadError.REJECTED -> stringResource(R.string.uploads_error_rejected)
                UploadError.PROCESSING_FAILED -> stringResource(R.string.uploads_error_processing)
                UploadError.DUPLICATE -> stringResource(R.string.uploads_error_duplicate)
                UploadError.NOT_REPORTED -> stringResource(R.string.uploads_error_not_reported)
                UploadError.UNEXPECTED, null -> stringResource(R.string.uploads_error_other)
            }
    }
