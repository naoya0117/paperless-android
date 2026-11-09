package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.ApiVersion
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.CurrentUser
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class PaperlessClientTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    @Test
    fun `sends version, token and custom headers`() =
        runTest {
            server.enqueue(uiSettingsResponse())
            val client =
                server.client {
                    PaperlessClientConfig(
                        serverUrl = it,
                        token = AuthToken("abc"),
                        customHeaders = listOf(HttpHeader("CF-Access-Client-Id", "id-123")),
                    )
                }

            client.server.fetchServerInfo()

            val request = server.takeRequest()
            assertEquals("/api/ui_settings/", request.url.encodedPath)
            assertEquals("application/json; version=10", request.headers["Accept"])
            assertEquals("Token abc", request.headers["Authorization"])
            assertEquals("id-123", request.headers["CF-Access-Client-Id"])
        }

    @Test
    fun `resolves api paths below a sub path`() =
        runTest {
            server.enqueue(uiSettingsResponse())
            val client =
                PaperlessClient.create(
                    PaperlessClientConfig(ServerUrl.parse(server.url("/paperless").toString()), AuthToken("abc")),
                    testBaseClient,
                )

            client.server.fetchServerInfo()

            assertEquals("/paperless/api/ui_settings/", server.takeRequest().url.encodedPath)
        }

    @Test
    fun `does not send credentials to another host after a redirect`() =
        runTest {
            MockWebServer().use { other ->
                other.start()
                other.enqueue(uiSettingsResponse())
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(302)
                        .addHeader("Location", other.url("/api/ui_settings/"))
                        .build(),
                )
                val client =
                    server.client {
                        PaperlessClientConfig(
                            it,
                            AuthToken("abc"),
                            customHeaders = listOf(HttpHeader("X-Secret", "s")),
                        )
                    }

                client.server.fetchServerInfo()

                assertEquals("Token abc", server.takeRequest().headers["Authorization"])
                val redirected = other.takeRequest().headers
                assertNull(redirected["Authorization"])
                assertNull(redirected["X-Secret"])
                assertFalse(redirected["Accept"].orEmpty().contains("version="))
            }
        }

    @Test
    fun `sign in obtains a token and reads server info`() =
        runTest {
            server.enqueue(jsonResponse(200, "token.json"))
            server.enqueue(uiSettingsResponse())

            val session = server.client().signIn("alice", "pw", mfaCode = " 123456 ")

            val tokenRequest = server.takeRequest()
            assertEquals("POST", tokenRequest.method)
            assertEquals("/api/token/", tokenRequest.url.encodedPath)
            assertNull(tokenRequest.headers["Authorization"])
            assertEquals(
                """{"username":"alice","password":"pw","code":"123456"}""",
                tokenRequest.body?.utf8(),
            )
            assertEquals(
                "Token 0123456789abcdef0123456789abcdef01234567",
                server.takeRequest().headers["Authorization"],
            )

            assertEquals(AuthToken("0123456789abcdef0123456789abcdef01234567"), session.token)
            assertEquals(ApiVersion(10), session.serverInfo.apiVersion)
            assertEquals("3.4.2", session.serverInfo.serverVersion)
            assertEquals(
                CurrentUser(3, "alice", firstName = "Alice", lastName = null, isStaff = false, isSuperuser = false),
                session.serverInfo.user,
            )
            assertEquals(AuthToken("0123456789abcdef0123456789abcdef01234567"), session.client.config.token)
        }

    @Test
    fun `omits a blank mfa code`() =
        runTest {
            server.enqueue(jsonResponse(200, "token.json"))

            server.client().auth.obtainToken("alice", "pw", mfaCode = "")

            assertEquals("""{"username":"alice","password":"pw"}""", server.takeRequest().body?.utf8())
        }

    @Test
    fun `sign in with a token from a newer server keeps the client version`() =
        runTest {
            server.enqueue(uiSettingsResponse(apiVersion = 11))

            val session = server.client().signIn(AuthToken("abc"))

            assertEquals(ApiVersion(11), session.serverInfo.serverMaxApiVersion)
            assertEquals(ApiVersion(10), session.client.config.apiVersion)
        }

    @Test
    fun `login errors are mapped`() =
        runTest {
            val cases =
                listOf(
                    "token_invalid_credentials.json" to PaperlessException.InvalidCredentials::class,
                    "token_mfa_required.json" to PaperlessException.MfaRequired::class,
                    "token_invalid_mfa_code.json" to PaperlessException.InvalidMfaCode::class,
                )
            for ((fixtureName, expected) in cases) {
                server.enqueue(jsonResponse(400, fixtureName))
                val e = assertFailsWith<PaperlessException> { server.client().auth.obtainToken("alice", "wrong") }
                assertEquals(fixtureName, expected, e::class)
            }
        }

    @Test
    fun `invalid credentials keep the server message`() =
        runTest {
            server.enqueue(jsonResponse(400, "token_invalid_credentials.json"))

            val e =
                assertFailsWith<PaperlessException.InvalidCredentials> {
                    server.client().auth.obtainToken("alice", "wrong")
                }

            assertEquals("Unable to log in with provided credentials.", e.serverMessage)
        }

    @Test
    fun `406 means the server does not support the api version`() =
        runTest {
            server.enqueue(jsonResponse(406, "error_not_acceptable.json"))

            val e =
                assertFailsWith<PaperlessException.UnsupportedServerVersion> {
                    server.client().signIn("alice", "pw")
                }

            assertNull(e.serverMaxApiVersion)
        }

    @Test
    fun `old server version is rejected after login`() =
        runTest {
            server.enqueue(uiSettingsResponse(apiVersion = 9))

            val e =
                assertFailsWith<PaperlessException.UnsupportedServerVersion> {
                    server.client().signIn(
                        AuthToken("abc"),
                    )
                }

            assertEquals(ApiVersion(9), e.serverMaxApiVersion)
        }

    @Test
    fun `throttling exposes retry after`() =
        runTest {
            server.enqueue(jsonResponse(429, "error_throttled.json", "Retry-After" to "42"))

            val e = assertFailsWith<PaperlessException.RateLimited> { server.client().auth.obtainToken("alice", "pw") }

            assertEquals(42.seconds, e.retryAfter)
        }

    @Test
    fun `invalid token is unauthorized`() =
        runTest {
            server.enqueue(jsonResponse(401, "error_invalid_token.json"))

            val e = assertFailsWith<PaperlessException.Unauthorized> { server.client().signIn(AuthToken("bad")) }

            assertEquals("Invalid token.", e.detail)
        }

    @Test
    fun `missing api version header is an invalid response`() =
        runTest {
            server.enqueue(jsonResponse(200, "ui_settings.json"))

            val e = assertFailsWith<PaperlessException.InvalidResponse> { server.client().signIn(AuthToken("abc")) }

            assertTrue(e.message.orEmpty().contains("X-Api-Version"))
        }

    @Test
    fun `http status codes are mapped`() =
        runTest {
            val cases =
                mapOf(
                    403 to PaperlessException.Forbidden::class,
                    404 to PaperlessException.NotFound::class,
                    500 to PaperlessException.ServerError::class,
                    502 to PaperlessException.ServerError::class,
                    418 to PaperlessException.UnexpectedStatus::class,
                )
            for ((code, expected) in cases) {
                // A reverse proxy error page is not JSON; it must not break the mapping.
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(code)
                        .body("<html>error</html>")
                        .build(),
                )
                val e = assertFailsWith<PaperlessException> { server.client().server.fetchServerInfo() }
                assertEquals("HTTP $code", expected, e::class)
            }
        }

    @Test
    fun `malformed json is an invalid response`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"unexpected": true}""")
                    .build(),
            )

            assertFailsWith<PaperlessException.InvalidResponse> { server.client().auth.obtainToken("alice", "pw") }
        }
}
