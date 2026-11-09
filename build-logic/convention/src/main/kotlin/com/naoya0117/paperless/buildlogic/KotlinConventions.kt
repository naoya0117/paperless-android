package com.naoya0117.paperless.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/** Bytecode level for every module. Independent of the JDK that runs Gradle, for reproducible builds. */
internal val JAVA_VERSION = JavaVersion.VERSION_17
private val JVM_TARGET = JvmTarget.JVM_17

internal fun Project.configureKotlin() {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JVM_TARGET)
            allWarningsAsErrors.set(true)
            freeCompilerArgs.add("-Xjdk-release=${JAVA_VERSION.majorVersion}")
        }
    }
}

internal fun Project.configureJava() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JAVA_VERSION
        targetCompatibility = JAVA_VERSION
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(JAVA_VERSION.majorVersion.toInt())
    }
}

internal fun Project.configureKtlint() {
    pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    extensions.configure<KtlintExtension> {
        android.set(true)
    }
}
