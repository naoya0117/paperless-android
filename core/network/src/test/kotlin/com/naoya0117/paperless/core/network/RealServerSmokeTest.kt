package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.ApiVersion
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.CustomFieldDataType
import com.naoya0117.paperless.core.model.CustomFieldDraft
import com.naoya0117.paperless.core.model.SelectOption
import com.naoya0117.paperless.core.model.TagDraft
import com.naoya0117.paperless.core.model.toDraft
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `label crud round trip`() =
        runTest {
            val admin = client().signIn("admin", "admin-password").client
            val finance = admin.tags.listAll().single { it.name == "Finance" }

            val created = admin.tags.create(TagDraft("Smoke test tag", color = "#336699", parentId = finance.id))
            try {
                assertEquals(finance.id, created.parentId)
                // Changing only the color keeps the parent.
                val recolored = admin.tags.update(created, created.toDraft().copy(color = "#112233"))
                assertEquals(finance.id, recolored.parentId)
                val updated =
                    admin.tags.update(
                        recolored,
                        recolored.toDraft().copy(name = "Smoke test tag 2", parentId = null),
                    )
                assertEquals("Smoke test tag 2", updated.name)
                assertNull(updated.parentId)
                assertEquals("#112233", updated.color)
            } finally {
                admin.tags.delete(created.id)
            }
            assertFailsWith<PaperlessException.Http.NotFound> { admin.tags.get(created.id) }
        }

    @Test
    fun `select options keep their ids on update`() =
        runTest {
            val admin = client().signIn("admin", "admin-password").client
            val created =
                admin.customFields.create(
                    CustomFieldDraft(
                        "Smoke test select",
                        CustomFieldDataType.SELECT,
                        listOf(SelectOption(null, "One"), SelectOption(null, "Two")),
                    ),
                )
            try {
                val one = created.selectOptions.single { it.label == "One" }
                val renamed = admin.customFields.update(created, created.toDraft().copy(name = "Smoke test select 2"))
                assertEquals(created.selectOptions, renamed.selectOptions)
                val updated =
                    admin.customFields.update(
                        renamed,
                        renamed.toDraft().copy(
                            selectOptions = listOf(SelectOption(one.id, "One renamed"), SelectOption(null, "Three")),
                        ),
                    )
                assertEquals(SelectOption(one.id, "One renamed"), updated.selectOptions.first())
                assertEquals(listOf("One renamed", "Three"), updated.selectOptions.map { it.label })
            } finally {
                admin.customFields.delete(created.id)
            }
        }

    private companion object {
        /** Created by tools/paperless-dev/capture_v10.py. */
        const val PASSWORD = "Fixture-Passw0rd!"
    }
}
