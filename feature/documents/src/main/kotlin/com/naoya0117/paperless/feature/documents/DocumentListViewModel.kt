package com.naoya0117.paperless.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.data.DocumentRepository
import com.naoya0117.paperless.core.data.LabelRepository
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.DocumentSearch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which documents a list shows. */
enum class DocumentListMode {
    ALL,

    /** Documents with an inbox tag (still to be processed). */
    INBOX,

    /** Full-text search results for the typed text. */
    SEARCH,
}

/** Names and colors to show for the IDs on a document. */
data class LabelNames(
    val correspondents: Map<Long, String> = emptyMap(),
    val documentTypes: Map<Long, String> = emptyMap(),
    val tags: Map<Long, TagLabel> = emptyMap(),
)

data class TagLabel(
    val name: String,
    /** `#rrggbb` */
    val color: String,
    val textColor: String,
)

@HiltViewModel
class DocumentListViewModel
    @Inject
    constructor(
        private val documents: DocumentRepository,
        private val labels: LabelRepository,
        accounts: AccountRepository,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        val mode: DocumentListMode =
            savedState.get<String>(MODE_ARGUMENT)?.let { name ->
                DocumentListMode.entries.firstOrNull { it.name == name }
            }
                ?: DocumentListMode.ALL

        private val searchInput = MutableStateFlow(savedState.get<String>(SEARCH_KEY).orEmpty())

        /** The search text as typed (for [DocumentListMode.SEARCH]). */
        val searchText: StateFlow<String> = searchInput.asStateFlow()

        /** Empty for a search without text. */
        @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
        val documentPages: Flow<PagingData<Document>> =
            when (mode) {
                DocumentListMode.ALL -> documents.documents(DocumentQuery())
                DocumentListMode.INBOX -> documents.documents(DocumentQuery(inInbox = true))
                DocumentListMode.SEARCH ->
                    searchInput
                        .map { it.trim() }
                        .distinctUntilChanged()
                        // Not a request per keystroke.
                        .debounce(SEARCH_DEBOUNCE_MILLIS)
                        .flatMapLatest { text ->
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
            combine(
                labels.correspondents.items,
                labels.documentTypes.items,
                labels.tags.items,
            ) { correspondents, types, tags ->
                LabelNames(
                    correspondents = correspondents.associate { it.id to it.name },
                    documentTypes = types.associate { it.id to it.name },
                    tags = tags.associate { it.id to TagLabel(it.name, it.color, it.textColor) },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), LabelNames())

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
            private const val SEARCH_KEY = "documents.search"
            private const val SEARCH_DEBOUNCE_MILLIS = 400L
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
