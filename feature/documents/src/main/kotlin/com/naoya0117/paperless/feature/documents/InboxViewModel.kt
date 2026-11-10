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
import javax.inject.Inject

/** How many documents wait in the inbox, for the inbox button (shown only when there are any). */
@HiltViewModel
class InboxViewModel
    @Inject
    constructor(
        private val documents: DocumentRepository,
    ) : ViewModel() {
        private val mutableCount = MutableStateFlow(0)

        /** 0 when the inbox is empty, not set up, or the count is unknown (e.g. offline). */
        val count: StateFlow<Int> = mutableCount.asStateFlow()

        private var job: Job? = null

        init {
            refresh()
        }

        /** E.g. when the list is shown again or refreshed. */
        fun refresh() {
            if (job?.isActive == true) return
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
                            // Unknown: keep the last count rather than hiding a full inbox while offline.
                            mutableCount.value
                        }
                }
        }
    }
