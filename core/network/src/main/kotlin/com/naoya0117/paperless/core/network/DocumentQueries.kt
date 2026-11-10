package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.DocumentQuery
import com.naoya0117.paperless.core.network.internal.dto.toQueryMap

/**
 * The query parameters the server receives for this query (including `page` and `page_size`), e.g. to tell
 * whether two queries ask for the same documents.
 *
 * @throws IllegalArgumentException if [DocumentQuery.extraParams] conflict with the typed properties
 */
public fun DocumentQuery.toQueryParameters(): Map<String, String> = toQueryMap()
