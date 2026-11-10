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
import com.naoya0117.paperless.core.model.descendantsAndSelf
import com.naoya0117.paperless.core.network.LabelApi
import com.naoya0117.paperless.core.network.PaperlessClient
import com.naoya0117.paperless.core.network.PaperlessException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tags, correspondents, document types, storage paths, custom fields and saved views of the active account:
 * read from the offline cache, refreshed from the server.
 */
@Singleton
class LabelRepository internal constructor(
    private val accounts: AccountRepository,
    cache: OfflineCache,
    clock: Clock,
) {
    @Inject
    constructor(accounts: AccountRepository, cache: OfflineCache) : this(accounts, cache, Clock.systemUTC())

    val savedViews: Labels<SavedView, SavedViewDraft> =
        Labels(accounts, cache.savedViews, PaperlessClient::savedViews, clock)

    /** Saved views can filter on, sort by or show labels; the server updates them when one is deleted. */
    private val refreshSavedViews: suspend (AccountSession) -> Unit = { savedViews.refreshQuietly(it) }

    val tags: Labels<Tag, TagDraft> =
        Labels(accounts, cache.tags, PaperlessClient::tags, clock, refreshSavedViews, TagHierarchy)
    val correspondents: Labels<Correspondent, CorrespondentDraft> =
        Labels(accounts, cache.correspondents, PaperlessClient::correspondents, clock, refreshSavedViews)
    val documentTypes: Labels<DocumentType, DocumentTypeDraft> =
        Labels(accounts, cache.documentTypes, PaperlessClient::documentTypes, clock, refreshSavedViews)
    val storagePaths: Labels<StoragePath, StoragePathDraft> =
        Labels(accounts, cache.storagePaths, PaperlessClient::storagePaths, clock, refreshSavedViews)
    val customFields: Labels<CustomField, CustomFieldDraft> =
        Labels(accounts, cache.customFields, PaperlessClient::customFields, clock, refreshSavedViews)

    private val all = listOf(tags, correspondents, documentTypes, storagePaths, customFields, savedViews)

    /**
     * Refreshes every kind for the active account. Each kind is refreshed even if others fail (e.g. a user
     * without permission to view one kind); the first failure is thrown afterwards, with the others
     * suppressed in it.
     *
     * @throws NotSignedInException
     * @throws PaperlessException e.g. when offline; the cache of the failed kinds is kept
     */
    suspend fun refreshAll() {
        val session = accounts.currentSession() ?: throw NotSignedInException()
        val failures =
            supervisorScope {
                all
                    .map { labels ->
                        async {
                            try {
                                labels.refresh(session)
                                null
                            } catch (e: PaperlessException) {
                                e
                            }
                        }
                    }.awaitAll()
                    .filterNotNull()
            }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }
}

/**
 * One kind of label of the active account. Writes go to the server first and reach the cache only when
 * they succeed; refreshes and cache updates of one kind run one at a time, so that a list fetched before
 * a write cannot overwrite it.
 *
 * @param T the label as read from the server
 * @param D the values to create or update it with
 */
class Labels<T, D> internal constructor(
    private val accounts: AccountRepository,
    private val cache: CachedLabels<T>,
    private val api: (PaperlessClient) -> LabelApi<T, D>,
    private val clock: Clock,
    private val afterDelete: suspend (AccountSession) -> Unit = {},
    private val changes: CacheChanges<T> = CacheChanges.single(),
) {
    private val mutex = Mutex()

    /** The cached items of the active account, ordered by name; empty if there is no active account. */
    val items: Flow<List<T>> = activeAccountFlow(emptyList(), cache::observe)

    /**
     * When the active account's items were last refreshed; `null` if never, so an empty [items] means
     * "not loaded yet" rather than "none on the server".
     */
    val refreshedAt: Flow<Instant?> = activeAccountFlow(null, cache::observeRefreshedAt)

    /**
     * Replaces the cached items with the server's.
     *
     * @throws NotSignedInException
     * @throws PaperlessException e.g. when offline; the cache is kept
     */
    suspend fun refresh() = refresh(session())

    internal suspend fun refresh(session: AccountSession) {
        mutex.withLock {
            val items = api(session.client).listAll()
            val refreshedAt = clock.instant()
            accounts.writeData(session) { cache.replaceAll(session.account.id, items, refreshedAt) }
        }
    }

    /** Like [refresh], but failures only leave the cache as it is. */
    internal suspend fun refreshQuietly(session: AccountSession) {
        try {
            refresh(session)
        } catch (_: PaperlessException) {
            // Refreshed with the next full refresh.
        }
    }

    /** @see LabelApi.create */
    suspend fun create(draft: D): T {
        val session = session()
        val created = api(session.client).create(draft)
        applyToCache(session) { cached -> changes.saved(cached, created) }
        return created
    }

    /** @see LabelApi.update */
    suspend fun update(
        current: T,
        draft: D,
    ): T {
        val session = session()
        val updated = api(session.client).update(current, draft)
        // Nothing was sent: keep the cache, which may be newer than the caller's copy.
        if (updated !== current) applyToCache(session) { cached -> changes.saved(cached, updated) }
        return updated
    }

    /**
     * Deletes the item on the server and from the cache. An item the server no longer has (404) counts as
     * deleted, so that it can be removed from the cache.
     *
     * @see LabelApi.delete
     */
    suspend fun delete(id: Long) {
        val session = session()
        try {
            api(session.client).delete(id)
        } catch (_: PaperlessException.Http.NotFound) {
            // Already deleted, e.g. in the web UI.
        }
        applyToCache(session) { cached -> changes.deleted(cached, id) }
        afterDelete(session)
    }

    private suspend fun session() = accounts.currentSession() ?: throw NotSignedInException()

    /**
     * Applies a write the server accepted. Not cancellable: the change happened on the server, and the
     * cache must not miss it because the caller went away (e.g. the user left the screen).
     */
    private suspend fun applyToCache(
        session: AccountSession,
        change: (List<T>) -> CacheChanges.Change<T>,
    ) = withContext(NonCancellable) {
        mutex.withLock {
            val accountId = session.account.id
            accounts.writeData(session) {
                val (upserts, deletions) = change(cache.observe(accountId).first())
                if (deletions.isNotEmpty()) cache.delete(accountId, deletions)
                if (upserts.isNotEmpty()) cache.upsert(accountId, upserts)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <R> activeAccountFlow(
        none: R,
        observe: (String) -> Flow<R>,
    ): Flow<R> =
        accounts.state
            .map { it.activeAccountId }
            .distinctUntilChanged()
            .flatMapLatest { accountId -> accountId?.let(observe) ?: flowOf(none) }
}

/** How a write the server accepted changes the cached items of one kind. */
internal interface CacheChanges<T> {
    data class Change<E>(
        val upserts: List<E>,
        val deletions: Set<Long>,
    )

    fun saved(
        cached: List<T>,
        item: T,
    ): Change<T>

    fun deleted(
        cached: List<T>,
        id: Long,
    ): Change<T>

    companion object {
        /** Items that do not affect each other. */
        fun <T> single(): CacheChanges<T> =
            object : CacheChanges<T> {
                override fun saved(
                    cached: List<T>,
                    item: T,
                ) = Change(listOf(item), emptySet())

                override fun deleted(
                    cached: List<T>,
                    id: Long,
                ) = Change(emptyList<T>(), setOf(id))
            }
    }
}

/** Mirrors what the server does to the tag tree: parents list their children, and deleting deletes sub-tags. */
internal object TagHierarchy : CacheChanges<Tag> {
    override fun saved(
        cached: List<Tag>,
        item: Tag,
    ): CacheChanges.Change<Tag> {
        val byId = cached.associateBy { it.id }
        val previousParent = byId[item.id]?.parentId
        val upserts = mutableListOf(item)
        if (previousParent != item.parentId) {
            previousParent?.let(byId::get)?.let { upserts += it.copy(childIds = it.childIds - item.id) }
            item.parentId?.let(byId::get)?.let { parent ->
                if (item.id !in parent.childIds) upserts += parent.copy(childIds = parent.childIds + item.id)
            }
        }
        return CacheChanges.Change(upserts, emptySet())
    }

    override fun deleted(
        cached: List<Tag>,
        id: Long,
    ): CacheChanges.Change<Tag> {
        val deleted = cached.descendantsAndSelf(id)
        val parent =
            cached.firstOrNull { it.id == id }?.parentId?.let { parentId ->
                cached.firstOrNull {
                    it.id ==
                        parentId
                }
            }
        val upserts = listOfNotNull(parent?.copy(childIds = parent.childIds - id)).filter { it.id !in deleted }
        return CacheChanges.Change(upserts, deleted)
    }
}
