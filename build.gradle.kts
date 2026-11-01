plugins {
    id("com.android.library") version "8.12.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.diffplug.spotless") version "6.25.0" apply false
}

apply(plugin = "com.diffplug.spotless")

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlin {
        // The setup plugin is its own build; its fixture projects are test data and are left as written.
        target("examples/**/src/**/*.kt", "gradle-plugin/src/main/**/*.kt", "gradle-plugin/src/test/kotlin/**/*.kt")
        ktlint("1.3.1").editorConfigOverride(
            mapOf(
                "max_line_length" to "120",
                "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_no-wildcard-imports" to "disabled",
            ),
        )
    }
}

subprojects {
    apply(plugin = "com.diffplug.spotless")
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint("1.3.1").editorConfigOverride(
                mapOf(
                    "max_line_length" to "120",
                    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                    "ktlint_standard_filename" to "disabled",
                    "ktlint_standard_no-wildcard-imports" to "disabled",
                ),
            )
        }
    }
}

// minSdk is 24 but notification channels and much of the notification API are 26+, so Android
// Lint's API checks gate every library module: an unguarded newer call fails `./gradlew lint`.
subprojects {
    plugins.withId("com.android.library") {
        configure<com.android.build.api.dsl.LibraryExtension> {
            lint {
                abortOnError = true
                warningsAsErrors = false
                checkOnly += setOf("NewApi", "InlinedApi")
                error += setOf("InlinedApi")
            }
        }
    }
}

// Unity 2022.3 exports an AGP 7.1.2 Gradle project whose D8 (3.1.x) rejects Java 17 sealed
// classes and NPEs on javac-21 enum MethodParameters. The Unity package vendors these AARs, so
// every release AAR must dex with THAT D8. AGP 7.1.2 bundles D8 (3.1.66) inside its builder jar,
// resolved from AGP 7.1.2's own dependency graph so the version is exactly the one Unity runs.
val legacyD8: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    legacyD8("com.android.tools.build:gradle:7.1.2")
}

val legacyDexModules = listOf("openpush", "openpush-fcm", "openpush-in-app-messages", "openpush-live-updates")

tasks.register("legacyDexCheck") {
    group = "verification"
    description = "Dexes every release AAR with the D8 bundled in AGP 7.1.2 (Unity 2022.3)."
    dependsOn(legacyDexModules.map { ":$it:assembleRelease" })
    doLast {
        val d8Jar = legacyD8.resolve().firstOrNull { it.name == "builder-7.1.2.jar" }
            ?: error("AGP 7.1.2 resolved no builder jar (which bundles D8)")
        val sdk = System.getenv("ANDROID_HOME") ?: error("ANDROID_HOME is not set")
        val androidJar = file("$sdk/platforms/android-34/android.jar")
        check(androidJar.exists()) { "install platforms;android-34 (sdkmanager \"platforms;android-34\")" }
        val work = layout.buildDirectory.dir("legacy-dex").get().asFile
        work.deleteRecursively()
        work.mkdirs()
        legacyDexModules.forEach { module ->
            val aar = project(":$module").layout.buildDirectory.file("outputs/aar/$module-release.aar").get().asFile
            check(aar.exists()) { "missing $aar" }
            val classes = File(work, "$module-classes.jar")
            zipTree(aar).matching { include("classes.jar") }.singleFile.copyTo(classes, overwrite = true)
            val out = File(work, module).apply { mkdirs() }
            println("legacyDexCheck: dexing $module with ${d8Jar.name}")
            javaexec {
                classpath(d8Jar)
                mainClass.set("com.android.tools.r8.D8")
                args("--release", "--min-api", "24", "--lib", androidJar.path, "--output", out.path, classes.path)
            }
        }
        println("legacyDexCheck: every release AAR dexes with AGP 7.1.2's D8")
    }
}
