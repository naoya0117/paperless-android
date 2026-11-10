package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.datastore.AccountCredentials
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.AccountsState
import com.naoya0117.paperless.core.model.Account
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.network.HttpHeader
import com.naoya0117.paperless.core.network.PaperlessClient
import com.naoya0117.paperless.core.network.PaperlessClientConfig
import com.naoya0117.paperless.core.network.PaperlessSession
import com.naoya0117.paperless.core.network.ServerUrl
import com.naoya0117.paperless.core.network.TlsConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Signing in and out, the accounts on this device, and the client of the active account. */
@Singleton
class AccountRepository
    @Inject
    constructor(
        private val store: AccountStore,
        private val clientFactory: PaperlessClientFactory,
        private val clientCertificates: ClientCertificateSource,
    ) {
        val state: Flow<AccountsState> = store.state

        private val clients = ConcurrentHashMap<String, CachedClient>()

        private class CachedClient(
            val revision: Int,
            val account: Account,
            val client: PaperlessClient,
        )

        /**
         * A client for the active account, or `null` if there is none or it needs to sign in again.
         * Emits a new client when the active account, its credentials or its TLS settings change (e.g. after
         * signing in again).
         */
        val activeClient: Flow<PaperlessClient?> =
            store.state
                .map { state -> state.activeAccount?.let { it to state.credentialsRevisions[it.id] } }
                .distinctUntilChanged()
                .map { active -> active?.let { (account, revision) -> client(account, revision) } }

        /**
         * Signs in and stores the account, which becomes the active one.
         *
         * @param serverUrl as typed by the user (see [ServerUrl.parse])
         * @param customHeaders extra headers for this server, e.g. for an authenticating proxy
         * @param tls client certificate and additionally trusted certificates, needed already to sign in
         * @throws IllegalArgumentException for an invalid server URL, header or certificate
         * @throws com.naoya0117.paperless.core.network.PaperlessException if signing in fails
         */
        suspend fun signIn(
            serverUrl: String,
            username: String,
            password: String,
            mfaCode: String? = null,
            customHeaders: List<Pair<String, String>> = emptyList(),
            tls: AccountTls = AccountTls(),
        ): Account {
            val client = unauthenticatedClient(serverUrl, customHeaders, tls)
            return save(client.signIn(username, password, mfaCode), customHeaders, tls)
        }

        /** Like [signIn], with a token created in the Paperless web UI. */
        suspend fun signInWithToken(
            serverUrl: String,
            token: AuthToken,
            customHeaders: List<Pair<String, String>> = emptyList(),
            tls: AccountTls = AccountTls(),
        ): Account {
            val client = unauthenticatedClient(serverUrl, customHeaders, tls)
            return save(client.signIn(token), customHeaders, tls)
        }

        suspend fun switchTo(accountId: String) = store.setActive(accountId)

        /**
         * Changes the account's TLS settings (e.g. a renewed client certificate); its client is rebuilt.
         *
         * @throws IllegalArgumentException if there is no such account or a certificate is invalid
         */
        suspend fun setTls(
            accountId: String,
            tls: AccountTls,
        ) {
            tls.trustedCertificates.forEach(TrustedCertificates::toX509) // rejects invalid ones before storing
            store.setTls(accountId, tls)
        }

        /**
         * Forgets the account and its secrets on this device. The token stays valid on the server (Paperless
         * has no API to revoke it); it can be regenerated in the web UI's profile settings.
         */
        suspend fun signOut(accountId: String) {
            store.remove(accountId)
            clients.remove(accountId)
        }

        /** A client for [account], or `null` if its secrets cannot be read anymore. */
        suspend fun client(account: Account): PaperlessClient? =
            client(account, store.state.first().credentialsRevisions[account.id])

        /** Clients are cached per account and credentials revision, so collectors share them. */
        private suspend fun client(
            account: Account,
            revision: Int?,
        ): PaperlessClient? {
            clients[account.id]?.let { if (it.revision == revision && it.account == account) return it.client }
            val credentials = store.credentials(account.id) ?: return null
            val client =
                clientFactory.create(
                    PaperlessClientConfig(
                        serverUrl = ServerUrl.parse(account.serverUrl),
                        token = credentials.token,
                        apiVersion = account.apiVersion,
                        customHeaders = credentials.customHeaders.map { (name, value) -> HttpHeader(name, value) },
                        tls = tlsConfig(account.tls),
                    ),
                )
            revision?.let { clients[account.id] = CachedClient(it, account, client) }
            return client
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
            tls: AccountTls,
        ): PaperlessClient =
            clientFactory.create(
                PaperlessClientConfig(
                    serverUrl = ServerUrl.parse(serverUrl),
                    customHeaders = customHeaders.map { (name, value) -> HttpHeader(name, value) },
                    tls = tlsConfig(tls),
                ),
            )

        /** `null` (platform defaults) when the account has no TLS settings. */
        private fun tlsConfig(tls: AccountTls): TlsConfig? =
            if (tls == AccountTls()) {
                null
            } else {
                TlsConfig(
                    keyManagers = listOfNotNull(tls.clientCertificateAlias?.let(clientCertificates::keyManager)),
                    additionalTrustedCertificates = tls.trustedCertificates.map(TrustedCertificates::toX509),
                )
            }

        private suspend fun save(
            session: PaperlessSession,
            customHeaders: List<Pair<String, String>>,
            tls: AccountTls,
        ): Account {
            val config = session.client.config
            val user = session.serverInfo.user
            val account =
                Account(
                    id = accountId(config.serverUrl, user.id),
                    serverUrl = config.serverUrl.toString(),
                    userId = user.id,
                    username = user.username,
                    displayName =
                        listOfNotNull(user.firstName, user.lastName)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .ifEmpty { null },
                    apiVersion = config.apiVersion,
                    serverVersion = session.serverInfo.serverVersion,
                    customHeaderNames = customHeaders.map { it.first },
                    needsSignIn = false,
                    tls = tls,
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
