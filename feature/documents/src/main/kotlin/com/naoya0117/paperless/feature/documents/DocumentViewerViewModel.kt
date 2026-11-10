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
import com.naoya0117.paperless.core.model.Note
import com.naoya0117.paperless.core.network.DocumentFileKind
import com.naoya0117.paperless.core.network.PaperlessException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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

    /** The note with [text] was added (the input can be cleared, unless typed on meanwhile). */
    data class NoteAdded(
        val text: String,
    ) : ViewerEvent

    data class Failed(
        val action: FileAction,
        val reason: FailureReason,
    ) : ViewerEvent
}

enum class FileAction { SHARE, OPEN, DOWNLOAD, DELETE, ADD_NOTE, DELETE_NOTE }

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
        private val handle: SavedStateHandle,
        @ApplicationContext private val context: Context,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) : ViewModel() {
        val documentId: Long = checkNotNull(handle.get<Long>(DOCUMENT_ID_ARGUMENT)) { "No document ID" }

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

        /** Whether the metadata and the edit button are shown (from the info button). */
        val infoVisible: StateFlow<Boolean> = info.asStateFlow()

        private val full = MutableStateFlow(handle.get<Boolean>(FULLSCREEN_KEY) ?: false)

        /** Only the document, without bars (toggled by tapping it). */
        val fullscreen: StateFlow<Boolean> = full.asStateFlow()

        private val mutableSearch = MutableStateFlow(SearchState())

        /** Searching within the document (from the search button). */
        val search: StateFlow<SearchState> = mutableSearch.asStateFlow()

        /** What to search; [SearchRequest.session] changes each time the search is closed. */
        private data class SearchRequest(
            val session: Int,
            val query: String,
        )

        private val searchQuery = MutableStateFlow(SearchRequest(0, ""))

        init {
            load()
            viewModelScope.launch { runSearches() }
        }

        /** Not while searching (a tap there is more likely meant for the highlighted page). */
        fun toggleFullscreen() {
            if (!mutableSearch.value.open) setFullscreen(!full.value)
        }

        /** Scrolling down hides the bars, up shows them (not while searching: the search bar stays). */
        fun onScrolled(down: Boolean) {
            if (!mutableSearch.value.open && full.value != down) setFullscreen(down)
        }

        private fun setFullscreen(value: Boolean) {
            full.value = value
            handle[FULLSCREEN_KEY] = value
        }

        fun openSearch() {
            setFullscreen(false)
            mutableSearch.update { it.copy(open = true) }
        }

        fun closeSearch() {
            // A new session: a search still running is dropped, and the same text later is searched again.
            searchQuery.value = SearchRequest(searchQuery.value.session + 1, "")
            mutableSearch.value = SearchState()
        }

        fun setSearchQuery(query: String) {
            mutableSearch.update { it.copy(query = query) }
            searchQuery.value = searchQuery.value.copy(query = query.trim())
        }

        fun nextMatch() = moveMatch(1)

        fun previousMatch() = moveMatch(-1)

        private fun moveMatch(step: Int) =
            mutableSearch.update {
                if (it.matches.isEmpty()) {
                    it
                } else {
                    // Moves there again even with a single match (e.g. after scrolling away).
                    it.copy(current = (it.current + step).mod(it.matches.size), moves = it.moves + 1)
                }
            }

        /** Searches once typing pauses; a newer query (or other content) replaces a search still running. */
        @OptIn(FlowPreview::class)
        private suspend fun runSearches() {
            combine(
                searchQuery.debounce { if (it.query.isEmpty()) 0L else SEARCH_DEBOUNCE_MILLIS },
                content,
            ) { request, content ->
                request to content
            }
                // Every request (also the same text again after another one): its results may have been dropped.
                .collectLatest { (request, content) ->
                    val query = request.query
                    if (query.isEmpty()) {
                        mutableSearch.update { it.copy(searching = false, matches = emptyList(), current = 0) }
                        return@collectLatest
                    }
                    mutableSearch.update { it.copy(searching = true) }
                    val matches =
                        try {
                            findMatches(query, content)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (
                            @Suppress("TooGenericExceptionCaught") _: Exception,
                        ) {
                            // E.g. closed meanwhile.
                            emptyList()
                        }
                    // Only for the search shown (not one closed or changed meanwhile).
                    mutableSearch.update {
                        if (it.open &&
                            it.query.trim() == query
                        ) {
                            it.copy(searching = false, matches = matches, current = 0)
                        } else {
                            it
                        }
                    }
                }
        }

        /** In the PDF's pages where the device can (Android 15+), otherwise in the document's text. */
        private suspend fun findMatches(
            query: String,
            content: ViewerContent,
        ): List<SearchMatch> {
            if (content is ViewerContent.Pdf && PdfPages.canSearchPages) {
                // A PDF without a text layer (e.g. a scan without an archived version) finds nothing there.
                content.pages
                    .search(query)
                    .takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
            return withContext(Dispatchers.Default) { searchText(fullText(), query) }
        }

        /** The full text fetched for searching (not shown: the fetched copy is left to [load]). */
        private var searchText: String? = null

        /** A fetch for searching failed: the cached text is searched from then on (no wait for each query). */
        private var searchFetchFailed = false

        /** The document's full text: lists cache it shortened, so the fetched copy (or a fetch) is used. */
        private suspend fun fullText(): String {
            fetched.value?.content?.let { return it }
            searchText?.let { return it }
            if (!searchFetchFailed) {
                try {
                    return documents.fetch(documentId).content.also { searchText = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") _: Exception,
                ) {
                    searchFetchFailed = true
                }
            }
            // Offline: the cached text (perhaps shortened) is better than nothing.
            return documents
                .cached(documentId)
                .first()
                ?.content
                .orEmpty()
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

        /** Adds a note; `false` if not sent (e.g. empty, or another action is running). */
        fun addNote(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || state.value.document == null || busy.value) return false
            runAction(FileAction.ADD_NOTE, viewModelScope) { document ->
                val notes = documents.addNote(document.id, trimmed)
                setNotes(notes)
                ViewerEvent.NoteAdded(trimmed)
            }
            return true
        }

        fun deleteNote(noteId: Long) =
            runAction(FileAction.DELETE_NOTE, viewModelScope) { document ->
                setNotes(documents.deleteNote(document.id, noteId))
                null
            }

        /** The repository updated the cached copy; the fetched one (if any) follows. */
        private fun setNotes(notes: List<Note>) {
            fetched.update { it?.copy(notes = notes) }
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
            block: suspend (Document) -> ViewerEvent?,
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
                event?.let(eventChannel::trySend)
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
             * (by modification time), the fetched one on a tie; the notes come from the fetched copy.
             */
            internal fun newer(
                cached: Document?,
                fetched: Document?,
            ): Document? =
                when {
                    cached == null -> fetched
                    fetched == null -> cached
                    // A cached copy without notes (e.g. from a list) keeps those of the fetched copy.
                    cached.modified > fetched.modified ->
                        if (cached.notes ==
                            null
                        ) {
                            cached.copy(notes = fetched.notes)
                        } else {
                            cached
                        }
                    else -> fetched
                }

            const val DOCUMENT_ID_ARGUMENT = "id"
            private const val FULLSCREEN_KEY = "viewer.fullscreen"
            private const val SEARCH_DEBOUNCE_MILLIS = 300L
            private const val PDF = "application/pdf"
            private const val ANY_TYPE = "*/*"
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
