package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.DocumentUpload
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody

/** `post_document` form: the file plus optional fields; tags are repeated, custom fields are a JSON object. */
internal fun DocumentUpload.toMultipart(
    file: RequestBody,
    fileName: String,
): MultipartBody =
    MultipartBody
        .Builder()
        .setType(MultipartBody.FORM)
        .apply {
            title?.let { addFormDataPart("title", it) }
            created?.let { addFormDataPart("created", it.toString()) }
            correspondentId?.let { addFormDataPart("correspondent", it.toString()) }
            documentTypeId?.let { addFormDataPart("document_type", it.toString()) }
            storagePathId?.let { addFormDataPart("storage_path", it.toString()) }
            tagIds.distinct().forEach { addFormDataPart("tags", it.toString()) }
            archiveSerialNumber?.let { addFormDataPart("archive_serial_number", it.toString()) }
            if (customFields.isNotEmpty()) {
                // `{"<field id>": <value>}`; the server also accepts a plain list of field IDs.
                val values = JsonObject(customFields.associate { it.fieldId.toString() to it.value.toJson() })
                addFormDataPart("custom_fields", values.toString())
            }
            addFormDataPart("document", fileName, file)
        }.build()
