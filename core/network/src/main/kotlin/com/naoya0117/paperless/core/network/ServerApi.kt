package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.SavedViewVisibility
import com.naoya0117.paperless.core.model.ServerInfo

public interface ServerApi {
    /**
     * Validates the token and reads the current user and server versions (`GET /api/ui_settings/`).
     *
     * @throws PaperlessException.Http.Unauthorized if the token is missing or invalid.
     * @throws PaperlessException.UnsupportedServerVersion if the server is too old for this client.
     */
    public suspend fun fetchServerInfo(): ServerInfo

    /** Which saved views the user shows on the dashboard and in the sidebar (from `GET /api/ui_settings/`). */
    public suspend fun fetchSavedViewVisibility(): SavedViewVisibility
}
