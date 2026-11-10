package com.naoya0117.paperless.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.NotSignedInException
import com.naoya0117.paperless.core.data.TrashRepository
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.network.PaperlessException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrashState(
    /** `null` until loaded (or when loading failed). */
    val documents: List<Document>? = null,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    /** A restore or deletion is running; the actions wait. */
    val busy: Boolean = false,
)

enum class TrashAction { RESTORE, DELETE, EMPTY }

sealed interface TrashEvent {
    data class Done(
        val action: TrashAction,
        val count: Int,
    ) : TrashEvent

    data class Failed(
        val action: TrashAction,
        /** The server refused (e.g. no permission), rather than the connection failing. */
        val refused: Boolean,
    ) : TrashEvent
}

/** The trash: deleted documents to restore or delete permanently (online only). */
@HiltViewModel
class TrashViewModel
    @Inject
    constructor(
        private val trash: TrashRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(TrashState())
        val state: StateFlow<TrashState> = mutableState.asStateFlow()

        private val eventChannel = Channel<TrashEvent>(Channel.BUFFERED)
        val events: Flow<TrashEvent> = eventChannel.receiveAsFlow()

        init {
            load()
        }

        fun load() {
            if (mutableState.value.loading) return
            mutableState.update { it.copy(loading = true, loadFailed = false) }
            viewModelScope.launch {
                val documents =
                    try {
                        trash.documents()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Exception,
                    ) {
                        null
                    }
                mutableState.update {
                    it.copy(
                        documents = documents ?: it.documents,
                        loading = false,
                        loadFailed = documents == null,
                    )
                }
            }
        }

        fun restore(id: Long) = run(TrashAction.RESTORE) { trash.restore(listOf(id)) }

        fun delete(id: Long) = run(TrashAction.DELETE) { trash.delete(listOf(id)) }

        fun empty() = run(TrashAction.EMPTY) { trash.deleteAll() }

        /** One action at a time; afterwards the trash is loaded again (the server decides what is left). */
        private fun run(
            action: TrashAction,
            block: suspend () -> List<Long>,
        ) {
            if (mutableState.value.busy) return
            mutableState.update { it.copy(busy = true) }
            viewModelScope.launch {
                val event =
                    try {
                        val done = block()
                        mutableState.update { state ->
                            state.copy(documents = state.documents?.filterNot { it.id in done })
                        }
                        TrashEvent.Done(action, done.size)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        TrashEvent.Failed(
                            action,
                            refused =
                                e is PaperlessException.Http &&
                                    e !is PaperlessException.Http.ServerError ||
                                    e is NotSignedInException,
                        )
                    } finally {
                        mutableState.update { it.copy(busy = false) }
                    }
                eventChannel.trySend(event)
                load()
            }
        }
    }
