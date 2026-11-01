#!/usr/bin/env bash
# Wrapper SDKs call the public API over JNI by name, which R8 cannot see. Every AAR must carry
# keep rules for its own public package (a minified Unity build otherwise fails
# with ClassNotFoundException: ai.openpush.android.OpenPush).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
status=0
expect() { # module rule
  local rules; rules="$(unzip -p "$root/$1/build/outputs/aar/$1-release.aar" proguard.txt 2>/dev/null || true)"
  printf '%s\n' "$rules" | grep -qF -- "$2" || { echo "$1: missing '$2'"; status=1; }
}
expect openpush '-keep public class ai.openpush.android.* { public *; }'
expect openpush '-keep interface kotlin.jvm.functions.Function* { *; }'
expect openpush '-keep class kotlin.Unit { public static final kotlin.Unit INSTANCE; }'
expect openpush '-keep class * implements ai.openpush.android.OpenPushBackgroundMessageHandler { public <init>(); }'
expect openpush-fcm '-keep public class ai.openpush.android.fcm.* { public *; }'
expect openpush-in-app-messages '-keep public class ai.openpush.android.inappmessages.* { public *; }'
expect openpush-live-updates '-keep public class ai.openpush.android.liveupdates.* { public *; }'
expect openpush-live-updates '-dontwarn android.app.Notification$ProgressStyle'
expect openpush-live-updates '-dontwarn android.app.Notification$ProgressStyle$Segment'
[ "$status" -eq 0 ] && echo "✓ consumer rules keep every public package"
exit "$status"
