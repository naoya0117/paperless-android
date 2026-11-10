package com.naoya0117.paperless.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.TrashRepository
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.network.PaperlessException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    /** How many documents are in the trash (more than [documents] if not all could be loaded). */
    val total: Int = 0,
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
        val reason: TrashFailure,
    ) : TrashEvent
}

enum class TrashFailure {
    /** Not allowed (only the owner may restore or delete a document). */
    REFUSED,

    /** The document is not in the trash any more (e.g. restored or deleted elsewhere). */
    GONE,

    /** The connection or the server failed, or the account must sign in again. */
    FAILED,
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

        /** Declared before [init], which starts the first load. */
        private var loadJob: Job? = null

        init {
            load()
        }

        /** Loads the trash; a load still running is replaced (it may be from before an action). */
        fun load() {
            loadJob?.cancel()
            mutableState.update { it.copy(loading = true, loadFailed = false) }
            loadJob =
                viewModelScope.launch {
                    val contents =
                        try {
                            trash.contents()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (
                            @Suppress("TooGenericExceptionCaught") _: Exception,
                        ) {
                            null
                        }
                    mutableState.update {
                        it.copy(
                            documents = contents?.documents ?: it.documents,
                            total = contents?.total ?: it.total,
                            loading = false,
                            loadFailed = contents == null,
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
                            state.copy(
                                documents = state.documents?.filterNot { it.id in done },
                                total = (state.total - done.size).coerceAtLeast(0),
                            )
                        }
                        TrashEvent.Done(action, done.size)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        TrashEvent.Failed(action, failure(e))
                    } finally {
                        mutableState.update { it.copy(busy = false) }
                    }
                eventChannel.trySend(event)
                load()
            }
        }

        private fun failure(error: Exception): TrashFailure =
            when (error) {
                is PaperlessException.Http.Forbidden -> TrashFailure.REFUSED
                is PaperlessException.Http.BadRequest, is PaperlessException.Http.NotFound -> TrashFailure.GONE
                else -> TrashFailure.FAILED
            }
    }
