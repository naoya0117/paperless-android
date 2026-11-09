package com.naoya0117.paperless.core.network.internal.api

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
    ): AuthToken {
        val request = TokenRequestDto(username, password, mfaCode?.trim()?.ifEmpty { null })
        val response =
            try {
                service.obtainToken(request)
            } catch (e: PaperlessException.Http.BadRequest) {
                throw e.toLoginException()
            }
        if (response.token.isBlank()) throw PaperlessException.InvalidResponse("empty token")
        return AuthToken(response.token)
    }

    /**
     * Paperless reports login failures as 400 `non_field_errors`. The MFA messages are not translated
     * (paperless/serialisers.py), unlike DRF's "Unable to log in with provided credentials.".
     */
    private fun PaperlessException.Http.BadRequest.toLoginException(): PaperlessException {
        val messages = errors["non_field_errors"].orEmpty()
        // Field errors, e.g. {"username": ["This field may not be blank."]}.
        val fieldMessage = (errors["username"].orEmpty() + errors["password"].orEmpty()).firstOrNull()
        return when {
            MFA_REQUIRED in messages -> PaperlessException.Auth.MfaRequired()
            INVALID_MFA_CODE in messages -> PaperlessException.Auth.InvalidMfaCode()
            messages.isNotEmpty() -> PaperlessException.Auth.InvalidCredentials(messages.first())
            fieldMessage != null -> PaperlessException.Auth.InvalidCredentials(fieldMessage)
            else -> this
        }
    }

    private companion object {
        const val MFA_REQUIRED = "MFA code is required"
        const val INVALID_MFA_CODE = "Invalid MFA code"
    }
}
