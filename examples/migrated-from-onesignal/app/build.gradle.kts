import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
    namespace = "ai.openpush.example.migrated"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.openpush.example.migrated"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.2.2"

        buildConfigField(
            "String",
            "OPENPUSH_APP_ID",
            exampleProperty("OPENPUSH_APP_ID", "replace-with-app-id").asBuildConfigString(),
        )
        manifestPlaceholders["openPushSdkKey"] =
            exampleProperty("OPENPUSH_SDK_KEY", "replace-with-public-sdk-key")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    // The ONLY OpenPush artifact this app depends on. It is the whole migration:
    // no source file imports `ai.openpush.android.OpenPush`, so if any call site
    // still needed a member the facade does not carry, this build would fail
    // rather than quietly reach past the facade into the core SDK.
    //
    // Under OneSignal this line read:
    //   implementation("com.onesignal:OneSignal:5.1.+")
    implementation("ai.openpush:openpush-android-onesignal:1.2.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    // Already here under OneSignal: v5's `Notifications.requestPermission` is a
    // suspend function on Android too, so this is not a migration edit.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-messaging")
}
