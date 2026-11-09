package com.naoya0117.paperless.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project

internal fun Project.configureAndroid(extension: CommonExtension) {
    extension.apply {
        compileSdk = libs.sdk("compileSdk")
        defaultConfig.minSdk = libs.sdk("minSdk")

        compileOptions.apply {
            sourceCompatibility = JAVA_VERSION
            targetCompatibility = JAVA_VERSION
        }

        lint.apply {
            abortOnError = true
            warningsAsErrors = false
            checkDependencies = true
        }
    }
    configureKotlin()
    configureKtlint()
}
