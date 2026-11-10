package com.naoya0117.paperless.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** How many documents wait in the inbox, for the inbox button (shown only when there are any). */
@HiltViewModel
class InboxViewModel internal constructor(
    private val documents: DocumentRepository,
    private val clock: Clock,
) : ViewModel() {
    @Inject
    constructor(documents: DocumentRepository) : this(documents, Clock.systemUTC())

    private val mutableCount = MutableStateFlow(0)

    /**
     * 0 when the inbox is empty or not set up. When the server cannot be reached, the number of cached inbox
     * documents, so that the inbox can still be opened offline.
     */
    val count: StateFlow<Int> = mutableCount.asStateFlow()

    private var job: Job? = null
    private var lastRefresh: Instant? = null

    init {
        refresh(force = true)
    }

    /**
     * Fetches the count again; the newest request wins. Without [force] (e.g. the list was shown again) at
     * most every [MIN_INTERVAL], so that browsing documents does not ask each time.
     */
    fun refresh(force: Boolean = false) {
        val now = clock.instant()
        val last = lastRefresh
        if (!force && last != null && Duration.between(last, now) < MIN_INTERVAL) return
        lastRefresh = now
        job?.cancel()
        job =
            viewModelScope.launch {
                mutableCount.value =
                    try {
                        documents.inboxCount()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Exception,
                    ) {
                        // Unknown: keep a known count, else offer the cached inbox.
                        mutableCount.value.takeIf { it > 0 } ?: documents.cachedInboxCount()
                    }
            }
    }

    private companion object {
        val MIN_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}
