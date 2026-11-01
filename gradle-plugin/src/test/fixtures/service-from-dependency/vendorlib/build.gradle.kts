// Stands in for an SDK whose AAR merges its own messaging service into the app.
plugins { id("com.android.library") }

android {
    namespace = "com.example.vendor"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
}
