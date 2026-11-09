package com.naoya0117.paperless.core.network.internal.service

import com.naoya0117.paperless.core.network.internal.dto.DocumentDto
import com.naoya0117.paperless.core.network.internal.dto.HistoryEntryDto
import com.naoya0117.paperless.core.network.internal.dto.MetadataDto
import com.naoya0117.paperless.core.network.internal.dto.NoteDto
import com.naoya0117.paperless.core.network.internal.dto.NoteRequestDto
import com.naoya0117.paperless.core.network.internal.dto.PageDto
import com.naoya0117.paperless.core.network.internal.dto.SuggestionsDto
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Streaming

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

    /** `kind` is `thumb`, `preview` or `download`. */
    @Streaming
    @GET("api/documents/{id}/{kind}/")
    suspend fun file(
        @Path("id") id: Long,
        @Path("kind") kind: String,
        @Query("original") original: Boolean?,
    ): Response<ResponseBody>

    @POST("api/documents/{id}/notes/")
    suspend fun addNote(
        @Path("id") id: Long,
        @Body body: NoteRequestDto,
    ): List<NoteDto>

    @DELETE("api/documents/{id}/notes/")
    suspend fun deleteNote(
        @Path("id") id: Long,
        @Query("id") noteId: Long,
    ): List<NoteDto>

    @GET("api/documents/{id}/metadata/")
    suspend fun metadata(
        @Path("id") id: Long,
    ): MetadataDto

    @GET("api/documents/{id}/suggestions/")
    suspend fun suggestions(
        @Path("id") id: Long,
    ): SuggestionsDto

    @GET("api/documents/{id}/history/")
    suspend fun history(
        @Path("id") id: Long,
    ): List<HistoryEntryDto>

    @GET("api/documents/next_asn/")
    suspend fun nextAsn(): Long
}
