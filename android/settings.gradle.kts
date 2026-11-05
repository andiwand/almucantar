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

rootProject.name = "almucantar"

// The AAR from source: Gradle replaces at.stefl.almucantar:core-android with this build. So a
// change in the core and in an app is one commit, and the apps do not wait for a release.
includeBuild("../core/android")

// The shared code of the apps: the place, the maps, the terrain, the camera, and the heading.
include(":shared")

// The apps.
include(":sonnseite")
include(":sternstunde")
include(":alidade")
