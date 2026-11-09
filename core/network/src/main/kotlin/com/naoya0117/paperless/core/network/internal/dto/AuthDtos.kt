package com.naoya0117.paperless.core.network.internal.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `POST /api/token/` */
@Serializable
internal data class TokenRequestDto(
    val username: String,
    val password: String,
    val code: String? = null,
)

@Serializable
internal data class TokenResponseDto(
    val token: String,
)

/** `GET /api/ui_settings/`; only the fields the client uses. */
@Serializable
internal data class UiSettingsDto(
    val user: UiSettingsUserDto,
)

@Serializable
internal data class UiSettingsUserDto(
    val id: Long,
    val username: String,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("is_staff") val isStaff: Boolean = false,
    @SerialName("is_superuser") val isSuperuser: Boolean = false,
)
