package com.naoya0117.paperless.core.network.internal.http

import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Trusts a server if either [base] or [additional] does. [base] is asked first with the socket / engine
 * aware checks where it supports them, so that Android's network security config keeps applying.
 * Client certificates are not checked by an app, so those calls go to [base] only.
 *
 * Validation is never skipped: a chain must pass one of the two platform trust managers (Android Lint
 * flags any custom trust manager).
 */
@Suppress("CustomX509TrustManager")
internal class AdditionalTrustManager(
    private val base: X509TrustManager,
    private val additional: X509TrustManager,
) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = checkEither({ base.checkServerTrusted(chain, authType) }) { additional.checkServerTrusted(chain, authType) }

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket?,
    ) = checkEither({
        if (base is X509ExtendedTrustManager) {
            base.checkServerTrusted(chain, authType, socket)
        } else {
            base.checkServerTrusted(chain, authType)
        }
    }) { additional.checkServerTrusted(chain, authType) }

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine?,
    ) = checkEither({
        if (base is X509ExtendedTrustManager) {
            base.checkServerTrusted(chain, authType, engine)
        } else {
            base.checkServerTrusted(chain, authType)
        }
    }) { additional.checkServerTrusted(chain, authType) }

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) = base.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket?,
    ) = base.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine?,
    ) = base.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers + additional.acceptedIssuers

    private inline fun checkEither(
        baseCheck: () -> Unit,
        additionalCheck: () -> Unit,
    ) {
        try {
            baseCheck()
        } catch (baseFailure: CertificateException) {
            try {
                additionalCheck()
            } catch (additionalFailure: CertificateException) {
                baseFailure.addSuppressed(additionalFailure)
                throw baseFailure
            }
        }
    }
}
