package com.naoya0117.paperless.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.net.Proxy

/** Ignores the JVM proxy settings of the build environment so requests reach MockWebServer directly. */
internal val testBaseClient: OkHttpClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()

internal fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/v10/$name")) { "Missing fixture $name" }.readText()

internal fun jsonResponse(
    code: Int,
    fixtureName: String,
    vararg headers: Pair<String, String>,
): MockResponse =
    MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
        .body(fixture(fixtureName))
        .build()

/** A successful `/api/ui_settings/` response including the headers set by ApiVersionMiddleware. */
internal fun uiSettingsResponse(apiVersion: Int = 10): MockResponse =
    jsonResponse(200, "ui_settings.json", "X-Api-Version" to "$apiVersion", "X-Version" to "3.4.2")

internal fun MockWebServer.client(config: (ServerUrl) -> PaperlessClientConfig = { PaperlessClientConfig(it) }) =
    PaperlessClient.create(config(ServerUrl.parse(url("/").toString())), testBaseClient)
