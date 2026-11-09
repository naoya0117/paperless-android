package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.ApiVersion
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.network.internal.api.ApiRegistry
import okhttp3.OkHttpClient

/**
 * Entry point to the Paperless-ngx REST API for one account. Create one per account; clients derived
 * via [withConfig] share the connection pool of the base [OkHttpClient]. The HTTP client and API
 * implementations are created lazily, so deriving clients is cheap.
 */
public class PaperlessClient private constructor(
    public val config: PaperlessClientConfig,
    private val baseClient: OkHttpClient,
    private val supportedVersions: ClosedRange<ApiVersion>,
) {
    private val apis = ApiRegistry(config, baseClient)

    public val auth: AuthApi get() = apis.auth
    public val server: ServerApi get() = apis.server

    public fun withConfig(transform: (PaperlessClientConfig) -> PaperlessClientConfig): PaperlessClient =
        PaperlessClient(transform(config), baseClient, supportedVersions)

    /**
     * Logs in with username and password, then validates the token and negotiates the API version.
     * @see AuthApi.obtainToken
     */
    public suspend fun signIn(
        username: String,
        password: String,
        mfaCode: String? = null,
    ): PaperlessSession {
        // Without a token: DRF authenticates /api/token/ requests too, so a stale token would fail with 401.
        val (client, token) = withVersionFallback(token = null) { it.auth.obtainToken(username, password, mfaCode) }
        // The server already accepted this API version; no need to negotiate again.
        val authenticated = client.withConfig { it.copy(token = token) }
        return PaperlessSession(authenticated, authenticated.server.fetchServerInfo())
    }

    /** Signs in with an existing token (e.g. one generated in the Paperless web UI). */
    public suspend fun signIn(token: AuthToken): PaperlessSession {
        val (client, info) = withVersionFallback(token) { it.server.fetchServerInfo() }
        return PaperlessSession(client, info)
    }

    /**
     * Runs [block] with the newest supported API version first and steps down while the server answers
     * 406, so the first version that succeeds is the newest one both sides support.
     *
     * @return the client that succeeded (configured with [token] and that version) and the result.
     */
    private suspend fun <T> withVersionFallback(
        token: AuthToken?,
        block: suspend (PaperlessClient) -> T,
    ): Pair<PaperlessClient, T> {
        var notAcceptable: PaperlessException.UnsupportedServerVersion? = null
        for (version in SupportedApiVersions.candidatesIn(supportedVersions)) {
            val client = withConfig { it.copy(token = token, apiVersion = version) }
            try {
                return client to block(client)
            } catch (e: PaperlessException.UnsupportedServerVersion) {
                notAcceptable = e
            }
        }
        throw checkNotNull(notAcceptable)
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
        ): PaperlessClient = create(config, baseClient, SupportedApiVersions.range)

        /** For tests: version negotiation over a different range. */
        internal fun create(
            config: PaperlessClientConfig,
            baseClient: OkHttpClient,
            supportedVersions: ClosedRange<ApiVersion>,
        ): PaperlessClient = PaperlessClient(config, baseClient, supportedVersions)
    }
}
