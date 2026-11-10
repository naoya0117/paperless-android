package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.ApiVersion
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.network.internal.http.AdditionalTrustManager
import java.security.KeyStore
import java.security.cert.X509Certificate
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
 * @property additionalTrustedCertificates trusted in addition to [trustManager] (e.g. a private CA or a
 *   self-signed server certificate) for every connection made with this config. The host name is still
 *   verified.
 */
public class TlsConfig(
    public val keyManagers: List<KeyManager> = emptyList(),
    public val trustManager: X509TrustManager? = null,
    public val additionalTrustedCertificates: List<X509Certificate> = emptyList(),
) {
    /**
     * Built once and shared by every client using this config: OkHttp only reuses pooled connections
     * for the same socket factory instance.
     */
    internal val resolvedTrustManager: X509TrustManager by lazy {
        val base = trustManager ?: trustManagerFor(null)
        if (additionalTrustedCertificates.isEmpty()) {
            base
        } else {
            AdditionalTrustManager(base, trustManagerFor(keyStoreOf(additionalTrustedCertificates)))
        }
    }

    internal val socketFactory: SSLSocketFactory by lazy {
        SSLContext
            .getInstance("TLS")
            .apply { init(keyManagers.toTypedArray().ifEmpty { null }, arrayOf(resolvedTrustManager), null) }
            .socketFactory
    }

    private companion object {
        /** `null` gives the platform default trust anchors. */
        fun trustManagerFor(keyStore: KeyStore?): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(keyStore)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        }

        fun keyStoreOf(certificates: List<X509Certificate>): KeyStore =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certificates.forEachIndexed { index, certificate -> setCertificateEntry("trusted-$index", certificate) }
            }
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
