package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.datastore.AccountCredentials
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.AccountsState
import com.naoya0117.paperless.core.model.Account
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.ApiVersion
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.network.HttpHeader
import com.naoya0117.paperless.core.network.PaperlessClient
import com.naoya0117.paperless.core.network.PaperlessClientConfig
import com.naoya0117.paperless.core.network.PaperlessSession
import com.naoya0117.paperless.core.network.ServerUrl
import com.naoya0117.paperless.core.network.TlsConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private val accountData: AccountDataCleaner,
    ) {
        val state: Flow<AccountsState> = store.state

        private val clients = ConcurrentHashMap<String, CachedClient>()

        /** Per account, how often it was signed out in this process; see [writeData]. */
        private val epochs = ConcurrentHashMap<String, Long>()

        /** Serializes [signOut] with [writeData]. */
        private val dataLock = Mutex()

        /** Shared per settings, so the SSL context (and with it pooled connections) is reused. */
        private val tlsConfigs = ConcurrentHashMap<AccountTls, TlsConfig>()

        /** What a client is built from; other account fields (e.g. the display name) do not matter. */
        private data class ConnectionKey(
            val revision: Int?,
            val serverUrl: String,
            val apiVersion: ApiVersion,
            val tls: AccountTls,
        )

        private class CachedClient(
            val key: ConnectionKey,
            val client: PaperlessClient,
        )

        private fun connectionKey(
            account: Account,
            revision: Int?,
        ) = ConnectionKey(revision, account.serverUrl, account.apiVersion, account.tls)

        /**
         * A client for the active account, or `null` if there is none or it needs to sign in again.
         * Emits a new client when the active account, its credentials or its TLS settings change (e.g. after
         * signing in again).
         */
        val activeClient: Flow<PaperlessClient?> =
            store.state
                .map { state -> state.activeAccount?.let { it to state.credentialsRevisions[it.id] } }
                .distinctUntilChangedBy { active ->
                    active?.let { (account, revision) ->
                        account.id to
                            connectionKey(account, revision)
                    }
                }.map { active -> active?.let { (account, revision) -> client(account, revision) } }

        /**
         * Signs in and stores the account, which becomes the active one.
         *
         * @param serverUrl as typed by the user (see [ServerUrl.parse])
         * @param customHeaders extra headers for this server, e.g. for an authenticating proxy
         * @param tls client certificate and additionally trusted certificates, needed already to sign in. They
         *   replace the account's settings: pass the current [Account.tls] when signing in to an account again.
         * @throws IllegalArgumentException for an invalid server URL, header or certificate
         * @throws com.naoya0117.paperless.core.network.PaperlessException if signing in fails
         */
        suspend fun signIn(
            serverUrl: String,
            username: String,
            password: String,
            mfaCode: String? = null,
            customHeaders: List<Pair<String, String>> = emptyList(),
            tls: AccountTls,
        ): Account {
            val client = unauthenticatedClient(serverUrl, customHeaders, tls)
            return save(client.signIn(username, password, mfaCode), customHeaders, tls)
        }

        /** Like [signIn], with a token created in the Paperless web UI. */
        suspend fun signInWithToken(
            serverUrl: String,
            token: AuthToken,
            customHeaders: List<Pair<String, String>> = emptyList(),
            tls: AccountTls,
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
            requireValid(tls)
            store.setTls(accountId, tls)
        }

        /**
         * Forgets the account and its secrets on this device. The token stays valid on the server (Paperless
         * has no API to revoke it); it can be regenerated in the web UI's profile settings. Cached server data
         * of the account is deleted too.
         */
        suspend fun signOut(accountId: String) {
            dataLock.withLock {
                // Sessions taken before this point can no longer write data (see writeData).
                epochs.merge(accountId, 1, Long::plus)
                // Data first: if the process dies in between, the account still exists and refills its cache.
                accountData.clear(accountId)
                store.remove(accountId)
                clients.remove(accountId)
            }
        }

        /**
         * Runs [block], which writes data of [session]'s account (e.g. to the offline cache), unless the account
         * was signed out since the session was taken: then nothing is written, so that a request finishing
         * late does not leave data behind (even if the same user has signed in again meanwhile).
         *
         * @return whether [block] ran
         */
        internal suspend fun writeData(
            session: AccountSession,
            block: suspend () -> Unit,
        ): Boolean =
            dataLock.withLock {
                if (epoch(session.account.id) != session.epoch) return false
                block()
                true
            }

        private fun epoch(accountId: String): Long = epochs[accountId] ?: 0

        /** Whether the account was not signed out since [session] was taken. */
        internal fun isCurrent(session: AccountSession): Boolean = epoch(session.account.id) == session.epoch

        /**
         * A client for the account with [account]'s ID as currently stored (so an outdated [Account] does not
         * bring back old settings), or `null` if it was removed or its secrets cannot be read anymore.
         */
        suspend fun client(account: Account): PaperlessClient? {
            val state = store.state.first()
            val current = state.accounts.firstOrNull { it.id == account.id } ?: return null
            return client(current, state.credentialsRevisions[current.id])
        }

        /** Clients are cached per account and connection settings, so collectors share them. */
        private suspend fun client(
            account: Account,
            revision: Int?,
        ): PaperlessClient? {
            val key = connectionKey(account, revision)
            clients[account.id]?.let { if (it.key == key) return it.client }
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
            if (revision != null) clients[account.id] = CachedClient(key, client)
            return client
        }

        /** The client of the active account at this moment. */
        suspend fun currentClient(): PaperlessClient? = currentSession()?.client

        /** The active account with its client, or `null` if there is none or it needs to sign in again. */
        suspend fun currentSession(): AccountSession? {
            // Read before the account: a sign-out in between then makes the session outdated, not current.
            val epochs = epochs.toMap()
            val account = store.state.first().activeAccount ?: return null
            return client(account)?.let { AccountSession(account, it, epochs[account.id] ?: 0) }
        }

        private fun unauthenticatedClient(
            serverUrl: String,
            customHeaders: List<Pair<String, String>>,
            tls: AccountTls,
        ): PaperlessClient =
            clientFactory.create(
                PaperlessClientConfig(
                    serverUrl = ServerUrl.parse(serverUrl),
                    customHeaders = customHeaders.map { (name, value) -> HttpHeader(name, value) },
                    tls = tlsConfig(requireValid(tls)),
                ),
            )

        /** @throws IllegalArgumentException if a trusted certificate is not a valid X.509 certificate */
        private fun requireValid(tls: AccountTls): AccountTls {
            tls.trustedCertificates.forEach(TrustedCertificates::toX509)
            return tls
        }

        /**
         * `null` (platform defaults) when the account has no TLS settings. Stored certificates were validated
         * before saving; any that are invalid anyway (e.g. an edited file) are skipped, which only trusts less.
         */
        private fun tlsConfig(tls: AccountTls): TlsConfig? =
            if (tls == AccountTls()) {
                null
            } else {
                tlsConfigs.getOrPut(tls) {
                    TlsConfig(
                        keyManagers = listOfNotNull(tls.clientCertificateAlias?.let(clientCertificates::keyManager)),
                        additionalTrustedCertificates =
                            tls.trustedCertificates.mapNotNull {
                                runCatching { TrustedCertificates.toX509(it) }.getOrNull()
                            },
                    )
                }
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
