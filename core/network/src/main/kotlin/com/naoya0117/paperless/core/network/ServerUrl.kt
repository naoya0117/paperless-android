package com.naoya0117.paperless.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Normalized base URL of a Paperless-ngx server, always ending with `/`.
 * Supports installations under a sub path (e.g. `https://example.com/paperless/`).
 */
public class ServerUrl private constructor(
    public val httpUrl: HttpUrl,
) {
    /** `true` for plain HTTP; the UI should warn before sending credentials over it. */
    public val isCleartext: Boolean get() = !httpUrl.isHttps

    /**
     * Whether [url] belongs to this server: same scheme, host and port, and below the base path. Used to
     * scope credentials, so other apps on the same host (e.g. `https://example.com/other/`) never get them.
     */
    public operator fun contains(url: HttpUrl): Boolean =
        url.scheme == httpUrl.scheme &&
            url.host == httpUrl.host &&
            url.port == httpUrl.port &&
            url.encodedPath.startsWith(httpUrl.encodedPath)

    override fun equals(other: Any?): Boolean = other is ServerUrl && other.httpUrl == httpUrl

    override fun hashCode(): Int = httpUrl.hashCode()

    override fun toString(): String = httpUrl.toString()

    public companion object {
        /**
         * Parses user input such as `paperless.example.com`, `http://192.168.0.10:8000` or
         * `https://example.com/paperless/api/`. A missing scheme defaults to HTTPS and a trailing
         * `/api` segment is removed.
         *
         * @throws IllegalArgumentException if the input is not an HTTP(S) URL without query or fragment.
         */
        public fun parse(input: String): ServerUrl {
            val trimmed = input.trim()
            require(trimmed.isNotEmpty()) { "Server URL is empty" }
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = requireNotNull(withScheme.toHttpUrlOrNull()) { "Not an HTTP(S) URL: $input" }
            require(
                url.query == null && url.fragment == null,
            ) { "Server URL must not have a query or fragment: $input" }
            require(url.username.isEmpty() && url.password.isEmpty()) { "Server URL must not contain credentials" }

            val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
            if (segments.lastOrNull() == "api") segments.removeAt(segments.lastIndex)
            val normalized =
                url
                    .newBuilder()
                    .encodedPath("/")
                    .apply { segments.forEach { addPathSegment(it) } }
                    .addPathSegment("")
                    .build()
            return ServerUrl(normalized)
        }
    }
}
