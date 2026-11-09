package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.ApiVersion
import com.naoya0117.paperless.core.model.AuthToken
import java.security.KeyStore
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Connection settings for one server account. Immutable; derive variants with [copy]. */
public data class PaperlessClientConfig(
    val serverUrl: ServerUrl,
    val token: AuthToken? = null,
    val apiVersion: ApiVersion = SupportedApiVersions.MAX,
    val customHeaders: List<HttpHeader> = emptyList(),
    val tls: TlsConfig? = null,
    val timeouts: Timeouts = Timeouts(),
    val logging: HttpLogging? = null,
)

/**
 * Custom TLS material. Leave `null` in [PaperlessClientConfig.tls] to use the platform defaults
 * (on Android: the app's network security config).
 *
 * @property keyManagers client certificate for mutual TLS.
 * @property trustManager trust anchors; `null` uses the platform default trust manager.
 */
public class TlsConfig(
    public val keyManagers: List<KeyManager> = emptyList(),
    public val trustManager: X509TrustManager? = null,
) {
    /**
     * Built once and shared by every client using this config: OkHttp only reuses pooled connections
     * for the same socket factory instance.
     */
    internal val resolvedTrustManager: X509TrustManager by lazy { trustManager ?: platformTrustManager() }

    internal val socketFactory: SSLSocketFactory by lazy {
        SSLContext
            .getInstance("TLS")
            .apply { init(keyManagers.toTypedArray().ifEmpty { null }, arrayOf(resolvedTrustManager), null) }
            .socketFactory
    }

    private fun platformTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }
}

public data class Timeouts(
    val connect: Duration = 15.seconds,
    val read: Duration = 30.seconds,
    val write: Duration = 30.seconds,
)

/**
 * HTTP logging for debugging. Credentials and custom header values are always redacted. Bodies are never
 * logged because they contain passwords (`/api/token/`) and tokens.
 */
public class HttpLogging(
    public val level: Level,
    public val logger: (String) -> Unit,
) {
    public enum class Level { BASIC, HEADERS }
}
