package com.naoya0117.paperless.feature.uploads

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.ApplicationScope
import com.naoya0117.paperless.core.data.NotSignedInException
import com.naoya0117.paperless.core.data.UploadRepository
import com.naoya0117.paperless.core.data.UploadSource
import com.naoya0117.paperless.core.model.Upload
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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

    /** None of the files could be read. */
    data class Failed(
        val count: Int,
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
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) : ViewModel() {
        val uploads: StateFlow<List<Upload>> =
            repository.uploads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        private val results = Channel<EnqueueResult>(Channel.BUFFERED)

        /** One event per [enqueue]. */
        val enqueueResults: Flow<EnqueueResult> = results.receiveAsFlow()

        /**
         * Copies the files into the upload queue of the active account (even if another account becomes active
         * meanwhile). The caller's permission for the URIs only lasts while it is alive (e.g. the activity a file
         * was shared to), so the copies are made at once. They are not cancelled with this screen, but files not
         * read before the activity is gone usually cannot be read any more; that result is then not shown.
         */
        fun enqueue(uris: List<Uri>) {
            if (uris.isEmpty()) return
            applicationScope.launch {
                val accountId = repository.activeAccountId()
                if (accountId == null) {
                    results.trySend(EnqueueResult.NotSignedIn)
                    return@launch
                }
                val files = uris.distinct()
                var queued = 0
                var failed = 0
                for (uri in files) {
                    try {
                        repository.enqueue(source(uri), accountId = accountId)
                        queued++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: NotSignedInException) {
                        // Signed out meanwhile: the rest cannot be queued either.
                        failed = files.size - queued
                        if (queued == 0) {
                            results.trySend(EnqueueResult.NotSignedIn)
                            return@launch
                        }
                        break
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Exception,
                    ) {
                        // Unreadable (IOException, SecurityException for a withdrawn permission, ...).
                        failed++
                    }
                }
                val result =
                    when {
                        failed == 0 -> EnqueueResult.Queued(queued)
                        queued == 0 -> EnqueueResult.Failed(failed)
                        else -> EnqueueResult.PartlyFailed(queued, failed)
                    }
                // Not waiting: nobody may collect any more (screen closed).
                results.trySend(result)
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
            // Both ask the other app's provider, which may be slow.
            val (name, type) =
                withContext(Dispatchers.IO) {
                    displayName(resolver, uri) to
                        runCatching { resolver.getType(uri) }.getOrNull()
                }
            return UploadSource(name ?: uri.lastPathSegment ?: DEFAULT_NAME, type) {
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
