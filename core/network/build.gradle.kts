plugins {
    alias(libs.plugins.paperless.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
}

dependencies {
    api(projects.core.model)
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}

tasks.test {
    // Opt-in smoke test against a local server (RealServerSmokeTest). The URL is an explicit input, and
    // runs against a server are never taken from the cache, as the server's state is not an input.
    val devUrl = providers.environmentVariable("PAPERLESS_DEV_URL").getOrElse("")
    environment("PAPERLESS_DEV_URL", devUrl)
    inputs.property("paperlessDevUrl", devUrl)
    if (devUrl.isNotEmpty()) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}
