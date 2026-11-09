package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.BulkEdit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The server rejects duplicate document IDs, so lists are de-duplicated. */
internal fun JsonObjectBuilder.putIds(
    key: String,
    ids: Collection<Long>,
) {
    putJsonArray(key) { ids.distinct().forEach { add(JsonPrimitive(it)) } }
}

/** `{"documents": [...], "method": "...", "parameters": {...}}` with the server's parameter names. */
internal fun BulkEdit.toRequestBody(documentIds: Collection<Long>): JsonObject =
    buildJsonObject {
        putIds("documents", documentIds)
        when (val edit = this@toRequestBody) {
            is BulkEdit.SetCorrespondent -> {
                put("method", "set_correspondent")
                putJsonObject("parameters") { put("correspondent", edit.correspondentId) }
            }
            is BulkEdit.SetDocumentType -> {
                put("method", "set_document_type")
                putJsonObject("parameters") { put("document_type", edit.documentTypeId) }
            }
            is BulkEdit.SetStoragePath -> {
                put("method", "set_storage_path")
                putJsonObject("parameters") { put("storage_path", edit.storagePathId) }
            }
            is BulkEdit.ModifyTags -> {
                put("method", "modify_tags")
                putJsonObject("parameters") {
                    putIds("add_tags", edit.add)
                    putIds("remove_tags", edit.remove)
                }
            }
            is BulkEdit.ModifyCustomFields -> {
                put("method", "modify_custom_fields")
                putJsonObject("parameters") {
                    // A {"<id>": value} object sets values; the server also accepts a plain list of IDs.
                    putJsonObject(
                        "add_custom_fields",
                    ) { edit.add.forEach { put(it.fieldId.toString(), it.value.toJson()) } }
                    putIds("remove_custom_fields", edit.remove)
                }
            }
        }
    }

internal fun documentsBody(documentIds: Collection<Long>): JsonObject =
    buildJsonObject {
        putIds("documents", documentIds)
    }

/** `/api/trash/` actions; without documents, `empty` deletes everything the user can see in the trash. */
internal fun trashBody(
    action: String,
    documentIds: Collection<Long>?,
): JsonObject =
    buildJsonObject {
        put("action", action)
        documentIds?.let { putIds("documents", it) }
    }

/** `{"result": "OK", "doc_ids": [...]}` */
internal fun JsonObject.docIds(): List<Long> =
    (get("doc_ids") as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.longOrNull
    }
