plugins {
    `kotlin-dsl`
}

group = "com.naoya0117.paperless.buildlogic"

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.ktlint.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = libs.plugins.paperless.android.application.get().pluginId
            implementationClass = "com.naoya0117.paperless.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = libs.plugins.paperless.android.library.get().pluginId
            implementationClass = "com.naoya0117.paperless.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("robolectric") {
            id = libs.plugins.paperless.robolectric.get().pluginId
            implementationClass = "com.naoya0117.paperless.buildlogic.RobolectricConventionPlugin"
        }
        register("jvmLibrary") {
            id = libs.plugins.paperless.jvm.library.get().pluginId
            implementationClass = "com.naoya0117.paperless.buildlogic.JvmLibraryConventionPlugin"
        }
    }
}
