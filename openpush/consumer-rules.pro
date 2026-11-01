# Wrapper SDKs (the Unity package) call this API over JNI by name. R8 cannot see those calls, so
# the public surface of this package is kept. Manifest components are kept by AGP's own rules.
-keep public class ai.openpush.android.* { public *; }
# Kotlin function types are the listener types of the public API.
-keep interface kotlin.jvm.functions.Function* { *; }
-keep class kotlin.Unit { public static final kotlin.Unit INSTANCE; }
# A background message handler is named only in manifest meta-data and created by reflection, so R8
# sees no use of it; keep every implementation and its no-argument constructor.
-keep class * implements ai.openpush.android.OpenPushBackgroundMessageHandler { public <init>(); }
