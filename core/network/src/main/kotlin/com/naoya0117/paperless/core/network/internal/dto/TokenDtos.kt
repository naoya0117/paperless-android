package com.naoya0117.paperless.core.network.internal.dto

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
