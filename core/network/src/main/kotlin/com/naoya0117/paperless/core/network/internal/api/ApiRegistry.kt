package com.naoya0117.paperless.core.network.internal.api

import com.naoya0117.paperless.core.model.Correspondent
import com.naoya0117.paperless.core.model.CorrespondentDraft
import com.naoya0117.paperless.core.model.CustomField
import com.naoya0117.paperless.core.model.CustomFieldDraft
import com.naoya0117.paperless.core.model.DocumentType
import com.naoya0117.paperless.core.model.DocumentTypeDraft
import com.naoya0117.paperless.core.model.SavedView
import com.naoya0117.paperless.core.model.SavedViewDraft
import com.naoya0117.paperless.core.model.StoragePath
import com.naoya0117.paperless.core.model.StoragePathDraft
import com.naoya0117.paperless.core.model.Tag
import com.naoya0117.paperless.core.model.TagDraft
import com.naoya0117.paperless.core.network.AuthApi
import com.naoya0117.paperless.core.network.DocumentsApi
import com.naoya0117.paperless.core.network.LabelApi
import com.naoya0117.paperless.core.network.ListQuery
import com.naoya0117.paperless.core.network.PaperlessClientConfig
import com.naoya0117.paperless.core.network.ServerApi
import com.naoya0117.paperless.core.network.TasksApi
import com.naoya0117.paperless.core.network.internal.dto.CorrespondentDto
import com.naoya0117.paperless.core.network.internal.dto.CustomFieldDto
import com.naoya0117.paperless.core.network.internal.dto.DocumentTypeDto
import com.naoya0117.paperless.core.network.internal.dto.PageDto
import com.naoya0117.paperless.core.network.internal.dto.SavedViewDto
import com.naoya0117.paperless.core.network.internal.dto.StoragePathDto
import com.naoya0117.paperless.core.network.internal.dto.TagDto
import com.naoya0117.paperless.core.network.internal.dto.toCreateBody
import com.naoya0117.paperless.core.network.internal.dto.toModel
import com.naoya0117.paperless.core.network.internal.dto.toPatchBody
import com.naoya0117.paperless.core.network.internal.http.OkHttpClientFactory
import com.naoya0117.paperless.core.network.internal.http.PaperlessCallAdapterFactory
import com.naoya0117.paperless.core.network.internal.http.PaperlessJson
import com.naoya0117.paperless.core.network.internal.service.CorrespondentService
import com.naoya0117.paperless.core.network.internal.service.CustomFieldService
import com.naoya0117.paperless.core.network.internal.service.DocumentTypeService
import com.naoya0117.paperless.core.network.internal.service.SavedViewService
import com.naoya0117.paperless.core.network.internal.service.StoragePathService
import com.naoya0117.paperless.core.network.internal.service.TagService
import kotlinx.serialization.json.JsonObject
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

    val documents: DocumentsApi by lazy { DocumentsApiImpl(retrofit.create()) }
    val tasks: TasksApi by lazy { TasksApiImpl(retrofit.create()) }

    val tags: LabelApi<Tag, TagDraft> by lazy {
        val s = retrofit.create<TagService>()
        crud(
            s::list,
            s::get,
            s::create,
            s::update,
            s::delete,
            TagDto::toModel,
            Tag::id,
            TagDraft::toCreateBody,
            TagDraft::toPatchBody,
        )
    }
    val correspondents: LabelApi<Correspondent, CorrespondentDraft> by lazy {
        val s = retrofit.create<CorrespondentService>()
        crud(
            s::list,
            s::get,
            s::create,
            s::update,
            s::delete,
            CorrespondentDto::toModel,
            Correspondent::id,
            CorrespondentDraft::toCreateBody,
            CorrespondentDraft::toPatchBody,
            // Only then does the list include each correspondent's newest document date.
            extraListParams = mapOf("last_correspondence" to "true"),
        )
    }
    val documentTypes: LabelApi<DocumentType, DocumentTypeDraft> by lazy {
        val s = retrofit.create<DocumentTypeService>()
        crud(
            s::list,
            s::get,
            s::create,
            s::update,
            s::delete,
            DocumentTypeDto::toModel,
            DocumentType::id,
            DocumentTypeDraft::toCreateBody,
            DocumentTypeDraft::toPatchBody,
        )
    }
    val storagePaths: LabelApi<StoragePath, StoragePathDraft> by lazy {
        val s = retrofit.create<StoragePathService>()
        crud(
            s::list,
            s::get,
            s::create,
            s::update,
            s::delete,
            StoragePathDto::toModel,
            StoragePath::id,
            StoragePathDraft::toCreateBody,
            StoragePathDraft::toPatchBody,
        )
    }
    val savedViews: LabelApi<SavedView, SavedViewDraft> by lazy {
        val s = retrofit.create<SavedViewService>()
        crud(
            s::list,
            s::get,
            s::create,
            s::update,
            s::delete,
            SavedViewDto::toModel,
            SavedView::id,
            SavedViewDraft::toCreateBody,
            SavedViewDraft::toPatchBody,
            // The saved views endpoint has no filters and no document counts.
            supportsNameFilter = false,
            supportedOrderings = setOf(ListQuery.Ordering.Field.NAME),
        )
    }
    val customFields: LabelApi<CustomField, CustomFieldDraft> by lazy {
        val s = retrofit.create<CustomFieldService>()
        crud(
            s::list,
            s::get,
            s::create,
            s::update,
            s::delete,
            CustomFieldDto::toModel,
            CustomField::id,
            CustomFieldDraft::toCreateBody,
            CustomFieldDraft::toPatchBody,
        )
    }

    /** Named arguments keep the many function references of a [CrudApiImpl] readable at the call site. */
    @Suppress("LongParameterList")
    private fun <Dto, T, D> crud(
        list: suspend (Map<String, String>) -> PageDto<Dto>,
        get: suspend (Long) -> Dto,
        create: suspend (JsonObject) -> Dto,
        update: suspend (Long, JsonObject) -> Dto,
        delete: suspend (Long) -> Unit,
        toModel: (Dto) -> T,
        idOf: (T) -> Long,
        createBody: (D) -> JsonObject,
        patchBody: (D, T) -> JsonObject,
        extraListParams: Map<String, String> = emptyMap(),
        supportsNameFilter: Boolean = true,
        supportedOrderings: Set<ListQuery.Ordering.Field> =
            ListQuery.Ordering.Field.entries
                .toSet(),
    ): LabelApi<T, D> =
        CrudApiImpl(
            list,
            get,
            create,
            update,
            delete,
            toModel,
            idOf,
            createBody,
            { current, draft -> patchBody(draft, current) },
            extraListParams,
            supportsNameFilter,
            supportedOrderings,
        )
}
