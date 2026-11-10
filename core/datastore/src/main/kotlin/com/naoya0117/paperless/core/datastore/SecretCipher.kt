package com.naoya0117.paperless.core.datastore

/** Encrypts secrets before they are written to disk. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** @throws java.security.GeneralSecurityException if the data cannot be decrypted (e.g. the key is gone) */
    fun decrypt(encrypted: ByteArray): ByteArray
}
