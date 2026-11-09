package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.CustomFieldDataType
import com.naoya0117.paperless.core.model.CustomFieldDraft
import com.naoya0117.paperless.core.model.Matching
import com.naoya0117.paperless.core.model.MatchingAlgorithm
import com.naoya0117.paperless.core.model.SelectOption
import com.naoya0117.paperless.core.model.TagDraft
import com.naoya0117.paperless.core.model.toDraft
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
import java.time.LocalDate

class LabelApiTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun client() = server.client { PaperlessClientConfig(it, AuthToken("abc")) }

    @Test
    fun `lists tags with hierarchy`() =
        runTest {
            server.enqueue(jsonResponse(200, "tags.json"))

            val page =
                client().tags.list(
                    ListQuery(
                        nameContains = "fin",
                        ordering = ListQuery.Ordering(ListQuery.Ordering.Field.NAME, descending = true),
                    ),
                )

            val request = server.takeRequest()
            assertEquals("/api/tags/", request.url.encodedPath)
            assertEquals("1", request.url.queryParameter("page"))
            assertEquals("25", request.url.queryParameter("page_size"))
            assertEquals("fin", request.url.queryParameter("name__icontains"))
            assertEquals("-name", request.url.queryParameter("ordering"))

            val finance = page.items.single { it.name == "Finance" }
            assertEquals(Matching(MatchingAlgorithm.ANY, "invoice bill", isInsensitive = true), finance.matching)
            assertEquals("#a6cee3", finance.color)
            assertEquals(0, finance.documentCount)
            assertNull(finance.parentId)
            val tax = page.items.single { it.name == "Tax" }
            assertEquals(listOf(tax.id), finance.childIds)
            assertEquals(finance.id, tax.parentId)
            assertTrue(page.items.single { it.name == "Inbox" }.isInboxTag)
            assertEquals(3, page.count)
            assertFalse(page.hasNext)
        }

    @Test
    fun `listAll follows pages and removes duplicates`() =
        runTest {
            val first = page(count = 3, next = "x", """{"id": 1, "name": "A"}, {"id": 2, "name": "B"}""")
            val second = page(count = 3, next = null, """{"id": 2, "name": "B"}, {"id": 3, "name": "C"}""")
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(first)
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(second)
                    .build(),
            )

            val all = client().correspondents.listAll()

            assertEquals(listOf("A", "B", "C"), all.map { it.name })
            val request = server.takeRequest()
            assertEquals("1", request.url.queryParameter("page"))
            assertEquals("${ListQuery.MAX_PAGE_SIZE}", request.url.queryParameter("page_size"))
            assertEquals("true", request.url.queryParameter("last_correspondence"))
            assertEquals("2", server.takeRequest().url.queryParameter("page"))
        }

    @Test
    fun `listAll ends when a later page disappeared`() =
        runTest {
            val first = page(count = 2, next = "x", """{"id": 1, "name": "A"}""")
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(first)
                    .build(),
            )
            server.enqueue(jsonResponse(404, "error_not_found.json"))

            assertEquals(listOf("A"), client().tags.listAll().map { it.name })
        }

    @Test
    fun `listAll stops on a server whose pagination never ends`() =
        runTest {
            var id = 0
            repeat(5) {
                // The count grows with every page, so a limit recomputed per page would never trip.
                val body = page(count = (it + 1) * 2000, next = "x", """{"id": ${++id}, "name": "A$id"}""")
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(body)
                        .build(),
                )
            }

            assertFailsWith<PaperlessException.InvalidResponse> { client().correspondents.listAll() }
        }

    @Test
    fun `reads the other label types`() =
        runTest {
            server.enqueue(jsonResponse(200, "correspondents.json"))
            server.enqueue(jsonResponse(200, "document_types.json"))
            server.enqueue(jsonResponse(200, "storage_paths.json"))
            val client = client()

            val correspondent =
                client.correspondents
                    .list()
                    .items
                    .single()
            val documentType =
                client.documentTypes
                    .list()
                    .items
                    .single()
            val storagePath =
                client.storagePaths
                    .list()
                    .items
                    .single()

            assertEquals("ACME Corp", correspondent.name)
            assertEquals(MatchingAlgorithm.AUTO, correspondent.matching.algorithm)
            assertEquals(MatchingAlgorithm.NONE, documentType.matching.algorithm)
            assertEquals("{{ created_year }}/{{ title }}", storagePath.path)
            assertEquals(
                listOf("/api/correspondents/", "/api/document_types/", "/api/storage_paths/"),
                List(3) { server.takeRequest().url.encodedPath },
            )
        }

    @Test
    fun `reads custom fields`() =
        runTest {
            server.enqueue(jsonResponse(200, "custom_fields.json"))

            val fields =
                client()
                    .customFields
                    .list()
                    .items
                    .associateBy { it.name }

            assertEquals(CustomFieldDataType.DATE, fields.getValue("Due date").dataType)
            assertEquals("EUR", fields.getValue("Amount").defaultCurrency)
            val priority = fields.getValue("Priority")
            assertEquals(CustomFieldDataType.SELECT, priority.dataType)
            assertEquals(listOf("High", "Low"), priority.selectOptions.map(SelectOption::label))
            assertTrue(priority.selectOptions.all { !it.id.isNullOrEmpty() })
        }

    @Test
    fun `unknown matching algorithms and data types are kept`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"id": 9, "name": "X", "matching_algorithm": 42}""")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"id": 9, "name": "Y", "data_type": "geo"}""")
                    .build(),
            )

            val tag = client().tags.get(9)
            val field = client().customFields.get(9)

            assertEquals(MatchingAlgorithm(42), tag.matching.algorithm)
            assertFalse(tag.matching.algorithm.isKnown)
            assertEquals(CustomFieldDataType("geo"), field.dataType)
        }

    @Test
    fun `create sends the whole draft`() =
        runTest {
            server.enqueue(jsonResponse(201, "tag_created.json"))

            val tag = client().tags.create(TagDraft(name = "Temporary", color = "#123456", parentId = 1))

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals(
                """{"name":"Temporary","color":"#123456","match":"","matching_algorithm":1,"is_insensitive":true,""" +
                    """"is_inbox_tag":false,"parent":1}""",
                request.body?.utf8(),
            )
            assertEquals("#ffffff", tag.textColor)
            assertNull(tag.documentCount)
        }

    @Test
    fun `update sends only what changed and null to clear the parent`() =
        runTest {
            server.enqueue(jsonResponse(201, "tag_created.json"))
            server.enqueue(jsonResponse(200, "tag_updated.json"))
            val client = client()
            val current = client.tags.create(TagDraft("Temporary"))
            server.takeRequest()

            val updated =
                client.tags.update(
                    current,
                    current.toDraft().copy(name = "Temporary renamed", parentId = null),
                )

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/tags/${current.id}/", request.url.encodedPath)
            assertEquals("""{"name":"Temporary renamed","parent":null}""", request.body?.utf8())
            assertNull(updated.parentId)
        }

    @Test
    fun `update keeps the parent and the name when they did not change`() =
        runTest {
            server.enqueue(jsonResponse(200, "tag_child.json"))
            server.enqueue(jsonResponse(200, "tag_child.json"))
            val client = client()
            val tax = client.tags.get(2)
            server.takeRequest()

            client.tags.update(tax, tax.toDraft().copy(color = "#000000", matching = Matching(MatchingAlgorithm.AUTO)))

            assertEquals("""{"color":"#000000","matching_algorithm":6}""", server.takeRequest().body?.utf8())
        }

    @Test
    fun `update without changes sends no request`() =
        runTest {
            server.enqueue(jsonResponse(200, "tag_child.json"))
            val client = client()
            val tax = client.tags.get(2)

            assertEquals(tax, client.tags.update(tax, tax.toDraft()))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `custom fields send the data type on create and the options on every select update`() =
        runTest {
            server.enqueue(jsonResponse(201, "custom_field_select_created.json"))
            server.enqueue(jsonResponse(200, "custom_field_select_created.json"))
            val client = client()
            val created =
                client.customFields.create(
                    CustomFieldDraft(
                        "Temporary select",
                        CustomFieldDataType.SELECT,
                        listOf(SelectOption(null, "High"), SelectOption(null, "Low")),
                    ),
                )
            assertEquals(
                """{"name":"Temporary select","data_type":"select","extra_data":{"select_options":[{"label":"High"},{"label":"Low"}]}}""",
                server.takeRequest().body?.utf8(),
            )

            client.customFields.update(created, created.toDraft().copy(name = "Renamed"))

            val options = created.selectOptions.joinToString(",") { """{"id":"${it.id}","label":"${it.label}"}""" }
            assertEquals(
                """{"name":"Renamed","extra_data":{"select_options":[$options]}}""",
                server.takeRequest().body?.utf8(),
            )
        }

    @Test
    fun `clearing the default currency sends null`() =
        runTest {
            server.enqueue(jsonResponse(200, "custom_fields.json"))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(
                        200,
                    ).body("""{"id": 3, "name": "Amount", "data_type": "monetary"}""")
                    .build(),
            )
            val client = client()
            val amount =
                client.customFields
                    .list()
                    .items
                    .single { it.name == "Amount" }
            server.takeRequest()

            client.customFields.update(amount, amount.toDraft().copy(defaultCurrency = null))

            assertEquals("""{"extra_data":{"default_currency":null}}""", server.takeRequest().body?.utf8())
        }

    @Test
    fun `the data type of a custom field cannot be changed`() =
        runTest {
            server.enqueue(jsonResponse(200, "custom_fields.json"))
            val client = client()
            val dueDate =
                client.customFields
                    .list()
                    .items
                    .single { it.name == "Due date" }

            assertFailsWith<IllegalArgumentException> {
                client.customFields.update(dueDate, CustomFieldDraft("Due date", CustomFieldDataType.STRING))
            }
        }

    @Test
    fun `delete accepts an empty 204`() =
        runTest {
            server.enqueue(MockResponse.Builder().code(204).build())

            client().documentTypes.delete(7)

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/document_types/7/", request.url.encodedPath)
        }

    @Test
    fun `errors are mapped`() =
        runTest {
            server.enqueue(jsonResponse(404, "error_not_found.json"))
            server.enqueue(jsonResponse(400, "error_duplicate_name.json"))

            assertFailsWith<PaperlessException.Http.NotFound> { client().tags.get(4) }
            val e = assertFailsWith<PaperlessException.Http.BadRequest> { client().tags.create(TagDraft("Finance")) }
            assertEquals(listOf("Object violates owner / name unique constraint"), e.errors["error"])
        }

    @Test
    fun `reads a single tag and the last correspondence date`() =
        runTest {
            server.enqueue(jsonResponse(200, "tag_child.json"))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(
                        200,
                    ).body("""{"id": 1, "name": "A", "last_correspondence": "2026-10-01"}""")
                    .build(),
            )

            assertEquals("Tax", client().tags.get(2).name)
            assertEquals(LocalDate.of(2026, 10, 1), client().correspondents.get(1).lastCorrespondence)
        }

    private fun page(
        count: Int,
        next: String?,
        results: String,
    ): String = """{"count": $count, "next": ${next?.let { "\"$it\"" }}, "previous": null, "results": [$results]}"""
}
