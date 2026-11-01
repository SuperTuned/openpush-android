import java.util.Properties

plugins {
    `java-gradle-plugin`
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    `maven-publish`
}

// One version for the SDK and its setup plugin, from the SDK's own gradle.properties.
val sdkProperties = Properties().apply { rootDir.resolve("../gradle.properties").inputStream().use(::load) }

group = "ai.openpush"
version = sdkProperties.getProperty("OPENPUSH_VERSION")

// The AGP the SDK repository builds with. The plugin compiles against its public API only and the
// TestKit fixtures run on it.
val agpVersion = "8.12.2"

// Java 17 bytecode, the floor AGP 8 itself requires, so no toolchain has to be provisioned.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

val agpForFixtures: Configuration by configurations.creating

dependencies {
    compileOnly("com.android.tools.build:gradle-api:$agpVersion")
    testImplementation("junit:junit:4.13.2")
    agpForFixtures("com.android.tools.build:gradle:$agpVersion")
}

gradlePlugin {
    plugins {
        create("openpushSetup") {
            id = "ai.openpush.setup"
            implementationClass = "ai.openpush.gradle.setup.OpenPushSetupPlugin"
            displayName = "OpenPush setup"
            description = "Detects existing push plumbing in an Android app and records it in openpush-setup.json."
        }
    }
}

// Fixtures apply com.android.application by id, so AGP rides the injected plugin classpath next to
// this plugin and both load in one class loader.
tasks.pluginUnderTestMetadata { pluginClasspath.from(agpForFixtures) }

tasks.test {
    // The fixtures build real Android projects.
    environment("ANDROID_HOME", System.getenv("ANDROID_HOME") ?: "")
    maxHeapSize = "1g"
    // Fixtures that compile against the SDK resolve this version from mavenLocal.
    systemProperty("openpush.version", version.toString())
}

publishing {
    // Every publication this build makes (the plugin jar and its plugin-marker POM) carries the
    // metadata Maven Central and the Gradle Plugin Portal require.
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("OpenPush setup Gradle plugin")
            description.set(
                "Detects existing push plumbing in an Android app, records Reuse / Create new choices in " +
                    "openpush-setup.json and applies them to the build.",
            )
            url.set("https://github.com/SuperTuned/openpush-android")
            licenses {
                license {
                    name.set("Apache License 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    name.set("JuneSoftware Inc")
                    organization.set("JuneSoftware Inc")
                    organizationUrl.set("https://openpush.ai")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/SuperTuned/openpush-android.git")
                developerConnection.set("scm:git:https://github.com/SuperTuned/openpush-android.git")
                url.set("https://github.com/SuperTuned/openpush-android")
            }
        }
    }
    // The same plain directory repository the SDK modules publish to, for the Unity package and for
    // hosts without Maven Central (`publishAllPublicationsToOpenpushDirRepository -PopenpushRepoDir=<dir>`).
    repositories {
        maven {
            name = "openpushDir"
            url = uri(
                findProperty("openpushRepoDir")?.toString()
                    ?: rootDir.resolve("../build/openpush-repo").path,
            )
        }
    }
}
