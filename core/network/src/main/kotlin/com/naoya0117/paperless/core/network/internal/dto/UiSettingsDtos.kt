package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.CurrentUser
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

internal fun UiSettingsUserDto.toModel(): CurrentUser =
    CurrentUser(
        id = id,
        username = username,
        firstName = firstName,
        lastName = lastName,
        isStaff = isStaff,
        isSuperuser = isSuperuser,
    )
