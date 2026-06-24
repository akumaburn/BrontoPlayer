plugins {
    kotlin("jvm") version "2.3.21"
    `java-library`
}

// Coordinates used by the Android :app module's dependency substitution.
group = "com.aemake.brontoplayer"
version = "1.0.0"

// Repositories are declared in settings.gradle.kts (dependencyResolutionManagement).

dependencies {
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
