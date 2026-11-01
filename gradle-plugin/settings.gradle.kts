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

// A build of its own so SDK consumers never resolve the Android Gradle Plugin through the SDK. A host
// applies it with `pluginManagement { includeBuild("<checkout>/openpush-android/gradle-plugin") }`, or
// from the openpushDir repository the SDK modules publish to.
rootProject.name = "openpush-gradle-plugin"
