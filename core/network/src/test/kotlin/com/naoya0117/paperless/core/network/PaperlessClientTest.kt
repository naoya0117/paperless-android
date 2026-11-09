package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.ApiVersion
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.CurrentUser
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun `does not follow a redirect that would re-send the password to another host`() =
        runTest {
            MockWebServer().use { other ->
                other.start()
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(307)
                        .addHeader("Location", other.url("/steal/"))
                        .build(),
                )

                val e =
                    assertFailsWith<PaperlessException.Http.Redirected> {
                        server.client().signIn("alice", "hunter2")
                    }

                assertEquals(307, e.statusCode)
                assertEquals(other.url("/steal/").toString(), e.location)
                assertEquals(0, other.requestCount)
            }
        }

    @Test
    fun `reports an http to https redirect instead of following it`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(301)
                    .addHeader("Location", "https://paperless.example.com/api/token/")
                    .build(),
            )

            val e = assertFailsWith<PaperlessException.Http.Redirected> { server.client().signIn("alice", "pw") }

            assertEquals("https://paperless.example.com/api/token/", e.location)
            assertEquals(1, server.requestCount)
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
                    "token_invalid_credentials.json" to PaperlessException.Auth.InvalidCredentials::class,
                    "token_mfa_required.json" to PaperlessException.Auth.MfaRequired::class,
                    "token_invalid_mfa_code.json" to PaperlessException.Auth.InvalidMfaCode::class,
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
                assertFailsWith<PaperlessException.Auth.InvalidCredentials> {
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
    fun `falls back to an older api version when the newest one is not accepted`() =
        runTest {
            server.enqueue(jsonResponse(406, "error_not_acceptable.json"))
            server.enqueue(uiSettingsResponse(apiVersion = 9))
            val client =
                PaperlessClient.create(
                    PaperlessClientConfig(ServerUrl.parse(server.url("/").toString())),
                    testBaseClient,
                    supportedVersions = ApiVersion(9)..ApiVersion(10),
                )

            val session = client.signIn(AuthToken("abc"))

            assertEquals("application/json; version=10", server.takeRequest().headers["Accept"])
            assertEquals("application/json; version=9", server.takeRequest().headers["Accept"])
            assertEquals(ApiVersion(9), session.serverInfo.apiVersion)
            assertEquals(ApiVersion(9), session.client.config.apiVersion)
        }

    @Test
    fun `fails when the server accepts none of the supported versions`() =
        runTest {
            repeat(2) { server.enqueue(jsonResponse(406, "error_not_acceptable.json")) }
            val client =
                PaperlessClient.create(
                    PaperlessClientConfig(ServerUrl.parse(server.url("/").toString())),
                    testBaseClient,
                    supportedVersions = ApiVersion(10)..ApiVersion(11),
                )

            assertFailsWith<PaperlessException.UnsupportedServerVersion> { client.signIn("alice", "pw") }
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `login does not send a stale token`() =
        runTest {
            server.enqueue(jsonResponse(200, "token.json"))
            server.enqueue(uiSettingsResponse())

            server.client { PaperlessClientConfig(it, token = AuthToken("revoked")) }.signIn("alice", "pw")

            assertNull(server.takeRequest().headers["Authorization"])
        }

    @Test
    fun `throttling exposes retry after`() =
        runTest {
            server.enqueue(jsonResponse(429, "error_throttled.json", "Retry-After" to "42"))

            val e =
                assertFailsWith<PaperlessException.Http.RateLimited> { server.client().auth.obtainToken("alice", "pw") }

            assertEquals(42.seconds, e.retryAfter)
        }

    @Test
    fun `invalid token is unauthorized`() =
        runTest {
            server.enqueue(jsonResponse(401, "error_invalid_token.json"))

            val e = assertFailsWith<PaperlessException.Http.Unauthorized> { server.client().signIn(AuthToken("bad")) }

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
                    403 to PaperlessException.Http.Forbidden::class,
                    404 to PaperlessException.Http.NotFound::class,
                    500 to PaperlessException.Http.ServerError::class,
                    502 to PaperlessException.Http.ServerError::class,
                    418 to PaperlessException.Http.UnexpectedStatus::class,
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

    @Test
    fun `blank login fields are invalid credentials`() =
        runTest {
            server.enqueue(jsonResponse(400, "token_blank_username.json"))

            val e =
                assertFailsWith<PaperlessException.Auth.InvalidCredentials> {
                    server.client().auth.obtainToken("", "pw")
                }

            assertEquals("This field may not be blank.", e.serverMessage)
        }

    @Test
    fun `missing token is unauthorized`() =
        runTest {
            server.enqueue(jsonResponse(401, "error_unauthenticated.json", "WWW-Authenticate" to "Token"))

            val e = assertFailsWith<PaperlessException.Http.Unauthorized> { server.client().server.fetchServerInfo() }

            assertEquals("Authentication credentials were not provided.", e.detail)
        }

    @Test
    fun `sign in fails without the ui settings view permission`() =
        runTest {
            server.enqueue(
                jsonResponse(
                    403,
                    "ui_settings_without_permission.json",
                    "X-Api-Version" to "10",
                    "X-Version" to "3.4.2",
                ),
            )

            val e = assertFailsWith<PaperlessException.Http.Forbidden> { server.client().signIn(AuthToken("abc")) }

            assertEquals("You do not have permission to perform this action.", e.detail)
        }

    @Test
    fun `406 without a drf body is not a version problem`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(406)
                    .body("<html>Not Acceptable (WAF)</html>")
                    .build(),
            )

            val e =
                assertFailsWith<PaperlessException.Http.UnexpectedStatus> { server.client().auth.obtainToken("a", "b") }

            assertEquals(406, e.statusCode)
        }

    @Test
    fun `successful response without a body is an invalid response`() =
        runTest {
            server.enqueue(MockResponse.Builder().code(204).build())

            assertFailsWith<PaperlessException.InvalidResponse> { server.client().auth.obtainToken("alice", "pw") }
        }
}
