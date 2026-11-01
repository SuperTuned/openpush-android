pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "openpush-android"
include(":openpush")
include(":openpush-fcm")
include(":openpush-live-updates")
include(":openpush-in-app-messages")
include(":openpush-onesignal-compat")
