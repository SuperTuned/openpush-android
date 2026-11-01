pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    // The setup plugin from this checkout. A host on the published plugin drops this line.
    includeBuild("../../gradle-plugin")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "openpush-existing-fcm-service-example"
include(":app")

// Builds against this checkout of the SDK instead of the published artifacts.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("ai.openpush:openpush-android")).using(project(":openpush"))
        substitute(module("ai.openpush:openpush-android-fcm")).using(project(":openpush-fcm"))
        substitute(module("ai.openpush:openpush-android-live-updates"))
            .using(project(":openpush-live-updates"))
    }
}
