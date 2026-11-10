pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        // Robolectric's Android jars and its native runtime are large (150 MB or more each), and Maven Central
        // rate-limits the CI runner for them (HTTP 429). Google's mirror of Maven Central serves the same artifacts.
        exclusiveContent {
            forRepository {
                maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "MavenCentralGoogleMirror" }
            }
            filter {
                includeModule("org.robolectric", "android-all-instrumented")
                includeModule("org.robolectric", "nativeruntime-dist-compat")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "paperless-android"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:model")
include(":core:network")
include(":core:data")
include(":core:datastore")
include(":core:database")
include(":feature:login")
include(":feature:documents")
include(":feature:uploads")
include(":feature:labels")
