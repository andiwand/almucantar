// The AAR at.stefl.almucantar:core-android: the Kotlin API and libalmucantar_jni.so.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.DeploymentValidation

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.spotless)
    alias(libs.plugins.maven.publish)
}

group = "at.stefl.almucantar"

// The release gives the version (almucantar.version or ALMUCANTAR_VERSION, for example 0.2.0 or
// core-v0.2.0). Other builds are snapshots.
val almucantarVersion =
    providers
        .gradleProperty("almucantar.version")
        .orElse(providers.environmentVariable("ALMUCANTAR_VERSION"))
        .filter { it.isNotEmpty() }
        .map { it.removePrefix("core-v").removePrefix("v") }
        .getOrElse("0.0.0-SNAPSHOT")

version = almucantarVersion

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

// The ABIs that `build_native.py` builds. Set `-Palmucantar.abis=` or ALMUCANTAR_ABIS (empty) when
// the libraries come from somewhere else, for example from the CI jobs of each ABI. The
// environment variables also reach this build when the app includes it as a composite build.
val nativeAbis =
    providers
        .gradleProperty("almucantar.abis")
        .orElse(providers.environmentVariable("ALMUCANTAR_ABIS"))
        .getOrElse("armv8,x86_64")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

val buildNative =
    tasks.register<Exec>("buildNative") {
        description = "Builds libalmucantar_jni.so for almucantar.abis into native/prebuilt"
        commandLine(
            buildList {
                add(
                    providers
                        .gradleProperty("almucantar.python")
                        .orElse(providers.environmentVariable("ALMUCANTAR_PYTHON"))
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
                    "nothing in $jniLibs: run build_native.py, or set -Palmucantar.abis=<abis>"
                )
            }
            for (directory in abiDirectories) {
                val library = directory.resolve("libalmucantar_jni.so")
                if (!library.isFile || library.length() == 0L) {
                    throw GradleException("missing or empty native library: $library")
                }
            }
        }
    }

tasks.named("preBuild") { dependsOn(checkNative) }

android {
    namespace = "at.stefl.almucantar.core"
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

    sourceSets {
        named("main") { jniLibs.srcDir("native/prebuilt/jniLibs") }
        // The GeoTIFF test files of the core, for the instrumented tests.
        named("androidTest") { assets.srcDir("../test/data/geotiff") }
    }

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

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release"))

    // Releases the deployment and waits until it is published, so the exit code of the task says
    // whether the release worked.
    publishToMavenCentral(true, DeploymentValidation.PUBLISHED)

    // Only Maven Central needs a signature. Without a key, publishToMavenLocal still works.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates("at.stefl.almucantar", "core-android", almucantarVersion)

    pom {
        name = "core-android"
        description =
            "The almucantar core for Android: sun and moon position, sun path, magnetic declination, " +
                "camera projection, a heading filter, and the terrain horizon"
        url = "https://github.com/andiwand/almucantar"
        licenses {
            license {
                name = "MPL-2.0"
                url = "https://www.mozilla.org/en-US/MPL/2.0/"
            }
        }
        developers {
            developer {
                id = "andiwand"
                name = "Andreas Stefl"
                url = "https://github.com/andiwand"
            }
        }
        scm {
            connection = "scm:git:https://github.com/andiwand/almucantar.git"
            developerConnection = "scm:git:git@github.com:andiwand/almucantar.git"
            url = "https://github.com/andiwand/almucantar"
        }
    }
}
