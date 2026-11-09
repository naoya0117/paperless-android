package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.ApiVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SupportedApiVersionsTest {
    @Test
    fun `uses the server version when it is within range`() {
        assertEquals(ApiVersion(10), SupportedApiVersions.negotiate(ApiVersion(10)))
    }

    @Test
    fun `caps newer servers at the client maximum`() {
        assertEquals(SupportedApiVersions.MAX, SupportedApiVersions.negotiate(ApiVersion(11)))
    }

    @Test
    fun `rejects servers older than the client minimum`() {
        val e =
            assertThrows(PaperlessException.UnsupportedServerVersion::class.java) {
                SupportedApiVersions.negotiate(ApiVersion(9))
            }
        assertEquals(ApiVersion(9), e.serverMaxApiVersion)
    }
}
