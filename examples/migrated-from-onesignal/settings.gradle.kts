pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "openpush-migrated-from-onesignal"
include(":app")

// Builds against this checkout of the SDK instead of the published artifact.
// Once the SDK is on Maven Central, remove this block to resolve
// ai.openpush:openpush-android-onesignal from there.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("ai.openpush:openpush-android-onesignal"))
            .using(project(":openpush-onesignal-compat"))
    }
}
