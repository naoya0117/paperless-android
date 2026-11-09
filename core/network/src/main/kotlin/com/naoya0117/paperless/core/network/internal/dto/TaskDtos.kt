package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.PaperlessTask
import com.naoya0117.paperless.core.model.TaskQuery
import com.naoya0117.paperless.core.model.TaskStatus
import com.naoya0117.paperless.core.model.TaskType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** API v10 task (TaskSerializerV10). */
@Serializable
internal data class TaskDto(
    val id: Long,
    @SerialName("task_id") val taskId: String,
    @SerialName("task_type") val taskType: String = "",
    val status: String = "",
    @SerialName("date_created") val dateCreated: String? = null,
    @SerialName("date_started") val dateStarted: String? = null,
    @SerialName("date_done") val dateDone: String? = null,
    @SerialName("input_data") val inputData: JsonElement = JsonNull,
    @SerialName("result_data") val resultData: JsonElement = JsonNull,
    @SerialName("related_document_ids") val relatedDocumentIds: List<Long> = emptyList(),
    val acknowledged: Boolean = false,
)

internal fun TaskDto.toModel(): PaperlessTask {
    val input = inputData as? JsonObject
    val result = resultData as? JsonObject
    return PaperlessTask(
        id = id,
        taskId = taskId,
        type = TaskType(taskType),
        status = TaskStatus(status.lowercase()),
        created = dateCreated?.let(::parseInstant),
        started = dateStarted?.let(::parseInstant),
        done = dateDone?.let(::parseInstant),
        fileName = input?.string("filename"),
        documentId = (result?.get("document_id") as? JsonPrimitive)?.longOrNull ?: relatedDocumentIds.firstOrNull(),
        relatedDocumentIds = relatedDocumentIds,
        errorMessage = result?.string("error_message"),
        acknowledged = acknowledged,
    )
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }

internal fun TaskQuery.toQueryMap(): Map<String, String> =
    buildMap {
        putPaging(page, pageSize)
        status?.let { put("status", it.value) }
        type?.let { put("task_type", it.value) }
        acknowledged?.let { put("acknowledged", it.toString()) }
    }

@Serializable
internal data class AcknowledgeRequestDto(
    val tasks: List<Long>,
)

@Serializable
internal data class AcknowledgeResponseDto(
    val result: Int = 0,
)
