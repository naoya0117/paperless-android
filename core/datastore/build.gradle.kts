plugins {
    alias(libs.plugins.paperless.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.naoya0117.paperless.core.datastore"
}

dependencies {
    api(projects.core.model)
    api(libs.datastore.core)
    implementation(libs.javax.inject)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
