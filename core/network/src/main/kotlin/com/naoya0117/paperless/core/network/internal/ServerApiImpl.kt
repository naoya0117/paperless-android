package com.naoya0117.paperless.core.network.internal

import com.naoya0117.paperless.core.model.ApiVersion
import com.naoya0117.paperless.core.model.ServerInfo
import com.naoya0117.paperless.core.network.PaperlessException
import com.naoya0117.paperless.core.network.ServerApi
import com.naoya0117.paperless.core.network.SupportedApiVersions
import com.naoya0117.paperless.core.network.internal.dto.toModel
import com.naoya0117.paperless.core.network.internal.service.UiSettingsService

internal class ServerApiImpl(
    private val service: UiSettingsService,
) : ServerApi {
    override suspend fun fetchServerInfo(): ServerInfo =
        apiCall {
            val response = service.uiSettings()
            if (!response.isSuccessful) throw response.toPaperlessException()
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

            ServerInfo(
                apiVersion = SupportedApiVersions.negotiate(serverMax),
                serverMaxApiVersion = serverMax,
                serverVersion = response.headers()["X-Version"]?.trim()?.ifEmpty { null },
                user = body.user.toModel(),
            )
        }
}
