package com.naoya0117.paperless.core.network.internal.service

import com.naoya0117.paperless.core.network.internal.dto.PageDto
import com.naoya0117.paperless.core.network.internal.dto.SavedViewDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.QueryMap

internal interface SavedViewService {
    @GET("api/saved_views/")
    suspend fun list(
        @QueryMap query: Map<String, String>,
    ): PageDto<SavedViewDto>

    @GET("api/saved_views/{id}/")
    suspend fun get(
        @Path("id") id: Long,
    ): SavedViewDto

    @POST("api/saved_views/")
    suspend fun create(
        @Body body: JsonObject,
    ): SavedViewDto

    @PATCH("api/saved_views/{id}/")
    suspend fun update(
        @Path("id") id: Long,
        @Body body: JsonObject,
    ): SavedViewDto

    @DELETE("api/saved_views/{id}/")
    suspend fun delete(
        @Path("id") id: Long,
    )
}
