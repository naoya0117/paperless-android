package com.naoya0117.paperless.core.network.internal.api

import com.naoya0117.paperless.core.model.Page
import com.naoya0117.paperless.core.model.PaperlessTask
import com.naoya0117.paperless.core.model.TaskQuery
import com.naoya0117.paperless.core.network.TasksApi
import com.naoya0117.paperless.core.network.internal.dto.AcknowledgeRequestDto
import com.naoya0117.paperless.core.network.internal.dto.toModel
import com.naoya0117.paperless.core.network.internal.dto.toQueryMap
import com.naoya0117.paperless.core.network.internal.service.TaskService
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

internal class TasksApiImpl(
    private val service: TaskService,
) : TasksApi {
    override suspend fun list(query: TaskQuery): Page<PaperlessTask> =
        pageOrEmptyBeyondEnd(query.page) { service.list(query.toQueryMap()) }.toModel { parsing { it.toModel() } }

    override suspend fun get(taskId: String): PaperlessTask? =
        parsing {
            service
                .byTaskId(taskId)
                .results
                .firstOrNull { it.taskId == taskId }
                ?.toModel()
        }

    override suspend fun awaitCompletion(
        taskId: String,
        pollInterval: Duration,
        timeout: Duration,
    ): PaperlessTask? {
        require(pollInterval.isPositive()) { "pollInterval must be positive" }
        var last: PaperlessTask? = null
        withTimeoutOrNull(timeout) {
            while (true) {
                last = get(taskId)
                if (last?.status?.isFinished == true) return@withTimeoutOrNull
                delay(pollInterval)
            }
        }
        return last
    }

    override suspend fun acknowledge(ids: Collection<Long>): Int =
        if (ids.isEmpty()) 0 else service.acknowledge(AcknowledgeRequestDto(ids.distinct())).result
}
