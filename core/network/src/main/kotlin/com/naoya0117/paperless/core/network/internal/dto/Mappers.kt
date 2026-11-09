package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.CurrentUser

internal fun UiSettingsUserDto.toModel(): CurrentUser =
    CurrentUser(
        id = id,
        username = username,
        firstName = firstName,
        lastName = lastName,
        isStaff = isStaff,
        isSuperuser = isSuperuser,
    )
