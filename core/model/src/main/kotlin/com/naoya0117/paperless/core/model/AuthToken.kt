package com.naoya0117.paperless.core.model

/** DRF auth token, sent as `Authorization: Token <value>`. [toString] is redacted so it never ends up in logs. */
@JvmInline
value class AuthToken(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Token must not be blank" }
    }

    override fun toString(): String = "AuthToken(***)"
}
