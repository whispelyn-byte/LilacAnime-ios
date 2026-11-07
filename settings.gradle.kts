pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "LilacAnime"
include(":shared")
// The Android port of the iOS app (docs/android-port.md).
if (providers.gradleProperty("includeAndroid").orNull == "true") include(":androidApp")
// The original Android app's old code, kept for its versionCode (iosApp/scripts/android-version.py); it does not build as is.
if (providers.gradleProperty("includeLegacyAndroid").orNull == "true") include(":app")
