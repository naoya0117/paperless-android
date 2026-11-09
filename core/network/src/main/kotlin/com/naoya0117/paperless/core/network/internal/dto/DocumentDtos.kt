package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.CustomFieldValue
import com.naoya0117.paperless.core.model.Document
import com.naoya0117.paperless.core.model.FieldValue
import com.naoya0117.paperless.core.model.SearchHit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** `/api/documents/`; update responses carry `permissions` instead of `user_can_change`. */
@Serializable
internal data class DocumentDto(
    val id: Long,
    val title: String = "",
    val content: String = "",
    val created: String,
    val added: String,
    val modified: String,
    val correspondent: Long? = null,
    @SerialName("document_type") val documentType: Long? = null,
    @SerialName("storage_path") val storagePath: Long? = null,
    val tags: List<Long> = emptyList(),
    @SerialName("archive_serial_number") val archiveSerialNumber: Long? = null,
    @SerialName("original_file_name") val originalFileName: String? = null,
    @SerialName("archived_file_name") val archivedFileName: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("page_count") val pageCount: Int? = null,
    @SerialName("custom_fields") val customFields: List<CustomFieldValueDto> = emptyList(),
    val owner: Long? = null,
    @SerialName("user_can_change") val userCanChange: Boolean? = null,
    val permissions: JsonObject? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("__search_hit__") val searchHit: SearchHitDto? = null,
)

@Serializable
internal data class CustomFieldValueDto(
    val field: Long,
    val value: JsonElement = JsonNull,
)

@Serializable
internal data class SearchHitDto(
    val score: Double = 0.0,
    val rank: Int = 0,
    val highlights: String? = null,
)

internal fun DocumentDto.toModel(): Document =
    Document(
        id = id,
        title = title,
        content = content,
        // API v9+ sends a date; tolerate a timestamp from older behaviour.
        created = LocalDate.parse(created.take(10)),
        added = parseInstant(added),
        modified = parseInstant(modified),
        correspondentId = correspondent,
        documentTypeId = documentType,
        storagePathId = storagePath,
        tagIds = tags,
        archiveSerialNumber = archiveSerialNumber,
        originalFileName = originalFileName,
        archivedFileName = archivedFileName,
        mimeType = mimeType,
        pageCount = pageCount,
        customFields = customFields.map { CustomFieldValue(it.field, it.value.toFieldValue()) },
        owner = owner,
        // Responses with full permissions (e.g. after an update) come from a user who could change it.
        userCanChange = userCanChange ?: (permissions != null),
        deletedAt = deletedAt?.let(::parseInstant),
        searchHit = searchHit?.let { SearchHit(it.score, it.rank, it.highlights) },
    )

private fun parseInstant(value: String): Instant = OffsetDateTime.parse(value).toInstant()

internal fun JsonElement.toFieldValue(): FieldValue =
    when (this) {
        is JsonNull -> FieldValue.Empty
        is JsonArray -> FieldValue.DocumentLinks(mapNotNull { (it as? JsonPrimitive)?.longOrNull })
        is JsonPrimitive ->
            if (isString) {
                FieldValue.Text(content)
            } else {
                booleanOrNull?.let(FieldValue::Bool)
                    ?: longOrNull?.let(FieldValue::Integer)
                    ?: doubleOrNull?.let(FieldValue::Decimal)
                    ?: FieldValue.Text(content)
            }
        is JsonObject -> FieldValue.Text(toString())
    }

internal fun FieldValue.toJson(): JsonElement =
    when (this) {
        FieldValue.Empty -> JsonNull
        is FieldValue.Text -> JsonPrimitive(value)
        is FieldValue.Integer -> JsonPrimitive(value)
        is FieldValue.Decimal -> JsonPrimitive(value)
        is FieldValue.Bool -> JsonPrimitive(value)
        is FieldValue.DocumentLinks -> JsonArray(documentIds.map(::JsonPrimitive))
    }
