package com.naoya0117.paperless.core.database

import androidx.room.Embedded
import androidx.room.Entity

/*
 * One table per label kind; rows belong to an account (accountId from the accounts store) and are keyed by
 * the server's ID. Lists are stored as JSON text (see Mappers.kt): they are always read whole.
 */

internal data class MatchingColumns(
    val algorithm: Int,
    val pattern: String,
    val isInsensitive: Boolean,
)

@Entity(tableName = "tags", primaryKeys = ["accountId", "id"])
internal data class TagEntity(
    val accountId: String,
    val id: Long,
    val name: String,
    val slug: String,
    @Embedded(prefix = "matching_") val matching: MatchingColumns,
    val documentCount: Int?,
    val owner: Long?,
    val userCanChange: Boolean,
    val color: String,
    val textColor: String,
    val isInboxTag: Boolean,
    val parentId: Long?,
    val childIdsJson: String,
)

@Entity(tableName = "correspondents", primaryKeys = ["accountId", "id"])
internal data class CorrespondentEntity(
    val accountId: String,
    val id: Long,
    val name: String,
    val slug: String,
    @Embedded(prefix = "matching_") val matching: MatchingColumns,
    val documentCount: Int?,
    val owner: Long?,
    val userCanChange: Boolean,
    /** ISO date. */
    val lastCorrespondence: String?,
)

@Entity(tableName = "document_types", primaryKeys = ["accountId", "id"])
internal data class DocumentTypeEntity(
    val accountId: String,
    val id: Long,
    val name: String,
    val slug: String,
    @Embedded(prefix = "matching_") val matching: MatchingColumns,
    val documentCount: Int?,
    val owner: Long?,
    val userCanChange: Boolean,
)

@Entity(tableName = "storage_paths", primaryKeys = ["accountId", "id"])
internal data class StoragePathEntity(
    val accountId: String,
    val id: Long,
    val name: String,
    val slug: String,
    @Embedded(prefix = "matching_") val matching: MatchingColumns,
    val documentCount: Int?,
    val owner: Long?,
    val userCanChange: Boolean,
    val path: String,
)

@Entity(tableName = "custom_fields", primaryKeys = ["accountId", "id"])
internal data class CustomFieldEntity(
    val accountId: String,
    val id: Long,
    val name: String,
    val dataType: String,
    val selectOptionsJson: String,
    val defaultCurrency: String?,
    val documentCount: Int?,
)

@Entity(tableName = "saved_views", primaryKeys = ["accountId", "id"])
internal data class SavedViewEntity(
    val accountId: String,
    val id: Long,
    val name: String,
    val icon: String?,
    val sortField: String?,
    val sortReverse: Boolean,
    val filterRulesJson: String,
    val pageSize: Int?,
    val displayMode: String?,
    /** `null` for the web UI's defaults. */
    val displayFieldsJson: String?,
    val owner: Long?,
    val userCanChange: Boolean,
)
