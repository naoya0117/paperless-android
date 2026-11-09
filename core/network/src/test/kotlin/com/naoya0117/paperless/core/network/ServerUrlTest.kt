package com.naoya0117.paperless.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    @Test
    fun `defaults to https and adds a trailing slash`() {
        assertEquals("https://paperless.example.com/", ServerUrl.parse(" paperless.example.com ").toString())
    }

    @Test
    fun `keeps sub paths and ports`() {
        assertEquals(
            "http://192.168.0.10:8000/paperless/",
            ServerUrl.parse("http://192.168.0.10:8000/paperless").toString(),
        )
    }

    @Test
    fun `strips a trailing api segment`() {
        assertEquals("https://example.com/paperless/", ServerUrl.parse("https://example.com/paperless/api/").toString())
        assertEquals("https://example.com/", ServerUrl.parse("https://example.com/api").toString())
    }

    @Test
    fun `detects cleartext`() {
        assertTrue(ServerUrl.parse("http://example.com").isCleartext)
        assertFalse(ServerUrl.parse("https://example.com").isCleartext)
    }

    @Test
    fun `rejects invalid input`() {
        listOf("", "ftp://example.com", "https://example.com/?a=b", "https://user:pw@example.com").forEach {
            assertThrows("input: $it", IllegalArgumentException::class.java) { ServerUrl.parse(it) }
        }
    }

    @Test
    fun `contains requires same origin and base path`() {
        val url = ServerUrl.parse("https://example.com/paperless/")
        assertTrue("https://example.com/paperless/api/documents/".toHttpUrl() in url)
        assertFalse("https://example.com/other-app/".toHttpUrl() in url)
        assertFalse("https://example.com/paperless-other/".toHttpUrl() in url)
        assertFalse("http://example.com/paperless/".toHttpUrl() in url)
        assertFalse("https://example.com:8443/paperless/".toHttpUrl() in url)
        assertFalse("https://evil.example/paperless/".toHttpUrl() in url)
    }
}
