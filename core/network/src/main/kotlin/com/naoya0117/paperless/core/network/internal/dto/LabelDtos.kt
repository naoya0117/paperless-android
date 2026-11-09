package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.Correspondent
import com.naoya0117.paperless.core.model.CorrespondentDraft
import com.naoya0117.paperless.core.model.DocumentType
import com.naoya0117.paperless.core.model.DocumentTypeDraft
import com.naoya0117.paperless.core.model.Matching
import com.naoya0117.paperless.core.model.MatchingAlgorithm
import com.naoya0117.paperless.core.model.StoragePath
import com.naoya0117.paperless.core.model.StoragePathDraft
import com.naoya0117.paperless.core.model.Tag
import com.naoya0117.paperless.core.model.TagDraft
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// Responses of /api/tags/, /api/correspondents/, /api/document_types/ and /api/storage_paths/.
// `document_count` is missing on create/update responses; `user_can_change` is replaced by `permissions`
// when `full_perms=true`, which this client does not request.

@Serializable
internal data class TagDto(
    val id: Long,
    val slug: String = "",
    val name: String,
    val color: String = "#a6cee3",
    @SerialName("text_color") val textColor: String = "#000000",
    val match: String = "",
    @SerialName("matching_algorithm") val matchingAlgorithm: Int = MatchingAlgorithm.NONE.id,
    @SerialName("is_insensitive") val isInsensitive: Boolean = true,
    @SerialName("is_inbox_tag") val isInboxTag: Boolean = false,
    @SerialName("document_count") val documentCount: Int? = null,
    val owner: Long? = null,
    @SerialName("user_can_change") val userCanChange: Boolean = true,
    val parent: Long? = null,
    /** Nested full tag objects; only their IDs are kept. */
    val children: List<TagChildDto> = emptyList(),
)

@Serializable
internal data class TagChildDto(
    val id: Long,
)

@Serializable
internal data class CorrespondentDto(
    val id: Long,
    val slug: String = "",
    val name: String,
    val match: String = "",
    @SerialName("matching_algorithm") val matchingAlgorithm: Int = MatchingAlgorithm.NONE.id,
    @SerialName("is_insensitive") val isInsensitive: Boolean = true,
    @SerialName("document_count") val documentCount: Int? = null,
    val owner: Long? = null,
    @SerialName("user_can_change") val userCanChange: Boolean = true,
    @SerialName("last_correspondence") val lastCorrespondence: String? = null,
)

@Serializable
internal data class DocumentTypeDto(
    val id: Long,
    val slug: String = "",
    val name: String,
    val match: String = "",
    @SerialName("matching_algorithm") val matchingAlgorithm: Int = MatchingAlgorithm.NONE.id,
    @SerialName("is_insensitive") val isInsensitive: Boolean = true,
    @SerialName("document_count") val documentCount: Int? = null,
    val owner: Long? = null,
    @SerialName("user_can_change") val userCanChange: Boolean = true,
)

@Serializable
internal data class StoragePathDto(
    val id: Long,
    val slug: String = "",
    val name: String,
    val path: String = "",
    val match: String = "",
    @SerialName("matching_algorithm") val matchingAlgorithm: Int = MatchingAlgorithm.NONE.id,
    @SerialName("is_insensitive") val isInsensitive: Boolean = true,
    @SerialName("document_count") val documentCount: Int? = null,
    val owner: Long? = null,
    @SerialName("user_can_change") val userCanChange: Boolean = true,
)

private fun matching(
    algorithm: Int,
    pattern: String,
    isInsensitive: Boolean,
) = Matching(MatchingAlgorithm(algorithm), pattern, isInsensitive)

internal fun TagDto.toModel() =
    Tag(
        id = id,
        name = name,
        slug = slug,
        matching = matching(matchingAlgorithm, match, isInsensitive),
        documentCount = documentCount,
        owner = owner,
        userCanChange = userCanChange,
        color = color,
        textColor = textColor,
        isInboxTag = isInboxTag,
        parentId = parent,
        childIds = children.map { it.id },
    )

internal fun CorrespondentDto.toModel() =
    Correspondent(
        id = id,
        name = name,
        slug = slug,
        matching = matching(matchingAlgorithm, match, isInsensitive),
        documentCount = documentCount,
        owner = owner,
        userCanChange = userCanChange,
        lastCorrespondence = lastCorrespondence?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
    )

internal fun DocumentTypeDto.toModel() =
    DocumentType(
        id = id,
        name = name,
        slug = slug,
        matching = matching(matchingAlgorithm, match, isInsensitive),
        documentCount = documentCount,
        owner = owner,
        userCanChange = userCanChange,
    )

internal fun StoragePathDto.toModel() =
    StoragePath(
        id = id,
        name = name,
        slug = slug,
        matching = matching(matchingAlgorithm, match, isInsensitive),
        documentCount = documentCount,
        owner = owner,
        userCanChange = userCanChange,
        path = path,
    )

// Requests. Properties defaulting to null are omitted when null (server default / unchanged);
// properties without a default are always sent (see PaperlessJson).

@Serializable
internal data class TagWriteDto(
    val name: String,
    val color: String? = null,
    val match: String? = null,
    @SerialName("matching_algorithm") val matchingAlgorithm: Int? = null,
    @SerialName("is_insensitive") val isInsensitive: Boolean? = null,
    @SerialName("is_inbox_tag") val isInboxTag: Boolean? = null,
    val parent: Long?,
)

@Serializable
internal data class MatchingLabelWriteDto(
    val name: String,
    val match: String? = null,
    @SerialName("matching_algorithm") val matchingAlgorithm: Int? = null,
    @SerialName("is_insensitive") val isInsensitive: Boolean? = null,
)

@Serializable
internal data class StoragePathWriteDto(
    val name: String,
    val path: String,
    val match: String? = null,
    @SerialName("matching_algorithm") val matchingAlgorithm: Int? = null,
    @SerialName("is_insensitive") val isInsensitive: Boolean? = null,
)

internal fun TagDraft.toDto() =
    TagWriteDto(
        name = name,
        color = color,
        match = matching?.pattern,
        matchingAlgorithm = matching?.algorithm?.id,
        isInsensitive = matching?.isInsensitive,
        isInboxTag = isInboxTag,
        parent = parentId,
    )

internal fun CorrespondentDraft.toDto() =
    MatchingLabelWriteDto(name, matching?.pattern, matching?.algorithm?.id, matching?.isInsensitive)

internal fun DocumentTypeDraft.toDto() =
    MatchingLabelWriteDto(name, matching?.pattern, matching?.algorithm?.id, matching?.isInsensitive)

internal fun StoragePathDraft.toDto() =
    StoragePathWriteDto(name, path, matching?.pattern, matching?.algorithm?.id, matching?.isInsensitive)
