# migrated-from-onesignal (Android)

> OneSignal is a trademark of OneSignal, Inc. OpenPush is not affiliated with, endorsed by, or
> sponsored by OneSignal, Inc. The OneSignal name appears only to describe the optional
> compatibility layer for apps migrating from the OneSignal SDK.

A receive-only app that ran on OneSignal v5 and now runs on OpenPush. It exists to
answer one question with a diff instead of a promise: **how much of an app has to
change?**

`Before/` holds the pre-migration version of this sample app, so the diff below is not a
hand-written illustration — it is output you can regenerate:

```sh
for f in ExampleApplication MainActivity ExampleMessagingService; do
  diff -u "Before/$f.kt.onesignal" "app/src/main/java/ai/openpush/example/migrated/$f.kt"
done
diff -u Before/AndroidManifest.xml.onesignal app/src/main/AndroidManifest.xml
```

`Before/` files carry an `.onesignal` suffix on purpose: they reference
`com.onesignal`, which this project does not depend on, so they must not be
compiled. They sit outside `app/src`, so Gradle never sees them.

## The whole migration

Three edits, plus one documented exception.

1. **One import line, in each file that already imported OneSignal** —
   `import com.onesignal.OneSignal` → `import ai.openpush.android.onesignal.*`.
   The wildcard is deliberate: the refused v5 members are Kotlin extension
   declarations and only resolve where the package is in scope, so a single-class
   import turns their messages back into bare `unresolved reference` errors.
   Two files here. It is a find-and-replace: the package differs from
   `com.onesignal`, which is also why both SDKs can sit in one app while you
   migrate screen by screen. The dependency line changes with it:

   ```diff
   -implementation("com.onesignal:OneSignal:5.1.+")
   +implementation("ai.openpush:openpush-android-onesignal:1.2.2")
   ```

2. **The forwarding this app needs because it kept its own messaging service** —
   a handful of added lines, one added override, and one manifest entry. The facade brings
   the FCM module, which fetches and registers the token and supplies its own
   messaging service and click handling; an app that had no service under v5 skips
   this edit. This app had one, so:
   - `ExampleMessagingService.onNewToken` → `OneSignal.register(token)`
   - `ExampleMessagingService.onMessageReceived` →
     `OneSignal.Notifications.beginReceived` / `processForegroundMessage` / `finishReceived`
     (the duplicate check for a host-owned service)
   - the tap trampoline → `OneSignal.Notifications.processClick(extras)`
   - `MainActivity.onRequestPermissionsResult` →
     `OneSignal.Notifications.onPermissionResult(requestCode, grantResults)` (optional; kept
     here to show the legacy forward stays harmless)

   - `AndroidManifest.xml` → removes the module's merged `OpenPushMessagingService`
     with `tools:node="remove"`, so the two services do not filter the same intent

   `ExampleMessagingService.kt` gains its own import as part of this edit: that
   file did not talk to OneSignal before, because the messaging work was done
   elsewhere. **This app kept the `FirebaseMessagingService` it already had** — the
   migration forwards into it rather than replacing it. The token already on the
   device is still registered at launch by the module; `onNewToken` covers rotations.

3. **One non-code configuration entry**: `ai.openpush.sdk_key` manifest
   `<meta-data>`. That is the only reason `initWithContext` can keep the v5
   signature.

**The exception** — the fourth edit, which this repo's guide names in its §1.1, the
iOS guide in its §1, and the Unity guide in its §1.1: OneSignal v5's listeners take
a **conforming object**, and OpenPush's take a **lambda** returning a
`ListenerHandle` (see
[`MIGRATING-FROM-ONESIGNAL.md` §1.1](../../MIGRATING-FROM-ONESIGNAL.md#11-the-fourth-edit-listeners-and-observers-become-lambdas),
and the click and foreground listener rows in its §2). So the
`INotificationClickListener` object becomes a lambda, and so does the
`IInAppMessageClickListener` object beside it — one exception, applied twice. Those
are the only hunks below that are not one of the three edits.

Each platform's fourth edit has a different shape — an object becoming a lambda
here, a protocol conformance becoming a closure on iOS, an `EventHandler` becoming
an `Action<T>` on Unity — but it is the same underlying fact: OpenPush's listener
registration takes a function, never a conforming type.

**No call site changed.** `initWithContext`, the suspend
`Notifications.requestPermission(fallbackToSettings = true)`, `login`, `logout`,
`User.addTag`, `User.addAlias`, `User.externalId`,
`User.pushSubscription.optedIn` / `optIn()` / `optOut()` / `id`, and
`InAppMessages.addTrigger` are all untouched.
That is the claim this example exists to make checkable.

## The diff

### `ExampleApplication.kt` — edit 1, and the exception

```diff
 package ai.openpush.example.migrated

-import com.onesignal.OneSignal                                  # EDIT 1
-import com.onesignal.inAppMessages.IInAppMessageClickEvent      # THE EXCEPTION
-import com.onesignal.inAppMessages.IInAppMessageClickListener   # THE EXCEPTION
-import com.onesignal.notifications.INotificationClickEvent      # THE EXCEPTION
-import com.onesignal.notifications.INotificationClickListener   # THE EXCEPTION
+import ai.openpush.android.onesignal.*                          # EDIT 1
 import android.app.Application
 import android.app.NotificationChannel
 import android.app.NotificationManager
@@
         OneSignal.initWithContext(this, APP_ID)

-        OneSignal.Notifications.addClickListener(object : INotificationClickListener {
-            override fun onClick(event: INotificationClickEvent) {
-                Log.i(TAG, "notification opened · ${event.notification.notificationId}")
-            }
-        })
+        OneSignal.Notifications.addClickListener { event ->
+            Log.i(TAG, "notification opened · ${event.messageId}")
+        }

-        OneSignal.InAppMessages.addClickListener(object : IInAppMessageClickListener {
-            override fun onClick(event: IInAppMessageClickEvent) {
-                Log.i(TAG, "in-app clicked · ${event.result.actionId ?: "none"}")
-            }
-        })
+        OneSignal.InAppMessages.addClickListener { event ->
+            Log.i(TAG, "in-app clicked · ${event.actionId ?: "none"}")
+        }

         OneSignal.InAppMessages.addTrigger("example", "1")
     }
```

`OneSignal.initWithContext(this, APP_ID)` is a context line, not a changed one, and
so is `OneSignal.InAppMessages.addTrigger("example", "1")`: the in-app trigger call
survives the migration character for character. Only the two listener registrations
change shape, and both change it the same way.

### `MainActivity.kt` — edit 1, and two forwards

```diff
 package ai.openpush.example.migrated

-import com.onesignal.OneSignal                        # EDIT 1
+import ai.openpush.android.onesignal.*                # EDIT 1
 import android.content.Intent
@@
+    // Optional: the prompt answers in OpenPush's own Activity; this legacy forward stays harmless.
+    @Deprecated("ComponentActivity deprecates this in favour of ActivityResult; the SDK needs the raw result.")
+    override fun onRequestPermissionsResult(          # EDIT 2
+        requestCode: Int,
+        permissions: Array<String>,
+        grantResults: IntArray,
+    ) {
+        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
+        OneSignal.Notifications.onPermissionResult(requestCode, grantResults)
+    }
+
     override fun onNewIntent(intent: Intent) {
@@
     private fun reportClick(intent: Intent?) {
         val messageId = intent?.getStringExtra(ExampleMessagingService.EXTRA_MESSAGE_ID) ?: return
         Log.i(TAG, "opened by notification · $messageId")
+        OneSignal.Notifications.processClick(mapOf("op_message_id" to messageId))   # EDIT 2
         intent.removeExtra(ExampleMessagingService.EXTRA_MESSAGE_ID)
     }
```

`askForNotificationPermission()` — v5's `suspend requestPermission` inside
`lifecycleScope.launch` — is untouched. It resumes with the answer from
OpenPush's own permission Activity; the forward above is kept to show it is harmless (it
never resumes anything twice).

### `ExampleMessagingService.kt` — edit 2, into the service that was already here

```diff
 package ai.openpush.example.migrated

+import ai.openpush.android.onesignal.*                # EDIT 2
 import android.app.PendingIntent
@@
     override fun onNewToken(token: String) {
         Log.i(TAG, "provider token rotated")
+        OneSignal.register(token)                     # EDIT 2
     }

     override fun onMessageReceived(message: RemoteMessage) {
+        // A copy of a push this device already handled: drop it before anything runs.
+        if (!OneSignal.Notifications.beginReceived(message.data)) return             # EDIT 2
+        try {
+            // Forward every delivery. Returns true only when the app was foregrounded and a
+            // foreground listener called preventDefault(); the "received" receipt is posted
+            // either way.
+            if (OneSignal.Notifications.processForegroundMessage(message.data)) return   # EDIT 2
         display(message.data)
+        } finally {
+            OneSignal.Notifications.finishReceived(message.data)                     # EDIT 2
+        }
     }
```

`display()` and `tapIntent()` — the app's own notification building — are untouched.

### `AndroidManifest.xml` — edit 3, and edit 2's service removal

```diff
-<manifest xmlns:android="http://schemas.android.com/apk/res/android">
+<manifest xmlns:android="http://schemas.android.com/apk/res/android"
+    xmlns:tools="http://schemas.android.com/tools">
@@
         android:dataExtractionRules="@xml/data_extraction_rules">

+        <meta-data android:name="ai.openpush.sdk_key"                 # EDIT 3
+                   android:value="${openPushSdkKey}" />
+
         <activity
@@
         </service>
+
+        <service                                                       # EDIT 2
+            android:name="ai.openpush.android.fcm.OpenPushMessagingService"
+            tools:node="remove" />
     </application>
```

The `# EDIT n` markers are annotation for this README; they are not in the source.
Across all four files: 3 import lines swapped or added, 4 forwarding lines, 1 added
override, 2 manifest entries, and the listener-object rewrite. Nothing else.

## Running it

1. Copy `local.properties.example` to `local.properties` and fill in the app id
   and public SDK key from your OpenPush app settings. You can instead pass
   `-POPENPUSH_APP_ID=… -POPENPUSH_SDK_KEY=…`. The SDK key is public and may ship
   in the app; a device credential is not.
2. Drop your Firebase project's `google-services.json` into `app/`. It is
   gitignored, and the plugin is applied only when the file is present. Without
   it, the `ai.openpush.fcm.project_id`, `application_id` and `api_key` manifest
   values described in the migration guide work too.
3. `./gradlew :app:assembleDebug` from this directory.

`app/build.gradle.kts` declares **`ai.openpush:openpush-android-onesignal` and no
other OpenPush artifact**, and no source file imports `ai.openpush.android.OpenPush`.
That is the machine-checked half of "no call site changed": if any line in this app
still needed a member the facade does not carry, the build would fail rather than
quietly reach past the facade into the core SDK.

This example carries its own Gradle wrapper, so `./gradlew :app:assembleDebug`
builds it locally from this directory with the same Gradle version CI uses.

## Try it on a device

1. `local.properties` holds `OPENPUSH_APP_ID=<your app id>` and `OPENPUSH_SDK_KEY=<your public SDK key>`.
   `app/google-services.json` is your Firebase project's file with an Android app for
   `ai.openpush.example.migrated`. Neither is committed.
2. `./gradlew :app:installDebug`, then open **Migrated From OneSignal** and allow notifications.
3. The screen should show `subscription <id>`, the FCM `token`, `permission granted true`, and, after a
   console send to this subscription, `last push <title> · <op_message_id>`. The foreground listener
   calls `preventDefault()`, so a push that arrives while the app is open is recorded but not shown.

The device-status lines (`LastPush.kt` and the few lines that read it in `MainActivity.kt` and
`ExampleMessagingService.kt`) are test scaffolding, not part of the migration, so the regenerated diff
shows them beside the edits above.

## See also

- [`MIGRATING-FROM-ONESIGNAL.md`](../../MIGRATING-FROM-ONESIGNAL.md) — every
  forwarded symbol, every refused one with its message, and the note on the four
  top-level refusal objects.
- [`../receive-only-example`](../receive-only-example) — the same app written
  natively against `OpenPush`, with the Live Updates wiring this one leaves out.
