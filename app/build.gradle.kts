import java.util.Properties

plugins {
    // AGP 9 provides built-in Kotlin support, so the kotlin-android plugin is NOT applied.
    // The Compose Compiler plugin is still required when Compose is enabled.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing is driven by a gitignored keystore.properties at the repo root (see
// keystore.properties.sample). When it's absent — e.g. a fresh clone of this open-source repo or
// CI without the key — the release build is simply left unsigned instead of failing.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "com.aemake.brontoplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aemake.brontoplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Signed with the upload key when keystore.properties is present; otherwise unsigned.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Kotlin compiler options via AGP 9's built-in Kotlin support (top-level kotlin {} DSL).
// Media3 @UnstableApi usages are opted into per-file with @androidx.annotation.OptIn (it is an
// androidx @RequiresOptIn marker handled by lint, not a Kotlin compiler opt-in marker).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    constraints {
        // Google Play Billing transitively brings androidx.fragment 1.1.0. We don't use Fragments,
        // but its presence trips the InvalidFragmentVersionForActivityResult lint check on our
        // ComponentActivity's registerForActivityResult usage. Bump the (already transitive) version
        // past 1.3.0 to satisfy it.
        implementation(libs.androidx.fragment) {
            because("Play Billing pulls fragment 1.1.0; ActivityResult lint needs >= 1.3.0")
        }
    }

    // Pure-JVM M4B parsing engine (composite build — see settings.gradle.kts).
    implementation("com.aemake.brontoplayer:m4b:1.0.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.coroutines.android)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Media3 (ExoPlayer + MediaSession)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    // Persistence
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    // Storage Access Framework helpers
    implementation(libs.documentfile)

    // Image loading (cover art)
    implementation(libs.coil.compose)

    // Google Play Billing — optional donation tips (consumable, grant nothing).
    implementation(libs.billing)

    testImplementation(libs.junit)
}
