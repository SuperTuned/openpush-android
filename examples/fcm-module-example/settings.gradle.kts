pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "openpush-fcm-module-example"
include(":app")

// Builds against this checkout of the SDK instead of the published artifacts.
// Once the SDK is on Maven Central, remove this block to resolve ai.openpush:openpush-android-fcm from there.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("ai.openpush:openpush-android")).using(project(":openpush"))
        substitute(module("ai.openpush:openpush-android-fcm")).using(project(":openpush-fcm"))
        substitute(module("ai.openpush:openpush-android-live-updates"))
            .using(project(":openpush-live-updates"))
    }
}
