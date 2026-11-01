plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("ai.openpush.setup")
}

// Add the google-services.json of your own Firebase project to this directory. It is never committed,
// so the plugin is applied only when the file is present and the example still builds without it.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "ai.openpush.example.existing"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.openpush.example.existing"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.2.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("ai.openpush:openpush-android-fcm:1.2.2")
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-messaging")
}
