package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.model.TrustedCertificate
import okhttp3.tls.HeldCertificate
import okhttp3.tls.certificatePem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedCertificatesTest {
    private val first =
        HeldCertificate
            .Builder()
            .commonName("first")
            .build()
            .certificate
    private val second =
        HeldCertificate
            .Builder()
            .commonName("second")
            .build()
            .certificate

    @Test
    fun `reads pem with several certificates`() {
        val pem = first.certificatePem() + second.certificatePem()

        val certificates = TrustedCertificates.parse(pem.encodeToByteArray())

        assertEquals(listOf(TrustedCertificate(first.encoded), TrustedCertificate(second.encoded)), certificates)
    }

    @Test
    fun `reads der`() {
        assertEquals(listOf(TrustedCertificate(first.encoded)), TrustedCertificates.parse(first.encoded))
    }

    @Test
    fun `converts back to x509`() {
        assertEquals(first, TrustedCertificates.toX509(TrustedCertificate(first.encoded)))
    }

    @Test
    fun `other data is rejected`() {
        for (bytes in listOf(ByteArray(0), "hello".encodeToByteArray(), byteArrayOf(0x30, 0x03, 1, 2, 3))) {
            assertTrue(
                bytes.decodeToString(),
                runCatching { TrustedCertificates.parse(bytes) }.exceptionOrNull() is IllegalArgumentException,
            )
        }
    }
}
