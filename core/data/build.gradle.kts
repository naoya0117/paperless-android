plugins {
    alias(libs.plugins.paperless.android.library)
}

android {
    namespace = "com.naoya0117.paperless.core.data"
}

dependencies {
    api(projects.core.model)
    api(projects.core.network)
    api(projects.core.datastore)
    implementation(libs.javax.inject)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
