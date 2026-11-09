package com.naoya0117.paperless.core.network.internal.service

import com.naoya0117.paperless.core.network.internal.dto.AcknowledgeRequestDto
import com.naoya0117.paperless.core.network.internal.dto.AcknowledgeResponseDto
import com.naoya0117.paperless.core.network.internal.dto.PageDto
import com.naoya0117.paperless.core.network.internal.dto.TaskDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap

internal interface TaskService {
    /** API v10 pages tasks (v9 returned a plain list). */
    @GET("api/tasks/")
    suspend fun list(
        @QueryMap query: Map<String, String>,
    ): PageDto<TaskDto>

    @GET("api/tasks/")
    suspend fun byTaskId(
        @Query("task_id") taskId: String,
    ): PageDto<TaskDto>

    @POST("api/tasks/acknowledge/")
    suspend fun acknowledge(
        @Body body: AcknowledgeRequestDto,
    ): AcknowledgeResponseDto
}
