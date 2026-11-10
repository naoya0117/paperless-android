package com.naoya0117.paperless

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The about screen's library list: read once per visit (not again after rotating or coming back to it). */
@HiltViewModel
class AboutViewModel
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : ViewModel() {
        internal val libraries: StateFlow<LibrariesState> =
            flow {
                emit(
                    runCatching {
                        loadLibraries(
                            context,
                        )
                    }.fold({ LibrariesState.Loaded(it) }, { LibrariesState.Failed }),
                )
            }.flowOn(Dispatchers.IO)
                .stateIn(viewModelScope, SharingStarted.Eagerly, LibrariesState.Loading)
    }
