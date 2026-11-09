package com.naoya0117.paperless.core.network

import com.naoya0117.paperless.core.model.ApiVersion

/**
 * API versions this client implements. Paperless-ngx accepts a sliding window of versions
 * (3.x: 9 and 10), so the client picks the highest version both sides support.
 */
public object SupportedApiVersions {
    public val MIN: ApiVersion = ApiVersion.V10
    public val MAX: ApiVersion = ApiVersion.V10

    public val range: ClosedRange<ApiVersion> get() = MIN..MAX

    /**
     * @param serverMax highest version the server supports (`X-Api-Version` response header).
     * @throws PaperlessException.UnsupportedServerVersion if the server is older than [MIN].
     */
    public fun negotiate(serverMax: ApiVersion): ApiVersion {
        if (serverMax < MIN) throw PaperlessException.UnsupportedServerVersion(serverMax)
        return minOf(serverMax, MAX)
    }
}
