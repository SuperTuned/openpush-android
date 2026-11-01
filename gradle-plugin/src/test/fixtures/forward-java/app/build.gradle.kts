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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The SDK under test, from `./gradlew publishToMavenLocal` of this checkout.
dependencies {
    implementation("ai.openpush:openpush-android:${providers.gradleProperty("openpushVersion").get()}")
    implementation("ai.openpush:openpush-android-fcm:${providers.gradleProperty("openpushVersion").get()}")
}
