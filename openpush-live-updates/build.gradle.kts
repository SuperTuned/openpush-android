import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

plugins {
    id("com.android.library")
    id("maven-publish")
    id("signing")
}

group = "ai.openpush"
version = providers.gradleProperty("OPENPUSH_VERSION").get()

android {
    namespace = "ai.openpush.android.liveupdates"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    publishing { singleVariant("release") { withSourcesJar() } }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// javac 21 writes nameless enum MethodParameters that Unity 2022.3's D8 NPEs on; javac 17 does not.
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(rootProject.file("API.md"))
    from(rootProject.file("README.md"))
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "ai.openpush"
                artifactId = "openpush-android-live-updates"
                version = project.version.toString()
                from(components["release"])
                artifact(javadocJar)
                pom {
                    name.set("OpenPush Android Live Updates")
                    description.set("Dependency-free Android Live Update renderer shared by native and Unity apps.")
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
        }
        // A plain directory repository: the Unity package vendors this output as its in-package
        // Maven repo (`publishAllPublicationsToOpenpushDirRepository -PopenpushRepoDir=<dir>`).
        repositories {
            maven {
                name = "openpushDir"
                url = uri(
                    findProperty("openpushRepoDir")?.toString()
                        ?: rootProject.layout.buildDirectory.dir("openpush-repo").get().asFile.path,
                )
            }
        }
    }
    extensions.configure<SigningExtension> {
        val signingKey = providers.environmentVariable("MAVEN_SIGNING_KEY").orNull
        val signingPassword = providers.environmentVariable("MAVEN_SIGNING_PASSWORD").orNull
        isRequired = !signingKey.isNullOrBlank() || !signingPassword.isNullOrBlank()
        if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
            useInMemoryPgpKeys(signingKey, signingPassword)
        }
        sign(publishing.publications["release"])
    }
}
