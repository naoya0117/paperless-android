package com.naoya0117.paperless.core.model

/** One page of a list endpoint. */
data class Page<T>(
    val items: List<T>,
    /** Total number of matching objects across all pages. */
    val count: Int,
    val hasNext: Boolean,
)
