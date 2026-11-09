plugins {
    alias(libs.plugins.paperless.android.library)
}

android {
    namespace = "com.naoya0117.paperless.core.data"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.network)
    implementation(libs.kotlinx.coroutines.core)
}
