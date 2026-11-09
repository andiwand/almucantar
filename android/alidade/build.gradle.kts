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

// The release gives the version (alidade.version or ALIDADE_VERSION, for example 0.2.0 or
// alidade-android-v0.1.0). The version code has two digits for each part: 1.2.3 is 10203.
val appVersion =
    providers
        .gradleProperty("alidade.version")
        .orElse(providers.environmentVariable("ALIDADE_VERSION"))
        .filter { it.isNotEmpty() }
        .map { it.removePrefix("alidade-android-v").removePrefix("v") }
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
val keystoreFile = providers.environmentVariable("ALIDADE_KEYSTORE_FILE").orNull

android {
    namespace = "at.stefl.alidade"
    // Compose 2026.09 needs API 37 to compile.
    compileSdk = 37

    defaultConfig {
        applicationId = "at.stefl.alidade"
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
                storePassword = providers.environmentVariable("ALIDADE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ALIDADE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ALIDADE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // The app bundle carries the symbols of the native core, so the crashes and the ANRs in
            // Google Play show the functions and the lines of the C++ code.
            ndk { debugSymbolLevel = "FULL" }
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { compose = true }

    // The photo draws the stars of the star data, which the web app and sternstunde also use.
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
        // A newer Gradle is not a defect of this code either.
        disable += "AndroidGradlePluginVersion"
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

    testImplementation(libs.junit)
    // org.json for the JVM tests of the favorites: android.jar has only stubs of it.
    testImplementation(libs.json)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
