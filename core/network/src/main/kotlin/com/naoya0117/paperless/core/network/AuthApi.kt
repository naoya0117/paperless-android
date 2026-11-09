package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.AuthToken

public interface AuthApi {
    /**
     * Exchanges credentials for a token (`POST /api/token/`). Throttled by the server (5/min by default).
     *
     * @param mfaCode TOTP code; required when the account has two-factor authentication enabled.
     * @throws PaperlessException.InvalidCredentials
     * @throws PaperlessException.MfaRequired
     * @throws PaperlessException.InvalidMfaCode
     * @throws PaperlessException.RateLimited
     * @throws PaperlessException.UnsupportedServerVersion if the server rejects the API version (406).
     */
    public suspend fun obtainToken(
        username: String,
        password: String,
        mfaCode: String? = null,
    ): AuthToken
}
