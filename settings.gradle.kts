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
        // Only these two (F-Droid allows no other repositories). CI adds a mirror for Robolectric's large jars in
        // an init script (.gitea/workflows/ci.yml).
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
include(":core:testing")
include(":feature:login")
include(":feature:documents")
include(":feature:uploads")
include(":feature:labels")
