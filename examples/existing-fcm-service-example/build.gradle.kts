plugins {
    id("com.android.application") version "8.12.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Declared beside AGP so both load in one class loader; the app module applies it.
    id("ai.openpush.setup") apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}
