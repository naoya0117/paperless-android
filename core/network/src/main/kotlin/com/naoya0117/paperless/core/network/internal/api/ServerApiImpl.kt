package com.naoya0117.paperless.core.network.internal.api

import com.naoya0117.paperless.core.model.ApiVersion
import com.naoya0117.paperless.core.model.ServerInfo
import com.naoya0117.paperless.core.network.PaperlessException
import com.naoya0117.paperless.core.network.ServerApi
import com.naoya0117.paperless.core.network.internal.dto.toModel
import com.naoya0117.paperless.core.network.internal.service.UiSettingsService

/** @param apiVersion the version this client sends; the server accepted it if the request succeeds. */
internal class ServerApiImpl(
    private val service: UiSettingsService,
    private val apiVersion: ApiVersion,
) : ServerApi {
    override suspend fun fetchServerInfo(): ServerInfo {
        val response = service.uiSettings()
        val body = response.body() ?: throw PaperlessException.InvalidResponse("empty body")

        // Set by ApiVersionMiddleware for authenticated requests only.
        val serverMax =
            response
                .headers()["X-Api-Version"]
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let(::ApiVersion)
                ?: throw PaperlessException.InvalidResponse("missing X-Api-Version header")

        return ServerInfo(
            apiVersion = apiVersion,
            serverMaxApiVersion = serverMax,
            serverVersion = response.headers()["X-Version"]?.trim()?.ifEmpty { null },
            user = body.user.toModel(),
        )
    }
}
