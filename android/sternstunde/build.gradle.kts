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

// The release gives the version (sternstunde.version or STERNSTUNDE_VERSION, for example 0.2.0 or
// sternstunde-android-v0.1.0). The version code has two digits for each part: 1.2.3 is 10203.
val appVersion =
    providers
        .gradleProperty("sternstunde.version")
        .orElse(providers.environmentVariable("STERNSTUNDE_VERSION"))
        .filter { it.isNotEmpty() }
        .map { it.removePrefix("sternstunde-android-v").removePrefix("v") }
        .getOrElse("0.1.0")
val appVersionCode =
    appVersion
        .split(".")
        .map { it.toInt() }
        .let { (major, minor, patch) ->
            require(minor < 100 && patch < 100) { "every part of the version must be below 100" }
            major * 10000 + minor * 100 + patch
        }

// The release signing comes from environment variables; without them, a release build is
// unsigned.
val keystoreFile = providers.environmentVariable("STERNSTUNDE_KEYSTORE_FILE").orNull

android {
    namespace = "at.stefl.sternstunde"
    // Compose 2026.09 needs API 37 to compile.
    compileSdk = 37

    defaultConfig {
        applicationId = "at.stefl.sternstunde"
        // LENS_DISTORTION of Camera2 starts at API 28.
        minSdk = 28
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
        // The store screenshots (ScreenshotTests in src/androidTest/).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = providers.environmentVariable("STERNSTUNDE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("STERNSTUNDE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("STERNSTUNDE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { compose = true }

    // The star data (PLAN.md 14.2) is shared with the web app, so the app takes it from the root of
    // the repository. The APK compresses it.
    sourceSets["main"].assets.directories.add("../../assets/stars")

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
    implementation(project(":shared"))
    implementation(libs.core.android)

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

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
