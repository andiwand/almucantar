pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sunradar"

// The AAR from source: Gradle replaces at.stefl.sunradar:core-android with this build. So a
// change in the core and in the app is one commit, and the app does not wait for a release.
includeBuild("../core/android")

include(":app")
