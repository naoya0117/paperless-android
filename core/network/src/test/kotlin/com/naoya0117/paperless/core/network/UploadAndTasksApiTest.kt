package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.CustomFieldValue
import com.naoya0117.paperless.core.model.DocumentUpload
import com.naoya0117.paperless.core.model.FieldValue
import com.naoya0117.paperless.core.model.TaskQuery
import com.naoya0117.paperless.core.model.TaskStatus
import com.naoya0117.paperless.core.model.TaskType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds

class UploadAndTasksApiTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun client() = server.client { PaperlessClientConfig(it, AuthToken("abc")) }

    @Test
    fun `uploads a file with metadata and reports progress`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("\"a6b857b0-968b-425d-87bc-fc54e83dbe77\"")
                    .build(),
            )
            val bytes = ByteArray(200 * 1024) { (it % 251).toByte() }
            val progress = mutableListOf<Pair<Long, Long?>>()
            var opened = 0

            val taskId =
                client().documents.upload(
                    UploadContent("請求書.pdf", "application/pdf", bytes.size.toLong()) {
                        opened++
                        bytes.inputStream()
                    },
                    DocumentUpload(
                        title = "Invoice",
                        created = LocalDate.of(2026, 10, 1),
                        correspondentId = 1,
                        tagIds = listOf(1, 2, 1),
                        archiveSerialNumber = 7,
                        customFields =
                            listOf(
                                CustomFieldValue(1, FieldValue.Text("2026-11-30")),
                                CustomFieldValue(4, FieldValue.Empty),
                            ),
                    ),
                ) { sent, total -> progress += sent to total }

            assertEquals("a6b857b0-968b-425d-87bc-fc54e83dbe77", taskId)
            assertEquals(1, opened)
            assertEquals(0L to bytes.size.toLong(), progress.first())
            assertEquals(bytes.size.toLong() to bytes.size.toLong(), progress.last())
            val request = server.takeRequest()
            assertEquals("/api/documents/post_document/", request.url.encodedPath)
            assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data"))
            val body = request.body!!.utf8()

            fun field(name: String) =
                Regex("""name="$name"\r\n(?:Content-Length: \d+\r\n)?\r\n([^\r]*)""")
                    .findAll(body)
                    .map {
                        it.groupValues[1]
                    }.toList()
            assertEquals(listOf("Invoice"), field("title"))
            assertEquals(listOf("2026-10-01"), field("created"))
            assertEquals(listOf("1"), field("correspondent"))
            assertEquals(listOf("1", "2"), field("tags"))
            assertEquals(listOf("7"), field("archive_serial_number"))
            assertEquals(listOf("""{"1":"2026-11-30","4":null}"""), field("custom_fields"))
            assertTrue(body.contains("""name="document"; filename="請求書.pdf""""))
            val file = request.body!!.toByteArray()
            assertTrue(
                "the whole file is sent",
                file.size > bytes.size && file.toList().windowed(16).contains(bytes.take(16)),
            )
        }

    @Test
    fun `unsupported file types are a bad request`() =
        runTest {
            server.enqueue(jsonResponse(400, "error_unsupported_file_type.json"))

            val e =
                assertFailsWith<PaperlessException.Http.BadRequest> {
                    client().documents.upload(UploadContent("random.bin") { ByteArray(8).inputStream() })
                }

            assertTrue(
                e.errors
                    .getValue("document")
                    .single()
                    .endsWith("not supported"),
            )
        }

    @Test
    fun `reads successful and failed tasks`() =
        runTest {
            server.enqueue(jsonResponse(200, "tasks_success.json"))
            server.enqueue(jsonResponse(200, "task_failure.json"))
            val client = client()

            val succeeded =
                client.tasks.list(
                    TaskQuery(status = TaskStatus.SUCCESS, type = TaskType.CONSUME_FILE, pageSize = 2),
                )
            val failed =
                client.tasks
                    .list()
                    .items
                    .single()

            val listRequest = server.takeRequest().url
            assertEquals("success", listRequest.queryParameter("status"))
            assertEquals("consume_file", listRequest.queryParameter("task_type"))
            assertEquals("-date_created", listRequest.queryParameter("ordering"))
            val task = succeeded.items.first()
            assertEquals(TaskStatus.SUCCESS, task.status)
            assertTrue(task.status.isFinished)
            assertEquals(TaskType.CONSUME_FILE, task.type)
            assertTrue(task.documentId != null && task.documentId in task.relatedDocumentIds)
            assertTrue(!task.fileName.isNullOrEmpty())
            assertNull(task.message)
            assertNull(task.duplicateOfDocumentId)

            assertEquals(TaskStatus.FAILURE, failed.status)
            assertNull(failed.documentId)
            assertTrue(failed.message!!.contains("corrupt.pdf"))
        }

    @Test
    fun `get ignores tasks with another task id and unknown tasks`() =
        runTest {
            // The fixture's task has a different task_id than requested.
            server.enqueue(jsonResponse(200, "task_failure.json"))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"count": 0, "next": null, "results": []}""")
                    .build(),
            )
            val client = client()

            assertNull(client.tasks.get("not-the-fixture-task"))
            assertNull(client.tasks.get("unknown"))
        }

    // runBlocking: runTest's virtual time would fire the timeout while waiting for real network I/O.
    @Test
    fun `awaitCompletion polls until the task is finished`() =
        runBlocking {
            fun task(status: String) =
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"count": 1, "next": null, "results": [{"id": 1, "task_id": "t", "status": "$status"}]}""")
                    .build()
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("""{"count": 0, "next": null, "results": []}""")
                    .build(),
            )
            server.enqueue(task("pending"))
            server.enqueue(task("started"))
            server.enqueue(task("success"))

            val done = client().tasks.awaitCompletion("t", pollInterval = 1.milliseconds)

            assertEquals(TaskStatus.SUCCESS, (done as TaskWaitResult.Finished).task.status)
            assertEquals(4, server.requestCount)
            assertEquals("t", server.takeRequest().url.queryParameter("task_id"))
        }

    @Test
    fun `awaitCompletion returns the last state on timeout`() =
        runBlocking {
            repeat(50) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(
                            """{"count": 1, "next": null, "results": [{"id": 1, "task_id": "t", "status": "STARTED"}]}""",
                        ).build(),
                )
            }

            val result = client().tasks.awaitCompletion("t", pollInterval = 10.milliseconds, timeout = 30.milliseconds)

            val last = (result as TaskWaitResult.TimedOut).lastSeen!!
            assertEquals(TaskStatus.STARTED, last.status)
            assertFalse(last.status.isFinished)
        }

    @Test
    fun `acknowledges tasks`() =
        runTest {
            server.enqueue(jsonResponse(200, "tasks_acknowledged.json"))
            val client = client()

            assertEquals(1, client.tasks.acknowledge(listOf(18, 18)))
            assertEquals(0, client.tasks.acknowledge(emptyList()))

            val request = server.takeRequest()
            assertEquals("/api/tasks/acknowledge/", request.url.encodedPath)
            assertEquals("""{"tasks":[18]}""", request.body?.utf8())
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `awaitCompletion keeps polling through transient failures`() =
        runBlocking {
            server.enqueue(MockResponse.Builder().code(502).build())
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(429)
                    .addHeader("Retry-After", "0")
                    .build(),
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(
                        """{"count": 1, "next": null, "results": [{"id": 1, "task_id": "t", "status": "success",
                        "result_data": {"document_id": 9}}]}""",
                    ).build(),
            )

            val done = client().tasks.awaitCompletion("t", pollInterval = 1.milliseconds) as TaskWaitResult.Finished

            assertEquals(9L, done.task.documentId)
        }

    @Test
    fun `awaitCompletion stops on errors that polling cannot fix`() =
        runBlocking<Unit> {
            server.enqueue(jsonResponse(401, "error_invalid_token.json"))

            assertFailsWith<PaperlessException.Http.Unauthorized> {
                client().tasks.awaitCompletion("t", pollInterval = 1.milliseconds)
            }
        }

    @Test
    fun `duplicates and stopped consumption carry no document id`() =
        runTest {
            val body =
                """{"count": 2, "next": null, "results": [
                {"id": 1, "task_id": "a", "status": "failure", "result_data": {"duplicate_of": 3, "duplicate_in_trash": false},
                 "related_document_ids": [3]},
                {"id": 2, "task_id": "b", "status": "success", "result_data": {"reason": "Barcode splitting complete!"}}]}"""
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(body)
                    .build(),
            )

            val (duplicate, stopped) = client().tasks.list().items

            assertNull(duplicate.documentId)
            assertEquals(3L, duplicate.duplicateOfDocumentId)
            assertNull(stopped.documentId)
            assertEquals("Barcode splitting complete!", stopped.message)
        }

    @Test
    fun `file and callback failures are local file errors`() =
        runTest {
            repeat(3) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body("\"t\"")
                        .build(),
                )
            }
            val client = client()

            val unreadable = UploadContent("gone.pdf") { throw SecurityException("permission revoked") }
            val wrongLength = UploadContent("short.pdf", contentLength = 100) { ByteArray(10).inputStream() }
            val content = UploadContent("ok.pdf") { ByteArray(10).inputStream() }

            assertFailsWith<PaperlessException.LocalFile> { client.documents.upload(unreadable) }
            assertFailsWith<PaperlessException.LocalFile> { client.documents.upload(wrongLength) }
            assertFailsWith<PaperlessException.LocalFile> {
                client.documents.upload(content) { _, _ -> throw IllegalStateException("touched a view") }
            }
        }

    @Test
    fun `duplicate custom fields are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            DocumentUpload(
                customFields =
                    listOf(
                        CustomFieldValue(1, FieldValue.Empty),
                        CustomFieldValue(1, FieldValue.Bool(true)),
                    ),
            )
        }
    }
}
