package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.Correspondent
import com.naoya0117.paperless.core.model.CorrespondentDraft
import com.naoya0117.paperless.core.model.CustomField
import com.naoya0117.paperless.core.model.CustomFieldDataType
import com.naoya0117.paperless.core.model.CustomFieldDraft
import com.naoya0117.paperless.core.model.DocumentType
import com.naoya0117.paperless.core.model.DocumentTypeDraft
import com.naoya0117.paperless.core.model.Matching
import com.naoya0117.paperless.core.model.StoragePath
import com.naoya0117.paperless.core.model.StoragePathDraft
import com.naoya0117.paperless.core.model.Tag
import com.naoya0117.paperless.core.model.TagDraft
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// Request bodies for the label endpoints. Create sends the whole draft; update (PATCH) sends only what
// differs from the current object: the server re-validates every field it receives (e.g. name uniqueness
// against the *current user*, which fails when editing another owner's label), and an omitted field
// keeps its value. `null` is sent explicitly where it means "clear" (e.g. a tag's parent).

private fun JsonObjectBuilder.putMatching(matching: Matching) {
    put("match", matching.pattern)
    put("matching_algorithm", matching.algorithm.id)
    put("is_insensitive", matching.isInsensitive)
}

private fun JsonObjectBuilder.putName(
    name: String,
    current: String?,
) {
    if (name != current) put("name", name)
}

/** Pattern and algorithm go together: the server only validates a regex when it receives both. */
private fun JsonObjectBuilder.putMatchingChanges(
    matching: Matching,
    current: Matching,
) {
    if (matching.pattern != current.pattern || matching.algorithm != current.algorithm) {
        put("match", matching.pattern)
        put("matching_algorithm", matching.algorithm.id)
    }
    if (matching.isInsensitive != current.isInsensitive) put("is_insensitive", matching.isInsensitive)
}

internal fun TagDraft.toCreateBody(): JsonObject =
    buildJsonObject {
        put("name", name)
        put("color", color)
        putMatching(matching)
        put("is_inbox_tag", isInboxTag)
        put("parent", parentId)
    }

internal fun TagDraft.toPatchBody(current: Tag): JsonObject =
    buildJsonObject {
        putName(name, current.name)
        if (color != current.color) put("color", color)
        putMatchingChanges(matching, current.matching)
        if (isInboxTag != current.isInboxTag) put("is_inbox_tag", isInboxTag)
        if (parentId != current.parentId) put("parent", parentId)
    }

internal fun CorrespondentDraft.toCreateBody(): JsonObject =
    buildJsonObject {
        put("name", name)
        putMatching(matching)
    }

internal fun CorrespondentDraft.toPatchBody(current: Correspondent): JsonObject =
    buildJsonObject {
        putName(name, current.name)
        putMatchingChanges(matching, current.matching)
    }

internal fun DocumentTypeDraft.toCreateBody(): JsonObject =
    buildJsonObject {
        put("name", name)
        putMatching(matching)
    }

internal fun DocumentTypeDraft.toPatchBody(current: DocumentType): JsonObject =
    buildJsonObject {
        putName(name, current.name)
        putMatchingChanges(matching, current.matching)
    }

internal fun StoragePathDraft.toCreateBody(): JsonObject =
    buildJsonObject {
        put("name", name)
        put("path", path)
        putMatching(matching)
    }

internal fun StoragePathDraft.toPatchBody(current: StoragePath): JsonObject =
    buildJsonObject {
        putName(name, current.name)
        if (path != current.path) put("path", path)
        putMatchingChanges(matching, current.matching)
    }

internal fun CustomFieldDraft.toCreateBody(): JsonObject =
    buildJsonObject {
        put("name", name)
        put("data_type", dataType.value)
        extraData()?.let { put("extra_data", it) }
    }

/**
 * The server replaces `extra_data` as a whole, and requires the options on every update of a select field
 * (even a rename). So once anything changes, `extra_data` is sent completely for select fields, and for
 * monetary fields when the currency changed.
 */
internal fun CustomFieldDraft.toPatchBody(current: CustomField): JsonObject {
    require(dataType == current.dataType) { "The data type cannot be changed (${current.dataType} → $dataType)" }
    val nameChanged = name != current.name
    val extraDataChanged = selectOptions != current.selectOptions || defaultCurrency != current.defaultCurrency
    if (!nameChanged && !extraDataChanged) return JsonObject(emptyMap())
    return buildJsonObject {
        if (nameChanged) put("name", name)
        val extraData = extraData()
        if (extraData != null &&
            (extraDataChanged || dataType == CustomFieldDataType.SELECT)
        ) {
            put("extra_data", extraData)
        }
    }
}

private fun CustomFieldDraft.extraData(): JsonObject? =
    when (dataType) {
        CustomFieldDataType.SELECT ->
            buildJsonObject {
                putJsonArray("select_options") {
                    selectOptions.forEach { option ->
                        addJsonObject {
                            option.id?.let { put("id", it) }
                            put("label", option.label)
                        }
                    }
                }
            }
        CustomFieldDataType.MONETARY -> buildJsonObject { put("default_currency", defaultCurrency) }
        else -> null
    }
