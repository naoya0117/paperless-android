package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.CustomFieldValue
import com.naoya0117.paperless.core.model.DateRange
import com.naoya0117.paperless.core.model.DocumentOrdering
import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.model.DocumentSearch
import com.naoya0117.paperless.core.model.FieldValue
import com.naoya0117.paperless.core.model.IdFilter
import com.naoya0117.paperless.core.model.TagFilter
import com.naoya0117.paperless.core.model.toDraft
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class DocumentsApiTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun client() = server.client { PaperlessClientConfig(it, AuthToken("abc")) }

    @Test
    fun `reads documents`() =
        runTest {
            server.enqueue(jsonResponse(200, "documents.json"))

            val page = client().documents.list()

            val request = server.takeRequest()
            assertEquals("/api/documents/", request.url.encodedPath)
            assertEquals("true", request.url.queryParameter("truncate_content"))
            assertEquals(3, page.count)
            val invoice = page.items.single { it.title == "Invoice 2026-001" }
            assertEquals(LocalDate.of(2026, 10, 1), invoice.created)
            assertTrue(invoice.added.isBefore(Instant.now()))
            assertEquals(1L, invoice.archiveSerialNumber)
            assertEquals("application/pdf", invoice.mimeType)
            assertEquals(
                listOf(FieldValue.Text("2026-11-30"), FieldValue.Text("EUR120.00")),
                invoice.customFields.map { it.value },
            )
            assertTrue(invoice.userCanChange)
            assertNull(invoice.searchHit)
            assertNull(invoice.deletedAt)
        }

    @Test
    fun `builds filter and search parameters`() =
        runTest {
            server.enqueue(jsonResponse(200, "documents.json"))

            client().documents.list(
                DocumentQuery(
                    page = 2,
                    pageSize = 50,
                    search = DocumentSearch.Text("invoice"),
                    tags = TagFilter.Matching(all = setOf(1, 2), anyOf = setOf(3), none = setOf(4)),
                    correspondent = IdFilter.AnyOf(setOf(1)),
                    documentType = IdFilter.NotAssigned,
                    storagePath = IdFilter.NoneOf(setOf(5, 6)),
                    created = DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
                    added = DateRange(from = LocalDate.of(2026, 11, 1)),
                    archiveSerialNumber = 7,
                    inInbox = true,
                    customFieldQuery = """["Due date","exists",true]""",
                    ordering = DocumentOrdering(DocumentOrdering.Field.TITLE, descending = false),
                    truncateContent = false,
                ),
            )

            val url = server.takeRequest().url
            val expected =
                mapOf(
                    "page" to "2",
                    "page_size" to "50",
                    "text" to "invoice",
                    "tags__id__all" to "1,2",
                    "tags__id__in" to "3",
                    "tags__id__none" to "4",
                    "correspondent__id__in" to "1",
                    "document_type__isnull" to "true",
                    "storage_path__id__none" to "5,6",
                    "created__gte" to "2026-01-01",
                    "created__lte" to "2026-12-31",
                    "added__date__gte" to "2026-11-01",
                    "archive_serial_number" to "7",
                    "is_in_inbox" to "true",
                    "custom_field_query" to """["Due date","exists",true]""",
                    "ordering" to "title",
                )
            assertEquals(expected, url.queryParameterNames.associateWith { url.queryParameter(it) })
        }

    @Test
    fun `search kinds map to their parameters`() =
        runTest {
            val cases =
                listOf(
                    DocumentSearch.Title("invoice") to ("title_search" to "invoice"),
                    DocumentSearch.Advanced("tag:finance") to ("query" to "tag:finance"),
                    DocumentSearch.MoreLike(1) to ("more_like_id" to "1"),
                )
            for ((search, parameter) in cases) {
                server.enqueue(jsonResponse(200, "documents_more_like.json"))
                client().documents.list(DocumentQuery(search = search))
                assertEquals(parameter.second, server.takeRequest().url.queryParameter(parameter.first))
            }
        }

    @Test
    fun `untagged filter`() =
        runTest {
            server.enqueue(jsonResponse(200, "documents.json"))

            client().documents.list(DocumentQuery(tags = TagFilter.Untagged))

            assertEquals("false", server.takeRequest().url.queryParameter("is_tagged"))
        }

    @Test
    fun `search results carry the hit`() =
        runTest {
            server.enqueue(jsonResponse(200, "documents_search_text.json"))

            val hits = client().documents.list(DocumentQuery(search = DocumentSearch.Text("invoice"))).items

            assertEquals(listOf(1, 2), hits.map { it.searchHit!!.rank })
            assertTrue(
                hits
                    .first()
                    .searchHit!!
                    .highlights!!
                    .contains("<b>Invoice</b>"),
            )
        }

    @Test
    fun `search text is validated`() {
        assertThrows(IllegalArgumentException::class.java) { DocumentSearch.Text(" ") }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentSearch.Advanced(
                "x".repeat(
                    DocumentSearch.MAX_LENGTH + 1,
                ),
            )
        }
    }

    @Test
    fun `update sends only what changed`() =
        runTest {
            server.enqueue(jsonResponse(200, "document.json"))
            server.enqueue(jsonResponse(200, "document_updated.json"))
            val client = client()
            val current = client.documents.get(1)
            server.takeRequest()

            val updated =
                client.documents.update(
                    current,
                    current.toDraft().copy(
                        title = "Invoice 2026-001 (paid)",
                        correspondentId = null,
                        tagIds = current.tagIds.reversed(),
                        customFields = current.customFields + CustomFieldValue(9, FieldValue.Empty),
                    ),
                )

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            val customFields =
                current.customFields.joinToString(",") {
                    """{"field":${it.fieldId},"value":"${(it.value as FieldValue.Text).value}"}"""
                }
            assertEquals(
                """{"title":"Invoice 2026-001 (paid)","correspondent":null,""" +
                    """"custom_fields":[$customFields,{"field":9,"value":null}]}""",
                request.body?.utf8(),
            )
            assertEquals("Invoice 2026-001 (paid)", updated.title)
            assertTrue("update responses carry permissions instead of user_can_change", updated.userCanChange)
        }

    @Test
    fun `update without changes sends no request`() =
        runTest {
            server.enqueue(jsonResponse(200, "document.json"))
            val client = client()
            val current = client.documents.get(1)

            assertEquals(current, client.documents.update(current, current.toDraft()))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `reads every kind of custom field value`() =
        runTest {
            val body =
                """{"id": 5, "created": "2026-01-01", "added": "2026-01-01T00:00:00Z", "modified": "2026-01-01T00:00:00+09:00",
                "custom_fields": [{"field": 1, "value": null}, {"field": 2, "value": 42}, {"field": 3, "value": 1.5},
                {"field": 4, "value": true}, {"field": 5, "value": [7, 8]}, {"field": 6, "value": "x"}]}"""
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(body)
                    .build(),
            )

            val document = client().documents.get(5)

            assertEquals(
                listOf(
                    FieldValue.Empty,
                    FieldValue.Integer(42),
                    FieldValue.Decimal(1.5),
                    FieldValue.Bool(true),
                    FieldValue.DocumentLinks(listOf(7, 8)),
                    FieldValue.Text("x"),
                ),
                document.customFields.map { it.value },
            )
            assertEquals(Instant.parse("2025-12-31T15:00:00Z"), document.modified)
            assertTrue("missing user_can_change and permissions means read-only", !document.userCanChange)
        }

    @Test
    fun `invalid custom field query is a bad request`() =
        runTest {
            server.enqueue(jsonResponse(400, "error_invalid_custom_field_query.json"))

            val e =
                assertFailsWith<PaperlessException.Http.BadRequest> {
                    client().documents.list(DocumentQuery(customFieldQuery = """["nope"]"""))
                }

            assertEquals(listOf("Invalid custom field query expression"), e.errors["custom_field_query"])
        }

    @Test
    fun `delete moves the document to the trash`() =
        runTest {
            server.enqueue(MockResponse.Builder().code(204).build())

            client().documents.delete(3)

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/documents/3/", request.url.encodedPath)
        }

    @Test
    fun `values in unknown forms are written back unchanged`() =
        runTest {
            val body =
                """{"id": 5, "created": "2026-01-01", "added": "2026-01-01T00:00:00Z", "modified": "2026-01-01T00:00:00Z",
                "custom_fields": [{"field": 1, "value": {"lat": 1.5}}, {"field": 2, "value": [7, "x"]}]}"""
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(body)
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(body)
                    .build(),
            )
            val client = client()
            val document = client.documents.get(5)
            server.takeRequest()

            assertEquals(
                listOf(FieldValue.Unknown("""{"lat":1.5}"""), FieldValue.Unknown("""[7,"x"]""")),
                document.customFields.map { it.value },
            )
            client.documents.update(
                document,
                document.toDraft().copy(
                    customFields =
                        document.customFields + CustomFieldValue(3, FieldValue.Bool(true)),
                ),
            )
            assertEquals(
                """{"custom_fields":[{"field":1,"value":{"lat":1.5}},{"field":2,"value":[7,"x"]},{"field":3,"value":true}]}""",
                server.takeRequest().body?.utf8(),
            )
        }

    @Test
    fun `reordered custom fields are not a change`() =
        runTest {
            server.enqueue(jsonResponse(200, "document.json"))
            val client = client()
            val current = client.documents.get(1)

            client.documents.update(current, current.toDraft().copy(customFields = current.customFields.reversed()))

            assertEquals(1, server.requestCount)
        }

    @Test
    fun `an unparseable date is an invalid response`() =
        runTest {
            val body =
                """{"id": 5, "created": "someday", "added": "2026-01-01T00:00:00Z",
                "modified": "2026-01-01T00:00:00Z"}"""
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(body)
                    .build(),
            )

            assertFailsWith<PaperlessException.InvalidResponse> { client().documents.get(5) }
        }

    @Test
    fun `a page beyond the end is empty`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(404)
                    .body("""{"detail": "Invalid page."}""")
                    .build(),
            )
            server.enqueue(jsonResponse(404, "error_not_found.json"))

            val page = client().documents.list(DocumentQuery(page = 9))

            assertEquals(emptyList<Any>(), page.items)
            assertTrue(!page.hasNext)
            assertFailsWith<PaperlessException.Http.NotFound> { client().documents.list(DocumentQuery(page = 1)) }
        }

    @Test
    fun `filters and values are validated`() {
        assertThrows(IllegalArgumentException::class.java) { IdFilter.AnyOf(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { IdFilter.NoneOf(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { FieldValue.Decimal(Double.NaN) }
        // 4096 emoji are 8192 UTF-16 units but 4096 characters for the server.
        DocumentSearch.Text("😀".repeat(DocumentSearch.MAX_LENGTH))
    }
}
