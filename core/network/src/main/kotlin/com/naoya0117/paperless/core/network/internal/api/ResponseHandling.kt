package com.naoya0117.paperless.core.network.internal.api

import com.naoya0117.paperless.core.network.PaperlessException
import com.naoya0117.paperless.core.network.internal.dto.PageDto
import java.time.DateTimeException

/**
 * Converting DTOs to models happens after the call adapter, so its failures (e.g. an unparseable date)
 * are converted here to keep every failure a [PaperlessException].
 */
internal inline fun <T> parsing(block: () -> T): T =
    try {
        block()
    } catch (e: DateTimeException) {
        throw PaperlessException.InvalidResponse(e.message ?: "invalid date", e)
    } catch (e: IllegalArgumentException) {
        throw PaperlessException.InvalidResponse(e.message ?: "invalid value", e)
    }

/**
 * DRF answers a page beyond the last one with 404 "Invalid page." (e.g. after deletions during infinite
 * scrolling); report that as an empty last page rather than as a missing object.
 */
internal suspend fun <T> pageOrEmptyBeyondEnd(
    page: Int,
    load: suspend () -> PageDto<T>,
): PageDto<T> =
    try {
        load()
    } catch (e: PaperlessException.Http.NotFound) {
        if (page == 1) throw e
        PageDto(count = 0, next = null, results = emptyList())
    }
