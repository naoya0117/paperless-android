package com.naoya0117.paperless.core.database

import com.naoya0117.paperless.core.model.CustomFieldValue
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.FieldValue
import com.naoya0117.paperless.core.model.Note
import com.naoya0117.paperless.core.model.NoteAuthor
import com.naoya0117.paperless.core.model.SearchHit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

private val json = Json { ignoreUnknownKeys = true }

private val longList = ListSerializer(Long.serializer())
private val fieldValues = ListSerializer(StoredFieldValue.serializer())
private val notes = ListSerializer(StoredNote.serializer())

@Serializable
private data class StoredFieldValue(
    val fieldId: Long,
    val kind: String,
    val text: String? = null,
    val integer: Long? = null,
    val decimal: Double? = null,
    val bool: Boolean? = null,
    val ids: List<Long>? = null,
)

@Serializable
private data class StoredNote(
    val id: Long,
    val text: String,
    val created: Long,
    val user: StoredNoteAuthor? = null,
)

@Serializable
private data class StoredNoteAuthor(
    val id: Long,
    val username: String,
    val firstName: String? = null,
    val lastName: String? = null,
)

private fun CustomFieldValue.toStored(): StoredFieldValue =
    when (val v = value) {
        FieldValue.Empty -> StoredFieldValue(fieldId, "empty")
        is FieldValue.Text -> StoredFieldValue(fieldId, "text", text = v.value)
        is FieldValue.Integer -> StoredFieldValue(fieldId, "integer", integer = v.value)
        is FieldValue.Decimal -> StoredFieldValue(fieldId, "decimal", decimal = v.value)
        is FieldValue.Bool -> StoredFieldValue(fieldId, "bool", bool = v.value)
        is FieldValue.DocumentLinks -> StoredFieldValue(fieldId, "links", ids = v.documentIds)
        is FieldValue.Unknown -> StoredFieldValue(fieldId, "unknown", text = v.json)
    }

private fun StoredFieldValue.toModel(): CustomFieldValue {
    val value =
        when (kind) {
            "text" -> text?.let(FieldValue::Text)
            "integer" -> integer?.let(FieldValue::Integer)
            "decimal" -> decimal?.takeIf { it.isFinite() }?.let(FieldValue::Decimal)
            "bool" -> bool?.let(FieldValue::Bool)
            "links" -> ids?.let(FieldValue::DocumentLinks)
            "unknown" -> text?.let(FieldValue::Unknown)
            else -> null
        }
    return CustomFieldValue(fieldId, value ?: FieldValue.Empty)
}

internal fun encodeCustomFields(values: List<CustomFieldValue>): String =
    json.encodeToString(fieldValues, values.map { it.toStored() })

internal fun decodeCustomFields(encoded: String): List<CustomFieldValue> =
    json.decodeFromString(fieldValues, encoded).map { it.toModel() }

internal fun encodeIds(ids: List<Long>): String = json.encodeToString(longList, ids)

internal fun decodeIds(encoded: String): List<Long> = json.decodeFromString(longList, encoded)

internal fun Document.toEntity(accountId: String) =
    DocumentEntity(
        accountId = accountId,
        id = id,
        title = title,
        content = content,
        created = created.toString(),
        added = added.toEpochMilli(),
        modified = modified.toEpochMilli(),
        correspondentId = correspondentId,
        documentTypeId = documentTypeId,
        storagePathId = storagePathId,
        tagIdsJson = json.encodeToString(longList, tagIds),
        archiveSerialNumber = archiveSerialNumber,
        originalFileName = originalFileName,
        archivedFileName = archivedFileName,
        mimeType = mimeType,
        pageCount = pageCount,
        customFieldsJson = encodeCustomFields(customFields),
        notesJson =
            notes?.let { list ->
                json.encodeToString(
                    com.naoya0117.paperless.core.database.notes,
                    list.map { note ->
                        StoredNote(
                            note.id,
                            note.text,
                            note.created.toEpochMilli(),
                            note.user?.let { StoredNoteAuthor(it.id, it.username, it.firstName, it.lastName) },
                        )
                    },
                )
            },
        owner = owner,
        userCanChange = userCanChange,
        deletedAt = deletedAt?.toEpochMilli(),
    )

internal fun DocumentEntity.toModel(searchHit: SearchHit? = null) =
    Document(
        id = id,
        title = title,
        content = content,
        created = LocalDate.parse(created),
        added = Instant.ofEpochMilli(added),
        modified = Instant.ofEpochMilli(modified),
        correspondentId = correspondentId,
        documentTypeId = documentTypeId,
        storagePathId = storagePathId,
        tagIds = json.decodeFromString(longList, tagIdsJson),
        archiveSerialNumber = archiveSerialNumber,
        originalFileName = originalFileName,
        archivedFileName = archivedFileName,
        mimeType = mimeType,
        pageCount = pageCount,
        customFields = decodeCustomFields(customFieldsJson),
        notes =
            notesJson?.let { encoded ->
                json.decodeFromString(notes, encoded).map { note ->
                    Note(
                        note.id,
                        note.text,
                        Instant.ofEpochMilli(note.created),
                        note.user?.let { NoteAuthor(it.id, it.username, it.firstName, it.lastName) },
                    )
                }
            },
        owner = owner,
        userCanChange = userCanChange,
        deletedAt = deletedAt?.let(Instant::ofEpochMilli),
        searchHit = searchHit,
    )

internal fun DocumentListRow.toModel(): Document =
    document.toModel(
        if (searchScore != null && searchRank != null) SearchHit(searchScore, searchRank, searchHighlights) else null,
    )

internal fun Document.toListItem(
    accountId: String,
    listKey: String,
    position: Int,
) = DocumentListItemEntity(accountId, listKey, position, id, searchHit?.score, searchHit?.rank, searchHit?.highlights)
