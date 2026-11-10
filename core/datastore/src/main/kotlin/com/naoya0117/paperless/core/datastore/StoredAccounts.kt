package com.naoya0117.paperless.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** The file format. Secrets are Base64 of [SecretCipher] output. Add fields with defaults only. */
@Serializable
internal data class StoredAccounts(
    val activeId: String? = null,
    val accounts: List<StoredAccount> = emptyList(),
)

@Serializable
internal data class StoredAccount(
    val id: String,
    val serverUrl: String,
    val userId: Long,
    val username: String,
    val displayName: String? = null,
    val apiVersion: Int,
    val serverVersion: String? = null,
    val encryptedToken: String,
    val customHeaders: List<StoredHeader> = emptyList(),
)

@Serializable
internal data class StoredHeader(
    val name: String,
    val encryptedValue: String,
)

internal object StoredAccountsSerializer : Serializer<StoredAccounts> {
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue: StoredAccounts = StoredAccounts()

    override suspend fun readFrom(input: InputStream): StoredAccounts =
        try {
            json.decodeFromString(StoredAccounts.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Unreadable accounts file", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Unreadable accounts file", e)
        }

    override suspend fun writeTo(
        t: StoredAccounts,
        output: OutputStream,
    ) {
        output.write(json.encodeToString(StoredAccounts.serializer(), t).encodeToByteArray())
    }
}
