package com.naoya0117.paperless

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.LabelRepository
import com.naoya0117.paperless.core.model.SavedView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.Collator
import javax.inject.Inject

/** What the menu lists besides fixed items: the saved views (from the cache, refreshed with the lists). */
@HiltViewModel
class DrawerViewModel
    @Inject
    constructor(
        labels: LabelRepository,
    ) : ViewModel() {
        private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

        val savedViews: StateFlow<List<SavedView>> =
            labels.savedViews.items
                .map { views -> views.sortedWith(compareBy(collator) { it.name }) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    }
