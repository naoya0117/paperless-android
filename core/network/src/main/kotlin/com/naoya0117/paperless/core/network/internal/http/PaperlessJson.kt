package com.naoya0117.paperless.core.network.internal.http

import kotlinx.serialization.json.Json

/**
 * Lenient decoding (unknown keys ignored, missing nullable keys read as `null`). Request bodies that need
 * per-property control over omitted vs. explicit `null` are built as `JsonObject` (see LabelRequests.kt).
 */
internal val PaperlessJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }
