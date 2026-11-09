package com.naoya0117.paperless.core.network.internal.api

import com.naoya0117.paperless.core.network.AuthApi
import com.naoya0117.paperless.core.network.PaperlessClientConfig
import com.naoya0117.paperless.core.network.ServerApi
import com.naoya0117.paperless.core.network.internal.http.OkHttpClientFactory
import com.naoya0117.paperless.core.network.internal.http.PaperlessCallAdapterFactory
import com.naoya0117.paperless.core.network.internal.http.PaperlessJson
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
}
