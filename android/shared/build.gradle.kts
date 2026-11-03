// The code that the apps share: the place and the offline base map, the maps, the terrain and its
// cache, the camera, the heading tracker, the moon phase, and the sun detector. An app depends on
// it with implementation(project(":shared")).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("src/*/kotlin/**/*.kt")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
}

android {
    namespace = "at.stefl.almucantar.shared"
    // Compose 2026.09 needs API 37 to compile.
    compileSdk = 37

    defaultConfig {
        // LENS_DISTORTION of Camera2 starts at API 28.
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures { compose = true }

    // The offline base map (PLAN.md 13.3) is shared with all apps, so the module takes it from the
    // root of the repository. The APK of an app compresses it.
    sourceSets["main"].assets.directories.add("../../assets/basemap")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // A newer dependency is not a defect of this code.
        disable += "GradleDependency"
        disable += "NewerVersionAvailable"
    }
}

dependencies {
    // The public API of this module uses the types of the core.
    api(libs.core.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
