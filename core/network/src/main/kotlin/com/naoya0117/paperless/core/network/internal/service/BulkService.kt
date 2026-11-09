package com.naoya0117.paperless.core.network.internal.service

import com.naoya0117.paperless.core.network.internal.dto.DocumentDto
import com.naoya0117.paperless.core.network.internal.dto.PageDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.QueryMap

internal interface BulkService {
    /** Responds `{"result": "OK"}`. */
    @POST("api/documents/bulk_edit/")
    suspend fun bulkEdit(
        @Body body: JsonObject,
    ): JsonObject

    /** API v10: deleting moved from bulk_edit to its own endpoint. */
    @POST("api/documents/delete/")
    suspend fun delete(
        @Body body: JsonObject,
    ): JsonObject

    @POST("api/documents/reprocess/")
    suspend fun reprocess(
        @Body body: JsonObject,
    ): JsonObject

    @GET("api/trash/")
    suspend fun trash(
        @QueryMap query: Map<String, String>,
    ): PageDto<DocumentDto>

    /** Responds `{"result": "OK", "doc_ids": [...]}`. */
    @POST("api/trash/")
    suspend fun trashAction(
        @Body body: JsonObject,
    ): JsonObject
}
