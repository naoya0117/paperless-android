package com.naoya0117.paperless.core.network.internal.service

import com.naoya0117.paperless.core.network.internal.dto.CorrespondentDto
import com.naoya0117.paperless.core.network.internal.dto.CustomFieldDto
import com.naoya0117.paperless.core.network.internal.dto.DocumentTypeDto
import com.naoya0117.paperless.core.network.internal.dto.PageDto
import com.naoya0117.paperless.core.network.internal.dto.StoragePathDto
import com.naoya0117.paperless.core.network.internal.dto.TagDto
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.QueryMap

// One interface per endpoint: Retrofit needs concrete types, so the shared CRUD logic lives in CrudApiImpl.

internal interface TagService {
    @GET("api/tags/")
    suspend fun list(
        @QueryMap query: Map<String, String>,
    ): PageDto<TagDto>

    @GET("api/tags/{id}/")
    suspend fun get(
        @Path("id") id: Long,
    ): TagDto

    @POST("api/tags/")
    suspend fun create(
        @Body body: JsonObject,
    ): TagDto

    @PATCH("api/tags/{id}/")
    suspend fun update(
        @Path("id") id: Long,
        @Body body: JsonObject,
    ): TagDto

    @DELETE("api/tags/{id}/")
    suspend fun delete(
        @Path("id") id: Long,
    )
}

internal interface CorrespondentService {
    @GET("api/correspondents/")
    suspend fun list(
        @QueryMap query: Map<String, String>,
    ): PageDto<CorrespondentDto>

    @GET("api/correspondents/{id}/")
    suspend fun get(
        @Path("id") id: Long,
    ): CorrespondentDto

    @POST("api/correspondents/")
    suspend fun create(
        @Body body: JsonObject,
    ): CorrespondentDto

    @PATCH("api/correspondents/{id}/")
    suspend fun update(
        @Path("id") id: Long,
        @Body body: JsonObject,
    ): CorrespondentDto

    @DELETE("api/correspondents/{id}/")
    suspend fun delete(
        @Path("id") id: Long,
    )
}

internal interface DocumentTypeService {
    @GET("api/document_types/")
    suspend fun list(
        @QueryMap query: Map<String, String>,
    ): PageDto<DocumentTypeDto>

    @GET("api/document_types/{id}/")
    suspend fun get(
        @Path("id") id: Long,
    ): DocumentTypeDto

    @POST("api/document_types/")
    suspend fun create(
        @Body body: JsonObject,
    ): DocumentTypeDto

    @PATCH("api/document_types/{id}/")
    suspend fun update(
        @Path("id") id: Long,
        @Body body: JsonObject,
    ): DocumentTypeDto

    @DELETE("api/document_types/{id}/")
    suspend fun delete(
        @Path("id") id: Long,
    )
}

internal interface StoragePathService {
    @GET("api/storage_paths/")
    suspend fun list(
        @QueryMap query: Map<String, String>,
    ): PageDto<StoragePathDto>

    @GET("api/storage_paths/{id}/")
    suspend fun get(
        @Path("id") id: Long,
    ): StoragePathDto

    @POST("api/storage_paths/")
    suspend fun create(
        @Body body: JsonObject,
    ): StoragePathDto

    @PATCH("api/storage_paths/{id}/")
    suspend fun update(
        @Path("id") id: Long,
        @Body body: JsonObject,
    ): StoragePathDto

    @DELETE("api/storage_paths/{id}/")
    suspend fun delete(
        @Path("id") id: Long,
    )
}

internal interface CustomFieldService {
    @GET("api/custom_fields/")
    suspend fun list(
        @QueryMap query: Map<String, String>,
    ): PageDto<CustomFieldDto>

    @GET("api/custom_fields/{id}/")
    suspend fun get(
        @Path("id") id: Long,
    ): CustomFieldDto

    @POST("api/custom_fields/")
    suspend fun create(
        @Body body: JsonObject,
    ): CustomFieldDto

    @PATCH("api/custom_fields/{id}/")
    suspend fun update(
        @Path("id") id: Long,
        @Body body: JsonObject,
    ): CustomFieldDto

    @DELETE("api/custom_fields/{id}/")
    suspend fun delete(
        @Path("id") id: Long,
    )
}
