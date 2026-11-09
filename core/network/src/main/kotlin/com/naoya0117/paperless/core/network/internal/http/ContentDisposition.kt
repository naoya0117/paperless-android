package com.naoya0117.paperless.core.network.internal.http

import java.net.URLDecoder
import java.nio.charset.Charset

/**
 * The file name from a `Content-Disposition` header. `filename*` (RFC 5987/6266, e.g.
 * `filename*=utf-8''My%20file.pdf`) is preferred over the plain `filename` parameter.
 */
internal fun fileNameFromContentDisposition(header: String?): String? {
    if (header == null) return null
    val parameters =
        header
            .split(';')
            .drop(1)
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index < 0) null else part.substring(0, index).trim().lowercase() to part.substring(index + 1).trim()
            }.toMap()
    parameters["filename*"]?.let { extended ->
        val match = Regex("^([^']*)'[^']*'(.*)$").find(extended) ?: return@let
        val (charsetName, encoded) = match.destructured
        val charset = runCatching { Charset.forName(charsetName.ifEmpty { "UTF-8" }) }.getOrNull() ?: return@let
        // URLDecoder would turn '+' into a space; RFC 5987 percent-encodes spaces, so keep '+' literal.
        return URLDecoder.decode(encoded.replace("+", "%2B"), charset).ifEmpty { null }
    }
    return parameters["filename"]
        ?.removeSurrounding("\"")
        ?.replace("\\\"", "\"")
        ?.ifEmpty { null }
}
