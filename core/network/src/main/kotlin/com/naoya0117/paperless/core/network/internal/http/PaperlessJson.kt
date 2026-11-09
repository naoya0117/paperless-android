package com.naoya0117.paperless.core.network.internal.http

import kotlinx.serialization.json.Json

/**
 * Encoding: properties equal to their default are omitted (`encodeDefaults = false`), while `null` is sent
 * for nullable properties without a default. Request DTOs use this to choose per property between
 * "leave to the server" (`= null` default) and "explicitly clear" (no default).
 *
 * Decoding: unknown keys are ignored, and `null` for a property with a default falls back to the default.
 */
internal val PaperlessJson =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
