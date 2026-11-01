package ai.openpush.gradle.setup

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File

/**
 * A copy of `src/test/fixtures/<name>` in a scratch directory, completed with the settings and app
 * build file every fixture shares unless it brings its own.
 */
internal class FixtureProject(name: String, root: File) {
    val directory: File = root.resolve(name)
    val app: File = directory.resolve("app")
    val setupFile: File = app.resolve(SetupFile.NAME)

    init {
        File("src/test/fixtures/$name").copyRecursively(directory)
        directory.resolve("settings.gradle.kts").takeUnless(File::exists)?.writeText(SETTINGS)
        app.resolve("build.gradle.kts").takeUnless(File::exists)?.writeText(APP_BUILD)
        directory.resolve("gradle.properties").takeUnless(File::exists)?.writeText(
            "android.useAndroidX=true\nopenpushVersion=${System.getProperty("openpush.version")}\n",
        )
        System.getenv("ANDROID_HOME")?.takeIf { it.isNotEmpty() }?.let {
            directory.resolve("local.properties").writeText("sdk.dir=$it\n")
        }
    }

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(directory)
        .withPluginClasspath()
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    fun build(vararg arguments: String): BuildResult = runner(*arguments).build()

    fun fail(vararg arguments: String): BuildResult = runner(*arguments).buildAndFail()

    /** The variant's final merged manifest: the delivery check's pass-through output. */
    fun mergedManifest(variant: String = "debug"): String {
        val step = "openpushCheck" + variant.replaceFirstChar(Char::uppercaseChar)
        return app.resolve("build/intermediates").walkTopDown()
            .first { it.name == "AndroidManifest.xml" && step in it.path }
            .readText()
    }

    @Suppress("UNCHECKED_CAST")
    fun found(): List<Map<String, Any?>> = setup()["found"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    fun setup(): Map<String, Any?> = Json.parse(setupFile.readText()) as Map<String, Any?>

    private companion object {
        const val SETTINGS = """
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // The SDK under test, published by `./gradlew publishToMavenLocal` of this checkout.
        mavenLocal { content { includeGroup("ai.openpush") } }
        google()
        mavenCentral()
    }
}

rootProject.name = "fixture"
include(":app")
"""

        const val APP_BUILD = """
plugins {
    id("com.android.application")
    id("ai.openpush.setup")
}

android {
    namespace = "com.example.fixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.fixture"
        minSdk = 24
        targetSdk = 36
    }
}
"""
    }
}
