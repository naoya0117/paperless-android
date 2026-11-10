plugins {
    alias(libs.plugins.paperless.android.application)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.naoya0117.paperless"

    defaultConfig {
        applicationId = "com.naoya0117.paperless"
    }

    // Release signing is configured only when all properties are provided
    // (e.g. in ~/.gradle/gradle.properties or via ORG_GRADLE_PROJECT_* env vars).
    // Without them the release APK is unsigned, which is what F-Droid expects.
    val signingProps =
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .associateWith { providers.gradleProperty("paperless.signing.$it").orNull }
    val releaseSigning =
        if (signingProps.values.all { it != null }) {
            signingConfigs.create("release") {
                storeFile = file(signingProps.getValue("storeFile")!!)
                storePassword = signingProps.getValue("storePassword")
                keyAlias = signingProps.getValue("keyAlias")
                keyPassword = signingProps.getValue("keyPassword")
            }
        } else {
            null
        }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning
            // Reproducible builds: do not embed VCS info (commit hash) into the APK.
            vcsInfo.include = false
        }
    }
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.feature.login)
    implementation(projects.feature.documents)
    implementation(projects.feature.uploads)
    implementation(projects.feature.labels)
    implementation(libs.navigation.compose)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
