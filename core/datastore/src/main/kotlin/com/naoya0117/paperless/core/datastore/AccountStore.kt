package com.naoya0117.paperless.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.naoya0117.paperless.core.model.Account
import com.naoya0117.paperless.core.model.ApiVersion
import com.naoya0117.paperless.core.model.AuthToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.security.GeneralSecurityException
import java.util.Base64

/** The accounts on this device and which one is active. */
data class AccountsState(
    val accounts: List<Account>,
    val activeAccountId: String?,
) {
    val activeAccount: Account? get() = accounts.firstOrNull { it.id == activeAccountId }
}

/**
 * Persists accounts with their secrets encrypted by [cipher]. Non-secret data is readable without
 * decrypting; secrets are only decrypted by [credentials].
 */
class AccountStore internal constructor(
    private val dataStore: DataStore<StoredAccounts>,
    private val cipher: SecretCipher,
) {
    val state: Flow<AccountsState> =
        dataStore.data.map { stored ->
            AccountsState(stored.accounts.map { it.toAccount() }, stored.activeId)
        }

    /** Adds or replaces the account (matched by [Account.id]) and makes it active. */
    suspend fun save(
        account: Account,
        credentials: AccountCredentials,
    ) {
        val stored = account.toStored(credentials)
        dataStore.updateData { current ->
            current.copy(
                activeId = account.id,
                accounts = current.accounts.filterNot { it.id == account.id } + stored,
            )
        }
    }

    /** Removes the account and its secrets; another account becomes active if it was the active one. */
    suspend fun remove(accountId: String) {
        dataStore.updateData { current ->
            val remaining = current.accounts.filterNot { it.id == accountId }
            current.copy(
                accounts = remaining,
                activeId = current.activeId.takeIf { it != accountId } ?: remaining.firstOrNull()?.id,
            )
        }
    }

    /** @throws IllegalArgumentException if there is no such account */
    suspend fun setActive(accountId: String) {
        dataStore.updateData { current ->
            require(current.accounts.any { it.id == accountId }) { "Unknown account $accountId" }
            current.copy(activeId = accountId)
        }
    }

    /** The decrypted secrets, or `null` if the account does not exist or its secrets cannot be read. */
    suspend fun credentials(accountId: String): AccountCredentials? {
        val stored =
            dataStore.data
                .first()
                .accounts
                .firstOrNull { it.id == accountId } ?: return null
        return stored.decryptCredentials()
    }

    private fun StoredAccount.decryptCredentials(): AccountCredentials? =
        try {
            AccountCredentials(
                token = AuthToken(decrypt(encryptedToken)),
                customHeaders = customHeaders.map { it.name to decrypt(it.encryptedValue) },
            )
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun StoredAccount.toAccount() =
        Account(
            id = id,
            serverUrl = serverUrl,
            userId = userId,
            username = username,
            displayName = displayName,
            apiVersion = ApiVersion(apiVersion),
            serverVersion = serverVersion,
            customHeaderNames = customHeaders.map { it.name },
            needsSignIn = decryptCredentials() == null,
        )

    private fun Account.toStored(credentials: AccountCredentials) =
        StoredAccount(
            id = id,
            serverUrl = serverUrl,
            userId = userId,
            username = username,
            displayName = displayName,
            apiVersion = apiVersion.value,
            serverVersion = serverVersion,
            encryptedToken = encrypt(credentials.token.value),
            customHeaders = credentials.customHeaders.map { (name, value) -> StoredHeader(name, encrypt(value)) },
        )

    private fun encrypt(value: String): String =
        BASE64_ENCODER.encodeToString(cipher.encrypt(value.encodeToByteArray()))

    private fun decrypt(value: String): String = cipher.decrypt(BASE64_DECODER.decode(value)).decodeToString()

    companion object {
        private val BASE64_ENCODER = Base64.getEncoder()
        private val BASE64_DECODER = Base64.getDecoder()

        /**
         * @param file where to keep the accounts, e.g. `context.dataStoreFile("accounts.json")`. Only one
         *   store may use a file at a time.
         */
        fun create(
            file: File,
            cipher: SecretCipher,
        ): AccountStore =
            AccountStore(
                DataStoreFactory.create(
                    serializer = StoredAccountsSerializer,
                    // An unreadable file loses the accounts but keeps the app usable (users sign in again).
                    corruptionHandler = ReplaceFileCorruptionHandler { StoredAccounts() },
                    produceFile = { file },
                ),
                cipher,
            )
    }
}
