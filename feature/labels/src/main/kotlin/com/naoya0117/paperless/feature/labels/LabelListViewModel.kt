package com.naoya0117.paperless.feature.labels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.LabelRepository
import com.naoya0117.paperless.core.model.MatchingLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator
import javax.inject.Inject

data class LabelListState(
    /** `null` until the cache was read. */
    val items: List<MatchingLabel>? = null,
    val query: String = "",
    val refreshing: Boolean = false,
    /** The last refresh failed (e.g. offline); the cached labels are shown. */
    val refreshFailed: Boolean = false,
)

/** The labels of one [LabelKind], from the cache, refreshed from the server when opened. */
@HiltViewModel
class LabelListViewModel
    @Inject
    constructor(
        repository: LabelRepository,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        val kind: LabelKind = LabelKind.of(savedState.get<String>(KIND_ARGUMENT))
        private val store = LabelStore(repository, kind)

        /** As typed; read directly by the search field (not through [state], which may lag a frame). */
        val query: StateFlow<String> = savedState.getStateFlow(QUERY_KEY, "")
        private val refreshing = MutableStateFlow(false)
        private val refreshFailed = MutableStateFlow(false)
        private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        private val handle = savedState

        val state: StateFlow<LabelListState> =
            combine(store.items, query, refreshing, refreshFailed) { items, query, refreshing, failed ->
                val sorted = items.sortedWith(compareBy(collator) { it.name })
                val shown =
                    if (query.isBlank()) {
                        sorted
                    } else {
                        sorted.filter {
                            it.name.contains(
                                query.trim(),
                                ignoreCase = true,
                            )
                        }
                    }
                LabelListState(shown, query, refreshing, failed)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), LabelListState())

        init {
            refresh()
        }

        fun search(text: String) {
            handle[QUERY_KEY] = text
        }

        fun refresh() {
            if (refreshing.value) return
            refreshing.value = true
            viewModelScope.launch {
                refreshFailed.value =
                    try {
                        store.refresh()
                        false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Exception,
                    ) {
                        true
                    } finally {
                        refreshing.value = false
                    }
            }
        }

        companion object {
            const val KIND_ARGUMENT = "kind"
            private const val QUERY_KEY = "query"
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
