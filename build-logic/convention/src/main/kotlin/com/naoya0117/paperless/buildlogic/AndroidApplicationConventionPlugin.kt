package com.naoya0117.paperless.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            val versionName = providers.gradleProperty("paperless.versionName").get()

            extensions.configure<ApplicationExtension> {
                configureAndroid(this)

                defaultConfig {
                    targetSdk = libs.sdk("targetSdk")
                    this.versionName = versionName
                    versionCode = versionCodeOf(versionName)
                }

                buildFeatures {
                    compose = true
                    buildConfig = true
                }

                // F-Droid: do not embed the encrypted dependency metadata blob, and keep the APK reproducible.
                dependenciesInfo {
                    includeInApk = false
                    includeInBundle = false
                }

                packaging {
                    resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
                }
            }

            dependencies {
                val bom = libs.findLibrary("compose-bom").get()
                add("implementation", platform(bom))
                add("testImplementation", libs.findLibrary("junit").get())
            }
        }
}

/** SemVer `major.minor.patch` → `major * 10000 + minor * 100 + patch`. */
internal fun versionCodeOf(versionName: String): Int {
    val (major, minor, patch) =
        requireNotNull(Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(versionName)) {
            "paperless.versionName must be MAJOR.MINOR.PATCH, was '$versionName'"
        }.destructured
    require(minor.toInt() < 100 && patch.toInt() < 100) { "minor and patch must be < 100: $versionName" }
    return major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
}
