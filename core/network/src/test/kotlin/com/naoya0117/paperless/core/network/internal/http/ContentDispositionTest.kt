package com.naoya0117.paperless.core.network.internal.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentDispositionTest {
    @Test
    fun `prefers the extended file name`() {
        val header =
            "attachment; filename=\"2026-10-01 ACME.pdf\"; filename*=utf-8''2026-10-01%20ACME%20%E8%AB%8B%E6%B1%82%2B1.pdf"
        assertEquals("2026-10-01 ACME 請求+1.pdf", fileNameFromContentDisposition(header))
    }

    @Test
    fun `reads the plain file name`() {
        assertEquals("0000001.webp", fileNameFromContentDisposition("inline; filename=\"0000001.webp\""))
        assertEquals("plain.pdf", fileNameFromContentDisposition("attachment; filename=plain.pdf"))
    }

    @Test
    fun `missing or malformed values are null`() {
        assertNull(fileNameFromContentDisposition(null))
        assertNull(fileNameFromContentDisposition("inline"))
        assertEquals(
            "fallback.pdf",
            fileNameFromContentDisposition("inline; filename*=bogus; filename=\"fallback.pdf\""),
        )
    }

    @Test
    fun `honours quotes and escapes in the plain file name`() {
        assertEquals("ACME; Inc.pdf", fileNameFromContentDisposition("attachment; filename=\"ACME; Inc.pdf\""))
        assertEquals("say \"hi\".pdf", fileNameFromContentDisposition("attachment; filename=\"say \\\"hi\\\".pdf\""))
    }

    @Test
    fun `falls back to the plain name when the extended one is malformed`() {
        assertEquals(
            "ok.pdf",
            fileNameFromContentDisposition("attachment; filename*=utf-8''bad%zz.pdf; filename=\"ok.pdf\""),
        )
    }

    @Test
    fun `strips directories and hidden-file dots`() {
        assertEquals("x.pdf", fileNameFromContentDisposition("attachment; filename*=utf-8''..%2F..%2Fx.pdf"))
        assertEquals("evil.pdf", fileNameFromContentDisposition("attachment; filename=\"..\\\\evil.pdf\""))
        assertNull(fileNameFromContentDisposition("attachment; filename=\"..\""))
    }
}
