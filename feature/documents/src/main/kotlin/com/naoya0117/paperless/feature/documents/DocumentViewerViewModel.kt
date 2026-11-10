package com.naoya0117.paperless.feature.documents

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.ApplicationScope
import com.naoya0117.paperless.core.data.CachedDocumentFile
import com.naoya0117.paperless.core.data.DocumentFileRepository
import com.naoya0117.paperless.core.data.DocumentRepository
import com.naoya0117.paperless.core.data.LabelRepository
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.network.DocumentFileKind
import com.naoya0117.paperless.core.network.PaperlessException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** What the viewer shows of the document's file. */
sealed interface ViewerContent {
    data object Loading : ViewerContent

    data class Pdf(
        val pages: PdfPages,
    ) : ViewerContent

    data class Image(
        val file: File,
    ) : ViewerContent

    /** A file type the viewer cannot show (e.g. an office document without an archived PDF). */
    data class Unsupported(
        val fileName: String?,
    ) : ViewerContent

    /** Loading failed; [retry][DocumentViewerViewModel.retry] may help (e.g. once online). */
    data object Failed : ViewerContent
}

/** What the viewer does with the document's file, or what came of it; shown once. */
sealed interface ViewerEvent {
    /** Hand the file to another app: to share it (a chooser) or to open it. */
    data class SendFile(
        val uri: Uri,
        val mimeType: String,
        val open: Boolean,
    ) : ViewerEvent

    data class Saved(
        val fileName: String,
    ) : ViewerEvent

    data object Deleted : ViewerEvent

    data class Failed(
        val action: FileAction,
        val reason: FailureReason,
    ) : ViewerEvent
}

enum class FileAction { SHARE, OPEN, DOWNLOAD, DELETE }

enum class FailureReason {
    /** The server could not be reached or refused (e.g. offline, no permission). */
    SERVER,

    /** The file could not be stored or copied (e.g. out of storage). */
    STORAGE,
}

data class ViewerState(
    /** From the cache at once, then from the server (with notes and full details). */
    val document: Document? = null,
    val labels: LabelNames = LabelNames(),
    val content: ViewerContent = ViewerContent.Loading,
    /** A share, download or deletion is running; the actions wait. */
    val busy: Boolean = false,
)

@HiltViewModel
class DocumentViewerViewModel
    @Inject
    constructor(
        private val documents: DocumentRepository,
        private val files: DocumentFileRepository,
        labels: LabelRepository,
        savedState: SavedStateHandle,
        @ApplicationContext private val context: Context,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) : ViewModel() {
        val documentId: Long = checkNotNull(savedState.get<Long>(DOCUMENT_ID_ARGUMENT)) { "No document ID" }

        private val fetched = MutableStateFlow<Document?>(null)
        private val content = MutableStateFlow<ViewerContent>(ViewerContent.Loading)
        private val busy = MutableStateFlow(false)

        val state: StateFlow<ViewerState> =
            combine(
                documents.cached(documentId),
                fetched,
                labels.names(),
                content,
                busy,
            ) { cached, full, names, file, busy ->
                ViewerState(newer(cached, full), names, file, busy)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ViewerState())

        private val eventChannel = Channel<ViewerEvent>(Channel.BUFFERED)

        /** One event per action. */
        val events: Flow<ViewerEvent> = eventChannel.receiveAsFlow()

        /** One load at a time (e.g. Retry tapped twice), so that no PDF is opened twice. */
        private var loadJob: Job? = null

        private val info = MutableStateFlow(false)

        /** Whether the metadata and the edit button are shown (toggled by tapping the document). */
        val infoVisible: StateFlow<Boolean> = info.asStateFlow()

        init {
            load()
        }

        fun toggleInfo() {
            info.value = !info.value
        }

        fun hideInfo() {
            info.value = false
        }

        fun retry() = load()

        /** Shares the document's file (the archived PDF if there is one) with an app the user chooses. */
        fun share() = sendFile(FileAction.SHARE)

        /** Opens the document's file in another app (e.g. a PDF reader). */
        fun openExternally() = sendFile(FileAction.OPEN)

        /** Saves the document's file to the device's Downloads; finished even if the viewer is left. */
        fun download() =
            runAction(FileAction.DOWNLOAD, applicationScope) { document ->
                val file = files.file(document, DocumentFileKind.DOWNLOAD)
                val type = mimeType(document, file)
                val saved =
                    local { DocumentFileExport.saveToDownloads(context, file, fileName(document, file, type), type) }
                ViewerEvent.Saved(saved)
            }

        /**
         * Moves the document to the trash; finished even if the viewer is left, so that the cached lists follow
         * what the server did.
         */
        fun delete() =
            runAction(FileAction.DELETE, applicationScope) { document ->
                documents.delete(document.id)
                ViewerEvent.Deleted
            }

        private fun sendFile(action: FileAction) =
            runAction(action, viewModelScope) { document ->
                val file = files.file(document, DocumentFileKind.DOWNLOAD)
                val type = mimeType(document, file)
                val uri = local { DocumentFileExport.shareable(context, file, fileName(document, file, type)) }
                ViewerEvent.SendFile(uri, type ?: ANY_TYPE, open = action == FileAction.OPEN)
            }

        /** A failure on the device (storing or copying the file), not of the server or the connection. */
        private class LocalFailure(
            cause: Throwable,
        ) : Exception(cause)

        private inline fun <T> local(block: () -> T): T =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                throw LocalFailure(e)
            }

        /** Runs one action at a time; failures become an event. */
        private fun runAction(
            action: FileAction,
            scope: CoroutineScope,
            block: suspend (Document) -> ViewerEvent,
        ) {
            val document = state.value.document ?: return
            if (!busy.compareAndSet(expect = false, update = true)) return
            scope.launch {
                val event =
                    try {
                        block(document)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: LocalFailure) {
                        ViewerEvent.Failed(action, FailureReason.STORAGE)
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Exception,
                    ) {
                        // Downloading or deleting failed: PaperlessException, NotSignedInException (signed out
                        // meanwhile), or the file could not be cached.
                        ViewerEvent.Failed(action, FailureReason.SERVER)
                    } finally {
                        busy.value = false
                    }
                // Not waiting: the viewer may be gone (then the event is dropped).
                eventChannel.trySend(event)
            }
        }

        private fun fileName(
            document: Document,
            file: CachedDocumentFile,
            mimeType: String?,
        ): String =
            DocumentFileExport.fileName(
                file.fileName ?: document.archivedFileName ?: document.originalFileName,
                document.title,
                mimeType,
            )

        /** Without parameters such as "; charset=binary". */
        private fun mimeType(
            document: Document,
            file: CachedDocumentFile,
        ): String? = (file.contentType ?: document.mimeType)?.substringBefore(';')?.trim()?.lowercase()

        private fun load() {
            if (loadJob?.isActive == true) return
            if (content.value == ViewerContent.Failed) content.value = ViewerContent.Loading
            loadJob =
                viewModelScope.launch {
                    val document =
                        try {
                            documents.fetch(documentId).also { fetched.value = it }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (
                            @Suppress("TooGenericExceptionCaught") _: Exception,
                        ) {
                            // Offline: the cached document (if any) is shown.
                            documents.cachedNow()
                        }
                    if (document == null) {
                        content.value = ViewerContent.Failed
                        return@launch
                    }
                    if (content.value is ViewerContent.Loading) content.value = openFile(document)
                }
        }

        /** The cached document, waited for once (the state may not be collected yet). */
        private suspend fun DocumentRepository.cachedNow(): Document? = cached(documentId).first()

        private suspend fun openFile(document: Document): ViewerContent =
            try {
                val file = files.file(document, DocumentFileKind.PREVIEW)
                val type = mimeType(document, file)
                when {
                    type == PDF -> ViewerContent.Pdf(openPdf(file.file))
                    type?.startsWith("image/") == true -> ViewerContent.Image(file.file)
                    else -> ViewerContent.Unsupported(file.fileName ?: document.originalFileName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: PaperlessException) {
                ViewerContent.Failed
            } catch (_: IOException) {
                // Not downloadable, not storable, or not a PDF the device can render.
                ViewerContent.Failed
            } catch (_: SecurityException) {
                // A password protected PDF.
                ViewerContent.Unsupported(document.originalFileName)
            } catch (_: IllegalStateException) {
                // Signed out meanwhile.
                ViewerContent.Failed
            }

        /**
         * Opens the PDF even if the screen is left meanwhile, so that the renderer is never lost unclosed; then
         * closes it again if so.
         */
        private suspend fun openPdf(file: File): PdfPages {
            val pages = withContext(NonCancellable) { PdfPages.open(file) }
            if (!currentCoroutineContext().isActive) {
                pages.close()
                throw CancellationException("Left while opening")
            }
            return pages
        }

        override fun onCleared() {
            (content.value as? ViewerContent.Pdf)?.pages?.close()
        }

        companion object {
            /**
             * The fetched copy has the full content and notes, but the cache follows edits: the newer one wins
             * (by modification time), the fetched one on a tie.
             */
            internal fun newer(
                cached: Document?,
                fetched: Document?,
            ): Document? =
                when {
                    cached == null -> fetched
                    fetched == null -> cached
                    cached.modified > fetched.modified -> cached
                    else -> fetched
                }

            const val DOCUMENT_ID_ARGUMENT = "id"
            private const val PDF = "application/pdf"
            private const val ANY_TYPE = "*/*"
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
