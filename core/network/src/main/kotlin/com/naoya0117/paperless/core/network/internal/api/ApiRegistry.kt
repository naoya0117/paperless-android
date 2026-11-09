package com.naoya0117.paperless.core.network.internal.api

import com.naoya0117.paperless.core.model.Correspondent
import com.naoya0117.paperless.core.model.CorrespondentDraft
import com.naoya0117.paperless.core.model.CustomField
import com.naoya0117.paperless.core.model.CustomFieldDraft
import com.naoya0117.paperless.core.model.DocumentType
import com.naoya0117.paperless.core.model.DocumentTypeDraft
import com.naoya0117.paperless.core.model.StoragePath
import com.naoya0117.paperless.core.model.StoragePathDraft
import com.naoya0117.paperless.core.model.Tag
import com.naoya0117.paperless.core.model.TagDraft
import com.naoya0117.paperless.core.network.AuthApi
import com.naoya0117.paperless.core.network.LabelApi
import com.naoya0117.paperless.core.network.PaperlessClientConfig
import com.naoya0117.paperless.core.network.ServerApi
import com.naoya0117.paperless.core.network.internal.dto.CorrespondentDto
import com.naoya0117.paperless.core.network.internal.dto.CustomFieldDto
import com.naoya0117.paperless.core.network.internal.dto.DocumentTypeDto
import com.naoya0117.paperless.core.network.internal.dto.StoragePathDto
import com.naoya0117.paperless.core.network.internal.dto.TagDto
import com.naoya0117.paperless.core.network.internal.dto.toDto
import com.naoya0117.paperless.core.network.internal.dto.toModel
import com.naoya0117.paperless.core.network.internal.http.OkHttpClientFactory
import com.naoya0117.paperless.core.network.internal.http.PaperlessCallAdapterFactory
import com.naoya0117.paperless.core.network.internal.http.PaperlessJson
import com.naoya0117.paperless.core.network.internal.service.CorrespondentService
import com.naoya0117.paperless.core.network.internal.service.CustomFieldService
import com.naoya0117.paperless.core.network.internal.service.DocumentTypeService
import com.naoya0117.paperless.core.network.internal.service.StoragePathService
import com.naoya0117.paperless.core.network.internal.service.TagService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

/**
 * Creates the HTTP client and API implementations for one [config], lazily on first use.
 *
 * This is the single place that picks an implementation per API version. When an endpoint changes
 * in a new version, add an implementation for it and select it here by `config.apiVersion`, e.g.
 * `val tasks: TasksApi by lazy { if (config.apiVersion >= ApiVersion(11)) TasksApiV11(...) else TasksApiV10(...) }`.
 * APIs that do not differ keep a single implementation.
 */
internal class ApiRegistry(
    private val config: PaperlessClientConfig,
    private val baseClient: OkHttpClient,
) {
    private val retrofit: Retrofit by lazy {
        Retrofit
            .Builder()
            .baseUrl(config.serverUrl.httpUrl)
            .client(OkHttpClientFactory.create(config, baseClient))
            .addCallAdapterFactory(PaperlessCallAdapterFactory())
            .addConverterFactory(PaperlessJson.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    val auth: AuthApi by lazy { AuthApiImpl(retrofit.create()) }
    val server: ServerApi by lazy { ServerApiImpl(retrofit.create(), config.apiVersion) }

    val tags: LabelApi<Tag, TagDraft> by lazy {
        val s = retrofit.create<TagService>()
        CrudApiImpl(s::list, s::get, s::create, s::update, s::delete, TagDto::toModel, TagDraft::toDto)
    }
    val correspondents: LabelApi<Correspondent, CorrespondentDraft> by lazy {
        val s = retrofit.create<CorrespondentService>()
        CrudApiImpl(
            s::list,
            s::get,
            s::create,
            s::update,
            s::delete,
            CorrespondentDto::toModel,
            CorrespondentDraft::toDto,
        )
    }
    val documentTypes: LabelApi<DocumentType, DocumentTypeDraft> by lazy {
        val s = retrofit.create<DocumentTypeService>()
        CrudApiImpl(
            s::list,
            s::get,
            s::create,
            s::update,
            s::delete,
            DocumentTypeDto::toModel,
            DocumentTypeDraft::toDto,
        )
    }
    val storagePaths: LabelApi<StoragePath, StoragePathDraft> by lazy {
        val s = retrofit.create<StoragePathService>()
        CrudApiImpl(s::list, s::get, s::create, s::update, s::delete, StoragePathDto::toModel, StoragePathDraft::toDto)
    }
    val customFields: LabelApi<CustomField, CustomFieldDraft> by lazy {
        val s = retrofit.create<CustomFieldService>()
        CrudApiImpl(
            s::list,
            s::get,
            s::create,
            s::update,
            s::delete,
            CustomFieldDto::toModel,
            toCreateDto = { it.toDto(includeDataType = true) },
            toUpdateDto = { it.toDto(includeDataType = false) },
        )
    }
}
