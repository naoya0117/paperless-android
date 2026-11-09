package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.ApiVersion
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.CustomFieldDataType
import com.naoya0117.paperless.core.model.CustomFieldDraft
import com.naoya0117.paperless.core.model.DateRange
import com.naoya0117.paperless.core.model.DocumentOrdering
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.DocumentSearch
import com.naoya0117.paperless.core.model.DocumentUpload
import com.naoya0117.paperless.core.model.IdFilter
import com.naoya0117.paperless.core.model.Matching
import com.naoya0117.paperless.core.model.MatchingAlgorithm
import com.naoya0117.paperless.core.model.SelectOption
import com.naoya0117.paperless.core.model.TagDraft
import com.naoya0117.paperless.core.model.TagFilter
import com.naoya0117.paperless.core.model.TaskStatus
import com.naoya0117.paperless.core.model.toDraft
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

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
    fun `an invalid regex is rejected on update`() =
        runTest {
            val admin = client().signIn("admin", "admin-password").client
            val created =
                admin.tags.create(
                    TagDraft("Smoke test regex", matching = Matching(MatchingAlgorithm.ANY, "a(b")),
                )
            try {
                assertFailsWith<PaperlessException.Http.BadRequest> {
                    admin.tags.update(
                        created,
                        created.toDraft().copy(matching = created.matching.copy(algorithm = MatchingAlgorithm.REGEX)),
                    )
                }
            } finally {
                admin.tags.delete(created.id)
            }
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

    @Test
    fun `document filters and search work on the server`() =
        runTest {
            val admin = client().signIn("admin", "admin-password").client
            val tags = admin.tags.listAll().associateBy { it.name }

            suspend fun titles(query: DocumentQuery) =
                admin.documents
                    .list(query)
                    .items
                    .map { it.title }
                    .toSet()

            val financeAndTax = setOf(tags.getValue("Finance").id, tags.getValue("Tax").id)
            assertEquals(
                setOf("Invoice 2026-002"),
                titles(DocumentQuery(tags = TagFilter.Matching(all = financeAndTax))),
            )
            assertEquals(setOf("Contract renewal"), titles(DocumentQuery(correspondent = IdFilter.NotAssigned)))
            assertEquals(setOf("Contract renewal"), titles(DocumentQuery(search = DocumentSearch.Text("renewal"))))
            assertEquals(
                setOf("Invoice 2026-001", "Invoice 2026-002"),
                titles(DocumentQuery(created = DateRange(from = LocalDate.of(2026, 10, 1)))),
            )
            assertEquals(
                setOf("Invoice 2026-001"),
                titles(DocumentQuery(customFieldQuery = """["Due date","exists",true]""")),
            )
            assertEquals(emptyList<Any>(), admin.documents.list(DocumentQuery(page = 99)).items)
            val ordered =
                admin.documents.list(
                    DocumentQuery(ordering = DocumentOrdering(DocumentOrdering.Field.CREATED, descending = false)),
                )
            assertEquals(ordered.items.sortedBy { it.created }, ordered.items)
        }

    @Test
    fun `document update round trip`() =
        runTest {
            val admin = client().signIn("admin", "admin-password").client
            val invoice =
                admin.documents
                    .list(
                        DocumentQuery(search = DocumentSearch.Title("Invoice 2026-001")),
                    ).items
                    .single()
                    .let {
                        admin.documents.get(it.id)
                    }
            val changed =
                admin.documents.update(
                    invoice,
                    invoice.toDraft().copy(title = "Invoice 2026-001 (smoke)", archiveSerialNumber = null),
                )
            try {
                assertEquals("Invoice 2026-001 (smoke)", changed.title)
                assertNull(changed.archiveSerialNumber)
                assertEquals(invoice.tagIds.toSet(), changed.tagIds.toSet())
                assertEquals(invoice.customFields, changed.customFields)
            } finally {
                admin.documents.update(changed, invoice.toDraft())
            }
            assertEquals(invoice.title, admin.documents.get(invoice.id).title)
        }

    @Test
    fun `document files notes and details`() =
        runTest {
            val admin = client().signIn("admin", "admin-password").client
            val invoice =
                admin.documents
                    .list(
                        DocumentQuery(search = DocumentSearch.Title("Invoice 2026-001")),
                    ).items
                    .single()

            val thumbnail =
                admin.documents.openFile(invoice.id, DocumentFileKind.THUMBNAIL).use {
                    it.contentType to it.readBytes()
                }
            assertEquals("image/webp", thumbnail.first)
            assertEquals("RIFF", String(thumbnail.second, 0, 4))
            val preview = admin.documents.openFile(invoice.id, DocumentFileKind.PREVIEW).use { it.readBytes() }
            assertEquals("%PDF", String(preview, 0, 4))
            admin.documents.openFile(invoice.id, DocumentFileKind.ORIGINAL).use { original ->
                assertEquals("Invoice 2026-001.pdf", original.fileName?.substringAfter("ACME Corp "))
                assertTrue("the original is the small uploaded PDF", original.readBytes().size < preview.size)
            }

            val notes = admin.documents.addNote(invoice.id, "Smoke test note")
            try {
                assertEquals("admin", notes.last().user?.username)
                assertEquals(
                    notes.map { it.text },
                    admin.documents
                        .get(invoice.id)
                        .notes!!
                        .map { it.text },
                )
            } finally {
                notes.filter { it.text == "Smoke test note" }.forEach { admin.documents.deleteNote(invoice.id, it.id) }
            }
            assertTrue(
                admin.documents
                    .get(invoice.id)
                    .notes!!
                    .none { it.text == "Smoke test note" },
            )

            assertTrue(admin.documents.metadata(invoice.id).hasArchiveVersion)
            admin.documents.suggestions(invoice.id)
            assertTrue(admin.documents.history(invoice.id).isNotEmpty())
            assertTrue(admin.documents.nextArchiveSerialNumber() > (invoice.archiveSerialNumber ?: 0))
        }

    @Test
    fun `saved views list the same documents as the web ui would`() =
        runTest {
            val admin = client().signIn("admin", "admin-password").client
            val views = admin.savedViews.listAll().associateBy { it.name }

            val invoices = admin.documents.list(views.getValue("Invoices").toDocumentQuery()).items
            assertEquals(listOf("Invoice 2026-002", "Invoice 2026-001"), invoices.map { it.title })
            val noCorrespondent = admin.documents.list(views.getValue("No correspondent").toDocumentQuery()).items
            assertEquals(listOf("Contract renewal"), noCorrespondent.map { it.title })

            val visibility = admin.server.fetchSavedViewVisibility()
            assertEquals(listOf(views.getValue("Invoices").id), visibility.dashboardViewIds)
        }

    @Test
    fun `upload consume and clean up`() =
        runBlocking {
            val session = client().signIn("admin", "admin-password")
            val admin = session.client
            val finance = admin.tags.listAll().single { it.name == "Finance" }
            val pdf = minimalPdf("Smoke upload ${System.nanoTime()}")
            val progress = mutableListOf<Long>()

            val taskId =
                admin.documents.upload(
                    UploadContent("smoke.pdf", "application/pdf", pdf.size.toLong()) { pdf.inputStream() },
                    DocumentUpload(title = "Smoke upload", tagIds = listOf(finance.id)),
                ) { sent, _ -> progress += sent }
            val result = admin.tasks.awaitCompletion(taskId, pollInterval = 500.milliseconds, timeout = 2.minutes)

            assertEquals(pdf.size.toLong(), progress.last())
            val task = (result as TaskWaitResult.Finished).task
            assertEquals(TaskStatus.SUCCESS, task.status)
            val documentId = task.documentId!!
            try {
                val document = admin.documents.get(documentId)
                assertEquals("Smoke upload", document.title)
                assertTrue(finance.id in document.tagIds)
            } finally {
                admin.documents.delete(documentId)
                emptyTrash(session, documentId)
            }

            val unsupported =
                assertFailsWith<PaperlessException.Http.BadRequest> {
                    admin.documents.upload(UploadContent("random.bin") { ByteArray(512) { it.toByte() }.inputStream() })
                }
            assertTrue("document" in unsupported.errors)
        }

    /** The trash API is not part of the client yet; keep the sample data clean with a direct request. */
    private fun emptyTrash(
        session: PaperlessSession,
        documentId: Long,
    ) {
        val request =
            Request
                .Builder()
                .url(
                    session.client.config.serverUrl.httpUrl
                        .resolve("api/trash/")!!,
                ).header("Authorization", "Token ${session.token.value}")
                .header("Accept", "application/json; version=10")
                .post(
                    """{"documents": [$documentId], "action": "empty"}""".toRequestBody(
                        "application/json".toMediaType(),
                    ),
                ).build()
        testBaseClient.newCall(request).execute().use { assertTrue(it.isSuccessful) }
    }

    /** A one-page PDF with a text line (Helvetica), like tools/paperless-dev/capture_v10.py creates. */
    private fun minimalPdf(text: String): ByteArray {
        val content = "BT /F1 14 Tf 72 720 Td ($text) Tj ET"
        val objects =
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length ${content.length} >>\nstream\n$content\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets =
            objects.mapIndexed { index, obj ->
                out.length.also { out.append("${index + 1} 0 obj\n$obj\nendobj\n") }
            }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append("%010d 00000 n \n".format(it)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    private companion object {
        /** Created by tools/paperless-dev/capture_v10.py. */
        const val PASSWORD = "Fixture-Passw0rd!"
    }
}
