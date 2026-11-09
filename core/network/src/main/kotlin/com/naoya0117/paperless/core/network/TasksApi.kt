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
     * Polls [get] until the task is finished or [timeout] elapses.
     *
     * @return the finished task, or the last state seen (possibly `null`) when the timeout elapsed
     */
    public suspend fun awaitCompletion(
        taskId: String,
        pollInterval: Duration = 1.seconds,
        timeout: Duration = 10.minutes,
    ): PaperlessTask?

    /** Dismisses tasks (by [PaperlessTask.id]); returns how many were changed. */
    public suspend fun acknowledge(ids: Collection<Long>): Int
}
