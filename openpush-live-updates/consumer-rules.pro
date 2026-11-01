# Instantiated by the merged application manifest.
-keep public class ai.openpush.android.liveupdates.OpenPushLiveUpdateDismissReceiver {
    public <init>();
}
# Wrapper SDKs call this API over JNI by name; keep the public surface of this package.
-keep public class ai.openpush.android.liveupdates.* { public *; }
# API 36 types referenced behind SDK_INT guards; a host compiling against an older SDK lacks them.
-dontwarn android.app.Notification$ProgressStyle
-dontwarn android.app.Notification$ProgressStyle$Segment
