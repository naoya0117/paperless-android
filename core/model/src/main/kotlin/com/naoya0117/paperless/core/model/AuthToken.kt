package com.naoya0117.paperless.core.model

/**
 * DRF auth token, sent as `Authorization: Token <value>`. [toString] is redacted so it never ends up in logs.
 *
 * Only visible ASCII is allowed: anything else (e.g. a trailing newline from copy & paste) cannot be sent in
 * an HTTP header. Trim user input before creating a token.
 */
@JvmInline
value class AuthToken(
    val value: String,
) {
    init {
        require(value.isNotEmpty() && value.all { it in '!'..'~' }) { "Token must be non-empty visible ASCII" }
    }

    override fun toString(): String = "AuthToken(***)"
}
