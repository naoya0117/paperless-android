package com.naoya0117.paperless.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.naoya0117.paperless.core.model.CustomFieldValue
import com.naoya0117.paperless.core.model.DocumentUpload
import com.naoya0117.paperless.core.model.FieldValue
import com.naoya0117.paperless.core.model.Upload
import com.naoya0117.paperless.core.model.UploadError
import com.naoya0117.paperless.core.model.UploadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class UploadQueueTest {
    private val queue = UploadQueue.inMemory(ApplicationProvider.getApplicationContext(), BundledSQLiteDriver())

    @After
    fun tearDown() = queue.close()

    private fun upload(
        id: String,
        accountId: String = "a",
        createdAt: Long = 0,
    ) = Upload(
        id = id,
        accountId = accountId,
        fileName = "scan.pdf",
        mimeType = "application/pdf",
        size = 10,
        metadata =
            DocumentUpload(
                title = "Scan",
                created = LocalDate.of(2026, 11, 10),
                correspondentId = 2,
                documentTypeId = 3,
                storagePathId = null,
                tagIds = listOf(1, 4),
                archiveSerialNumber = 9,
                customFields = listOf(CustomFieldValue(5, FieldValue.Text("x"))),
            ),
        state = UploadState.QUEUED,
        sentBytes = 0,
        taskId = null,
        documentId = null,
        duplicateOfDocumentId = null,
        errorKind = null,
        error = null,
        createdAt = Instant.ofEpochMilli(createdAt),
    )

    @Test
    fun `uploads round trip per account, newest first`() =
        runTest {
            queue.add(upload("1", createdAt = 1), "/files/1")
            queue.add(upload("2", createdAt = 2), "/files/2")
            queue.add(upload("3", accountId = "b"), "/files/3")

            assertEquals(listOf(upload("2", createdAt = 2), upload("1", createdAt = 1)), queue.observe("a").first())
            assertEquals("/files/1", queue.filePath("1"))
            assertEquals(listOf(upload("3", accountId = "b")), queue.forAccount("b"))
            assertEquals(setOf("/files/1", "/files/2", "/files/3"), queue.allFilePaths())
        }

    @Test
    fun `updates keep the file and fail for removed uploads`() =
        runTest {
            queue.add(upload("1"), "/files/1")
            val done = upload("1").copy(state = UploadState.DONE, taskId = "t", documentId = 12, sentBytes = 10)
            val failed = upload("1").copy(state = UploadState.FAILED, errorKind = UploadError.DUPLICATE, error = "dup")

            queue.update(done)
            queue.setSentBytes("1", 7)

            assertEquals(done.copy(sentBytes = 7), queue.get("1"))
            assertEquals(emptyList<Upload>(), queue.unfinished())
            queue.update(failed)
            assertEquals(failed, queue.get("1"))
            assertEquals("/files/1", queue.filePath("1"))
            queue.remove("1")
            assertNull(queue.get("1"))
            assertFalse(queue.update(done))
        }
}
