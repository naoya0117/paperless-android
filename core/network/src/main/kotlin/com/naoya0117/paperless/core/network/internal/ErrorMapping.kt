package com.naoya0117.paperless.core.network.internal

import com.naoya0117.paperless.core.network.PaperlessException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.time.Duration.Companion.seconds

/** Runs an API call and converts every failure into a [PaperlessException]. */
internal suspend fun <T> apiCall(block: suspend () -> T): T =
    try {
        block()
    } catch (e: PaperlessException) {
        throw e
    } catch (e: HttpException) {
        throw e.toPaperlessException()
    } catch (e: SerializationException) {
        throw PaperlessException.InvalidResponse(e.message ?: "malformed body", e)
    } catch (e: IOException) {
        throw e.toPaperlessException()
    }

/** Converts a non-2xx Retrofit [retrofit2.Response]. */
internal fun retrofit2.Response<*>.toPaperlessException(): PaperlessException {
    val errorBody = errorBody()?.string()
    return httpError(code(), errorBody, headers()["Retry-After"])
}

private fun HttpException.toPaperlessException(): PaperlessException =
    response()?.toPaperlessException() ?: httpError(code(), null, null)

private fun httpError(
    code: Int,
    body: String?,
    retryAfter: String?,
): PaperlessException {
    val errors = parseDrfErrors(body)
    val detail = errors["detail"]?.firstOrNull()
    return when (code) {
        400 -> PaperlessException.BadRequest(errors)
        401 -> PaperlessException.Unauthorized(detail)
        403 -> PaperlessException.Forbidden(detail)
        404 -> PaperlessException.NotFound()
        406 -> PaperlessException.UnsupportedServerVersion(serverMaxApiVersion = null)
        429 -> PaperlessException.RateLimited(retryAfter?.trim()?.toLongOrNull()?.seconds)
        in 500..599 -> PaperlessException.ServerError(code)
        else -> PaperlessException.UnexpectedStatus(code)
    }
}

private fun IOException.toPaperlessException(): PaperlessException =
    when {
        // OkHttp tries every route (e.g. IPv4 and IPv6) and throws the last failure with the earlier
        // ones suppressed. A TLS failure on one route explains more than "connection refused" on another.
        hasTlsFailure() -> PaperlessException.Tls(this)
        this is UnknownHostException -> PaperlessException.UnknownHost(this)
        this is ConnectException || this is NoRouteToHostException -> PaperlessException.ConnectionFailed(this)
        // SocketTimeoutException is an InterruptedIOException, as is OkHttp's call timeout.
        this is InterruptedIOException -> PaperlessException.Timeout(this)
        else -> PaperlessException.OtherNetwork(this)
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
