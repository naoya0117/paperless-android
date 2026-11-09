package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.ApiVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class SupportedApiVersionsTest {
    @Test
    fun `candidates are tried from the newest version down`() {
        assertEquals(
            listOf(ApiVersion(11), ApiVersion(10), ApiVersion(9)),
            SupportedApiVersions.candidatesIn(ApiVersion(9)..ApiVersion(11)),
        )
    }

    @Test
    fun `the client currently supports v10 only`() {
        assertEquals(listOf(ApiVersion.V10), SupportedApiVersions.candidates)
    }
}
