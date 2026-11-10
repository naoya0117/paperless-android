package com.naoya0117.paperless.core.model

/**
 * TLS settings of one account. Neither part is secret: the private key stays in the Android KeyChain.
 *
 * @property clientCertificateAlias KeyChain alias of the client certificate for mutual TLS
 * @property trustedCertificates trusted in addition to the device's trust store
 */
data class AccountTls(
    val clientCertificateAlias: String? = null,
    val trustedCertificates: List<TrustedCertificate> = emptyList(),
)

/** A DER-encoded X.509 certificate. Parse with `TrustedCertificates.parse` in `:core:data`. */
class TrustedCertificate(
    der: ByteArray,
) {
    private val bytes = der.copyOf()

    val der: ByteArray get() = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is TrustedCertificate && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "TrustedCertificate(${bytes.size} bytes)"
}
