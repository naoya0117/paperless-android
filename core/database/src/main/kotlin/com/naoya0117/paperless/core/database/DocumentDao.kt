package com.naoya0117.paperless.core.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class DocumentDao {
    @Query(
        """
        SELECT d.*, i.searchScore, i.searchRank, i.searchHighlights
        FROM document_list_items i
        INNER JOIN documents d ON d.accountId = i.accountId AND d.id = i.documentId
        WHERE i.accountId = :accountId AND i.listKey = :listKey
        ORDER BY i.position
        """,
    )
    abstract fun list(
        accountId: String,
        listKey: String,
    ): PagingSource<Int, DocumentListRow>

    @Query("SELECT * FROM documents WHERE accountId = :accountId AND id = :id")
    abstract suspend fun get(
        accountId: String,
        id: Long,
    ): DocumentEntity?

    @Query("SELECT * FROM documents WHERE accountId = :accountId AND id = :id")
    abstract fun observe(
        accountId: String,
        id: Long,
    ): Flow<DocumentEntity?>

    @Query("SELECT COUNT(*) FROM document_list_items WHERE accountId = :accountId AND listKey = :listKey")
    abstract suspend fun itemCount(
        accountId: String,
        listKey: String,
    ): Int

    @Query("SELECT * FROM document_lists WHERE accountId = :accountId AND listKey = :listKey")
    abstract suspend fun listState(
        accountId: String,
        listKey: String,
    ): DocumentListEntity?

    @Upsert
    abstract suspend fun upsertDocuments(documents: List<DocumentEntity>)

    /** Only changes a cached row; a document that is not cached stays uncached. */
    @Update
    abstract suspend fun update(document: DocumentEntity)

    @Upsert
    abstract suspend fun upsertList(list: DocumentListEntity)

    /** Documents already in the list are skipped (see the unique index). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertItems(items: List<DocumentListItemEntity>)

    @Query(
        "SELECT COALESCE(MAX(position) + 1, 0) FROM document_list_items WHERE accountId = :accountId AND listKey = :listKey",
    )
    abstract suspend fun nextPosition(
        accountId: String,
        listKey: String,
    ): Int

    @Query("DELETE FROM document_list_items WHERE accountId = :accountId AND listKey = :listKey")
    abstract suspend fun clearList(
        accountId: String,
        listKey: String,
    )

    /**
     * Deletes the least recently refreshed lists beyond the newest [keep], except those in [protectedKeys]
     * (which do not count towards [keep]). Their items are removed by [deleteItemsOfMissingLists].
     */
    @Query(
        """
        DELETE FROM document_lists WHERE accountId = :accountId AND listKey IN (
            SELECT listKey FROM document_lists
            WHERE accountId = :accountId AND listKey NOT IN (:protectedKeys)
            ORDER BY refreshedAt DESC LIMIT -1 OFFSET :keep
        )
        """,
    )
    abstract suspend fun deleteOldLists(
        accountId: String,
        protectedKeys: Collection<String>,
        keep: Int,
    )

    @Query(
        """
        DELETE FROM document_list_items WHERE accountId = :accountId AND listKey NOT IN (
            SELECT listKey FROM document_lists WHERE accountId = :accountId
        )
        """,
    )
    abstract suspend fun deleteItemsOfMissingLists(accountId: String)

    @Query(
        """
        DELETE FROM documents WHERE accountId = :accountId AND id NOT IN (
            SELECT documentId FROM document_list_items WHERE accountId = :accountId
        )
        """,
    )
    abstract suspend fun deleteUnlistedDocuments(accountId: String)

    @Query("DELETE FROM documents WHERE accountId = :accountId AND id = :id")
    abstract suspend fun deleteDocument(
        accountId: String,
        id: Long,
    )

    @Query("DELETE FROM document_list_items WHERE accountId = :accountId AND documentId = :id")
    abstract suspend fun deleteFromLists(
        accountId: String,
        id: Long,
    )

    @Query("DELETE FROM documents WHERE accountId = :accountId")
    abstract suspend fun deleteAllDocuments(accountId: String)

    @Query("DELETE FROM document_lists WHERE accountId = :accountId")
    abstract suspend fun deleteAllLists(accountId: String)

    @Query("DELETE FROM document_list_items WHERE accountId = :accountId")
    abstract suspend fun deleteAllListItems(accountId: String)
}
