package com.naoya0117.paperless.core.network.internal.api

import com.naoya0117.paperless.core.model.Page
import com.naoya0117.paperless.core.model.PaperlessTask
import com.naoya0117.paperless.core.model.TaskQuery
import com.naoya0117.paperless.core.network.PaperlessException
import com.naoya0117.paperless.core.network.TaskWaitResult
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
    ): TaskWaitResult {
        require(pollInterval.isPositive()) { "pollInterval must be positive" }
        var last: PaperlessTask? = null
        val finished = withTimeoutOrNull(timeout) { pollUntilFinished(taskId, pollInterval) { last = it } }
        return finished?.let(TaskWaitResult::Finished) ?: TaskWaitResult.TimedOut(last)
    }

    /** Polls until the task is finished; transient failures only delay the next poll. */
    private suspend fun pollUntilFinished(
        taskId: String,
        pollInterval: Duration,
        onSeen: (PaperlessTask) -> Unit,
    ): PaperlessTask {
        while (true) {
            var wait = pollInterval
            try {
                val task = get(taskId)
                if (task != null) {
                    onSeen(task)
                    if (task.status.isFinished) return task
                }
            } catch (e: PaperlessException.Http.RateLimited) {
                wait = maxOf(pollInterval, e.retryAfter ?: pollInterval)
            } catch (_: PaperlessException.Network) {
                // The server keeps working on the task meanwhile.
            } catch (_: PaperlessException.Http.ServerError) {
            }
            delay(wait)
        }
    }

    override suspend fun acknowledge(ids: Collection<Long>): Int =
        if (ids.isEmpty()) 0 else service.acknowledge(AcknowledgeRequestDto(ids.distinct())).result
}
