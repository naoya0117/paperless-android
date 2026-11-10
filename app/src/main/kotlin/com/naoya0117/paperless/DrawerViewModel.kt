package com.naoya0117.paperless

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.data.LabelRepository
import com.naoya0117.paperless.core.model.SavedView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator
import javax.inject.Inject

/** What the menu lists besides fixed items: the saved views (from the cache, refreshed with the lists). */
@HiltViewModel
class DrawerViewModel
    @Inject
    constructor(
        labels: LabelRepository,
        private val accounts: AccountRepository,
    ) : ViewModel() {
        /** The views the user shows in the web UI's sidebar, in that order; `null` if unknown (e.g. offline). */
        private val sidebarIds = MutableStateFlow<List<Long>?>(null)

        /** Every saved view, by name (e.g. for titles). */
        val allSavedViews: StateFlow<List<SavedView>> =
            labels.savedViews.items
                .map { views ->
                    // Per call: follows a change of the app's language.
                    val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
                    views.sortedWith(compareBy(collator) { it.name })
                }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /**
         * The views listed in the menu: like the web UI's sidebar (its views in its order), or all of them if
         * the sidebar setting is unknown or empty.
         */
        val savedViews: StateFlow<List<SavedView>> =
            combine(allSavedViews, sidebarIds) { views, ids ->
                if (ids.isNullOrEmpty()) {
                    views
                } else {
                    val byId = views.associateBy { it.id }
                    ids.mapNotNull(byId::get).ifEmpty { views }
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        init {
            viewModelScope.launch {
                sidebarIds.value =
                    try {
                        accounts
                            .currentSession()
                            ?.client
                            ?.server
                            ?.fetchSavedViewVisibility()
                            ?.sidebarViewIds
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") _: Exception,
                    ) {
                        // Offline or not allowed: all views are listed.
                        null
                    }
            }
        }
    }
