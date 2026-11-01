# FCM module example

The smallest app that integrates OpenPush through `ai.openpush:openpush-android-fcm`
instead of wiring the provider itself. Every host source file in it is listed below.

## What this proves

The module integration path works end to end from a host that declares none of it:

- **The messaging service is merged in, not declared by the app.** `app/src/main/AndroidManifest.xml`
  declares **no** `com.google.firebase.MESSAGING_EVENT` service. The module's
  `OpenPushMessagingService` arrives through manifest merging and takes delivery. If it
  ever stopped being declared — or a host `<service>` silently displaced it — this app
  would receive nothing.
- **The module renders the notification.** The host supplies only two manifest knobs,
  `ai.openpush.notification_channel_id` and `ai.openpush.notification_icon`. There is no
  `Notification.Builder` anywhere in this app.
- **The module registers the device.** The app never calls Firebase: the module fetches the
  token after `initialize` and registers every rotation, and `MainActivity` only redraws when the
  subscription changes.
- **The module handles the tap.** `OpenPushClickActivity` is merged in the same way,
  reports the click, and relaunches the app. The host listener reads the parsed notification title,
  action id and launch URL instead of decoding the raw map.
- **The modules survive R8.** The `release` build type minifies with the default
  optimized ProGuard configuration and nothing else. CI builds it on every change, so a
  keep rule the SDK's `consumer-rules.pro` is missing fails the build here rather than in
  a customer's release. The merged manifest's components — `OpenPushMessagingService`,
  `OpenPushClickActivity` and the Live Updates dismiss receiver — come through the
  minified APK under their original names, and so does `ExampleBackgroundHandler`, which only
  manifest meta-data names: CI checks the release mapping for it.

One dependency line pulls all of it in. `openpush-android-fcm` `api`-exposes
`openpush-android` and `openpush-android-live-updates`, so `OpenPush` itself needs no
second declaration.

## What it deliberately does not show

Runtime UI for tags, aliases, login/logout, outcomes, in-app messages, Live Updates,
consent gating and foreground handling is out of scope here. The two compile-checked recipe
files keep the Java and host-owned-service alternatives honest without running them. The
[receive-only example](../receive-only-example) is the Feature Lab: it takes the opposite
path — a host-owned `FirebaseMessagingService` — and exercises the rest of the API.

## Running it

1. Copy `local.properties.example` to `local.properties` and fill in your app id and
   your **public** SDK key. Set `OPENPUSH_SERVER_URL` only for a self-hosted server.
   `-POPENPUSH_APP_ID=… -POPENPUSH_SDK_KEY=… -POPENPUSH_SERVER_URL=…` works too,
   and `local.properties` is gitignored.
2. Drop your Firebase project's `google-services.json` into `app/`. It is also
   gitignored, and the Google Services plugin is applied only when the file is present —
   so the example still builds without one, which is what CI does.
3. `./gradlew :app:installDebug`

## Try it on a device

1. `local.properties` holds `OPENPUSH_APP_ID=<your app id>` and `OPENPUSH_SDK_KEY=<your public SDK key>`
   (plus `OPENPUSH_SERVER_URL` for a self-hosted server). `app/google-services.json` is your Firebase
   project's file with an Android app for `ai.openpush.example.fcm`. Neither is committed.
2. `./gradlew :app:installDebug`, then open **OpenPush FCM Module** and allow notifications. The prompt
   answers in OpenPush's own Activity; `MainActivity` forwards no permission result.
3. The screen should show `initialized true`, a **Subscription id**, the **FCM token**, and
   **Notification permission granted: true**.
4. Send a push from the console to this subscription. With the app open, **Last push** shows its
   title and `op_message_id` with `seen: received while open`. With the app in the background, the
   module's renderer posts it, and tapping it fills **Last push** with `seen: opened from the shade`.

Left at the placeholder credentials the app builds and launches, logs one line saying it
is not configured, and initializes nothing: a half-configured SDK that registers against
a placeholder app id would be worse than one that plainly did nothing.

## Files

| File | Why it exists |
| --- | --- |
| `settings.gradle.kts` | `includeBuild("../..")` builds against this checkout instead of Maven Central |
| `app/build.gradle.kts` | one OpenPush dependency, the Firebase BOM, credentials into `BuildConfig`, the minified `release` build type |
| `app/src/main/AndroidManifest.xml` | permissions, the two renderer meta-data keys, the background message handler, and no messaging service |
| `ExampleApplication.kt` | creates the channel, initializes when configured, registers the click listener |
| `JavaHostIntegration.java` | initializes through Java static methods and compile-checks the wrapper/tag forms without `INSTANCE` |
| `FcmApiCoverage.kt` | compile-checks the host-service coexistence hook and host-owned renderer alternative without declaring either in the manifest |
| `MainActivity.kt` | one `TextView` of `pushSubscription.snapshot()`, the permission and the last push, redrawn by observers, and the permission prompt (nothing forwarded) |
| `LastPush.kt` | keeps the last push the foreground and click listeners saw, for the status screen |
| `ExampleBackgroundHandler.kt` | the manifest-named background message handler; only the SDK's keep rule brings it through R8 |
