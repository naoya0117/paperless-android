import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.paperless.android.application)
    alias(libs.plugins.paperless.robolectric)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries)
}

// The open source libraries in the APK and their licenses, for the about screen (res/raw/aboutlibraries.json).
aboutLibraries {
    // Only what the Maven POMs say: no network access (reproducible, F-Droid).
    offlineMode = true
    collect {
        fetchRemoteLicense = false
        fetchRemoteFunding = false
    }
    export {
        // Not shown; also keeps the output the same between builds.
        excludeFields.addAll("developers", "funding", "organization", "scm", "website")
    }
    license {
        // The app is GPL-3.0-or-later: fail the build on a dependency whose license is not known to be compatible.
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        allowedLicenses.addAll("Apache-2.0")
        // These carry the libraries' copyright: their texts are bundled per library (BundledText in AboutScreen.kt).
        allowedLicensesMap =
            mapOf(
                "MIT" to listOf("org.checkerframework:checker-qual"),
                // libyuv in CameraX's native code
                "BSD-3-Clause" to listOf("androidx.camera:camera-core"),
            )
    }
}

android {
    namespace = "com.naoya0117.paperless"

    defaultConfig {
        applicationId = "com.naoya0117.paperless"
    }

    // Release signing is configured only when all four values are provided: as Gradle properties
    // `paperless.signing.<name>` (e.g. in ~/.gradle/gradle.properties), or as environment variables
    // `PAPERLESS_SIGNING_<NAME>` (e.g. PAPERLESS_SIGNING_STORE_PASSWORD; used by the release workflow).
    // Without them the release APK is unsigned, which is what F-Droid expects.
    val signingProps =
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .associateWith { name ->
                val env = "PAPERLESS_SIGNING_" + name.replace(Regex("([A-Z])"), "_$1").uppercase()
                providers
                    .gradleProperty("paperless.signing.$name")
                    .orElse(providers.environmentVariable(env))
                    .orNull
            }
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

    // The about screen's test reads the app's raw resources (the generated library list).
    testOptions.unitTests.isIncludeAndroidResources = true

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
    implementation(libs.aboutlibraries.core)
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

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
}

/**
 * Apache-2.0 section 4 (d): the attribution notices of the libraries' NOTICE files must be shown
 * (res/raw/third_party_notices.txt, maintained by hand). Fails when a library in the APK ships a NOTICE file but
 * is not mentioned there, e.g. after adding a dependency.
 */
abstract class CheckThirdPartyNotices : DefaultTask() {
    /** The Java resources (META-INF etc.) of the external libraries in the APK, as packaged by AGP. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val javaResources: ConfigurableFileCollection

    /** The library (`group:name`) of each of [javaResources], in the same order. */
    @get:Input
    abstract val libraries: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val notices: RegularFileProperty

    @get:OutputFile
    abstract val result: RegularFileProperty

    @TaskAction
    fun check() {
        val text = notices.get().asFile.readText()
        val withNotice =
            javaResources.files
                .zip(libraries.get())
                .filter { (file, _) -> hasNotice(file) }
                .map { it.second }
                .toSet()
        val missing = withNotice.filter { it !in text }
        check(missing.isEmpty()) {
            "Libraries with a NOTICE file not in ${notices.get().asFile.name}: $missing"
        }
        result.get().asFile.writeText(withNotice.sorted().joinToString("\n"))
    }

    private fun hasNotice(file: File): Boolean {
        fun isNotice(path: String) = path.substringAfterLast('/').uppercase().startsWith("NOTICE")
        return when {
            file.isDirectory -> file.walkTopDown().any { it.isFile && isNotice(it.name) }
            file.isFile -> ZipFile(file).use { zip -> zip.entries().asSequence().any { isNotice(it.name) } }
            else -> false
        }
    }
}

val noticeCheck =
    tasks.register<CheckThirdPartyNotices>("checkThirdPartyNotices") {
        val resources =
            configurations
                .named("releaseRuntimeClasspath")
                .get()
                .incoming
                .artifactView {
                    attributes { attribute(Attribute.of("artifactType", String::class.java), "android-java-res") }
                    // The app's own modules have no NOTICE.
                    componentFilter { it is ModuleComponentIdentifier }
                }.artifacts
        javaResources.from(resources.artifactFiles)
        libraries.set(
            resources.resolvedArtifacts.map { artifacts ->
                artifacts.map { (it.id.componentIdentifier as ModuleComponentIdentifier).moduleIdentifier.toString() }
            },
        )
        notices.set(layout.projectDirectory.file("src/main/res/raw/third_party_notices.txt"))
        result.set(layout.buildDirectory.file("reports/thirdPartyNotices.txt"))
    }
tasks.named("check") { dependsOn(noticeCheck) }
