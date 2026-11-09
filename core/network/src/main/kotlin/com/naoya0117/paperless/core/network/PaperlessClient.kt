package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.ServerInfo
import com.naoya0117.paperless.core.network.internal.AuthApiImpl
import com.naoya0117.paperless.core.network.internal.PaperlessHeadersInterceptor
import com.naoya0117.paperless.core.network.internal.PaperlessJson
import com.naoya0117.paperless.core.network.internal.ServerApiImpl
import com.naoya0117.paperless.core.network.internal.service.AuthService
import com.naoya0117.paperless.core.network.internal.service.UiSettingsService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.time.toJavaDuration

/**
 * Entry point to the Paperless-ngx REST API for one account. Create one per account; clients derived
 * via [withConfig] share the connection pool of the base [OkHttpClient].
 */
public class PaperlessClient private constructor(
    public val config: PaperlessClientConfig,
    private val baseClient: OkHttpClient,
) {
    private val retrofit: Retrofit =
        Retrofit
            .Builder()
            .baseUrl(config.serverUrl.httpUrl)
            .client(buildOkHttpClient())
            .addConverterFactory(PaperlessJson.asConverterFactory("application/json".toMediaType()))
            .build()

    public val auth: AuthApi = AuthApiImpl(retrofit.create(AuthService::class.java))
    public val server: ServerApi = ServerApiImpl(retrofit.create(UiSettingsService::class.java))

    public fun withConfig(transform: (PaperlessClientConfig) -> PaperlessClientConfig): PaperlessClient =
        PaperlessClient(transform(config), baseClient)

    /**
     * Logs in with username and password, then validates the token and negotiates the API version.
     * @see AuthApi.obtainToken
     */
    public suspend fun signIn(
        username: String,
        password: String,
        mfaCode: String? = null,
    ): Session = signIn(auth.obtainToken(username, password, mfaCode))

    /** Signs in with an existing token (e.g. generated in the Paperless web UI). */
    public suspend fun signIn(token: AuthToken): Session {
        val authenticated = withConfig { it.copy(token = token) }
        val info = authenticated.server.fetchServerInfo()
        val client =
            if (info.apiVersion == authenticated.config.apiVersion) {
                authenticated
            } else {
                authenticated.withConfig { it.copy(apiVersion = info.apiVersion) }
            }
        return Session(client, info)
    }

    private fun buildOkHttpClient(): OkHttpClient =
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
                config.logging?.let { addNetworkInterceptor(loggingInterceptor(it)) }
            }.build()

    private fun OkHttpClient.Builder.applyTls(tls: TlsConfig) {
        val trustManager = tls.trustManager ?: platformTrustManager()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(tls.keyManagers.toTypedArray().ifEmpty { null }, arrayOf(trustManager), null)
        sslSocketFactory(sslContext.socketFactory, trustManager)
    }

    private fun loggingInterceptor(logging: HttpLogging): HttpLoggingInterceptor =
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

    /** An authenticated client together with what was learned about the server. */
    public class Session internal constructor(
        public val client: PaperlessClient,
        public val serverInfo: ServerInfo,
    ) {
        public val token: AuthToken get() = checkNotNull(client.config.token)
    }

    public companion object {
        private val defaultBaseClient: OkHttpClient by lazy { OkHttpClient() }

        /**
         * @param baseClient shared [OkHttpClient] (connection pool, dispatcher); per-account settings are
         *   applied on top of it.
         */
        public fun create(
            config: PaperlessClientConfig,
            baseClient: OkHttpClient = defaultBaseClient,
        ): PaperlessClient = PaperlessClient(config, baseClient)

        private fun platformTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        }
    }
}
