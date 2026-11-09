package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.ServerInfo

public interface ServerApi {
    /**
     * Validates the token and reads the current user and server versions (`GET /api/ui_settings/`).
     *
     * @throws PaperlessException.Unauthorized if the token is missing or invalid.
     * @throws PaperlessException.UnsupportedServerVersion if the server is too old for this client.
     */
    public suspend fun fetchServerInfo(): ServerInfo
}
