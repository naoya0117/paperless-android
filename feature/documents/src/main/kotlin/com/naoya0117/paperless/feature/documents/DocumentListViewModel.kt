package com.naoya0117.paperless.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.data.ApplicationScope
import com.naoya0117.paperless.core.data.DocumentRepository
import com.naoya0117.paperless.core.data.LabelRepository
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.DocumentSearch
import com.naoya0117.paperless.core.model.SavedView
import com.naoya0117.paperless.core.network.toDocumentQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which documents a list shows. */
enum class DocumentListMode {
    ALL,

    /** Documents with an inbox tag (new, still to be checked). */
    INBOX,

    /** Full-text search results for the entered text (the search tab). */
    SEARCH,

    /** The documents of a saved view (see [DocumentListViewModel.VIEW_ID_ARGUMENT]). */
    VIEW,
}

@HiltViewModel
class DocumentListViewModel
    @Inject
    constructor(
        private val documents: DocumentRepository,
        private val labels: LabelRepository,
        accounts: AccountRepository,
        private val savedState: SavedStateHandle,
        private val actions: DocumentActions,
        @ApplicationScope private val applicationScope: CoroutineScope,
    ) : ViewModel() {
        private val busy = MutableStateFlow(false)

        /** An action on a document (from the long-press menu) runs; the others wait. */
        val actionRunning: StateFlow<Boolean> = busy.asStateFlow()

        private val eventChannel = Channel<ViewerEvent>(Channel.BUFFERED)

        /** What came of an action, shown once. */
        val events: Flow<ViewerEvent> = eventChannel.receiveAsFlow()

        fun share(document: Document) =
            run(document, FileAction.SHARE, viewModelScope) { actions.send(it, open = false) }

        fun openExternally(document: Document) =
            run(document, FileAction.OPEN, viewModelScope) { actions.send(it, open = true) }

        /** Finished even if the list is left. */
        fun download(document: Document) = run(document, FileAction.DOWNLOAD, applicationScope) { actions.download(it) }

        /** Moves it to the trash; finished even if the list is left (the cached lists follow the server). */
        fun delete(document: Document) = run(document, FileAction.DELETE, applicationScope) { actions.delete(it) }

        private fun run(
            document: Document,
            action: FileAction,
            scope: CoroutineScope,
            block: suspend (Document) -> ViewerEvent,
        ) {
            if (!busy.compareAndSet(expect = false, update = true)) return
            scope.launch {
                val event =
                    try {
                        block(document)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        DocumentActions.failed(action, e)
                    } finally {
                        busy.value = false
                    }
                // Not waiting: the list may be gone (then the event is dropped).
                eventChannel.trySend(event)
            }
        }

        val mode: DocumentListMode =
            savedState.get<String>(MODE_ARGUMENT)?.let { name ->
                DocumentListMode.entries.firstOrNull { it.name == name }
            }
                ?: DocumentListMode.ALL

        private val searchInput = MutableStateFlow(savedState.get<String>(SEARCH_KEY).orEmpty())

        /** The search text as typed (for [DocumentListMode.SEARCH]). */
        val searchText: StateFlow<String> = searchInput.asStateFlow()

        /** The entered search text once typing pauses ([DocumentListMode.SEARCH]); empty when none. */
        @OptIn(FlowPreview::class)
        val activeSearch: StateFlow<String> =
            searchInput
                .map { it.trim() }
                .distinctUntilChanged()
                // Not a request per keystroke; clearing the search applies at once.
                .debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MILLIS }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Eagerly, searchInput.value.trim())

        private val viewId: Long? = savedState.get<Long>(VIEW_ID_ARGUMENT)

        private val viewFlow: Flow<SavedView?> =
            if (mode != DocumentListMode.VIEW || viewId == null) {
                flowOf(null)
            } else {
                labels.savedViews.items.map { views -> views.firstOrNull { it.id == viewId } }
            }

        /** The saved view shown ([DocumentListMode.VIEW]); `null` while not cached (or gone). */
        val savedView: StateFlow<SavedView?> = viewFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** The view is not on the server any more (it is not among the refreshed saved views). */
        val savedViewMissing: StateFlow<Boolean> =
            if (mode != DocumentListMode.VIEW) {
                MutableStateFlow(false)
            } else {
                combine(viewFlow, labels.savedViews.refreshedAt) { view, refreshedAt ->
                    view == null &&
                        refreshedAt != null
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)
            }

        /** The saved view's query, like the web UI builds it; `null` while the view is not known. */
        private val viewQuery: Flow<DocumentQuery?> =
            combine(viewFlow, labels.customFields.items, labels.customFields.refreshedAt) { view, fields, refreshedAt ->
                view?.toDocumentQuery(
                    // A sort by a deleted custom field falls back to the created date (once the fields are known).
                    existingCustomFieldIds = refreshedAt?.let { fields.map { it.id }.toSet() },
                )
            }.distinctUntilChanged()

        /** The documents of [mode]; for a search without text, none. */
        @OptIn(ExperimentalCoroutinesApi::class)
        val documentPages: Flow<PagingData<Document>> =
            when (mode) {
                DocumentListMode.ALL -> documents.documents(DocumentQuery())
                DocumentListMode.INBOX -> documents.documents(DocumentQuery(inInbox = true))
                DocumentListMode.VIEW ->
                    viewQuery.flatMapLatest { query ->
                        if (query == null) flowOf(PagingData.empty()) else documents.documents(query)
                    }
                DocumentListMode.SEARCH ->
                    activeSearch.flatMapLatest { text ->
                        if (text.isEmpty()) {
                            flowOf(PagingData.empty())
                        } else {
                            documents.documents(DocumentQuery(search = DocumentSearch.Text(text)))
                        }
                    }
            }.cachedIn(viewModelScope)

        /** For thumbnails ([DocumentThumbnail]), which are cached per account. */
        val accountId: StateFlow<String?> =
            accounts.state
                .map { it.activeAccountId }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

        val labelNames: StateFlow<LabelNames> =
            labels.names().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), LabelNames())

        init {
            refreshLabels()
        }

        fun setSearchText(value: String) {
            searchInput.value = value
            savedState[SEARCH_KEY] = value
        }

        /** Labels change rarely; refreshed with the list (pull to refresh) and when the screen opens. */
        fun refreshLabels() {
            viewModelScope.launch {
                try {
                    labels.refreshAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") _: Exception,
                ) {
                    // Offline or not allowed: the cached names stay; the list shows its own errors.
                }
            }
        }

        companion object {
            /** Navigation argument with the [DocumentListMode] name. */
            const val MODE_ARGUMENT = "mode"

            /** Navigation argument with the saved view's ID, for [DocumentListMode.VIEW]. */
            const val VIEW_ID_ARGUMENT = "viewId"
            private const val SEARCH_KEY = "documents.search"
            private const val SEARCH_DEBOUNCE_MILLIS = 400L
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
