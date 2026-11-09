package com.naoya0117.paperless.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HttpHeaderTest {
    @Test
    fun `rejects headers managed by the client`() {
        listOf("Authorization", "accept", "Host").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { HttpHeader(it, "x") }
        }
    }

    @Test
    fun `rejects invalid characters`() {
        assertThrows(IllegalArgumentException::class.java) { HttpHeader("X-Bad Name", "x") }
        assertThrows(IllegalArgumentException::class.java) { HttpHeader("X-Ok", "line\nbreak") }
    }

    @Test
    fun `redacts the value in toString`() {
        assertEquals(
            "HttpHeader(CF-Access-Client-Secret: ***)",
            HttpHeader("CF-Access-Client-Secret", "s3cret").toString(),
        )
    }
}
