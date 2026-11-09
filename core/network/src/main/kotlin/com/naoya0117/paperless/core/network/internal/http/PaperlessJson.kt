package com.naoya0117.paperless.core.network.internal.http

import kotlinx.serialization.json.Json

internal val PaperlessJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
