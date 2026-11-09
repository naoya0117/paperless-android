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
    // Opt-in smoke test against a local server (RealServerSmokeTest); not cached, as the server is external.
    environment("PAPERLESS_DEV_URL", providers.environmentVariable("PAPERLESS_DEV_URL").getOrElse(""))
    if (providers.environmentVariable("PAPERLESS_DEV_URL").isPresent) outputs.upToDateWhen { false }
}
