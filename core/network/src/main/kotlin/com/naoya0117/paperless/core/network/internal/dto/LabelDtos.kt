package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.Correspondent
import com.naoya0117.paperless.core.model.DocumentType
import com.naoya0117.paperless.core.model.Matching
import com.naoya0117.paperless.core.model.MatchingAlgorithm
import com.naoya0117.paperless.core.model.StoragePath
import com.naoya0117.paperless.core.model.Tag
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// Responses of /api/tags/, /api/correspondents/, /api/document_types/ and /api/storage_paths/.
// `document_count` is missing on create/update responses; `user_can_change` is replaced by `permissions`
// when `full_perms=true`, which this client does not request, and is treated as `false` when missing.

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
    @SerialName("user_can_change") val userCanChange: Boolean = false,
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
    @SerialName("user_can_change") val userCanChange: Boolean = false,
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
    @SerialName("user_can_change") val userCanChange: Boolean = false,
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
    @SerialName("user_can_change") val userCanChange: Boolean = false,
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
