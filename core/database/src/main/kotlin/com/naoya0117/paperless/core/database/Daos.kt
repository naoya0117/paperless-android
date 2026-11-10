package com.naoya0117.paperless.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Operations shared by every label table; the queries name the table, so each kind declares them. */
internal abstract class LabelDao<E> {
    abstract fun observe(accountId: String): Flow<List<E>>

    @Upsert
    abstract suspend fun upsert(items: List<E>)

    abstract suspend fun delete(
        accountId: String,
        id: Long,
    )

    abstract suspend fun deleteAll(accountId: String)

    @Transaction
    open suspend fun replaceAll(
        accountId: String,
        items: List<E>,
    ) {
        deleteAll(accountId)
        upsert(items)
    }
}

@Dao
internal abstract class TagDao : LabelDao<TagEntity>() {
    @Query("SELECT * FROM tags WHERE accountId = :accountId ORDER BY name COLLATE NOCASE, id")
    abstract override fun observe(accountId: String): Flow<List<TagEntity>>

    @Query("DELETE FROM tags WHERE accountId = :accountId AND id = :id")
    abstract override suspend fun delete(
        accountId: String,
        id: Long,
    )

    @Query("DELETE FROM tags WHERE accountId = :accountId")
    abstract override suspend fun deleteAll(accountId: String)
}

@Dao
internal abstract class CorrespondentDao : LabelDao<CorrespondentEntity>() {
    @Query("SELECT * FROM correspondents WHERE accountId = :accountId ORDER BY name COLLATE NOCASE, id")
    abstract override fun observe(accountId: String): Flow<List<CorrespondentEntity>>

    @Query("DELETE FROM correspondents WHERE accountId = :accountId AND id = :id")
    abstract override suspend fun delete(
        accountId: String,
        id: Long,
    )

    @Query("DELETE FROM correspondents WHERE accountId = :accountId")
    abstract override suspend fun deleteAll(accountId: String)
}

@Dao
internal abstract class DocumentTypeDao : LabelDao<DocumentTypeEntity>() {
    @Query("SELECT * FROM document_types WHERE accountId = :accountId ORDER BY name COLLATE NOCASE, id")
    abstract override fun observe(accountId: String): Flow<List<DocumentTypeEntity>>

    @Query("DELETE FROM document_types WHERE accountId = :accountId AND id = :id")
    abstract override suspend fun delete(
        accountId: String,
        id: Long,
    )

    @Query("DELETE FROM document_types WHERE accountId = :accountId")
    abstract override suspend fun deleteAll(accountId: String)
}

@Dao
internal abstract class StoragePathDao : LabelDao<StoragePathEntity>() {
    @Query("SELECT * FROM storage_paths WHERE accountId = :accountId ORDER BY name COLLATE NOCASE, id")
    abstract override fun observe(accountId: String): Flow<List<StoragePathEntity>>

    @Query("DELETE FROM storage_paths WHERE accountId = :accountId AND id = :id")
    abstract override suspend fun delete(
        accountId: String,
        id: Long,
    )

    @Query("DELETE FROM storage_paths WHERE accountId = :accountId")
    abstract override suspend fun deleteAll(accountId: String)
}

@Dao
internal abstract class CustomFieldDao : LabelDao<CustomFieldEntity>() {
    @Query("SELECT * FROM custom_fields WHERE accountId = :accountId ORDER BY name COLLATE NOCASE, id")
    abstract override fun observe(accountId: String): Flow<List<CustomFieldEntity>>

    @Query("DELETE FROM custom_fields WHERE accountId = :accountId AND id = :id")
    abstract override suspend fun delete(
        accountId: String,
        id: Long,
    )

    @Query("DELETE FROM custom_fields WHERE accountId = :accountId")
    abstract override suspend fun deleteAll(accountId: String)
}

@Dao
internal abstract class SavedViewDao : LabelDao<SavedViewEntity>() {
    @Query("SELECT * FROM saved_views WHERE accountId = :accountId ORDER BY name COLLATE NOCASE, id")
    abstract override fun observe(accountId: String): Flow<List<SavedViewEntity>>

    @Query("DELETE FROM saved_views WHERE accountId = :accountId AND id = :id")
    abstract override suspend fun delete(
        accountId: String,
        id: Long,
    )

    @Query("DELETE FROM saved_views WHERE accountId = :accountId")
    abstract override suspend fun deleteAll(accountId: String)
}
