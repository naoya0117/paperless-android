package com.naoya0117.paperless.core.network.internal.service

import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.POST

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
}
