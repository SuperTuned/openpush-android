import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

group = "ai.openpush"
version = providers.gradleProperty("OPENPUSH_VERSION").get()

android {
    namespace = "ai.openpush.android.onesignal"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    // Java 11 bytecode: Unity 2022.3's AGP 7.1.2 D8 rejects Java 17 sealed classes. Native apps on a
    // current AGP are unaffected.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    publishing { singleVariant("release") { withSourcesJar() } }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// Build with JDK 17 regardless of JAVA_HOME: javac 21 writes nameless enum MethodParameters that
// AGP 7.1.2's D8 cannot read. Point Gradle at a JDK 17 with
// -Porg.gradle.java.installations.paths=<jdk17 home> when none is auto-detected.
kotlin { jvmToolchain(17) }

dependencies {
    // `api`, not `implementation`: the facade returns OpenPush types (IdentityResult,
    // PushSubscriptionState, ListenerHandle, ClickEvent, ForegroundEvent), and
    // `implementation` would hide them from consumers.
    api(project(":openpush"))
    api(project(":openpush-in-app-messages"))
    // v5 hosts never wrote token, rendering or click code; the FCM module supplies all three.
    api(project(":openpush-fcm"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
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
                artifactId = "openpush-android-onesignal"
                version = project.version.toString()
                from(components["release"])
                artifact(javadocJar)
                pom {
                    name.set("OpenPush Android OneSignal Compatibility")
                    description.set(
                        "Optional drop-in OneSignal v5 name alias for the OpenPush Android SDK. Names only — no " +
                            "OneSignal implementation code. OneSignal is a trademark of OneSignal, Inc. OpenPush is not " +
                            "affiliated with, endorsed by, or sponsored by OneSignal, Inc.",
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
