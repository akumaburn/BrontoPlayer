pluginManagement {
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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BrontoPlayer"

// The M4B parsing engine is a pure-JVM library kept as an independent build so it
// can be compiled and unit-tested without the Android SDK. The :app module consumes
// it via dependency substitution (see app/build.gradle.kts).
includeBuild("m4b")

include(":app")
