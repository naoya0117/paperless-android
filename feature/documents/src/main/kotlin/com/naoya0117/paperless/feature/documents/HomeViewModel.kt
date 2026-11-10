package com.naoya0117.paperless.feature.documents

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.data.DocumentRepository
import com.naoya0117.paperless.core.data.LabelRepository
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.SavedView
import com.naoya0117.paperless.core.network.toDocumentQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeState(
    /** The views on the dashboard, in its order; `null` until known (from the server or saved before). */
    val views: List<SavedView>? = null,
    val refreshing: Boolean = false,
)

/**
 * The home screen: the saved views the user shows on the dashboard of the web UI, each with its first
 * documents. The dashboard setting is fetched with the views and kept for offline use.
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val documents: DocumentRepository,
        private val labels: LabelRepository,
        private val accounts: AccountRepository,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val dashboardIds = MutableStateFlow<List<Long>?>(null)
        private val refreshing = MutableStateFlow(false)
        private val refreshes = MutableStateFlow(0)

        val state: StateFlow<HomeState> =
            combine(labels.savedViews.items, dashboardIds, refreshing) { views, ids, refreshing ->
                val byId = views.associateBy { it.id }
                HomeState(ids?.mapNotNull(byId::get), refreshing)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeState())

        /** Counts pulls to refresh: the views' lists refresh then too. */
        val refreshCount: StateFlow<Int> = refreshes.asStateFlow()

        /** For thumbnails ([DocumentThumbnail]), which are cached per account. */
        val accountId: StateFlow<String?> =
            accounts.state
                .map { it.activeAccountId }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

        private val pages = HashMap<SavedView, Flow<PagingData<Document>>>()

        init {
            viewModelScope.launch {
                // Saved before: shown at once (and offline).
                accounts.state
                    .first()
                    .activeAccountId
                    ?.let { dashboardIds.value = savedIds(it) }
                load()
            }
        }

        /** The documents of [view] (its first page is shown), kept while the screen lives. */
        fun pages(view: SavedView): Flow<PagingData<Document>> =
            pages.getOrPut(view) { documents.documents(view.toDocumentQuery()).cachedIn(viewModelScope) }

        fun refresh() {
            if (refreshing.value) return
            refreshes.value++
            viewModelScope.launch { load() }
        }

        private suspend fun load() {
            refreshing.value = true
            try {
                labels.savedViews.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                // Offline: the cached views.
            }
            try {
                val session = accounts.currentSession()
                val ids =
                    session
                        ?.client
                        ?.server
                        ?.fetchSavedViewVisibility()
                        ?.dashboardViewIds
                if (session != null && ids != null) {
                    dashboardIds.value = ids
                    saveIds(session.account.id, ids)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                // Offline: as saved before (or none).
                if (dashboardIds.value == null) dashboardIds.value = emptyList()
            } finally {
                refreshing.value = false
            }
        }

        private val preferences get() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

        private fun savedIds(accountId: String): List<Long>? =
            preferences.getString(KEY_PREFIX + accountId, null)?.split(',')?.mapNotNull { it.toLongOrNull() }

        private fun saveIds(
            accountId: String,
            ids: List<Long>,
        ) = preferences.edit { putString(KEY_PREFIX + accountId, ids.joinToString(",")) }

        companion object {
            private const val PREFERENCES = "home"
            private const val KEY_PREFIX = "dashboard_views."
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
