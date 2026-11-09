package com.naoya0117.paperless.core.network.internal.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RetryAfterTest {
    private val now = Instant.parse("2026-10-21T07:27:00Z")

    @Test
    fun `parses delay seconds`() {
        assertEquals(42.seconds, parseRetryAfter(" 42 ", now))
    }

    @Test
    fun `parses an http date`() {
        assertEquals(60.seconds, parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT", now))
    }

    @Test
    fun `a date in the past means retry now`() {
        assertEquals(Duration.ZERO, parseRetryAfter("Wed, 21 Oct 2026 07:00:00 GMT", now))
    }

    @Test
    fun `missing or invalid values are null`() {
        assertNull(parseRetryAfter(null, now))
        assertNull(parseRetryAfter("", now))
        assertNull(parseRetryAfter("soon", now))
    }
}
