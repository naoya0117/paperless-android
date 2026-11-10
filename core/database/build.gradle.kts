plugins {
    alias(libs.plugins.paperless.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.naoya0117.paperless.core.database"

    testOptions.unitTests.isIncludeAndroidResources = true
}

room {
    // Checked in, so that migrations can be written and tested against the previous versions.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(projects.core.model)
    api(libs.paging.common)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.paging)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.paging.testing)
    testImplementation(libs.robolectric)
    // SQLite with its own native library: Robolectric's native SQLite does not run on every host (e.g. Linux aarch64).
    testImplementation(libs.sqlite.bundled.jvm)
}
