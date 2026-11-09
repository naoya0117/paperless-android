package com.naoya0117.paperless.core.network.internal.service

import com.naoya0117.paperless.core.network.internal.dto.TokenRequestDto
import com.naoya0117.paperless.core.network.internal.dto.TokenResponseDto
import retrofit2.http.Body
import retrofit2.http.POST

// Service paths are relative so that installations under a sub path keep working.
internal interface AuthService {
    @POST("api/token/")
    suspend fun obtainToken(
        @Body body: TokenRequestDto,
    ): TokenResponseDto
}
