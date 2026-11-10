package com.naoya0117.paperless.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteDriver
import com.naoya0117.paperless.core.model.Correspondent
import com.naoya0117.paperless.core.model.CustomField
import com.naoya0117.paperless.core.model.DocumentType
import com.naoya0117.paperless.core.model.SavedView
import com.naoya0117.paperless.core.model.StoragePath
import com.naoya0117.paperless.core.model.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.text.Collator
import java.time.Instant

/** Cached objects of one kind, per account. */
interface CachedLabels<T> {
    /** Ordered by name for the device's language. */
    fun observe(accountId: String): Flow<List<T>>

    /** When [replaceAll] last ran for the account; `null` if never (nothing cached yet). */
    fun observeRefreshedAt(accountId: String): Flow<Instant?>

    /** Replaces everything cached for the account with a full list from the server, fetched at [refreshedAt]. */
    suspend fun replaceAll(
        accountId: String,
        items: List<T>,
        refreshedAt: Instant,
    )

    /** Adds the items or replaces those with the same IDs. */
    suspend fun upsert(
        accountId: String,
        items: List<T>,
    )

    suspend fun delete(
        accountId: String,
        ids: Collection<Long>,
    )
}

/**
 * Data of the server accounts kept on the device, so that it can be shown at once and offline. Not
 * encrypted: it lives in app-private storage, which is excluded from backups. No secrets such as tokens,
 * which are in the accounts store.
 */
class OfflineCache private constructor(
    private val database: CacheDatabase,
) {
    val tags: CachedLabels<Tag> = labels(database.tags(), "tags", Tag::name, Tag::toEntity, TagEntity::toModel)

    val correspondents: CachedLabels<Correspondent> =
        labels(
            database.correspondents(),
            "correspondents",
            Correspondent::name,
            Correspondent::toEntity,
            CorrespondentEntity::toModel,
        )

    val documentTypes: CachedLabels<DocumentType> =
        labels(
            database.documentTypes(),
            "document_types",
            DocumentType::name,
            DocumentType::toEntity,
            DocumentTypeEntity::toModel,
        )

    val storagePaths: CachedLabels<StoragePath> =
        labels(
            database.storagePaths(),
            "storage_paths",
            StoragePath::name,
            StoragePath::toEntity,
            StoragePathEntity::toModel,
        )

    val customFields: CachedLabels<CustomField> =
        labels(
            database.customFields(),
            "custom_fields",
            CustomField::name,
            CustomField::toEntity,
            CustomFieldEntity::toModel,
        )

    val savedViews: CachedLabels<SavedView> =
        labels(database.savedViews(), "saved_views", SavedView::name, SavedView::toEntity, SavedViewEntity::toModel)

    val documents: CachedDocuments = CachedDocuments(database, ::transaction)

    /** Deletes everything cached for the account, e.g. after signing out. */
    suspend fun clear(accountId: String) =
        transaction {
            database.tags().deleteAll(accountId)
            database.correspondents().deleteAll(accountId)
            database.documentTypes().deleteAll(accountId)
            database.storagePaths().deleteAll(accountId)
            database.customFields().deleteAll(accountId)
            database.savedViews().deleteAll(accountId)
            database.cacheMeta().deleteAll(accountId)
            documents.clear(accountId)
        }

    /** For tests. */
    internal fun close() = database.close()

    private suspend fun transaction(block: suspend () -> Unit) =
        database.useWriterConnection { connection -> connection.immediateTransaction { block() } }

    private fun <T, E> labels(
        dao: LabelDao<E>,
        kind: String,
        nameOf: (T) -> String,
        toEntity: (T, String) -> E,
        toModel: (E) -> T,
    ): CachedLabels<T> =
        object : CachedLabels<T> {
            override fun observe(accountId: String): Flow<List<T>> =
                dao
                    .observe(accountId)
                    .map { rows ->
                        val collator = Collator.getInstance()
                        rows.map(toModel).sortedWith { a, b -> collator.compare(nameOf(a), nameOf(b)) }
                    }.distinctUntilChanged()
                    .flowOn(Dispatchers.Default)

            override fun observeRefreshedAt(accountId: String): Flow<Instant?> =
                database
                    .cacheMeta()
                    .observeRefreshedAt(accountId, kind)
                    .map { millis -> millis?.let(Instant::ofEpochMilli) }
                    .distinctUntilChanged()

            override suspend fun replaceAll(
                accountId: String,
                items: List<T>,
                refreshedAt: Instant,
            ) = transaction {
                dao.deleteAll(accountId)
                dao.upsert(items.map { toEntity(it, accountId) })
                database.cacheMeta().upsert(CacheMetaEntity(accountId, kind, refreshedAt.toEpochMilli()))
            }

            override suspend fun upsert(
                accountId: String,
                items: List<T>,
            ) = dao.upsert(items.map { toEntity(it, accountId) })

            override suspend fun delete(
                accountId: String,
                ids: Collection<Long>,
            ) = dao.delete(accountId, ids)
        }

    companion object {
        /**
         * The cache is only a copy of server data, so a schema change without a migration rebuilds it
         * instead of failing; it is filled again on the next refresh.
         */
        fun create(context: Context): OfflineCache =
            OfflineCache(
                Room
                    .databaseBuilder(context, CacheDatabase::class.java, "offline-cache.db")
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build(),
            )

        /**
         * An in-memory cache for tests. [driver] replaces Android's SQLite, e.g. with `BundledSQLiteDriver`
         * where Robolectric's SQLite is not available.
         */
        fun inMemory(
            context: Context,
            driver: SQLiteDriver? = null,
        ): OfflineCache =
            OfflineCache(
                Room
                    .inMemoryDatabaseBuilder(context, CacheDatabase::class.java)
                    .apply { driver?.let { setDriver(it) } }
                    .build(),
            )
    }
}
