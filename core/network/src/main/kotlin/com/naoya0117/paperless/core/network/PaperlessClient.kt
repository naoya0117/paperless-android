package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.network.internal.api.ApiRegistry
import com.naoya0117.paperless.core.network.internal.http.OkHttpClientFactory
import okhttp3.OkHttpClient

/**
 * Entry point to the Paperless-ngx REST API for one account. Create one per account; clients derived
 * via [withConfig] share the connection pool of the base [OkHttpClient]. API implementations are created
 * lazily, so deriving clients is cheap.
 */
public class PaperlessClient private constructor(
    public val config: PaperlessClientConfig,
    private val baseClient: OkHttpClient,
) {
    private val apis = ApiRegistry(config.serverUrl, OkHttpClientFactory.create(config, baseClient), config.apiVersion)

    public val auth: AuthApi get() = apis.auth
    public val server: ServerApi get() = apis.server

    public fun withConfig(transform: (PaperlessClientConfig) -> PaperlessClientConfig): PaperlessClient =
        PaperlessClient(transform(config), baseClient)

    /**
     * Logs in with username and password, then validates the token and negotiates the API version.
     * @see AuthApi.obtainToken
     */
    public suspend fun signIn(
        username: String,
        password: String,
        mfaCode: String? = null,
    ): PaperlessSession = signIn(auth.obtainToken(username, password, mfaCode))

    /** Signs in with an existing token (e.g. one generated in the Paperless web UI). */
    public suspend fun signIn(token: AuthToken): PaperlessSession {
        val authenticated = withConfig { it.copy(token = token) }
        val info = authenticated.server.fetchServerInfo()
        val client =
            if (info.apiVersion == authenticated.config.apiVersion) {
                authenticated
            } else {
                authenticated.withConfig { it.copy(apiVersion = info.apiVersion) }
            }
        return PaperlessSession(client, info)
    }

    public companion object {
        private val defaultBaseClient: OkHttpClient by lazy { OkHttpClient() }

        /**
         * @param baseClient shared [OkHttpClient] (connection pool, dispatcher); per-account settings are
         *   applied on top of it.
         */
        public fun create(
            config: PaperlessClientConfig,
            baseClient: OkHttpClient = defaultBaseClient,
        ): PaperlessClient = PaperlessClient(config, baseClient)
    }
}
