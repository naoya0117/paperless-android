package com.naoya0117.paperless.feature.uploads

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.NotSignedInException
import com.naoya0117.paperless.core.data.UploadRepository
import com.naoya0117.paperless.core.data.UploadSource
import com.naoya0117.paperless.core.model.Upload
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/** The outcome of adding files, shown once (e.g. in a snackbar). */
sealed interface EnqueueResult {
    data class Queued(
        val count: Int,
    ) : EnqueueResult

    /** Some or all files could not be read (e.g. the sharing app withdrew access); the rest were queued. */
    data class PartlyFailed(
        val queued: Int,
        val failed: Int,
    ) : EnqueueResult

    data object NotSignedIn : EnqueueResult
}

/** The uploads of the active account, and adding files to them (picked or shared from other apps). */
@HiltViewModel
class UploadsViewModel
    @Inject
    constructor(
        private val repository: UploadRepository,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        val uploads: StateFlow<List<Upload>> =
            repository.uploads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        private val results = Channel<EnqueueResult>(Channel.BUFFERED)

        /** One event per [enqueue]. */
        val enqueueResults: Flow<EnqueueResult> = results.receiveAsFlow()

        /**
         * Copies the files into the upload queue. The caller's permission for the URIs only lasts while it is
         * alive (e.g. the activity a file was shared to), so the copies are made at once.
         */
        fun enqueue(uris: List<Uri>) {
            if (uris.isEmpty()) return
            viewModelScope.launch {
                var queued = 0
                var failed = 0
                for (uri in uris.distinct()) {
                    try {
                        repository.enqueue(source(uri))
                        queued++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: NotSignedInException) {
                        results.send(EnqueueResult.NotSignedIn)
                        return@launch
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Exception,
                    ) {
                        // Unreadable (IOException, SecurityException for a withdrawn permission, ...).
                        failed++
                    }
                }
                val result =
                    if (failed ==
                        0
                    ) {
                        EnqueueResult.Queued(queued)
                    } else {
                        EnqueueResult.PartlyFailed(queued, failed)
                    }
                results.send(result)
            }
        }

        fun retry(id: String) {
            viewModelScope.launch { repository.retry(id) }
        }

        fun remove(id: String) {
            viewModelScope.launch { repository.remove(id) }
        }

        private suspend fun source(uri: Uri): UploadSource {
            val resolver = context.contentResolver
            val name = withContext(Dispatchers.IO) { displayName(resolver, uri) } ?: uri.lastPathSegment ?: DEFAULT_NAME
            return UploadSource(name, resolver.getType(uri)) {
                resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
            }
        }

        private fun displayName(
            resolver: ContentResolver,
            uri: Uri,
        ): String? =
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }

        private companion object {
            const val DEFAULT_NAME = "document"
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
