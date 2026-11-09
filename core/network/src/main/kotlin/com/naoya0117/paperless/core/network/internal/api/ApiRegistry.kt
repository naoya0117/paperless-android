package com.naoya0117.paperless.core.network.internal.api

import com.naoya0117.paperless.core.model.ApiVersion
import com.naoya0117.paperless.core.network.AuthApi
import com.naoya0117.paperless.core.network.ServerApi
import com.naoya0117.paperless.core.network.ServerUrl
import com.naoya0117.paperless.core.network.internal.http.PaperlessCallAdapterFactory
import com.naoya0117.paperless.core.network.internal.http.PaperlessJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

/**
 * Creates the API implementations for one negotiated [apiVersion], lazily on first use.
 *
 * This is the single place that picks an implementation per API version. When an endpoint changes
 * in a new version, add an implementation for it and select it here, e.g.
 * `val tasks: TasksApi by lazy { if (apiVersion >= ApiVersion(11)) TasksApiV11(...) else TasksApiV10(...) }`.
 * APIs that do not differ keep a single implementation.
 */
internal class ApiRegistry(
    serverUrl: ServerUrl,
    okHttpClient: OkHttpClient,
    @Suppress("unused") private val apiVersion: ApiVersion,
) {
    private val retrofit: Retrofit by lazy {
        Retrofit
            .Builder()
            .baseUrl(serverUrl.httpUrl)
            .client(okHttpClient)
            .addCallAdapterFactory(PaperlessCallAdapterFactory())
            .addConverterFactory(PaperlessJson.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    val auth: AuthApi by lazy { AuthApiImpl(retrofit.create()) }
    val server: ServerApi by lazy { ServerApiImpl(retrofit.create()) }
}
