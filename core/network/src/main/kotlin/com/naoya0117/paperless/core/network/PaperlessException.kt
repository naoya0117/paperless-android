package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.ApiVersion
import kotlin.time.Duration

/**
 * Every failure of a Paperless API call is reported as one of these. Grouped so that callers can
 * handle a whole category at once (e.g. `is PaperlessException.Network` → "check your connection").
 */
public sealed class PaperlessException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Login (`POST /api/token/`) was rejected. */
    public sealed class Auth(
        message: String,
    ) : PaperlessException(message) {
        /** Wrong username or password. [serverMessage] is the (possibly localized) message from the server. */
        public class InvalidCredentials(
            public val serverMessage: String?,
        ) : Auth("Invalid credentials: $serverMessage")

        /** The account has two-factor authentication enabled and no code was sent. */
        public class MfaRequired : Auth("MFA code is required")

        public class InvalidMfaCode : Auth("Invalid MFA code")
    }

    /** The server answered with a non-2xx status. */
    public sealed class Http(
        public val statusCode: Int,
        message: String,
    ) : PaperlessException(message) {
        /** 400 with DRF validation errors; `non_field_errors` and `detail` are included as keys. */
        public class BadRequest(
            public val errors: Map<String, List<String>>,
        ) : Http(400, "Bad request: $errors")

        /** 401: missing, invalid or revoked token. */
        public class Unauthorized(
            public val detail: String?,
        ) : Http(401, "Unauthorized: $detail")

        /** 403: authenticated but not allowed. */
        public class Forbidden(
            public val detail: String?,
        ) : Http(403, "Forbidden: $detail")

        public class NotFound : Http(404, "Not found")

        /**
         * 3xx: redirects are never followed (they could carry credentials elsewhere). Typical causes: the URL
         * should be `https://` ([location] says so), or an authenticating proxy redirects to its login page.
         */
        public class Redirected(
            statusCode: Int,
            public val location: String?,
        ) : Http(statusCode, "Redirected (HTTP $statusCode) to $location")

        /** 429: throttled (login is limited to 5/min by default). */
        public class RateLimited(
            public val retryAfter: Duration?,
        ) : Http(429, "Rate limited, retry after $retryAfter")

        /** 5xx */
        public class ServerError(
            statusCode: Int,
        ) : Http(statusCode, "Server error: HTTP $statusCode")

        /** Any other non-2xx status. */
        public class UnexpectedStatus(
            statusCode: Int,
        ) : Http(statusCode, "Unexpected HTTP status $statusCode")
    }

    /**
     * The server does not support any API version in [SupportedApiVersions.range]: either it answered
     * 406 Not Acceptable ([serverMaxApiVersion] is then unknown), or its `X-Api-Version` is too old.
     */
    public class UnsupportedServerVersion(
        public val serverMaxApiVersion: ApiVersion?,
    ) : PaperlessException(
            "Server API version ${serverMaxApiVersion ?: "unknown"} is not supported " +
                "(client supports ${SupportedApiVersions.MIN}..${SupportedApiVersions.MAX})",
        )

    /** The response could not be parsed or lacks required data. */
    public class InvalidResponse(
        message: String,
        cause: Throwable? = null,
    ) : PaperlessException("Invalid response: $message", cause)

    /** The request did not get an HTTP response. */
    public sealed class Network(
        message: String,
        cause: Throwable,
    ) : PaperlessException(message, cause) {
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
        public class Other(
            cause: Throwable,
        ) : Network("Network error: ${cause.message}", cause)
    }
}
