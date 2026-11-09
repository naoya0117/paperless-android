package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.Page
import com.naoya0117.paperless.core.model.PaperlessTask
import com.naoya0117.paperless.core.model.TaskQuery
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

public interface TasksApi {
    /** Newest first. */
    public suspend fun list(query: TaskQuery = TaskQuery()): Page<PaperlessTask>

    /** @return `null` if the server does not know the task (yet). */
    public suspend fun get(taskId: String): PaperlessTask?

    /**
     * Polls [get] until the task is finished or [timeout] elapses. Transient failures while polling
     * (network errors, 5xx, throttling) do not end the wait; other failures (e.g. an invalid token) do.
     * For long waits that must survive the app process, call [get] from a scheduled job instead.
     */
    public suspend fun awaitCompletion(
        taskId: String,
        pollInterval: Duration = 1.seconds,
        timeout: Duration = 10.minutes,
    ): TaskWaitResult

    /** Dismisses tasks (by [PaperlessTask.id]); returns how many were changed. */
    public suspend fun acknowledge(ids: Collection<Long>): Int
}

public sealed interface TaskWaitResult {
    public data class Finished(
        val task: PaperlessTask,
    ) : TaskWaitResult

    /** [lastSeen] is `null` if the server never reported the task (e.g. a wrong ID or a slow queue). */
    public data class TimedOut(
        val lastSeen: PaperlessTask?,
    ) : TaskWaitResult
}
