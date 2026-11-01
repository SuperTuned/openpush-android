import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Add the google-services.json of your own Firebase project to this directory.
// It is never committed, so the plugin is applied only when the file is present
// and the example still compiles without it.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

// The app id and the public SDK key are read from local.properties, or from -P
// properties on the command line, so neither is ever committed. Left at the
// placeholders, the app still builds — it just does not initialize the SDK.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
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
    namespace = "ai.openpush.example.fcm"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.openpush.example.fcm"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
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

    buildTypes {
        // Unsigned, but minified: this build type exists so CI proves the modules'
        // consumer ProGuard rules are enough to survive R8 in a host app. A keep
        // rule the SDK forgot shows up here as a missing class at build time, or
        // as a component R8 stripped out of the merged manifest's app.
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    // The only OpenPush artifact this app names. It `api`-exposes openpush-android
    // and openpush-android-live-updates, so `OpenPush` itself is on the compile
    // classpath without a second dependency line.
    implementation("ai.openpush:openpush-android-fcm:1.2.2")
    // The module already depends on firebase-messaging; the BOM is here because the
    // host still owns the Firebase version alignment the Google Services plugin
    // expects, and because this app calls FirebaseMessaging.getInstance() directly.
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-messaging")
}
