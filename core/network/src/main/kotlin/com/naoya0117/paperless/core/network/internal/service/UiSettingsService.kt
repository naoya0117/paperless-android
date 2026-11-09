package com.naoya0117.paperless.core.network.internal.service

import com.naoya0117.paperless.core.network.internal.dto.UiSettingsDto
import retrofit2.Response
import retrofit2.http.GET

internal interface UiSettingsService {
    /** Wrapped in [Response] because the API version is only available in the response headers. */
    @GET("api/ui_settings/")
    suspend fun uiSettings(): Response<UiSettingsDto>
}
