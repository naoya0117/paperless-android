package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.AuthToken
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Test

class AdditionalTrustTest {
    private val baseCa = HeldCertificate.Builder().certificateAuthority(0).build()
    private val privateCa = HeldCertificate.Builder().certificateAuthority(0).build()

    /** Stands in for the platform trust store, which a JVM test cannot add a CA to. */
    private val baseTrust = HandshakeCertificates.Builder().addTrustedCertificate(baseCa.certificate).build()

    private fun serverCertificate(issuer: HeldCertificate?) =
        HeldCertificate
            .Builder()
            .addSubjectAlternativeName("localhost")
            .apply { issuer?.let { signedBy(it) } }
            .build()

    private suspend fun fetchUsername(
        serverCertificate: HeldCertificate,
        issuer: HeldCertificate?,
        tls: TlsConfig,
    ): String =
        MockWebServer().use { server ->
            server.useHttps(
                HandshakeCertificates
                    .Builder()
                    .apply {
                        if (issuer == null) {
                            heldCertificate(serverCertificate)
                        } else {
                            heldCertificate(serverCertificate, issuer.certificate)
                        }
                    }.build()
                    .sslSocketFactory(),
            )
            server.start()
            server.enqueue(uiSettingsResponse())
            server
                .client { PaperlessClientConfig(it, AuthToken("abc"), tls = tls) }
                .server
                .fetchServerInfo()
                .user.username
        }

    private val tls =
        TlsConfig(trustManager = baseTrust.trustManager, additionalTrustedCertificates = listOf(privateCa.certificate))

    @Test
    fun `an additional ca is trusted`() =
        runTest {
            assertEquals("alice", fetchUsername(serverCertificate(privateCa), privateCa, tls))
        }

    @Test
    fun `the base trust anchors stay trusted`() =
        runTest {
            assertEquals("alice", fetchUsername(serverCertificate(baseCa), baseCa, tls))
        }

    @Test
    fun `a self-signed server certificate can be trusted directly`() =
        runTest {
            val selfSigned = serverCertificate(null)

            val username =
                fetchUsername(
                    selfSigned,
                    null,
                    TlsConfig(
                        trustManager = baseTrust.trustManager,
                        additionalTrustedCertificates = listOf(selfSigned.certificate),
                    ),
                )

            assertEquals("alice", username)
        }

    @Test
    fun `other certificates are still rejected`() =
        runTest {
            val otherCa = HeldCertificate.Builder().certificateAuthority(0).build()

            assertFailsWith<PaperlessException.Network.Tls> { fetchUsername(serverCertificate(otherCa), otherCa, tls) }
        }

    @Test
    fun `the host name is still verified`() =
        runTest {
            val wrongHost =
                HeldCertificate
                    .Builder()
                    .addSubjectAlternativeName("paperless.example.com")
                    .signedBy(privateCa)
                    .build()

            assertFailsWith<PaperlessException.Network.Tls> { fetchUsername(wrongHost, privateCa, tls) }
        }
}
