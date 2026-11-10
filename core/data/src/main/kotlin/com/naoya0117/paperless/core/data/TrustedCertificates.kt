package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.model.TrustedCertificate
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

object TrustedCertificates {
    /**
     * Reads certificates to trust from a file's contents: PEM (one or more certificates) or DER.
     *
     * @throws IllegalArgumentException if [bytes] contain no X.509 certificate
     */
    fun parse(bytes: ByteArray): List<TrustedCertificate> {
        val certificates =
            try {
                factory().generateCertificates(ByteArrayInputStream(bytes))
            } catch (e: CertificateException) {
                throw IllegalArgumentException("Not an X.509 certificate", e)
            }
        require(certificates.isNotEmpty()) { "Not an X.509 certificate" }
        return certificates.map { TrustedCertificate(it.encoded) }
    }

    /** @throws IllegalArgumentException if [certificate] is not a valid X.509 certificate */
    fun toX509(certificate: TrustedCertificate): X509Certificate =
        try {
            factory().generateCertificate(ByteArrayInputStream(certificate.der)) as X509Certificate
        } catch (e: CertificateException) {
            throw IllegalArgumentException("Not an X.509 certificate", e)
        }

    private fun factory() = CertificateFactory.getInstance("X.509")
}
