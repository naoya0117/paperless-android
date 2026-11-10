package com.naoya0117.paperless.feature.uploads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naoya0117.paperless.core.model.Upload
import com.naoya0117.paperless.core.model.UploadState

/** How far the uploads still running have got: being sent, waiting, or being processed by the server. */
internal data class UploadProgress(
    val sending: Int,
    val processing: Int,
    /** Of the files to send (0..1); `null` while nothing is being sent. */
    val sent: Float?,
) {
    val active: Int get() = sending + processing

    companion object {
        fun of(uploads: List<Upload>): UploadProgress {
            val sending = uploads.filter { it.state == UploadState.QUEUED || it.state == UploadState.UPLOADING }
            val total = sending.sumOf { it.size }
            return UploadProgress(
                sending = sending.size,
                processing = uploads.count { it.state == UploadState.PROCESSING },
                sent = if (sending.isEmpty() || total <= 0) null else sending.sumOf { it.sentBytes }.toFloat() / total,
            )
        }
    }
}

/**
 * A line under the search field while uploads run (e.g. a scan just added): how many and how far, with the
 * way to their list. Hidden when none runs.
 */
@Composable
fun UploadBanner(
    viewModel: UploadsViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uploads by viewModel.uploads.collectAsStateWithLifecycle()
    val progress = UploadProgress.of(uploads)
    AnimatedVisibility(visible = progress.active > 0, modifier = modifier) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        if (progress.sending > 0) {
                            pluralStringResource(R.plurals.upload_banner_sending, progress.sending, progress.sending)
                        } else {
                            pluralStringResource(
                                R.plurals.upload_banner_processing,
                                progress.processing,
                                progress.processing,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val sent = progress.sent
                if (sent != null && sent > 0f) {
                    LinearProgressIndicator(progress = { sent }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
