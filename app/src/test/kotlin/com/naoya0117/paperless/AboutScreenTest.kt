package com.naoya0117.paperless

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
// Not the app's (Hilt, starting background work): only resources are read.
@Config(application = android.app.Application::class)
class AboutScreenTest {
    @Test
    fun `every library in the app has a license and its text is bundled`() {
        val libraries = loadLibraries(ApplicationProvider.getApplicationContext())

        assertTrue("${libraries.size}", libraries.size > 10)
        libraries.forEach { library ->
            assertTrue(library.id, library.licenses.isNotEmpty())
            library.licenses.forEach { assertNotNull("${library.id}: $it", BundledText.of(it)) }
        }
    }

    @Test
    fun `the bundled GPL is the repository's LICENSE`() {
        // Unit tests run in the module directory.
        assertEquals(File("../LICENSE").readText(), File("src/main/res/raw/license_gpl_3_0.txt").readText())
    }

    @Test
    fun `license texts are reflowed into paragraphs`() {
        val text = "  1. Definitions.\n\n      \"License\" shall mean\n      the terms.\n   \n  END\n"

        assertEquals(
            listOf("1. Definitions.", "\"License\" shall mean the terms.", "END"),
            paragraphs(text, reflow = true),
        )
    }

    @Test
    fun `notices keep their lines`() {
        val text = "Library\r\nCopyright 2024 Someone\r\n\r\nOther\n"

        assertEquals(listOf("Library\nCopyright 2024 Someone", "Other"), paragraphs(text, reflow = false))
    }
}
