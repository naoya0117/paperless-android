package com.naoya0117.paperless.core.model

import java.time.Instant
import java.time.LocalDate

data class Document(
    val id: Long,
    val title: String,
    /** Extracted text; truncated in list results unless requested otherwise. */
    val content: String,
    /** The document's own date (API v9+: a date, not a timestamp). */
    val created: LocalDate,
    val added: Instant,
    val modified: Instant,
    val correspondentId: Long?,
    val documentTypeId: Long?,
    val storagePathId: Long?,
    val tagIds: List<Long>,
    val archiveSerialNumber: Long?,
    val originalFileName: String?,
    /** `null` if no archived (PDF/A) version was produced. */
    val archivedFileName: String?,
    val mimeType: String?,
    val pageCount: Int?,
    val customFields: List<CustomFieldValue>,
    val owner: Long?,
    /** Whether the current user may edit the document (`false` if the server did not say). */
    val userCanChange: Boolean,
    /** Set while the document is in the trash. */
    val deletedAt: Instant?,
    /** Present on results of a full-text search. */
    val searchHit: SearchHit?,
)

/**
 * @property highlights matching snippet; matches are wrapped in `<b>`/`</b>`.
 */
data class SearchHit(
    val score: Double,
    val rank: Int,
    val highlights: String?,
)

/** The value of one custom field on a document. */
data class CustomFieldValue(
    val fieldId: Long,
    val value: FieldValue,
)

/**
 * A custom field value as stored by the server; how to read it depends on the field's
 * [CustomFieldDataType]:
 * - string, longtext, url, date (`yyyy-MM-dd`), select (option ID): [Text]
 * - monetary: [Text] such as `EUR120.00` (currency code optional)
 * - integer: [Integer]; float: [Decimal]; boolean: [Bool]
 * - documentlink: [DocumentLinks]
 */
sealed interface FieldValue {
    data object Empty : FieldValue

    data class Text(
        val value: String,
    ) : FieldValue

    data class Integer(
        val value: Long,
    ) : FieldValue

    data class Decimal(
        val value: Double,
    ) : FieldValue

    data class Bool(
        val value: Boolean,
    ) : FieldValue

    data class DocumentLinks(
        val documentIds: List<Long>,
    ) : FieldValue
}

/**
 * The desired editable state of a document (see [TagDraft] for how create and update use drafts; documents
 * are created by uploading, so this is only used for updates).
 */
data class DocumentDraft(
    val title: String,
    val created: LocalDate,
    val correspondentId: Long?,
    val documentTypeId: Long?,
    val storagePathId: Long?,
    val tagIds: List<Long>,
    val archiveSerialNumber: Long?,
    /** The complete set of fields; fields left out are removed from the document. */
    val customFields: List<CustomFieldValue>,
)

fun Document.toDraft(): DocumentDraft =
    DocumentDraft(
        title = title,
        created = created,
        correspondentId = correspondentId,
        documentTypeId = documentTypeId,
        storagePathId = storagePathId,
        tagIds = tagIds,
        archiveSerialNumber = archiveSerialNumber,
        customFields = customFields,
    )
