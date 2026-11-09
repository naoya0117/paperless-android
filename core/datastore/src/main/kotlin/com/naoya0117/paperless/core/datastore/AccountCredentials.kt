package com.naoya0117.paperless.core.datastore

import com.naoya0117.paperless.core.model.AuthToken

/** The secrets of an account. [toString] is redacted. */
class AccountCredentials(
    val token: AuthToken,
    /** Extra headers as (name, value); the values are secret. */
    val customHeaders: List<Pair<String, String>> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        other is AccountCredentials && other.token == token && other.customHeaders == customHeaders

    override fun hashCode(): Int = 31 * token.hashCode() + customHeaders.hashCode()

    override fun toString(): String = "AccountCredentials(token=***, customHeaders=${customHeaders.map { it.first }})"
}
