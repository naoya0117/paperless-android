plugins {
    alias(libs.plugins.paperless.android.library)
}

android {
    namespace = "com.naoya0117.paperless.core.data"

    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(projects.core.model)
    api(projects.core.network)
    api(projects.core.datastore)
    api(projects.core.database)
    implementation(libs.javax.inject)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.paging.common)
    api(libs.work.runtime)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.sqlite.bundled.jvm)
    testImplementation(libs.paging.testing)
    testImplementation(libs.work.testing)
}
