package com.naoya0117.paperless.core.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index

/** A document as listed by the server (content shortened), shared by every list that contains it. */
@Entity(tableName = "documents", primaryKeys = ["accountId", "id"])
internal data class DocumentEntity(
    val accountId: String,
    val id: Long,
    val title: String,
    val content: String,
    /** ISO date. */
    val created: String,
    /** Epoch milliseconds. */
    val added: Long,
    val modified: Long,
    val correspondentId: Long?,
    val documentTypeId: Long?,
    val storagePathId: Long?,
    val tagIdsJson: String,
    val archiveSerialNumber: Long?,
    val originalFileName: String?,
    val archivedFileName: String?,
    val mimeType: String?,
    val pageCount: Int?,
    val customFieldsJson: String,
    /** `null` if the server did not include notes. */
    val notesJson: String?,
    val owner: Long?,
    val userCanChange: Boolean,
    val deletedAt: Long?,
)

/**
 * One cached result list of a document query ([listKey]): how far it was loaded and when it was refreshed.
 */
@Entity(tableName = "document_lists", primaryKeys = ["accountId", "listKey"])
internal data class DocumentListEntity(
    val accountId: String,
    val listKey: String,
    /** The server page to load next; `null` when the end was reached. */
    val nextPage: Int?,
    val refreshedAt: Long,
)

/** A document's place in a list. Search results carry their hit, which belongs to the query. */
@Entity(
    tableName = "document_list_items",
    primaryKeys = ["accountId", "listKey", "position"],
    indices = [
        // A document appears at most once per list, even if server pages shift while loading.
        Index(value = ["accountId", "listKey", "documentId"], unique = true),
        Index(value = ["accountId", "documentId"]),
    ],
)
internal data class DocumentListItemEntity(
    val accountId: String,
    val listKey: String,
    val position: Int,
    val documentId: Long,
    val searchScore: Double?,
    val searchRank: Int?,
    val searchHighlights: String?,
)

internal data class DocumentListRow(
    @Embedded val document: DocumentEntity,
    val searchScore: Double?,
    val searchRank: Int?,
    val searchHighlights: String?,
)
