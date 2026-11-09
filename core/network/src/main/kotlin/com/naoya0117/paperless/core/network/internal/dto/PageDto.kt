package com.naoya0117.paperless.core.network.internal.dto

import com.naoya0117.paperless.core.model.Page
import kotlinx.serialization.Serializable

/** DRF page; API v10 no longer returns the `all` list of IDs. */
@Serializable
internal data class PageDto<T>(
    val count: Int,
    val next: String? = null,
    val results: List<T>,
)

internal fun <T, R> PageDto<T>.toModel(transform: (T) -> R): Page<R> =
    Page(items = results.map(transform), count = count, hasNext = next != null)
