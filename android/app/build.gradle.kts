plugins {
    alias(libs.plugins.android.application)
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
    namespace = "at.stefl.sunradar"
    // Compose 2026.09 needs API 37 to compile.
    compileSdk = 37

    defaultConfig {
        applicationId = "at.stefl.sunradar"
        // LENS_DISTORTION of Camera2 starts at API 28.
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }

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
    implementation(libs.core.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)

    testImplementation(libs.junit)
}
