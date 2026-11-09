package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.ApiVersion
import kotlin.time.Duration

/** Every failure of a Paperless API call is reported as one of these. */
public sealed class PaperlessException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    // --- Login (`/api/token/`) ---

    /** Wrong username or password. [serverMessage] is the (possibly localized) message from the server. */
    public class InvalidCredentials(
        public val serverMessage: String?,
    ) : PaperlessException("Invalid credentials: $serverMessage")

    /** The account has two-factor authentication enabled and no code was sent. */
    public class MfaRequired : PaperlessException("MFA code is required")

    public class InvalidMfaCode : PaperlessException("Invalid MFA code")

    // --- HTTP status ---

    /** 401: missing, invalid or revoked token. */
    public class Unauthorized(
        public val detail: String?,
    ) : PaperlessException("Unauthorized: $detail")

    /** 403: authenticated but not allowed. */
    public class Forbidden(
        public val detail: String?,
    ) : PaperlessException("Forbidden: $detail")

    /** 404 */
    public class NotFound : PaperlessException("Not found")

    /**
     * The server does not support any API version in [SupportedApiVersions.range]: either it answered
     * 406 Not Acceptable, or its `X-Api-Version` ([serverMaxApiVersion]) is too old.
     */
    public class UnsupportedServerVersion(
        public val serverMaxApiVersion: ApiVersion?,
    ) : PaperlessException(
            "Server API version ${serverMaxApiVersion ?: "unknown"} is not supported " +
                "(client supports ${SupportedApiVersions.MIN}..${SupportedApiVersions.MAX})",
        )

    /** 429: throttled (login is limited to 5/min by default). */
    public class RateLimited(
        public val retryAfter: Duration?,
    ) : PaperlessException("Rate limited, retry after $retryAfter")

    /** 400 with DRF validation errors; `non_field_errors` and `detail` are included as keys. */
    public class BadRequest(
        public val errors: Map<String, List<String>>,
    ) : PaperlessException("Bad request: $errors")

    /** 5xx */
    public class ServerError(
        public val statusCode: Int,
    ) : PaperlessException("Server error: HTTP $statusCode")

    /** Any other non-2xx status. */
    public class UnexpectedStatus(
        public val statusCode: Int,
    ) : PaperlessException("Unexpected HTTP status $statusCode")

    /** The response could not be parsed or lacks required data. */
    public class InvalidResponse(
        message: String,
        cause: Throwable? = null,
    ) : PaperlessException("Invalid response: $message", cause)

    // --- Transport ---

    public sealed class Network(
        message: String,
        cause: Throwable,
    ) : PaperlessException(message, cause)

    /** DNS lookup failed: offline, or the host name is wrong. */
    public class UnknownHost(
        cause: Throwable,
    ) : Network("Unknown host", cause)

    /** TCP connection refused or unreachable. */
    public class ConnectionFailed(
        cause: Throwable,
    ) : Network("Connection failed", cause)

    public class Timeout(
        cause: Throwable,
    ) : Network("Timed out", cause)

    /** TLS handshake failed: untrusted certificate, host name mismatch, client certificate required, etc. */
    public class Tls(
        cause: Throwable,
    ) : Network("TLS error", cause)

    /** Any other I/O failure. */
    public class OtherNetwork(
        cause: Throwable,
    ) : Network("Network error: ${cause.message}", cause)
}
