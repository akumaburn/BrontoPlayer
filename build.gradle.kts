// Top-level build file. Plugins are declared here (apply false) and applied in modules.
// AGP 9+ ships built-in Kotlin support, so the kotlin-android and compose Gradle plugins are
// NOT applied to the Android module — Kotlin and the Compose compiler are provided by AGP.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
