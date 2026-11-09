package com.naoya0117.paperless.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthTokenTest {
    @Test
    fun `rejects values that cannot be sent in a header`() {
        listOf("", "abc\n", "a b", "tökén").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { AuthToken(it) }
        }
    }

    @Test
    fun `is redacted in toString`() {
        assertEquals("AuthToken(***)", AuthToken("0123abcd").toString())
    }
}
