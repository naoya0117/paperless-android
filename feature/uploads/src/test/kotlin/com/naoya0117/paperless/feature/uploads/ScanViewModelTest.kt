package com.naoya0117.paperless.feature.uploads

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.data.PaperlessClientFactory
import com.naoya0117.paperless.core.data.UploadRepository
import com.naoya0117.paperless.core.data.UploadScheduler
import com.naoya0117.paperless.core.data.UploadStorage
import com.naoya0117.paperless.core.database.UploadQueue
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.SecretCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScanViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var viewModel: ScanViewModel

    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x33).toByte() }.toByteArray()

        override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val accounts =
            AccountRepository(
                AccountStore.create(folder.root.resolve("accounts.json"), XorCipher),
                PaperlessClientFactory(OkHttpClient()),
                { error("no client certificates in this test") },
                {},
            )
        val scheduler =
            object : UploadScheduler {
                override fun schedule(
                    uploadId: String,
                    replace: Boolean,
                ) = Unit

                override fun cancel(uploadId: String) = Unit
            }
        val uploads =
            UploadRepository(
                accounts,
                UploadQueue.inMemory(ApplicationProvider.getApplicationContext(), BundledSQLiteDriver()),
                scheduler,
                UploadStorage(folder.newFolder("uploads")),
            )
        viewModel =
            ScanViewModel(uploads, ApplicationProvider.getApplicationContext(), applicationScope, SavedStateHandle())
    }

    @After
    fun tearDown() {
        applicationScope.cancel()
        Dispatchers.resetMain()
    }

    private fun image(name: String): Uri {
        val file = folder.root.resolve(name)
        file.outputStream().use {
            Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        return Uri.fromFile(file)
    }

    private suspend fun <T> Flow<T>.await(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { first(predicate) } }

    @Test
    fun `picked images become pages that can be turned, moved and removed`() =
        runTest {
            viewModel.addImages(listOf(image("a.jpg"), image("b.jpg"), image("c.jpg")))
            val pages = viewModel.state.await { it.pages.size == 3 && !it.working }.pages

            viewModel.rotate(0)
            viewModel.move(2, -2)
            viewModel.remove(1)

            val now = viewModel.state.value.pages
            assertEquals(listOf(pages[2].file, pages[1].file), now.map { it.file })
            assertEquals(listOf(0, 0), now.map { it.rotation })
            assertTrue(!pages[0].file.exists())
        }

    @Test
    fun `without an account the pages stay and it says so`() =
        runTest {
            viewModel.addImages(listOf(image("a.jpg")))
            viewModel.state.await { it.pages.size == 1 && !it.working }

            viewModel.upload()

            assertEquals(ScanEvent.NotSignedIn, viewModel.events.await { true })
            assertEquals(1, viewModel.state.value.pages.size)
        }

    @Test
    fun `the PDF is named after the title or the time`() {
        assertEquals("Invoice ACME.pdf", ScanViewModel.fileName("  Invoice ACME "))
        assertEquals("a_b.pdf", ScanViewModel.fileName("a/b"))
        assertEquals("Scan 2026-11-10 14.05.pdf", ScanViewModel.fileName("", LocalDateTime.of(2026, 11, 10, 14, 5)))
    }

    @Test
    fun `the PDF embeds the pages as JPEG with a valid cross-reference table`() {
        val file = folder.root.resolve("out.pdf")
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        JpegPdf(file).use { pdf ->
            pdf.addPage(100, 141, 595f, 839f) { it.write(jpeg) }
            pdf.addPage(141, 100, 839f, 595f) { it.write(jpeg) }
            pdf.finish()
        }
        val bytes = file.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(
            text.contains("/Count 2") && text.contains("/Filter /DCTDecode") && text.contains("/Length 0000000007"),
        )
        // Every object offset in the table points at that object.
        val xref = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        val entries =
            text
                .substring(xref)
                .lines()
                .drop(3)
                .takeWhile { it.endsWith(" n ") }
        entries.forEachIndexed { index, entry ->
            val offset = entry.substringBefore(' ').toInt()
            assertTrue(entry, text.startsWith("${index + 1} 0 obj", offset))
        }
        assertEquals(8, entries.size)
    }
}
