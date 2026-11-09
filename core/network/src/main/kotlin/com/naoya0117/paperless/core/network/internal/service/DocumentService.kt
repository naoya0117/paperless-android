package com.naoya0117.paperless.core.network.internal.service

import com.naoya0117.paperless.core.network.internal.dto.DocumentDto
import com.naoya0117.paperless.core.network.internal.dto.PageDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.QueryMap

internal interface DocumentService {
    @GET("api/documents/")
    suspend fun list(
        @QueryMap query: Map<String, String>,
    ): PageDto<DocumentDto>

    @GET("api/documents/{id}/")
    suspend fun get(
        @Path("id") id: Long,
    ): DocumentDto

    @PATCH("api/documents/{id}/")
    suspend fun update(
        @Path("id") id: Long,
        @Body body: JsonObject,
    ): DocumentDto

    @DELETE("api/documents/{id}/")
    suspend fun delete(
        @Path("id") id: Long,
    )
}
