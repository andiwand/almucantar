// The AAR at.stefl.sunradar:core-android: the Kotlin API and libsunradar_jni.so.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.spotless)
}

group = "at.stefl.sunradar"

// ktfmt in the kotlinlang style. `./gradlew spotlessApply` formats, `spotlessCheck` checks.
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

// The ABIs that `build_native.py` builds. Set `-Psunradar.abis=` or SUNRADAR_ABIS (empty) when
// the libraries come from somewhere else, for example from the CI jobs of each ABI. The
// environment variables also reach this build when the app includes it as a composite build.
val nativeAbis =
    providers
        .gradleProperty("sunradar.abis")
        .orElse(providers.environmentVariable("SUNRADAR_ABIS"))
        .getOrElse("armv8,x86_64")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

val buildNative =
    tasks.register<Exec>("buildNative") {
        description = "Builds libsunradar_jni.so for sunradar.abis into native/prebuilt"
        commandLine(
            buildList {
                add(
                    providers
                        .gradleProperty("sunradar.python")
                        .orElse(providers.environmentVariable("SUNRADAR_PYTHON"))
                        .getOrElse("python3")
                )
                add(file("build_native.py").absolutePath)
                nativeAbis.forEach {
                    add("--abi")
                    add(it)
                }
            }
        )
        // Conan and CMake check what is up to date, so the task runs each time.
        enabled = nativeAbis.isNotEmpty()
    }

// An AAR without the native library builds, but fails at run time in the app. So a
// missing library is a build error.
val checkNative =
    tasks.register("checkNative") {
        dependsOn(buildNative)
        val jniLibs = layout.projectDirectory.dir("native/prebuilt/jniLibs").asFile
        doLast {
            val abiDirectories = jniLibs.listFiles()?.filter { it.isDirectory }.orEmpty()
            if (abiDirectories.isEmpty()) {
                throw GradleException(
                    "nothing in $jniLibs: run build_native.py, or set -Psunradar.abis=<abis>"
                )
            }
            for (directory in abiDirectories) {
                val library = directory.resolve("libsunradar_jni.so")
                if (!library.isFile || library.length() == 0L) {
                    throw GradleException("missing or empty native library: $library")
                }
            }
        }
    }

tasks.named("preBuild") { dependsOn(checkNative) }

android {
    namespace = "at.stefl.sunradar.core"
    compileSdk = 36

    defaultConfig {
        // LENS_DISTORTION of Camera2 starts at API 28.
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Pushing the test APK to a cold emulator can take longer than the default timeout.
    installation { timeOutInMs = 10 * 60 * 1000 }

    sourceSets { named("main") { jniLibs.srcDir("native/prebuilt/jniLibs") } }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkTestSources = true
        // A newer dependency is not a defect of this code.
        disable += "GradleDependency"
        disable += "NewerVersionAvailable"
    }
}

dependencies {
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
