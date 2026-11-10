package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.database.CachedLabels
import com.naoya0117.paperless.core.database.OfflineCache
import com.naoya0117.paperless.core.model.Correspondent
import com.naoya0117.paperless.core.model.CorrespondentDraft
import com.naoya0117.paperless.core.model.CustomField
import com.naoya0117.paperless.core.model.CustomFieldDraft
import com.naoya0117.paperless.core.model.DocumentType
import com.naoya0117.paperless.core.model.DocumentTypeDraft
import com.naoya0117.paperless.core.model.SavedView
import com.naoya0117.paperless.core.model.SavedViewDraft
import com.naoya0117.paperless.core.model.StoragePath
import com.naoya0117.paperless.core.model.StoragePathDraft
import com.naoya0117.paperless.core.model.Tag
import com.naoya0117.paperless.core.model.TagDraft
import com.naoya0117.paperless.core.network.LabelApi
import com.naoya0117.paperless.core.network.PaperlessClient
import com.naoya0117.paperless.core.network.PaperlessException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tags, correspondents, document types, storage paths, custom fields and saved views of the active account:
 * read from the offline cache, refreshed from the server.
 */
@Singleton
class LabelRepository
    @Inject
    constructor(
        private val accounts: AccountRepository,
        cache: OfflineCache,
    ) {
        /** Writes reload all tags: the server also changes parents' child lists and deletes sub-tags. */
        val tags: Labels<Tag, TagDraft> = Labels(accounts, cache.tags, PaperlessClient::tags, reloadAfterWrite = true)
        val correspondents: Labels<Correspondent, CorrespondentDraft> =
            Labels(accounts, cache.correspondents, PaperlessClient::correspondents)
        val documentTypes: Labels<DocumentType, DocumentTypeDraft> =
            Labels(accounts, cache.documentTypes, PaperlessClient::documentTypes)
        val storagePaths: Labels<StoragePath, StoragePathDraft> =
            Labels(accounts, cache.storagePaths, PaperlessClient::storagePaths)
        val customFields: Labels<CustomField, CustomFieldDraft> =
            Labels(accounts, cache.customFields, PaperlessClient::customFields)
        val savedViews: Labels<SavedView, SavedViewDraft> =
            Labels(accounts, cache.savedViews, PaperlessClient::savedViews)

        private val all = listOf(tags, correspondents, documentTypes, storagePaths, customFields, savedViews)

        /**
         * Refreshes every kind for the active account at once.
         *
         * @throws NotSignedInException
         * @throws com.naoya0117.paperless.core.network.PaperlessException e.g. when offline; the cache is kept
         */
        suspend fun refreshAll() {
            val session = accounts.currentSession() ?: throw NotSignedInException()
            coroutineScope { all.map { async { it.refresh(session) } }.awaitAll() }
        }
    }

/**
 * One kind of label of the active account. Writes go to the server first and reach the cache only when
 * they succeed.
 *
 * @param T the label as read from the server
 * @param D the values to create or update it with
 */
class Labels<T, D> internal constructor(
    private val accounts: AccountRepository,
    private val cache: CachedLabels<T>,
    private val api: (PaperlessClient) -> LabelApi<T, D>,
    private val reloadAfterWrite: Boolean = false,
) {
    /** The cached items of the active account, ordered by name; empty if there is no active account. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val items: Flow<List<T>> =
        accounts.state
            .map { it.activeAccountId }
            .distinctUntilChanged()
            .flatMapLatest { accountId -> accountId?.let(cache::observe) ?: flowOf(emptyList()) }

    /**
     * Replaces the cached items with the server's.
     *
     * @throws NotSignedInException
     * @throws com.naoya0117.paperless.core.network.PaperlessException e.g. when offline; the cache is kept
     */
    suspend fun refresh() = refresh(session())

    internal suspend fun refresh(session: AccountSession) {
        val items = api(session.client).listAll()
        write(session) { cache.replaceAll(it, items) }
    }

    /** @see LabelApi.create */
    suspend fun create(draft: D): T {
        val session = session()
        val created = api(session.client).create(draft)
        afterWrite(session) { cache.upsert(it, created) }
        return created
    }

    /** @see LabelApi.update */
    suspend fun update(
        current: T,
        draft: D,
    ): T {
        val session = session()
        val updated = api(session.client).update(current, draft)
        afterWrite(session) { cache.upsert(it, updated) }
        return updated
    }

    /** @see LabelApi.delete */
    suspend fun delete(id: Long) {
        val session = session()
        api(session.client).delete(id)
        afterWrite(session) { cache.delete(it, id) }
    }

    private suspend fun session() = accounts.currentSession() ?: throw NotSignedInException()

    /**
     * Applies a successful write to the cache. Where the server changes related items too, everything is
     * reloaded; if that fails, the single change is applied so the cache still reflects it.
     */
    private suspend fun afterWrite(
        session: AccountSession,
        applyChange: suspend (accountId: String) -> Unit,
    ) {
        if (reloadAfterWrite) {
            try {
                refresh(session)
                return
            } catch (_: PaperlessException) {
                // Fall through: the write itself succeeded.
            }
        }
        write(session, applyChange)
    }

    private suspend fun write(
        session: AccountSession,
        block: suspend (accountId: String) -> Unit,
    ) {
        val accountId = session.account.id
        block(accountId)
        // The account may have been signed out while the request ran; do not leave its data behind.
        accounts.clearDataIfRemoved(accountId)
    }
}
