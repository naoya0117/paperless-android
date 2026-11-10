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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Cached objects of one kind, per account. Lists are ordered by name. */
interface CachedLabels<T> {
    fun observe(accountId: String): Flow<List<T>>

    /** Replaces everything cached for the account, e.g. with a full list from the server. */
    suspend fun replaceAll(
        accountId: String,
        items: List<T>,
    )

    /** Adds the item or replaces the one with the same ID. */
    suspend fun upsert(
        accountId: String,
        item: T,
    )

    suspend fun delete(
        accountId: String,
        id: Long,
    )
}

/**
 * Data of the server accounts kept on the device, so that it can be shown at once and offline. Not
 * encrypted: it lives in app-private storage, which is excluded from backups. Not secret data such as
 * tokens, which are in the accounts store.
 */
class OfflineCache private constructor(
    private val database: CacheDatabase,
) {
    val tags: CachedLabels<Tag> = RoomCachedLabels(database.tags(), Tag::toEntity, TagEntity::toModel)

    val correspondents: CachedLabels<Correspondent> =
        RoomCachedLabels(database.correspondents(), Correspondent::toEntity, CorrespondentEntity::toModel)

    val documentTypes: CachedLabels<DocumentType> =
        RoomCachedLabels(database.documentTypes(), DocumentType::toEntity, DocumentTypeEntity::toModel)

    val storagePaths: CachedLabels<StoragePath> =
        RoomCachedLabels(database.storagePaths(), StoragePath::toEntity, StoragePathEntity::toModel)

    val customFields: CachedLabels<CustomField> =
        RoomCachedLabels(database.customFields(), CustomField::toEntity, CustomFieldEntity::toModel)

    val savedViews: CachedLabels<SavedView> =
        RoomCachedLabels(database.savedViews(), SavedView::toEntity, SavedViewEntity::toModel)

    /** Deletes everything cached for the account, e.g. after signing out. */
    suspend fun clear(accountId: String) {
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                database.tags().deleteAll(accountId)
                database.correspondents().deleteAll(accountId)
                database.documentTypes().deleteAll(accountId)
                database.storagePaths().deleteAll(accountId)
                database.customFields().deleteAll(accountId)
                database.savedViews().deleteAll(accountId)
            }
        }
    }

    /** For tests. */
    internal fun close() = database.close()

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

private class RoomCachedLabels<T, E>(
    private val dao: LabelDao<E>,
    private val toEntity: (T, String) -> E,
    private val toModel: (E) -> T,
) : CachedLabels<T> {
    override fun observe(accountId: String): Flow<List<T>> = dao.observe(accountId).map { rows -> rows.map(toModel) }

    override suspend fun replaceAll(
        accountId: String,
        items: List<T>,
    ) = dao.replaceAll(accountId, items.map { toEntity(it, accountId) })

    override suspend fun upsert(
        accountId: String,
        item: T,
    ) = dao.upsert(listOf(toEntity(item, accountId)))

    override suspend fun delete(
        accountId: String,
        id: Long,
    ) = dao.delete(accountId, id)
}
