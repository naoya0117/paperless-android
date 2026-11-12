package com.naoya0117.paperless.feature.login

import androidx.lifecycle.SavedStateHandle
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.data.PaperlessClientFactory
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.SecretCipher
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.testing.TestViewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.tls.certificatePem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    /** Every view model of a test, cleared in tearDown (before Main is reset). */
    private val viewModels = TestViewModels()

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var accounts: AccountRepository
    private lateinit var viewModel: LoginViewModel
    private val savedState = SavedStateHandle()

    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x33).toByte() }.toByteArray()

        override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
    }

    private fun json(
        code: Int,
        body: String,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .addHeader("X-Api-Version", "10")
        .body(body)
        .build()

    private val uiSettings = json(200, """{"user": {"id": 3, "username": "alice"}, "settings": {}}""")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        accounts =
            AccountRepository(
                AccountStore.create(folder.root.resolve("accounts.json"), XorCipher),
                PaperlessClientFactory(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()),
                {
                    HandshakeCertificates
                        .Builder()
                        .heldCertificate(
                            HeldCertificate.Builder().build(),
                        ).build()
                        .keyManager
                },
                {},
            )
        viewModel = viewModels.create { LoginViewModel(accounts, savedState) }
    }

    @After
    fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
        server.close()
    }

    private fun url() = server.url("/").toString()

    /** Waits for the sign-in, which runs on real network threads. */
    private suspend fun awaitDone(): LoginState =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10_000) { viewModel.state.first { !it.inProgress } }
        }

    private fun enterPassword() {
        viewModel.setServerUrl(url())
        viewModel.setUsername("alice")
        viewModel.setPassword("pw")
    }

    @Test
    fun `the url is checked and plain http is flagged`() {
        assertFalse(viewModel.state.value.canSubmit)

        viewModel.setServerUrl("ftp://x")
        assertEquals(UrlProblem.INVALID, viewModel.state.value.urlProblem)

        viewModel.setServerUrl("paperless.example.com")
        assertNull(viewModel.state.value.urlProblem)
        assertFalse("https is assumed", viewModel.state.value.cleartext)

        viewModel.setServerUrl("http://192.168.1.2:8000")
        assertTrue(viewModel.state.value.cleartext)
    }

    @Test
    fun `signing in with a password`() =
        runTest {
            server.enqueue(json(200, """{"token": "abc"}"""))
            server.enqueue(uiSettings)
            enterPassword()
            assertTrue(viewModel.state.value.canSubmit)

            viewModel.submit()
            val state = awaitDone()

            assertEquals("alice", state.signedIn?.username)
            assertEquals("", state.password)
            assertFalse(state.canSubmit)
            assertNotNull(accounts.state.first().activeAccount)
        }

    @Test
    fun `two-factor authentication asks for the code, then signs in`() =
        runTest {
            server.enqueue(json(400, """{"non_field_errors": ["MFA code is required"]}"""))
            enterPassword()

            viewModel.submit()
            assertEquals(LoginStep.MFA_CODE, awaitDone().step)
            assertFalse("no code yet", viewModel.state.value.canSubmit)

            server.enqueue(json(400, """{"non_field_errors": ["Invalid MFA code"]}"""))
            viewModel.setMfaCode("12 34 56")
            assertEquals("123456", viewModel.state.value.mfaCode)
            viewModel.submit()
            val wrongCode = awaitDone()
            assertEquals(LoginError.INVALID_MFA_CODE, wrongCode.error)
            assertEquals(LoginStep.MFA_CODE, wrongCode.step)

            server.enqueue(json(200, """{"token": "abc"}"""))
            server.enqueue(uiSettings)
            viewModel.setMfaCode("654321")
            viewModel.submit()
            assertEquals("alice", awaitDone().signedIn?.username)
            val tokenRequest = (1..4).map { server.takeRequest() }.last { it.url.encodedPath == "/api/token/" }
            assertTrue(
                tokenRequest.body
                    ?.utf8()
                    .orEmpty()
                    .contains("654321"),
            )
        }

    @Test
    fun `failures are reported by kind`() =
        runTest {
            server.enqueue(json(400, """{"non_field_errors": ["Unable to log in with provided credentials."]}"""))
            enterPassword()
            viewModel.submit()
            assertEquals(LoginError.INVALID_CREDENTIALS, awaitDone().error)

            viewModel.setPassword("other")
            assertNull("editing clears the error", viewModel.state.value.error)

            server.enqueue(
                MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", "https://elsewhere/")
                    .build(),
            )
            viewModel.submit()
            val redirected = awaitDone()
            assertEquals(LoginError.REDIRECTED, redirected.error)
            assertEquals("https://elsewhere/", redirected.redirectTarget)

            viewModel.setServerUrl("http://unknown-host.invalid/")
            viewModel.submit()
            assertEquals(LoginError.UNKNOWN_HOST, awaitDone().error)
        }

    @Test
    fun `signing in with a token`() =
        runTest {
            server.enqueue(json(401, """{"detail": "Invalid token."}"""))
            viewModel.setServerUrl(url())
            viewModel.setMethod(LoginMethod.TOKEN)
            viewModel.setToken("wrong")
            viewModel.submit()
            assertEquals(LoginError.INVALID_TOKEN, awaitDone().error)

            server.enqueue(uiSettings)
            viewModel.setToken(" abc ")
            viewModel.submit()

            assertEquals("alice", awaitDone().signedIn?.username)
            assertEquals(AuthToken("abc"), accounts.currentClient()?.config?.token)
        }

    @Test
    fun `invalid headers are reported by position without their value`() =
        runTest {
            enterPassword()
            viewModel.setHeaders(
                listOf(HeaderInput("", ""), HeaderInput("X-Ok", "fine"), HeaderInput("X-Bad", "sec\u0001ret")),
            )

            viewModel.submit()

            val state = viewModel.state.value
            assertEquals(LoginError.INVALID_HEADER, state.error)
            assertEquals(2, state.invalidHeaderIndex)
            assertNull(state.errorDetail)
            assertFalse(state.inProgress)
            assertEquals(0, server.requestCount)

            viewModel.setHeaders(listOf(HeaderInput("", "value without a name")))
            viewModel.submit()
            assertEquals(0, viewModel.state.value.invalidHeaderIndex)
        }

    @Test
    fun `header values are trimmed`() =
        runTest {
            server.enqueue(json(200, """{"token": "abc"}"""))
            server.enqueue(uiSettings)
            enterPassword()
            viewModel.setHeaders(listOf(HeaderInput(" X-Proxy ", "secret\n")))

            viewModel.submit()
            awaitDone()

            assertEquals("secret", server.takeRequest().headers["X-Proxy"])
        }

    @Test
    fun `rate limits and proxy authentication have their own kinds`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(429)
                    .addHeader("Retry-After", "42")
                    .body("{}")
                    .build(),
            )
            enterPassword()
            viewModel.submit()
            val limited = awaitDone()
            assertEquals(LoginError.RATE_LIMITED, limited.error)
            assertEquals(42L, limited.retryAfterSeconds)

            server.enqueue(json(401, """{"detail": "Proxy says no"}"""))
            viewModel.submit()
            val proxy = awaitDone()
            assertEquals(LoginError.PROXY_AUTHENTICATION, proxy.error)
            assertEquals("Proxy says no", proxy.errorDetail)
        }

    @Test
    fun `wrong credentials at the code step go back to the credentials`() =
        runTest {
            server.enqueue(json(400, """{"non_field_errors": ["MFA code is required"]}"""))
            enterPassword()
            viewModel.submit()
            assertEquals(LoginStep.MFA_CODE, awaitDone().step)

            server.enqueue(json(400, """{"non_field_errors": ["Unable to log in with provided credentials."]}"""))
            viewModel.setMfaCode("123456")
            viewModel.submit()

            val state = awaitDone()
            assertEquals(LoginError.INVALID_CREDENTIALS, state.error)
            assertEquals(LoginStep.CREDENTIALS, state.step)
            assertEquals("Unable to log in with provided credentials.", state.errorDetail)
        }

    @Test
    fun `non-secret input survives process death`() {
        enterPassword()
        viewModel.setMethod(LoginMethod.TOKEN)
        viewModel.setToken("secret-token")
        viewModel.setHeaders(listOf(HeaderInput("X-Proxy", "secret")))
        viewModel.setClientCertificate("my-cert")

        val restored = viewModels.create { LoginViewModel(accounts, savedState) }.state.value

        assertEquals(url(), restored.serverUrl)
        assertEquals(LoginMethod.TOKEN, restored.method)
        assertEquals("alice", restored.username)
        assertEquals("", restored.token)
        assertEquals("", restored.password)
        assertEquals(listOf(HeaderInput("X-Proxy", "")), restored.headers)
        assertEquals("my-cert", restored.tls.clientCertificateAlias)
    }

    @Test
    fun `after a sign-in was handled the form starts over`() =
        runTest {
            server.enqueue(uiSettings)
            viewModel.setServerUrl(url())
            viewModel.setMethod(LoginMethod.TOKEN)
            viewModel.setToken("abc")
            viewModel.submit()
            assertNotNull(awaitDone().signedIn)

            viewModel.signedInHandled()

            assertEquals(LoginState(), viewModel.state.value)
        }

    /** Waits until the state matches, on real time (certificates are parsed on another dispatcher). */
    private suspend fun awaitState(predicate: (LoginState) -> Boolean): LoginState =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10_000) { viewModel.state.first(predicate) }
        }

    @Test
    fun `certificates can be added and removed`() =
        runTest {
            viewModel.addTrustedCertificates("not a certificate".encodeToByteArray())
            awaitState { it.error == LoginError.INVALID_CERTIFICATE }

            val certificate =
                HeldCertificate
                    .Builder()
                    .build()
                    .certificate.encoded
            viewModel.addTrustedCertificates(certificate)
            awaitState { it.tls.trustedCertificates.size == 1 }
            viewModel.addTrustedCertificates(certificate)
            viewModel.setClientCertificate("my-cert") // queued after the second add
            val added = awaitState { it.tls.clientCertificateAlias == "my-cert" }.tls.trustedCertificates
            assertEquals("duplicates are ignored", 1, added.size)

            viewModel.removeTrustedCertificate(added.single())
            assertEquals(AccountTls(clientCertificateAlias = "my-cert"), viewModel.state.value.tls)
        }

    @Test
    fun `at most a limited number of certificates is kept`() =
        runTest {
            val pem =
                (1..LoginViewModel.MAX_TRUSTED_CERTIFICATES + 1).joinToString("") {
                    HeldCertificate.Builder().build().certificatePem()
                }

            viewModel.addTrustedCertificates(pem.encodeToByteArray())

            assertEquals(emptyList<Any>(), awaitState { it.error != null }.tls.trustedCertificates)
        }

    @Test
    fun `after process death signing in again reads the header values again`() =
        runTest {
            server.enqueue(uiSettings)
            val account = accounts.signInWithToken(url(), AuthToken("abc"), listOf("X-Proxy" to "secret"), AccountTls())
            viewModel.signInAgain(account.id)
            awaitState { it.headers.isNotEmpty() }

            val restored = viewModels.create { LoginViewModel(accounts, savedState) }
            assertEquals(listOf(HeaderInput("X-Proxy", "")), restored.state.value.headers)
            restored.signInAgain(account.id)

            val state =
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(10_000) { restored.state.first { it.headers.singleOrNull()?.value == "secret" } }
                }
            assertEquals(account.id, state.reSignInAccountId)
        }

    @Test
    fun `a sign-in clears the form and allows preparing the same account again`() =
        runTest {
            server.enqueue(uiSettings)
            val account = accounts.signInWithToken(url(), AuthToken("abc"), tls = AccountTls())
            viewModel.signInAgain(account.id)
            awaitState { it.username == "alice" }
            server.enqueue(uiSettings)
            viewModel.setMethod(LoginMethod.TOKEN)
            viewModel.setToken("new")
            viewModel.submit()
            val signedIn = awaitDone()
            assertEquals(LoginState(signedIn = signedIn.signedIn), signedIn)

            viewModel.signInAgain(account.id) // e.g. the token expired again before the screen was left

            assertEquals("alice", awaitState { it.username == "alice" }.username)
        }

    @Test
    fun `signing in again keeps the account's settings and headers, once`() =
        runTest {
            server.enqueue(uiSettings)
            val account =
                accounts.signInWithToken(
                    url(),
                    AuthToken("abc"),
                    customHeaders = listOf("X-Proxy" to "secret"),
                    tls = AccountTls(clientCertificateAlias = "my-cert"),
                )

            viewModel.signInAgain(account.id)
            val state =
                withContext(Dispatchers.Default.limitedParallelism(1)) {
                    withTimeout(10_000) { viewModel.state.first { it.username.isNotEmpty() } }
                }

            assertEquals(account.serverUrl, state.serverUrl)
            assertEquals("alice", state.username)
            assertEquals("my-cert", state.tls.clientCertificateAlias)
            assertEquals(listOf(HeaderInput("X-Proxy", "secret")), state.headers)

            viewModel.setPassword("typed")
            viewModel.signInAgain(account.id) // e.g. the screen is shown again after rotation
            assertEquals("typed", viewModel.state.value.password)
        }

    @Test
    fun `secrets are not part of the state's text`() {
        enterPassword()
        viewModel.setHeaders(listOf(HeaderInput("X-Proxy", "proxy-secret")))

        val text = viewModel.state.value.toString()

        assertFalse(text, "pw," in text || "proxy-secret" in text)
        assertTrue(text, "alice" in text)
    }
}
