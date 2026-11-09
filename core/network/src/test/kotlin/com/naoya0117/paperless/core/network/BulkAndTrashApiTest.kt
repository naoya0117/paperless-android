package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.BulkEdit
import com.naoya0117.paperless.core.model.CustomFieldValue
import com.naoya0117.paperless.core.model.FieldValue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class BulkAndTrashApiTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun client() = server.client { PaperlessClientConfig(it, AuthToken("abc")) }

    @Test
    fun `bulk edits use the server's methods and parameters`() =
        runTest {
            val cases =
                listOf(
                    BulkEdit.SetCorrespondent(null) to
                        """"method":"set_correspondent","parameters":{"correspondent":null}""",
                    BulkEdit.SetDocumentType(4) to """"method":"set_document_type","parameters":{"document_type":4}""",
                    BulkEdit.SetStoragePath(5) to """"method":"set_storage_path","parameters":{"storage_path":5}""",
                    BulkEdit.ModifyTags(add = setOf(1), remove = setOf(2, 3)) to
                        """"method":"modify_tags","parameters":{"add_tags":[1],"remove_tags":[2,3]}""",
                    BulkEdit.ModifyCustomFields(
                        add = listOf(CustomFieldValue(1, FieldValue.Text("2026-11-30"))),
                        remove = setOf(3),
                    ) to
                        """"method":"modify_custom_fields","parameters":{"add_custom_fields":{"1":"2026-11-30"},"remove_custom_fields":[3]}""",
                )
            for ((edit, expected) in cases) {
                server.enqueue(jsonResponse(200, "bulk_edit_ok.json"))
                client().bulk.edit(listOf(1, 2, 1), edit)
                val request = server.takeRequest()
                assertEquals("/api/documents/bulk_edit/", request.url.encodedPath)
                assertEquals("""{"documents":[1,2],$expected}""", request.body?.utf8())
            }
        }

    @Test
    fun `bulk edit validation`() {
        assertThrows(IllegalArgumentException::class.java) { BulkEdit.ModifyTags() }
        assertThrows(IllegalArgumentException::class.java) { BulkEdit.ModifyTags(add = setOf(1), remove = setOf(1)) }
        assertThrows(IllegalArgumentException::class.java) {
            BulkEdit.ModifyCustomFields(add = listOf(CustomFieldValue(1, FieldValue.Empty)), remove = setOf(1))
        }
    }

    @Test
    fun `missing documents are a bad request`() =
        runTest {
            server.enqueue(jsonResponse(400, "error_bulk_edit_documents.json"))

            val e =
                assertFailsWith<PaperlessException.Http.BadRequest> {
                    client().bulk.edit(listOf(999999), BulkEdit.SetCorrespondent(1))
                }

            assertEquals("documents", e.errors.keys.single())
        }

    @Test
    fun `delete and reprocess use their own endpoints`() =
        runTest {
            server.enqueue(jsonResponse(200, "bulk_edit_ok.json"))
            server.enqueue(jsonResponse(200, "bulk_edit_ok.json"))
            val client = client()

            client.bulk.delete(listOf(2))
            client.bulk.reprocess(listOf(3, 4, 3))

            val delete = server.takeRequest()
            assertEquals("/api/documents/delete/", delete.url.encodedPath)
            assertEquals("""{"documents":[2]}""", delete.body?.utf8())
            val reprocess = server.takeRequest()
            assertEquals("/api/documents/reprocess/", reprocess.url.encodedPath)
            assertEquals("""{"documents":[3,4]}""", reprocess.body?.utf8())
        }

    @Test
    fun `empty selections send nothing`() =
        runTest {
            val client = client()
            client.bulk.edit(emptyList(), BulkEdit.SetCorrespondent(1))
            client.bulk.delete(emptyList())
            client.bulk.reprocess(emptyList())
            assertEquals(emptyList<Long>(), client.trash.restore(emptyList()))
            assertEquals(emptyList<Long>(), client.trash.empty(emptyList()))

            assertEquals(0, server.requestCount)
        }

    @Test
    fun `lists restores and empties the trash`() =
        runTest {
            server.enqueue(jsonResponse(200, "trash.json"))
            server.enqueue(jsonResponse(200, "trash_restored.json"))
            server.enqueue(jsonResponse(200, "trash_restored.json"))
            server.enqueue(jsonResponse(200, "trash_restored.json"))
            val client = client()

            val trash = client.trash.list()
            val restored = client.trash.restore(listOf(2))
            client.trash.empty(listOf(2))
            client.trash.emptyAll()

            val document = trash.items.single()
            assertEquals("Contract renewal", document.title)
            assertNotNull(document.deletedAt)
            assertEquals(listOf(2L), restored)
            assertEquals("/api/trash/", server.takeRequest().url.encodedPath)
            assertEquals("""{"action":"restore","documents":[2]}""", server.takeRequest().body?.utf8())
            assertEquals("""{"action":"empty","documents":[2]}""", server.takeRequest().body?.utf8())
            assertEquals("""{"action":"empty"}""", server.takeRequest().body?.utf8())
        }
}
