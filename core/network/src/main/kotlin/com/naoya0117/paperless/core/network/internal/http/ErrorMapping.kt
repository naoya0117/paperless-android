package com.naoya0117.paperless.core.network.internal.http

import com.naoya0117.paperless.core.network.PaperlessException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.net.ssl.SSLException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/** Converts a non-2xx response. */
internal fun Response<*>.toPaperlessException(): PaperlessException {
    val errors =
        try {
            parseDrfErrors(errorBody()?.string())
        } catch (_: IOException) {
            emptyMap()
        }
    val detail = errors["detail"]?.firstOrNull()
    return when (val code = code()) {
        400 -> PaperlessException.Http.BadRequest(errors)
        401 -> PaperlessException.Http.Unauthorized(detail)
        403 -> PaperlessException.Http.Forbidden(detail)
        404 -> PaperlessException.Http.NotFound()
        406 -> PaperlessException.UnsupportedServerVersion(serverMaxApiVersion = null)
        429 -> PaperlessException.Http.RateLimited(parseRetryAfter(headers()["Retry-After"]))
        in 500..599 -> PaperlessException.Http.ServerError(code)
        else -> PaperlessException.Http.UnexpectedStatus(code)
    }
}

/** `Retry-After` is either delay-seconds or an HTTP-date (RFC 9110 §10.2.3). */
internal fun parseRetryAfter(
    value: String?,
    now: Instant = Instant.now(),
): Duration? {
    val trimmed = value?.trim()?.ifEmpty { null } ?: return null
    trimmed.toLongOrNull()?.let { return it.coerceAtLeast(0).seconds }
    val date =
        try {
            ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            return null
        }
    return java.time.Duration
        .between(now, date)
        .toKotlinDuration()
        .coerceAtLeast(Duration.ZERO)
}

/** Converts a failure that produced no usable response. Unknown exception types are passed through. */
internal fun Throwable.toPaperlessException(): Throwable =
    when {
        this is PaperlessException -> this
        this is SerializationException -> PaperlessException.InvalidResponse(message ?: "malformed body", this)
        this !is IOException -> this
        // OkHttp tries every route (e.g. IPv4 and IPv6) and throws the last failure with the earlier
        // ones suppressed. A TLS failure on one route explains more than "connection refused" on another.
        hasTlsFailure() -> PaperlessException.Network.Tls(this)
        this is UnknownHostException -> PaperlessException.Network.UnknownHost(this)
        this is ConnectException || this is NoRouteToHostException -> PaperlessException.Network.ConnectionFailed(this)
        // SocketTimeoutException is an InterruptedIOException, as is OkHttp's call timeout.
        this is InterruptedIOException -> PaperlessException.Network.Timeout(this)
        else -> PaperlessException.Network.Other(this)
    }

/** Coroutine stack trace recovery may wrap the exception, so check the whole cause chain. */
private fun Throwable.hasTlsFailure(): Boolean =
    generateSequence(this) { it.cause }.any { e -> e is SSLException || e.suppressed.any { it is SSLException } }

/**
 * DRF error bodies are `{"detail": "..."}` or `{"field": ["msg", ...], "non_field_errors": [...]}`.
 * Non-JSON bodies (e.g. a reverse proxy's HTML page) yield an empty map.
 */
internal fun parseDrfErrors(body: String?): Map<String, List<String>> {
    if (body.isNullOrBlank()) return emptyMap()
    val json =
        try {
            PaperlessJson.parseToJsonElement(body)
        } catch (_: SerializationException) {
            return emptyMap()
        }
    return when (json) {
        is JsonObject -> json.mapValues { (_, value) -> value.messages() }
        is JsonArray -> mapOf("non_field_errors" to json.messages())
        else -> emptyMap()
    }
}

private fun JsonElement.messages(): List<String> =
    when (this) {
        is JsonPrimitive -> listOfNotNull(contentOrNull)
        is JsonArray -> flatMap { it.messages() }
        is JsonObject -> values.flatMap { it.messages() }
    }
