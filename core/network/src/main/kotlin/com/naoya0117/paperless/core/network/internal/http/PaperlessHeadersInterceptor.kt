package com.naoya0117.paperless.core.network.internal.http

import com.naoya0117.paperless.core.network.PaperlessClientConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds custom headers, the API version and the auth token to requests for the configured server only,
 * so credentials never leak to other hosts. Must be installed as a network interceptor so that it also
 * sees redirected requests. Later headers win, so custom headers cannot override them.
 */
internal class PaperlessHeadersInterceptor(
    private val config: PaperlessClientConfig,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url !in config.serverUrl) return chain.proceed(request)

        val builder = request.newBuilder()
        config.customHeaders.forEach { builder.header(it.name, it.value) }
        builder.header("Accept", "application/json; version=${config.apiVersion.value}")
        config.token?.let { builder.header("Authorization", "Token ${it.value}") }
        return chain.proceed(builder.build())
    }
}
