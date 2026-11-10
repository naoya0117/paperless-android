package com.naoya0117.paperless.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.DocumentFileRepository
import com.naoya0117.paperless.core.data.DocumentRepository
import com.naoya0117.paperless.core.data.LabelRepository
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.network.DocumentFileKind
import com.naoya0117.paperless.core.network.PaperlessException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

data class ViewerState(
    /** From the cache at once, then from the server (with notes and full details). */
    val document: Document? = null,
    val labels: LabelNames = LabelNames(),
    val content: ViewerContent = ViewerContent.Loading,
)

@HiltViewModel
class DocumentViewerViewModel
    @Inject
    constructor(
        private val documents: DocumentRepository,
        private val files: DocumentFileRepository,
        labels: LabelRepository,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        val documentId: Long = checkNotNull(savedState.get<Long>(DOCUMENT_ID_ARGUMENT)) { "No document ID" }

        private val fetched = MutableStateFlow<Document?>(null)
        private val content = MutableStateFlow<ViewerContent>(ViewerContent.Loading)

        val state: StateFlow<ViewerState> =
            combine(documents.cached(documentId), fetched, labels.names(), content) { cached, full, names, file ->
                ViewerState(newer(cached, full), names, file)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ViewerState())

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
                // Without parameters such as "; charset=binary".
                val type = (file.contentType ?: document.mimeType)?.substringBefore(';')?.trim()?.lowercase()
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
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
