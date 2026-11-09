package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.datastore.AccountCredentials
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.AccountsState
import com.naoya0117.paperless.core.model.Account
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.network.HttpHeader
import com.naoya0117.paperless.core.network.PaperlessClient
import com.naoya0117.paperless.core.network.PaperlessClientConfig
import com.naoya0117.paperless.core.network.PaperlessSession
import com.naoya0117.paperless.core.network.ServerUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Signing in and out, the accounts on this device, and the client of the active account. */
@Singleton
class AccountRepository
    @Inject
    constructor(
        private val store: AccountStore,
        private val clientFactory: PaperlessClientFactory,
    ) {
        val state: Flow<AccountsState> = store.state

        /**
         * A client for the active account, or `null` if there is none or it needs to sign in again.
         * Emits a new client when the active account or its credentials change.
         */
        val activeClient: Flow<PaperlessClient?> =
            store.state
                .map { it.activeAccount }
                .distinctUntilChanged()
                .map { account -> account?.let { client(it) } }

        /**
         * Signs in and stores the account, which becomes the active one.
         *
         * @param serverUrl as typed by the user (see [ServerUrl.parse])
         * @param customHeaders extra headers for this server, e.g. for an authenticating proxy
         * @throws IllegalArgumentException for an invalid server URL or header
         * @throws com.naoya0117.paperless.core.network.PaperlessException if signing in fails
         */
        suspend fun signIn(
            serverUrl: String,
            username: String,
            password: String,
            mfaCode: String? = null,
            customHeaders: List<Pair<String, String>> = emptyList(),
        ): Account {
            val client = unauthenticatedClient(serverUrl, customHeaders)
            return save(client.signIn(username, password, mfaCode), customHeaders)
        }

        /** Like [signIn], with a token created in the Paperless web UI. */
        suspend fun signInWithToken(
            serverUrl: String,
            token: AuthToken,
            customHeaders: List<Pair<String, String>> = emptyList(),
        ): Account {
            val client = unauthenticatedClient(serverUrl, customHeaders)
            return save(client.signIn(token), customHeaders)
        }

        suspend fun switchTo(accountId: String) = store.setActive(accountId)

        /**
         * Forgets the account and its secrets on this device. The token stays valid on the server (Paperless
         * has no API to revoke it); it can be regenerated in the web UI's profile settings.
         */
        suspend fun signOut(accountId: String) = store.remove(accountId)

        /** A client for [account], or `null` if its secrets cannot be read anymore. */
        suspend fun client(account: Account): PaperlessClient? {
            val credentials = store.credentials(account.id) ?: return null
            return clientFactory.create(
                PaperlessClientConfig(
                    serverUrl = ServerUrl.parse(account.serverUrl),
                    token = credentials.token,
                    apiVersion = account.apiVersion,
                    customHeaders = credentials.customHeaders.map { (name, value) -> HttpHeader(name, value) },
                ),
            )
        }

        /** The client of the active account at this moment. */
        suspend fun currentClient(): PaperlessClient? =
            store.state
                .first()
                .activeAccount
                ?.let { client(it) }

        private fun unauthenticatedClient(
            serverUrl: String,
            customHeaders: List<Pair<String, String>>,
        ): PaperlessClient =
            clientFactory.create(
                PaperlessClientConfig(
                    serverUrl = ServerUrl.parse(serverUrl),
                    customHeaders = customHeaders.map { (name, value) -> HttpHeader(name, value) },
                ),
            )

        private suspend fun save(
            session: PaperlessSession,
            customHeaders: List<Pair<String, String>>,
        ): Account {
            val config = session.client.config
            val user = session.serverInfo.user
            val account =
                Account(
                    id = accountId(config.serverUrl, user.id),
                    serverUrl = config.serverUrl.toString(),
                    userId = user.id,
                    username = user.username,
                    displayName = listOfNotNull(user.firstName, user.lastName).joinToString(" ").ifBlank { null },
                    apiVersion = config.apiVersion,
                    serverVersion = session.serverInfo.serverVersion,
                    customHeaderNames = customHeaders.map { it.first },
                    needsSignIn = false,
                )
            store.save(account, AccountCredentials(session.token, customHeaders))
            return account
        }

        private companion object {
            /** Stable for the same user on the same server, so signing in again updates the account. */
            fun accountId(
                serverUrl: ServerUrl,
                userId: Long,
            ): String {
                val digest = MessageDigest.getInstance("SHA-256").digest("$serverUrl#$userId".encodeToByteArray())
                return digest.take(ID_BYTES).joinToString("") { "%02x".format(it) }
            }

            const val ID_BYTES = 12
        }
    }
