package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.ApiVersion

/**
 * API versions this client implements. Paperless-ngx accepts a sliding window of versions
 * (3.x: 9 and 10) and answers 406 for any other, so the client tries [candidates] from the newest down
 * and uses the first one the server accepts.
 */
public object SupportedApiVersions {
    public val MIN: ApiVersion = ApiVersion.V10
    public val MAX: ApiVersion = ApiVersion.V10

    public val range: ClosedRange<ApiVersion> get() = MIN..MAX

    /** Versions in [range], newest first. */
    public val candidates: List<ApiVersion> get() = candidatesIn(range)

    internal fun candidatesIn(range: ClosedRange<ApiVersion>): List<ApiVersion> =
        (range.endInclusive.value downTo range.start.value).map(::ApiVersion)
}
