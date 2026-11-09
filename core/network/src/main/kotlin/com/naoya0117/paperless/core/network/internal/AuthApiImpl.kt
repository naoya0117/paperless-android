package com.naoya0117.paperless.core.network.internal

import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.network.AuthApi
import com.naoya0117.paperless.core.network.PaperlessException
import com.naoya0117.paperless.core.network.internal.dto.TokenRequestDto
import com.naoya0117.paperless.core.network.internal.service.AuthService

internal class AuthApiImpl(
    private val service: AuthService,
) : AuthApi {
    override suspend fun obtainToken(
        username: String,
        password: String,
        mfaCode: String?,
    ): AuthToken =
        try {
            val request = TokenRequestDto(username, password, mfaCode?.trim()?.ifEmpty { null })
            val response = apiCall { service.obtainToken(request) }
            if (response.token.isBlank()) throw PaperlessException.InvalidResponse("empty token")
            AuthToken(response.token)
        } catch (e: PaperlessException.BadRequest) {
            throw e.toLoginException()
        }

    /**
     * Paperless reports login failures as 400 `non_field_errors`. The MFA messages are not translated
     * (paperless/serialisers.py), unlike DRF's "Unable to log in with provided credentials.".
     */
    private fun PaperlessException.BadRequest.toLoginException(): PaperlessException {
        val messages = errors["non_field_errors"].orEmpty()
        return when {
            MFA_REQUIRED in messages -> PaperlessException.MfaRequired()
            INVALID_MFA_CODE in messages -> PaperlessException.InvalidMfaCode()
            messages.isNotEmpty() -> PaperlessException.InvalidCredentials(messages.first())
            else -> this
        }
    }

    private companion object {
        const val MFA_REQUIRED = "MFA code is required"
        const val INVALID_MFA_CODE = "Invalid MFA code"
    }
}
