package com.naoya0117.paperless.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider

/**
 * Lets Gradle download Robolectric's Android jar (so it is cached like any dependency, e.g. on CI) and runs
 * Robolectric offline with it. Robolectric's own downloader fails on some hosts. The jar must match the SDK
 * set in the module's `robolectric.properties`.
 */
class RobolectricConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            val androidAll =
                configurations.create("robolectricAndroidAll") {
                    isCanBeConsumed = false
                    isTransitive = false
                }
            dependencies.add(androidAll.name, libs.findLibrary("robolectric-android-all").get())
            dependencies.add("testImplementation", libs.findLibrary("robolectric").get())
            tasks.withType<Test>().configureEach {
                jvmArgumentProviders.add(OfflineRobolectric(androidAll))
            }
        }
}

private class OfflineRobolectric(
    @get:Classpath val androidAll: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> =
        listOf("-Drobolectric.offline=true", "-Drobolectric.dependency.dir=${androidAll.singleFile.parent}")
}
