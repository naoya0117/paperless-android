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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeState(
    /** The views on the dashboard, in its order; `null` until known (from the server or saved before). */
    val views: List<SavedView>? = null,
    val refreshing: Boolean = false,
    /** The dashboard setting could not be loaded (offline, and not known from before). */
    val loadFailed: Boolean = false,
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
        private val loadFailed = MutableStateFlow(false)
        private val refreshes = MutableStateFlow(0)

        /** Whether the dashboard setting came from the server (not only saved from before). */
        private var fetched = false

        /** When the views were refreshed as [load] last saw it (its own refresh does not start another). */
        private var seenRefresh: java.time.Instant? = null

        val state: StateFlow<HomeState> =
            combine(labels.savedViews.items, dashboardIds, refreshing, loadFailed) { views, ids, refreshing, failed ->
                val byId = views.associateBy { it.id }
                HomeState(ids?.distinct()?.mapNotNull(byId::get), refreshing, failed)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeState())

        /** Counts pulls to refresh: the views' lists refresh then too (each pull once). */
        val refreshCount: StateFlow<Int> = refreshes.asStateFlow()

        /** For thumbnails ([DocumentThumbnail]), which are cached per account. */
        val accountId: StateFlow<String?> =
            accounts.state
                .map { it.activeAccountId }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

        /** Per view ID: the view the flow is for, and the flow (replaced when the view changes). */
        private val pages = HashMap<Long, Pair<SavedView, Flow<PagingData<Document>>>>()

        init {
            viewModelScope.launch {
                // Saved before: shown at once (and offline).
                accounts.state
                    .first()
                    .activeAccountId
                    ?.let { dashboardIds.value = savedIds(it) }
                load()
            }
            // Not fetched yet (e.g. started offline): again once the views were refreshed (e.g. back online).
            viewModelScope.launch {
                labels.savedViews.refreshedAt.distinctUntilChanged().collect { refreshedAt ->
                    // Only for a refresh by someone else (e.g. the lists once back online), not this one's own.
                    if (!fetched && !refreshing.value && refreshedAt != null && refreshedAt != seenRefresh) load()
                }
            }
        }

        /** The documents of [view] (its first ones are shown), kept while the screen lives. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun pages(view: SavedView): Flow<PagingData<Document>> {
            pages[view.id]?.takeIf { it.first == view }?.let { return it.second }
            // Like the view's list (DocumentListViewModel): a sort by a deleted custom field falls back.
            val flow =
                combine(labels.customFields.items, labels.customFields.refreshedAt) { fields, refreshedAt ->
                    view.toDocumentQuery(existingCustomFieldIds = refreshedAt?.let { fields.map { it.id }.toSet() })
                }.distinctUntilChanged()
                    .flatMapLatest { query ->
                        try {
                            documents.documents(query)
                        } catch (_: IllegalArgumentException) {
                            // Conditions this app cannot combine: nothing rather than a crash.
                            flowOf(PagingData.empty())
                        }
                    }.cachedIn(viewModelScope)
            pages[view.id] = view to flow
            return flow
        }

        fun refresh() {
            // The views' lists refresh in any case; the dashboard setting once at a time.
            refreshes.value++
            if (!refreshing.value) viewModelScope.launch { load() }
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
            seenRefresh = labels.savedViews.refreshedAt.first()
            try {
                val session = accounts.currentSession()
                if (session == null) {
                    if (dashboardIds.value == null) dashboardIds.value = emptyList()
                } else {
                    val ids =
                        session.client.server
                            .fetchSavedViewVisibility()
                            .dashboardViewIds
                    dashboardIds.value = ids
                    saveIds(session.account.id, ids)
                    fetched = true
                    loadFailed.value = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                // Offline: as saved before (or none, then said so).
                if (dashboardIds.value == null) {
                    dashboardIds.value = emptyList()
                    loadFailed.value = true
                }
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
            /** Forgets the account's saved dashboard setting (when it signs out). */
            fun clear(
                context: Context,
                accountId: String,
            ) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { remove(KEY_PREFIX + accountId) }

            private const val PREFERENCES = "home"
            private const val KEY_PREFIX = "dashboard_views."
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
