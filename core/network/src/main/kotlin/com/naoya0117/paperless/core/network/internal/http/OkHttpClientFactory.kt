package com.naoya0117.paperless.core.network.internal.http

import com.naoya0117.paperless.core.network.HttpLogging
import com.naoya0117.paperless.core.network.PaperlessClientConfig
import com.naoya0117.paperless.core.network.TlsConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import kotlin.time.toJavaDuration

/** Applies per-account settings on top of a shared [OkHttpClient] (connection pool, dispatcher). */
internal object OkHttpClientFactory {
    fun create(
        config: PaperlessClientConfig,
        baseClient: OkHttpClient,
    ): OkHttpClient =
        baseClient
            .newBuilder()
            .apply {
                connectTimeout(config.timeouts.connect.toJavaDuration())
                readTimeout(config.timeouts.read.toJavaDuration())
                writeTimeout(config.timeouts.write.toJavaDuration())
                // Never follow redirects: OkHttp re-sends a POST body (the login password) on 307/308 to any
                // host, and turns POST into GET on 301/302. Redirects surface as Http.Redirected instead.
                followRedirects(false)
                followSslRedirects(false)
                // A network interceptor sees the request exactly as sent, so the headers are added last.
                addNetworkInterceptor(PaperlessHeadersInterceptor(config))
                config.tls?.let { applyTls(it) }
                config.logging?.let { addNetworkInterceptor(loggingInterceptor(it, config)) }
            }.build()

    private fun OkHttpClient.Builder.applyTls(tls: TlsConfig) {
        sslSocketFactory(tls.socketFactory, tls.resolvedTrustManager)
    }

    private fun loggingInterceptor(
        logging: HttpLogging,
        config: PaperlessClientConfig,
    ): HttpLoggingInterceptor =
        HttpLoggingInterceptor { logging.logger(it) }.apply {
            level =
                when (logging.level) {
                    HttpLogging.Level.BASIC -> HttpLoggingInterceptor.Level.BASIC
                    HttpLogging.Level.HEADERS -> HttpLoggingInterceptor.Level.HEADERS
                }
            redactHeader("Authorization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
            config.customHeaders.forEach { redactHeader(it.name) }
        }
}
