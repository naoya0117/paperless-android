package com.naoya0117.paperless.core.network.internal.service

import com.naoya0117.paperless.core.network.internal.dto.DocumentDto
import com.naoya0117.paperless.core.network.internal.dto.PageDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.QueryMap

internal interface TrashService {
    @GET("api/trash/")
    suspend fun list(
        @QueryMap query: Map<String, String>,
    ): PageDto<DocumentDto>

    /** Responds `{"result": "OK", "doc_ids": [...]}`. */
    @POST("api/trash/")
    suspend fun action(
        @Body body: JsonObject,
    ): JsonObject
}
