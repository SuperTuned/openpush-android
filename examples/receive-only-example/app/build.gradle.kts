import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Add the google-services.json of your own Firebase project to this directory.
// It is never committed, so the plugin is applied only when the file is present
// and the example still compiles without it.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use(::load)
}

fun exampleProperty(name: String, fallback: String): String =
    (providers.gradleProperty(name).orNull ?: localProperties.getProperty(name) ?: fallback).trim()

fun String.asBuildConfigString(): String = buildString {
    append('"')
    this@asBuildConfigString.forEach { character ->
        append(
            when (character) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> character
            },
        )
    }
    append('"')
}

android {
    namespace = "ai.openpush.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.openpush.example"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.2.2"

        buildConfigField(
            "String",
            "OPENPUSH_APP_ID",
            exampleProperty("OPENPUSH_APP_ID", "replace-with-app-id").asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "OPENPUSH_SDK_KEY",
            exampleProperty("OPENPUSH_SDK_KEY", "replace-with-public-sdk-key").asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "OPENPUSH_SERVER_URL",
            exampleProperty("OPENPUSH_SERVER_URL", "replace-with-server-url").asBuildConfigString(),
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("ai.openpush:openpush-android:1.2.2")
    implementation("ai.openpush:openpush-android-fcm:1.2.2")
    implementation("ai.openpush:openpush-android-in-app-messages:1.2.2")
    implementation("ai.openpush:openpush-android-live-updates:1.2.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui:1.7.3")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.3")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.3")
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-messaging")

    debugImplementation("androidx.compose.ui:ui-tooling:1.7.3")
}
