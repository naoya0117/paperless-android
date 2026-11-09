package com.naoya0117.paperless.core.model

/**
 * What the client learned about a server after authenticating.
 *
 * @property apiVersion API version the client uses: the newest one both sides support.
 * @property serverMaxApiVersion Highest API version the server supports (`X-Api-Version`).
 * @property serverVersion Paperless-ngx version (`X-Version`), e.g. `3.4.2`.
 */
data class ServerInfo(
    val apiVersion: ApiVersion,
    val serverMaxApiVersion: ApiVersion,
    val serverVersion: String?,
    val user: CurrentUser,
)
