package com.naoya0117.paperless.core.model

/**
 * A signed-in Paperless account on this device. Secrets (token, custom header values) are stored
 * separately and never part of this object.
 *
 * @property id local identifier, stable across sign-ins of the same user on the same server
 * @property customHeaderNames names of the extra headers sent to this server (values are secret)
 * @property needsSignIn the stored secrets can no longer be read (e.g. the device key was reset)
 */
data class Account(
    val id: String,
    val serverUrl: String,
    val userId: Long,
    val username: String,
    val displayName: String?,
    val apiVersion: ApiVersion,
    val serverVersion: String?,
    val customHeaderNames: List<String>,
    val needsSignIn: Boolean,
)
