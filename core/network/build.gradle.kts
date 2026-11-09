plugins {
    alias(libs.plugins.paperless.jvm.library)
}

kotlin {
    explicitApi()
}

dependencies {
    api(projects.core.model)
}
