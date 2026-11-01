-keepclassmembers class ai.openpush.android.inappmessages.IamBridge { @android.webkit.JavascriptInterface <methods>; }
# Wrapper SDKs call this API over JNI by name; keep the public surface of this package.
-keep public class ai.openpush.android.inappmessages.* { public *; }
