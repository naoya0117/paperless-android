package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.SecretCipher
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.TrustedCertificate
import com.naoya0117.paperless.core.network.PaperlessException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.Proxy

class AccountTlsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val ca = HeldCertificate.Builder().certificateAuthority(0).build()
    private val serverCertificate =
        HeldCertificate
            .Builder()
            .addSubjectAlternativeName("localhost")
            .signedBy(ca)
            .build()
    private val clientCertificate =
        HeldCertificate
            .Builder()
            .commonName("paperless-client")
            .signedBy(ca)
            .build()

    private val server = MockWebServer()
    private val requestedAliases = mutableListOf<String>()
    private lateinit var store: AccountStore
    private lateinit var repository: AccountRepository

    private val trustCa = AccountTls(trustedCertificates = listOf(TrustedCertificate(ca.certificate.encoded)))

    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x33).toByte() }.toByteArray()

        override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
    }

    @Before
    fun setUp() {
        server.useHttps(
            HandshakeCertificates
                .Builder()
                .heldCertificate(serverCertificate, ca.certificate)
                .addTrustedCertificate(ca.certificate)
                .build()
                .sslSocketFactory(),
        )
        server.start()
        val clientKeys = HandshakeCertificates.Builder().heldCertificate(clientCertificate, ca.certificate).build()
        store = AccountStore.create(folder.root.resolve("accounts.json"), XorCipher)
        repository =
            AccountRepository(
                store,
                PaperlessClientFactory(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()),
                { alias ->
                    requestedAliases += alias
                    clientKeys.keyManager
                },
            )
    }

    @After
    fun tearDown() = server.close()

    private fun serverUrl() = server.url("/").toString()

    private fun uiSettings() =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("X-Api-Version", "10")
            .body("""{"user": {"id": 3, "username": "alice"}, "settings": {}}""")
            .build()

    private fun tokenResponse() =
        MockResponse
            .Builder()
            .code(200)
            .body("""{"token": "abc"}""")
            .build()

    @Test
    fun `a server with a private ca is reachable once its certificate is trusted`() =
        runTest {
            server.enqueue(tokenResponse())
            server.enqueue(uiSettings())
            server.enqueue(uiSettings())

            val account = repository.signIn(serverUrl(), "alice", "pw", tls = trustCa)

            assertEquals(trustCa, account.tls)
            assertEquals(
                trustCa,
                repository.state
                    .first()
                    .activeAccount
                    ?.tls,
            )
            repository.activeClient
                .first()!!
                .server
                .fetchServerInfo()
        }

    @Test
    fun `without trusting the certificate signing in fails with a tls error`() =
        runTest {
            val result = runCatching { repository.signIn(serverUrl(), "alice", "pw", tls = AccountTls()) }

            assertTrue(result.exceptionOrNull().toString(), result.exceptionOrNull() is PaperlessException.Network.Tls)
            assertTrue(
                repository.state
                    .first()
                    .accounts
                    .isEmpty(),
            )
        }

    @Test
    fun `the client certificate is presented for mutual tls`() =
        runTest {
            server.requireClientAuth()
            server.enqueue(uiSettings())
            val tls = trustCa.copy(clientCertificateAlias = "my-cert")

            repository.signInWithToken(
                serverUrl(),
                com.naoya0117.paperless.core.model
                    .AuthToken("abc"),
                tls = tls,
            )

            assertEquals(
                "CN=paperless-client",
                server
                    .takeRequest()
                    .handshake
                    ?.peerPrincipal
                    ?.name,
            )
            assertEquals(listOf("my-cert"), requestedAliases)
        }

    @Test
    fun `changing the tls settings rebuilds the client`() =
        runTest {
            server.enqueue(uiSettings())
            val account =
                repository.signInWithToken(
                    serverUrl(),
                    com.naoya0117.paperless.core.model
                        .AuthToken("abc"),
                    tls = trustCa,
                )
            val before = repository.activeClient.first()!!

            val tls = trustCa.copy(clientCertificateAlias = "renewed")
            repository.setTls(account.id, tls)

            val after = repository.activeClient.first()!!
            assertNotSame(before, after)
            assertEquals(
                tls,
                repository.state
                    .first()
                    .activeAccount
                    ?.tls,
            )
            assertEquals(
                1,
                after.config.tls
                    ?.keyManagers
                    ?.size,
            )
            assertEquals(listOf("renewed"), requestedAliases)
        }

    @Test
    fun `invalid certificates are rejected before they are stored`() =
        runTest {
            server.enqueue(uiSettings())
            val account =
                repository.signInWithToken(
                    serverUrl(),
                    com.naoya0117.paperless.core.model
                        .AuthToken("abc"),
                    tls = trustCa,
                )
            val invalid = AccountTls(trustedCertificates = listOf(TrustedCertificate(byteArrayOf(1, 2, 3))))

            assertTrue(
                runCatching { repository.setTls(account.id, invalid) }.exceptionOrNull() is IllegalArgumentException,
            )
            assertEquals(
                trustCa,
                repository.state
                    .first()
                    .activeAccount
                    ?.tls,
            )
        }

    @Test
    fun `an outdated account object does not bring back old tls settings`() =
        runTest {
            server.enqueue(uiSettings())
            val outdated = repository.signInWithToken(serverUrl(), AuthToken("abc"), tls = trustCa)
            val tls = trustCa.copy(clientCertificateAlias = "added")
            repository.setTls(outdated.id, tls)

            val client = repository.client(outdated)!!

            assertSame(repository.activeClient.first(), client)
            assertEquals(
                1,
                client.config.tls
                    ?.keyManagers
                    ?.size,
            )
        }

    @Test
    fun `an invalid stored certificate is skipped instead of breaking the client`() =
        runTest {
            server.enqueue(uiSettings())
            val account = repository.signInWithToken(serverUrl(), AuthToken("abc"), tls = trustCa)
            // As if the file had been edited: bypasses the repository's validation.
            val broken = TrustedCertificate(byteArrayOf(1, 2, 3))
            store.setTls(account.id, trustCa.copy(trustedCertificates = trustCa.trustedCertificates + broken))

            val client = repository.activeClient.first()!!

            assertEquals(listOf(ca.certificate), client.config.tls?.additionalTrustedCertificates)
        }
}
