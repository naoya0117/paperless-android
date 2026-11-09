package com.naoya0117.paperless.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Pure Kotlin/JVM module without Android dependencies (e.g. :core:model, :core:network). */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            // Lets Android Lint analyze this module when checking :app (checkDependencies).
            pluginManager.apply("com.android.lint")
            configureJava()
            configureKotlin()
            configureKtlint()

            dependencies {
                add("testImplementation", libs.findLibrary("junit").get())
            }
        }
}
