# Getting started

OpenPush is receive-only. Your app supplies an OpenPush App ID and public SDK key, and the SDK needs
the device's Firebase token. On Path 1 your app fetches that token and forwards it; on Path 2 the
optional FCM module fetches and registers it for you. Choose a path based on whether your app
already owns a `FirebaseMessagingService`.

## Prerequisites

| Requirement | What you need |
| --- | --- |
| JDK | JDK 17 must be installed (Gradle uses it as the build toolchain; a newer JDK can run Gradle, but JDK 17 must be present) |
| Android Gradle Plugin | AGP 8.12 or newer |
| Gradle | Use the committed `./gradlew` wrapper; do not depend on a machine-wide Gradle version |
| Android | `minSdk 24`; compile against Android SDK 36 |
| Firebase | A Firebase project, an Android app registered to your package, and its `google-services.json` (Path 2 can use three manifest values instead) |
| OpenPush | An App ID and public SDK key |

Place `google-services.json` in the app module, normally `app/google-services.json`, and apply the
Google Services plugin in that module. Keep the file out of source control when it belongs to a
private Firebase project.

Add your App ID and public SDK key as application metadata:

```xml
<application ...>
    <meta-data android:name="ai.openpush.app_id" android:value="your-app-id" />
    <meta-data android:name="ai.openpush.sdk_key" android:value="pk_live_..." />
</application>
```

Then initialize once from `Application.onCreate`:

```kotlin
OpenPush.initialize(applicationContext)
```

The core library declares `INTERNET` and `POST_NOTIFICATIONS` and merges the AndroidX Startup
provider that observes Activity lifecycle. Android 13 and newer still require a runtime notification
permission request.

## Path 1 — your app owns Firebase messaging

Add the Firebase Messaging dependency and the core OpenPush artifact:

```kotlin
implementation(platform("com.google.firebase:firebase-bom:<version>"))
implementation("com.google.firebase:firebase-messaging")
implementation("ai.openpush:openpush-android:<version>")
```

Declare your service:

```xml
<service
    android:name=".AppMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

Forward token refreshes and every delivered message. `beginReceived` returns `false` for a second
copy of a push already handled, which the host drops. A `true` result from `processForegroundMessage`
means a foreground listener called `preventDefault()` and the host should skip its own display:

```kotlin
class AppMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        OpenPush.register(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!OpenPush.Notifications.beginReceived(message.data)) return
        try {
            if (OpenPush.Notifications.processForegroundMessage(message.data)) return
            renderWithYourNotificationBuilder(message.data)
        } finally {
            OpenPush.Notifications.finishReceived(message.data)
        }
    }
}
```

`onNewToken` fires only when Firebase issues a new token, so also forward the current one after
`initialize`:

```kotlin
FirebaseMessaging.getInstance().token.addOnSuccessListener(OpenPush::register)
```

Forward notification opens from your tap Activity or receiver with
`OpenPush.Notifications.processClick(data)`. The
[Feature Lab](examples/receive-only-example) contains complete host-owned rendering and tap wiring.

## Path 2 — use the optional FCM module

Use this path only when the app does not declare its own `FirebaseMessagingService`:

```kotlin
implementation(platform("com.google.firebase:firebase-bom:<version>"))
implementation("com.google.firebase:firebase-messaging")
implementation("ai.openpush:openpush-android-fcm:<version>")
```

The module merges its messaging service and click Activity, fetches the token and registers it
after every `initialize`, registers rotated tokens, renders notifications, and posts delivery
receipts. Your app makes no Firebase call. Configure display defaults in the application manifest:

```xml
<meta-data android:name="ai.openpush.notification_channel_id" android:value="messages" />
<meta-data android:name="ai.openpush.notification_icon" android:resource="@drawable/ic_notification" />
```

Without `google-services.json`, the module creates the default Firebase app from your Firebase
app's values instead:

```xml
<meta-data android:name="ai.openpush.fcm.project_id" android:value="your-project-id" />
<meta-data android:name="ai.openpush.fcm.application_id" android:value="1:1234567890:android:abc123" />
<meta-data android:name="ai.openpush.fcm.api_key" android:value="AIza…" />
```

To forward tokens yourself while keeping the module, add
`<meta-data android:name="ai.openpush.fcm.automatic_token" android:value="false" />`.

Do not let two services receive the same Firebase intent. If your app already owns one, use Path 1,
or keep the module in **Forward** mode: add
`<meta-data android:name="ai.openpush.fcm.delivery" android:value="forward" />`, and call
`OpenPushMessagingService.handle(this, message)` for pushes where `OpenPush.messageId(message.data)`
is not null, and `OpenPush.register(token)` from `onNewToken`. The module then disables its own
merged service at startup.

The `ai.openpush.setup` Gradle plugin does this for you: `./gradlew :app:openpushDoctor` lists what
the app already has, `./gradlew :app:openpushSetup` records Reuse / Create new choices in
`app/openpush-setup.json` and prints the forwarding lines for your service, and the build fails if
two services would split delivery with no choice recorded. See the
[README](README.md#setup-plugin) and the
[existing FCM service example](examples/existing-fcm-service-example). The [FCM module example](examples/fcm-module-example) shows the zero-service host and
builds a minified release APK.

## Notification permission result

Request permission from a resumed Activity:

```kotlin
OpenPush.Notifications.requestPermission(activity, fallbackToSettings = true) { granted -> /* … */ }
```

The prompt runs in OpenPush's own transparent Activity, so the callback and suspend forms finish
without your Activity forwarding `onRequestPermissionsResult`. Existing forwarding code into
`OpenPush.Notifications.onPermissionResult` is harmless: it never causes a second callback.
`requestPermission` without an Activity, called from `onCreate` before the Activity resumes, starts
the prompt when it resumes. Outside a coroutine pass a callback for the answer:

```kotlin
OpenPush.Notifications.requestPermission { granted -> /* … */ }
```

In a coroutine, the form without a callback suspends until the user answers:

```kotlin
lifecycleScope.launch {
    val granted = OpenPush.Notifications.requestPermission()
}
```

Java uses the callback form: `OpenPush.Notifications.requestPermission(false, granted -> Unit.INSTANCE);`.

## Optional in-app messages

Add `ai.openpush:openpush-android-in-app-messages:<version>`. Its initializer joins the same merged
Startup provider; there is no Activity base class or second initialization call. In-app messages
begin fetching after registration supplies a subscription ID, and the message's trigger conditions
must be satisfied before it can display.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| No provider token arrives | Confirm `google-services.json` belongs to the app package, is under the app module, and the Google Services plugin is applied. On Path 2, a `provider token unavailable` line in `OpenPush.onLog` names the reason, and the SDK asks again on the next foreground. |
| Requests report status `-1` | Confirm the merged manifest still contains `android.permission.INTERNET`; the library declares it, but a host manifest rule can remove it. Also confirm the device has network access. |
| Pushes arrive only sometimes, or never render | Two services receive `com.google.firebase.MESSAGING_EVENT`. Run `./gradlew :app:openpushDoctor`, then use Forward mode or remove one service. |
| The notification permission prompt never appears or its callback never runs | Call `requestPermission` while an Activity is resumed, and keep `ai.openpush.android.OpenPushPermissionActivity` in the merged manifest (without it, forward `Activity.onRequestPermissionsResult` to `OpenPush.Notifications.onPermissionResult`). On Android versions below 13 there is no runtime prompt. |
| An in-app message never appears | Wait for `OpenPush.User.pushSubscription.id` to be assigned, then verify the message's trigger values and time conditions. |

For an existing-provider switch, use the [migration guide](MIGRATING-FROM-ONESIGNAL.md) and the
[migration example](examples/migrated-from-onesignal). For the full surface, see [API.md](API.md).
