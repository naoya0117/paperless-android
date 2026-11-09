package com.naoya0117.paperless.core.model

/** Paperless-ngx REST API version, sent as `Accept: application/json; version=N`. */
@JvmInline
value class ApiVersion(
    val value: Int,
) : Comparable<ApiVersion> {
    init {
        require(value > 0) { "API version must be positive: $value" }
    }

    override fun compareTo(other: ApiVersion): Int = value.compareTo(other.value)

    override fun toString(): String = "v$value"

    companion object {
        val V10 = ApiVersion(10)
    }
}
