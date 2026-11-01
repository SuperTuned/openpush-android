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

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
