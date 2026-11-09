package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.ServerInfo

/** Result of a sign-in: a client configured with the token and negotiated API version. */
public class PaperlessSession internal constructor(
    public val client: PaperlessClient,
    public val serverInfo: ServerInfo,
) {
    public val token: AuthToken get() = checkNotNull(client.config.token)
}
