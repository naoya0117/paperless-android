package com.naoya0117.paperless.core.network

import okhttp3.Headers

/**
 * An extra header sent with every request to the server, e.g. for an authenticating reverse proxy
 * (`CF-Access-Client-Id`, ...). The value is treated as a secret: it is redacted in [toString] and logs.
 */
public class HttpHeader(
    public val name: String,
    public val value: String,
) {
    init {
        // Validates name and value characters.
        Headers.headersOf(name, value)
        require(name.lowercase() !in RESERVED) { "Header '$name' is managed by the client and cannot be overridden" }
    }

    override fun equals(other: Any?): Boolean = other is HttpHeader && other.name == name && other.value == value

    override fun hashCode(): Int = 31 * name.hashCode() + value.hashCode()

    override fun toString(): String = "HttpHeader($name: ***)"

    private companion object {
        val RESERVED = setOf("authorization", "accept", "host", "content-type", "content-length")
    }
}
