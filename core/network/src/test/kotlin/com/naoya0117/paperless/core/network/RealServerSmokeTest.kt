package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.ApiVersion
import com.naoya0117.paperless.core.model.AuthToken
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs against the local server from tools/paperless-dev after `capture_v10.py` created the test users.
 * Skipped unless `PAPERLESS_DEV_URL` is set (e.g. `PAPERLESS_DEV_URL=http://localhost:8000`), so CI never runs it.
 */
class RealServerSmokeTest {
    private val baseUrl: String? = System.getenv("PAPERLESS_DEV_URL")?.ifBlank { null }

    @Before
    fun requireServer() = assumeTrue("PAPERLESS_DEV_URL not set", baseUrl != null)

    private fun client() = PaperlessClient.create(PaperlessClientConfig(ServerUrl.parse(baseUrl!!)), testBaseClient)

    @Test
    fun `signs in with username and password`() =
        runTest {
            val session = client().signIn("alice", PASSWORD)

            assertEquals(ApiVersion.V10, session.serverInfo.apiVersion)
            assertEquals("alice", session.serverInfo.user.username)

            val again = client().signIn(session.token)
            assertEquals(session.serverInfo.user, again.serverInfo.user)
        }

    @Test
    fun `reports a required mfa code`() =
        runTest {
            assertFailsWith<PaperlessException.Auth.MfaRequired> { client().signIn("mfa", PASSWORD) }
        }

    @Test
    fun `rejects an invalid token`() =
        runTest {
            assertFailsWith<PaperlessException.Http.Unauthorized> { client().signIn(AuthToken("0".repeat(40))) }
        }

    private companion object {
        /** Created by tools/paperless-dev/capture_v10.py. */
        const val PASSWORD = "Fixture-Passw0rd!"
    }
}
