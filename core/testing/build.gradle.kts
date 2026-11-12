// Helpers for the unit tests of the other modules (testImplementation only; not in the app).
plugins {
    alias(libs.plugins.paperless.android.library)
}

android {
    namespace = "com.naoya0117.paperless.core.testing"
}

dependencies {
    api(libs.androidx.lifecycle.viewmodel)
    api(libs.kotlinx.coroutines.core)
}
