package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.HistoryChange
import com.naoya0117.paperless.core.model.NoteAuthor
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class DocumentDetailsApiTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun client() = server.client { PaperlessClientConfig(it, AuthToken("abc")) }

    @Test
    fun `streams files with their name and type`() =
        runTest {
            val bytes = ByteArray(64 * 1024) { it.toByte() }
            val cases =
                mapOf(
                    DocumentFileKind.THUMBNAIL to "/api/documents/1/thumb/",
                    DocumentFileKind.PREVIEW to "/api/documents/1/preview/",
                    DocumentFileKind.DOWNLOAD to "/api/documents/1/download/",
                    DocumentFileKind.ORIGINAL to "/api/documents/1/download/?original=true",
                )
            for ((kind, target) in cases) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/pdf")
                        .addHeader("Content-Disposition", "attachment; filename=\"a.pdf\"; filename*=utf-8''a%20b.pdf")
                        .body(Buffer().write(bytes))
                        .build(),
                )

                client().documents.openFile(1, kind).use { file ->
                    assertEquals("application/pdf", file.contentType)
                    assertEquals("a b.pdf", file.fileName)
                    assertEquals(bytes.size.toLong(), file.contentLength)
                    assertArrayEquals(bytes, file.stream.readBytes())
                }
                assertEquals(kind.name, target, server.takeRequest().target)
            }
        }

    @Test
    fun `file errors are mapped`() =
        runTest {
            server.enqueue(jsonResponse(404, "error_not_found.json"))

            assertFailsWith<PaperlessException.Http.NotFound> {
                client().documents.openFile(
                    9,
                    DocumentFileKind.PREVIEW,
                )
            }
        }

    @Test
    fun `adds and deletes notes`() =
        runTest {
            server.enqueue(jsonResponse(200, "note_added.json"))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("[]")
                    .build(),
            )
            val client = client()

            val notes = client.documents.addNote(1, "Paid on 2026-11-05")
            val remaining = client.documents.deleteNote(1, notes.single().id)

            val add = server.takeRequest()
            assertEquals("POST", add.method)
            assertEquals("""{"note":"Paid on 2026-11-05"}""", add.body?.utf8())
            assertEquals("Paid on 2026-11-05", notes.single().text)
            assertEquals(NoteAuthor(2, "admin", null, null), notes.single().user)
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/api/documents/1/notes/?id=${notes.single().id}", delete.target)
            assertEquals(emptyList<Any>(), remaining)
        }

    @Test
    fun `documents include their notes`() =
        runTest {
            val body =
                """{"id": 1, "created": "2026-01-01", "added": "2026-01-01T00:00:00Z", "modified": "2026-01-01T00:00:00Z",
                "notes": [{"id": 4, "note": "later", "created": "2026-01-03T00:00:00Z", "user": null},
                {"id": 3, "note": "first", "created": "2026-01-02T00:00:00Z", "user": {"id": 2, "username": "admin"}}]}"""
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(body)
                    .build(),
            )

            val notes = client().documents.get(1).notes!!

            assertEquals(listOf("first", "later"), notes.map { it.text })
            assertNull(notes.last().user)
        }

    @Test
    fun `reads metadata suggestions history and the next asn`() =
        runTest {
            server.enqueue(jsonResponse(200, "document_metadata.json"))
            server.enqueue(jsonResponse(200, "document_suggestions.json"))
            server.enqueue(jsonResponse(200, "document_history.json"))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("2")
                    .build(),
            )
            val client = client()

            val metadata = client.documents.metadata(1)
            val suggestions = client.documents.suggestions(1)
            val history = client.documents.history(1)
            val nextAsn = client.documents.nextArchiveSerialNumber()

            assertEquals("invoice.pdf", metadata.originalFileName)
            assertTrue(metadata.hasArchiveVersion)
            assertEquals(64, metadata.originalChecksum.length)
            assertTrue(metadata.archiveProperties.any { it.key == "Producer" })
            assertEquals(listOf(1L), suggestions.tagIds)
            assertEquals(emptyList<LocalDate>(), suggestions.dates)
            assertTrue(history.isNotEmpty())
            val noteAdded = history.first { "Note Added" in it.changes }.changes["Note Added"] as HistoryChange.Values
            assertNull("the server writes \"None\" for no value", noteAdded.old)
            assertTrue(noteAdded.new!!.toLong() > 0)
            assertEquals(history.sortedByDescending { it.timestamp }, history)
            assertEquals(2L, nextAsn)
            assertEquals(
                listOf("metadata", "suggestions", "history", "next_asn"),
                List(4) {
                    server
                        .takeRequest()
                        .url.pathSegments
                        .dropLast(1)
                        .last()
                },
            )
        }

    @Test
    fun `copyTo reports a dropped connection as a paperless exception`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(Buffer().write(ByteArray(256 * 1024)))
                    // Cut the connection once the body has started.
                    .onResponseBody(SocketEffect.ShutdownConnection)
                    .build(),
            )

            client().documents.openFile(1, DocumentFileKind.DOWNLOAD).use { file ->
                assertFailsWith<PaperlessException.Network> { file.copyTo(ByteArrayOutputStream()) }
            }
        }

    @Test
    fun `documents without notes in the response have null notes`() =
        runTest {
            val body =
                """{"id": 1, "created": "2026-01-01", "added": "2026-01-01T00:00:00Z",
                "modified": "2026-01-01T00:00:00Z"}"""
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(body)
                    .build(),
            )

            assertNull(client().documents.get(1).notes)
        }

    @Test
    fun `structured history changes are kept as json`() =
        runTest {
            val body =
                """[{"id": 2, "timestamp": "2026-01-02T00:00:00Z", "action": "update", "actor": null,
                "changes": {"custom_fields": {"type": "custom_field", "field": "Due date", "value": "2026-11-30"}}},
                {"id": 1, "timestamp": "2026-01-03T00:00:00Z", "action": "update", "actor": {"id": 2, "username": "admin"},
                "changes": {"title": ["a", "b"]}}]"""
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(body)
                    .build(),
            )

            val history = client().documents.history(1)

            assertEquals(listOf(1L, 2L), history.map { it.id })
            assertEquals(HistoryChange.Values("a", "b"), history[0].changes["title"])
            assertTrue(history[1].changes["custom_fields"] is HistoryChange.Other)
        }
}
