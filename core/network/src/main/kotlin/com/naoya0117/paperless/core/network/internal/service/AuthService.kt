package com.naoya0117.paperless.core.network.internal.service

import com.naoya0117.paperless.core.network.internal.dto.TokenRequestDto
import com.naoya0117.paperless.core.network.internal.dto.TokenResponseDto
import com.naoya0117.paperless.core.network.internal.dto.UiSettingsDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

// Paths are relative so that installations under a sub path keep working.

internal interface AuthService {
    @POST("api/token/")
    suspend fun obtainToken(
        @Body body: TokenRequestDto,
    ): TokenResponseDto
}

internal interface UiSettingsService {
    /** Wrapped in [Response] because the API version is only available in the response headers. */
    @GET("api/ui_settings/")
    suspend fun uiSettings(): Response<UiSettingsDto>
}
