package com.naoya0117.paperless.demo

import androidx.test.core.app.ApplicationProvider
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.DateRange
import com.naoya0117.paperless.core.model.DocumentOrdering
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.DocumentSearch
import com.naoya0117.paperless.core.model.IdFilter
import com.naoya0117.paperless.core.model.TagFilter
import com.naoya0117.paperless.core.model.toDraft
import com.naoya0117.paperless.core.network.DocumentFileKind
import com.naoya0117.paperless.core.network.PaperlessClient
import com.naoya0117.paperless.core.network.PaperlessClientConfig
import com.naoya0117.paperless.core.network.PaperlessException
import com.naoya0117.paperless.core.network.ServerUrl
import com.naoya0117.paperless.core.network.UploadContent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** The demo server through the app's real API client: its responses must parse like a real server's. */
@RunWith(RobolectricTestRunner::class)
class DemoServerTest {
    private val server = DemoServer(DemoData.load(ApplicationProvider.getApplicationContext()), DemoFiles())
    private val unauthenticated =
        PaperlessClient.create(
            PaperlessClientConfig(ServerUrl.parse(DemoModule.DEMO_SERVER_URL)),
            OkHttpClient.Builder().addInterceptor(server).build(),
        )

    private suspend fun client(): PaperlessClient = unauthenticated.signIn(AuthToken(DemoServer.DEMO_TOKEN)).client

    @Test
    fun `signing in works with a password or the demo token`() =
        runTest {
            val session = unauthenticated.signIn("anyone", "any password")
            assertEquals("demo", session.serverInfo.user.username)
            assertEquals(10, session.serverInfo.apiVersion.value)
            assertEquals(
                listOf(1L, 2L, 3L),
                session.client.server
                    .fetchSavedViewVisibility()
                    .dashboardViewIds,
            )
        }

    @Test
    fun `documents are paged, newest first by default`() =
        runTest {
            val client = client()
            val first = client.documents.list(DocumentQuery(pageSize = 5))
            assertEquals(14, first.count)
            assertTrue(first.hasNext)
            assertEquals(5, first.items.size)
            assertEquals(first.items.sortedByDescending { it.created }, first.items)
            val last = client.documents.list(DocumentQuery(page = 3, pageSize = 5))
            assertEquals(4, last.items.size)
            assertFalse(last.hasNext)
        }

    @Test
    fun `the list is searched, filtered and ordered`() =
        runTest {
            val client = client()

            fun titles(query: DocumentQuery) =
                suspend {
                    client.documents
                        .list(query)
                        .items
                        .map { it.title }
                }

            assertEquals(
                listOf("Electricity bill October 2026", "Electricity bill September 2026"),
                titles(DocumentQuery(search = DocumentSearch.Text("electricity bill")))(),
            )
            // Content is searched too.
            assertEquals(
                listOf("Car insurance policy 2027"),
                titles(DocumentQuery(search = DocumentSearch.Text("CI-2027-55120")))(),
            )
            assertEquals(3, client.documents.list(DocumentQuery(inInbox = true)).count)
            assertEquals(2, client.documents.list(DocumentQuery(tags = TagFilter.Matching(all = setOf(2L, 3L)))).count)
            assertTrue(
                client.documents.list(DocumentQuery(correspondent = IdFilter.AnyOf(setOf(1L)))).items.all {
                    it.correspondentId ==
                        1L
                },
            )
            assertTrue(
                client.documents.list(DocumentQuery(correspondent = IdFilter.NotAssigned)).items.all {
                    it.correspondentId ==
                        null
                },
            )
            val created = DateRange(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"))
            assertTrue(
                client.documents
                    .list(DocumentQuery(created = created))
                    .items
                    .all { it.created.monthValue == 11 },
            )
            val byTitle =
                titles(DocumentQuery(ordering = DocumentOrdering(DocumentOrdering.Field.TITLE, descending = false)))()
            assertEquals(byTitle.sortedBy { it.lowercase() }, byTitle)
        }

    @Test
    fun `edits, notes and the trash are kept in memory`() =
        runTest {
            val client = client()
            val document = client.documents.get(1)
            val updated =
                client.documents.update(
                    document,
                    document.toDraft().copy(title = "Renamed", tagIds = listOf(4)),
                )
            assertEquals("Renamed", updated.title)
            assertEquals(listOf(4L), client.documents.get(1).tagIds)

            assertEquals(1, client.documents.addNote(1, "Hello").size)

            client.documents.delete(1)
            assertEquals(13, client.documents.list().count)
            assertEquals(listOf(1L), client.trash.restore(listOf(1)))
            assertEquals(14, client.documents.list().count)
        }

    @Test
    fun `labels count their documents and nest tags`() =
        runTest {
            val client = client()
            val tags = client.tags.listAll()
            assertEquals(8, tags.size)
            assertEquals(3, tags.first { it.name == "Inbox" }.documentCount)
            assertEquals(listOf(3L), tags.first { it.name == "Finance" }.childIds)
            assertEquals(8, client.correspondents.listAll().size)
            assertEquals(3, client.savedViews.listAll().size)
            assertEquals(4, client.customFields.listAll().size)
        }

    @Test
    fun `files and details are served`() =
        runTest {
            val client = client()
            client.documents.openFile(4, DocumentFileKind.THUMBNAIL).use { file ->
                assertEquals("image/png", file.contentType)
                assertTrue(file.readBytes().isNotEmpty())
            }
            assertEquals(listOf(6L), client.documents.suggestions(4).correspondentIds)
            assertEquals(5L, client.documents.nextArchiveSerialNumber())
            assertEquals(1, client.documents.history(4).size)
        }

    @Test
    fun `uploading is refused with a message`() =
        runTest {
            try {
                client().documents.upload(
                    UploadContent("scan.pdf", "application/pdf", open = { ByteArray(1).inputStream() }),
                )
                fail("upload must fail")
            } catch (e: PaperlessException) {
                assertTrue(e.toString(), e.toString().contains("demo"))
            }
        }
}
