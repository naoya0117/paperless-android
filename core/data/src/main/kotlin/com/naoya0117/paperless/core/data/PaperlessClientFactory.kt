package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.network.PaperlessClient
import com.naoya0117.paperless.core.network.PaperlessClientConfig
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates clients for accounts; all of them share one connection pool and dispatcher ([baseClient], or the
 * network module's shared default).
 */
@Singleton
class PaperlessClientFactory(
    private val baseClient: OkHttpClient?,
) {
    @Inject
    constructor() : this(null)

    fun create(config: PaperlessClientConfig): PaperlessClient =
        if (baseClient == null) PaperlessClient.create(config) else PaperlessClient.create(config, baseClient)
}
