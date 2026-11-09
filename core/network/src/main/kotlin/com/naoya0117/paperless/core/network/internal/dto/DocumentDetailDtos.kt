package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.DocumentMetadata
import com.naoya0117.paperless.core.model.DocumentSuggestions
import com.naoya0117.paperless.core.model.FileProperty
import com.naoya0117.paperless.core.model.HistoryActor
import com.naoya0117.paperless.core.model.HistoryEntry
import com.naoya0117.paperless.core.model.Note
import com.naoya0117.paperless.core.model.NoteAuthor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.OffsetDateTime

/** API v8+: `user` is an object. */
@Serializable
internal data class NoteDto(
    val id: Long,
    val note: String = "",
    val created: String,
    val user: NoteUserDto? = null,
)

@Serializable
internal data class NoteUserDto(
    val id: Long,
    val username: String = "",
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
)

internal fun NoteDto.toModel() =
    Note(
        id = id,
        text = note,
        created = OffsetDateTime.parse(created).toInstant(),
        user =
            user?.let {
                NoteAuthor(
                    it.id,
                    it.username,
                    it.firstName?.ifEmpty { null },
                    it.lastName?.ifEmpty { null },
                )
            },
    )

@Serializable
internal data class NoteRequestDto(
    val note: String,
)

@Serializable
internal data class MetadataDto(
    @SerialName("original_filename") val originalFileName: String? = null,
    @SerialName("original_checksum") val originalChecksum: String = "",
    @SerialName("original_size") val originalSize: Long? = null,
    @SerialName("original_mime_type") val originalMimeType: String? = null,
    @SerialName("has_archive_version") val hasArchiveVersion: Boolean = false,
    @SerialName("archive_checksum") val archiveChecksum: String? = null,
    @SerialName("archive_size") val archiveSize: Long? = null,
    @SerialName("original_metadata") val originalMetadata: List<FilePropertyDto>? = null,
    @SerialName("archive_metadata") val archiveMetadata: List<FilePropertyDto>? = null,
)

@Serializable
internal data class FilePropertyDto(
    val namespace: String? = null,
    val prefix: String? = null,
    val key: String = "",
    val value: String = "",
)

internal fun MetadataDto.toModel() =
    DocumentMetadata(
        originalFileName = originalFileName,
        originalChecksum = originalChecksum,
        originalSize = originalSize,
        originalMimeType = originalMimeType,
        hasArchiveVersion = hasArchiveVersion,
        archiveChecksum = archiveChecksum,
        archiveSize = archiveSize,
        originalProperties = originalMetadata.orEmpty().map { it.toModel() },
        archiveProperties = archiveMetadata.orEmpty().map { it.toModel() },
    )

private fun FilePropertyDto.toModel() = FileProperty(namespace, prefix, key, value)

@Serializable
internal data class SuggestionsDto(
    val correspondents: List<Long> = emptyList(),
    val tags: List<Long> = emptyList(),
    @SerialName("document_types") val documentTypes: List<Long> = emptyList(),
    @SerialName("storage_paths") val storagePaths: List<Long> = emptyList(),
    val dates: List<String> = emptyList(),
)

internal fun SuggestionsDto.toModel() =
    DocumentSuggestions(
        correspondentIds = correspondents,
        tagIds = tags,
        documentTypeIds = documentTypes,
        storagePathIds = storagePaths,
        dates = dates.mapNotNull { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() },
    )

@Serializable
internal data class HistoryEntryDto(
    val id: Long,
    val timestamp: String,
    val action: String = "",
    val changes: JsonElement = JsonNull,
    val actor: HistoryActorDto? = null,
)

@Serializable
internal data class HistoryActorDto(
    val id: Long,
    val username: String = "",
)

internal fun HistoryEntryDto.toModel() =
    HistoryEntry(
        id = id,
        timestamp = OffsetDateTime.parse(timestamp).toInstant(),
        action = action,
        changes =
            (changes as? JsonObject)
                ?.mapValues { (_, change) ->
                    (change as? JsonArray)?.map { it.displayString() }
                        ?: listOf(change.displayString())
                }.orEmpty(),
        actor = actor?.let { HistoryActor(it.id, it.username) },
    )

/** The server stores most values as strings already ("None" for null); keep other JSON as text. */
private fun JsonElement.displayString(): String? =
    when (this) {
        is JsonNull -> null
        is JsonPrimitive -> contentOrNull
        else -> toString()
    }
