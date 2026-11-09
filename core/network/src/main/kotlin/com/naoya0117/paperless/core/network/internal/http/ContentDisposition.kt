package com.naoya0117.paperless.core.network.internal.http

import java.net.URLDecoder
import java.nio.charset.Charset

/**
 * The file name from a `Content-Disposition` header. `filename*` (RFC 5987/6266, e.g.
 * `filename*=utf-8''My%20file.pdf`) is preferred over the plain `filename` parameter; a malformed
 * `filename*` falls back to `filename`. The result is reduced to a safe plain file name.
 */
internal fun fileNameFromContentDisposition(header: String?): String? {
    if (header == null) return null
    val parameters =
        PARAMETER
            .findAll(header)
            .associate { match -> match.groupValues[1].lowercase() to match.groupValues[2] }
    val extended = parameters["filename*"]?.let(::decodeExtendedValue)
    val plain = parameters["filename"]?.let(::unquote)
    return (extended ?: plain)?.let(::sanitizeFileName)
}

// `; name=value` where value is a quoted string (with backslash escapes) or a token without `;`.
private val PARAMETER = Regex(""";\s*([^\s=;]+)\s*=\s*("(?:[^"\\]|\\.)*"|[^;]*)""")

private fun decodeExtendedValue(value: String): String? {
    val match = Regex("^([^']*)'[^']*'(.*)$").find(value.trim()) ?: return null
    val (charsetName, encoded) = match.destructured
    val charset = runCatching { Charset.forName(charsetName.ifEmpty { "UTF-8" }) }.getOrNull() ?: return null
    // URLDecoder would turn '+' into a space; RFC 5987 percent-encodes spaces, so keep '+' literal.
    return runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), charset) }.getOrNull()
}

private fun unquote(value: String): String {
    val trimmed = value.trim()
    if (trimmed.length < 2 || !trimmed.startsWith('"') || !trimmed.endsWith('"')) return trimmed
    return trimmed.substring(1, trimmed.length - 1).replace(Regex("""\\(.)"""), "$1")
}

/** Keeps only the last path segment, without control characters or leading dots (no `..`, no hidden files). */
internal fun sanitizeFileName(name: String): String? =
    name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filterNot { it.isISOControl() }
        .trimStart('.')
        .trim()
        .ifEmpty { null }
