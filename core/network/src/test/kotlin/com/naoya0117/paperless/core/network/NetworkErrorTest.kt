package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.AuthToken
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class NetworkErrorTest {
    @Test
    fun `read timeout`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(uiSettingsResponse().newBuilder().headersDelay(2, TimeUnit.SECONDS).build())
                val client =
                    server.client {
                        PaperlessClientConfig(it, AuthToken("abc"), timeouts = Timeouts(read = 200.milliseconds))
                    }

                assertFailsWith<PaperlessException.Network.Timeout> { client.server.fetchServerInfo() }
            }
        }

    @Test
    fun `connection refused`() =
        runTest {
            // Reserve a free port, then close it so nothing listens there.
            val port = ServerSocket(0).use { it.localPort }
            val client =
                PaperlessClient.create(
                    PaperlessClientConfig(ServerUrl.parse("http://127.0.0.1:$port")),
                    testBaseClient,
                )

            assertFailsWith<PaperlessException.Network.ConnectionFailed> { client.server.fetchServerInfo() }
        }

    @Test
    fun `unknown host`() =
        runTest {
            // RFC 2606: .invalid never resolves.
            val client =
                PaperlessClient.create(
                    PaperlessClientConfig(ServerUrl.parse("https://paperless.invalid")),
                    testBaseClient,
                )

            assertFailsWith<PaperlessException.Network.UnknownHost> { client.server.fetchServerInfo() }
        }

    @Test
    fun `untrusted certificate is a tls error`() =
        runTest {
            val serverCertificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            MockWebServer().use { server ->
                server.useHttps(
                    HandshakeCertificates
                        .Builder()
                        .heldCertificate(serverCertificate)
                        .build()
                        .sslSocketFactory(),
                )
                server.start()

                assertFailsWith<PaperlessException.Network.Tls> { server.client().server.fetchServerInfo() }
            }
        }

    @Test
    fun `custom trust manager accepts a private ca`() =
        runTest {
            val ca = HeldCertificate.Builder().certificateAuthority(0).build()
            val serverCertificate =
                HeldCertificate
                    .Builder()
                    .addSubjectAlternativeName(
                        "localhost",
                    ).signedBy(ca)
                    .build()
            MockWebServer().use { server ->
                server.useHttps(
                    HandshakeCertificates
                        .Builder()
                        .heldCertificate(serverCertificate, ca.certificate)
                        .build()
                        .sslSocketFactory(),
                )
                server.start()
                server.enqueue(uiSettingsResponse())
                val trust = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build()

                val client =
                    server.client {
                        PaperlessClientConfig(it, AuthToken("abc"), tls = TlsConfig(trustManager = trust.trustManager))
                    }

                assertEquals(
                    "alice",
                    client.server
                        .fetchServerInfo()
                        .user.username,
                )
            }
        }

    @Test
    fun `client certificate is presented for mutual tls`() =
        runTest {
            val ca = HeldCertificate.Builder().certificateAuthority(0).build()
            val serverCertificate =
                HeldCertificate
                    .Builder()
                    .addSubjectAlternativeName(
                        "localhost",
                    ).signedBy(ca)
                    .build()
            val clientCertificate =
                HeldCertificate
                    .Builder()
                    .commonName("paperless-client")
                    .signedBy(ca)
                    .build()
            MockWebServer().use { server ->
                server.useHttps(
                    HandshakeCertificates
                        .Builder()
                        .heldCertificate(serverCertificate, ca.certificate)
                        .addTrustedCertificate(ca.certificate)
                        .build()
                        .sslSocketFactory(),
                )
                server.requireClientAuth()
                server.start()
                server.enqueue(uiSettingsResponse())
                val clientTls =
                    HandshakeCertificates
                        .Builder()
                        .heldCertificate(clientCertificate, ca.certificate)
                        .addTrustedCertificate(ca.certificate)
                        .build()

                val client =
                    server.client {
                        PaperlessClientConfig(
                            it,
                            AuthToken("abc"),
                            tls = TlsConfig(listOf(clientTls.keyManager), clientTls.trustManager),
                        )
                    }

                client.server.fetchServerInfo()
                assertEquals(
                    "CN=paperless-client",
                    server
                        .takeRequest()
                        .handshake
                        ?.peerPrincipal
                        ?.name,
                )
            }
        }
}
