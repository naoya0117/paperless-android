package com.naoya0117.paperless.core.network.internal.http

import com.naoya0117.paperless.core.network.HttpLogging
import com.naoya0117.paperless.core.network.PaperlessClientConfig
import com.naoya0117.paperless.core.network.TlsConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
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
                // Network interceptors run once per request actually sent, including redirects, so the
                // origin check also keeps credentials away from redirect targets on other hosts.
                addNetworkInterceptor(PaperlessHeadersInterceptor(config))
                config.tls?.let { applyTls(it) }
                config.logging?.let { addNetworkInterceptor(loggingInterceptor(it, config)) }
            }.build()

    private fun OkHttpClient.Builder.applyTls(tls: TlsConfig) {
        val trustManager = tls.trustManager ?: platformTrustManager()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(tls.keyManagers.toTypedArray().ifEmpty { null }, arrayOf(trustManager), null)
        sslSocketFactory(sslContext.socketFactory, trustManager)
    }

    private fun platformTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
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
                    HttpLogging.Level.BODY -> HttpLoggingInterceptor.Level.BODY
                }
            redactHeader("Authorization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
            config.customHeaders.forEach { redactHeader(it.name) }
        }
}
