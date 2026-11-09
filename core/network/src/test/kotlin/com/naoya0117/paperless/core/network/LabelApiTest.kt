package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.CustomFieldDataType
import com.naoya0117.paperless.core.model.CustomFieldDraft
import com.naoya0117.paperless.core.model.Matching
import com.naoya0117.paperless.core.model.MatchingAlgorithm
import com.naoya0117.paperless.core.model.SelectOption
import com.naoya0117.paperless.core.model.SelectOptionDraft
import com.naoya0117.paperless.core.model.TagDraft
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
    fun `listAll follows pages`() =
        runTest {
            val first = """{"count": 3, "next": "${server.url(
                "/api/correspondents/?page=2&page_size=100",
            )}", "previous": null,
                "results": [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}]}"""
            val second = """{"count": 3, "next": null, "previous": "x", "results": [{"id": 3, "name": "C"}]}"""
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
            assertEquals("1", server.takeRequest().url.queryParameter("page"))
            assertEquals("2", server.takeRequest().url.queryParameter("page"))
        }

    @Test
    fun `listAll stops on a server whose pagination never ends`() =
        runTest {
            val endless = """{"count": 1, "next": "x", "previous": null, "results": [{"id": 1, "name": "A"}]}"""
            repeat(5) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(endless)
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
            assertTrue(priority.selectOptions.all { it.id.isNotEmpty() })
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
    fun `creates a tag with only the given values`() =
        runTest {
            server.enqueue(jsonResponse(201, "tag_created.json"))

            val tag = client().tags.create(TagDraft(name = "Temporary", color = "#123456", parentId = 1))

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("""{"name":"Temporary","color":"#123456","parent":1}""", request.body?.utf8())
            assertEquals("#ffffff", tag.textColor)
            assertNull(tag.documentCount)
        }

    @Test
    fun `update sends null to clear the parent`() =
        runTest {
            server.enqueue(jsonResponse(200, "tag_updated.json"))

            val tag =
                client().tags.update(
                    4,
                    TagDraft(name = "Temporary renamed", matching = Matching(MatchingAlgorithm.NONE), parentId = null),
                )

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/api/tags/4/", request.url.encodedPath)
            assertEquals(
                """{"name":"Temporary renamed","match":"","matching_algorithm":0,"is_insensitive":true,"parent":null}""",
                request.body?.utf8(),
            )
            assertNull(tag.parentId)
        }

    @Test
    fun `custom field data type is only sent on create`() =
        runTest {
            server.enqueue(jsonResponse(201, "custom_field_select_created.json"))
            server.enqueue(jsonResponse(201, "custom_field_select_created.json"))
            val draft =
                CustomFieldDraft(
                    name = "Temporary select",
                    dataType = CustomFieldDataType.SELECT,
                    selectOptions =
                        listOf(
                            SelectOptionDraft(id = "keep-me", label = "High"),
                            SelectOptionDraft(null, "Low"),
                        ),
                )

            client().customFields.create(draft)
            client().customFields.update(5, draft)

            val options = """"extra_data":{"select_options":[{"id":"keep-me","label":"High"},{"label":"Low"}]}"""
            assertEquals(
                """{"name":"Temporary select","data_type":"select",$options}""",
                server.takeRequest().body?.utf8(),
            )
            assertEquals("""{"name":"Temporary select",$options}""", server.takeRequest().body?.utf8())
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
}
