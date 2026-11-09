package com.naoya0117.paperless.core.model

/** The user the token belongs to. */
data class CurrentUser(
    val id: Long,
    val username: String,
    val firstName: String?,
    val lastName: String?,
    val isStaff: Boolean,
    val isSuperuser: Boolean,
)
