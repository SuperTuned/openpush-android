# OpenPush Android Feature Lab

This Material 3 sample is an interactive companion to `openpush-android`. It
opens in Demo mode, so every workspace can be explored without credentials or
network traffic, while still using native Android notifications and Live
Updates. Switch to Live mode after configuring a real OpenPush app.

The five workspaces cover:

- **Status** — notification permission, subscription state, registration,
  consent-gated startup, and Simple, Image, Button, Custom, Sound, and Grouped PNS fixtures.
- **User** — login/logout plus singular and plural tag and alias lifecycle operations.
- **In-app** — pause and resume display, set and clear trigger values one at a
  time or several in one call, watch lifecycle and click events arrive, and show a
  bundled document through the shipping presenter.
- **Events** — Custom Events, foreground interception, click callbacks, and an observable event log.
- **Activity** — start, update, and end an Android Live Update.

The In-app workspace's **demo document** works in Demo mode, with no credentials
and no network: `OpenPush.InAppMessages.previewLocalDocument` loads
`app/src/main/assets/iam_demo.html` into the SDK's own WebView and popup, so the
bridge, the layout rules and the link handling are the shipping ones. It records
nothing — no receipt is posted and no impression, dismissal or redisplay state is
written — and it neither consumes nor blocks the real display queue. Everything
else on that workspace needs a workspace: the message list comes from the server.

Before using Live mode on a device:

1. Copy `local.properties.example` to `local.properties`, then set the App ID and
   public SDK key. Set `OPENPUSH_SERVER_URL` only when using a self-hosted server.
   The same values may be passed with `-POPENPUSH_APP_ID=…`,
   `-POPENPUSH_SDK_KEY=…`, and `-POPENPUSH_SERVER_URL=…`.
2. Add the `google-services.json` of your own Firebase project to `app/`. It is
   git-ignored; provider configuration is never committed.
3. Open the Status workspace in Live mode and use **Save credential** to encrypt
   the linked installation's device credential with Android Keystore and request
   provider-token registration.
4. Build and install from this directory with `./gradlew :app:installDebug`.

## Try it on a device

1. `local.properties` holds `OPENPUSH_APP_ID=<your app id>` and `OPENPUSH_SDK_KEY=<your public SDK key>`
   (plus `OPENPUSH_SERVER_URL` for a self-hosted server). `app/google-services.json` is your Firebase
   project's file with an Android app for `ai.openpush.example`. Neither is committed.
2. `./gradlew :app:installDebug`. With credentials set the app opens in Live mode (Settings switches
   modes). On the Status workspace tap **Request notification permission** and **Save credential**.
3. On the Status workspace, look for:
   - **Permission: Allowed** in the top metrics and **Authorization: Allowed** under Notifications.
   - a **Subscription ID** and an issued **Provider token** under Device registration.
   - after a console send to this subscription: **Last push title** and **Last push op_message_id**
     under Notifications, and a **Push received** entry on the Events workspace.

The status screen reports whether this device provides the older ongoing-notification fallback,
is eligible for Android 16 promoted Live Updates, or has promotion disabled. The settings button
appears only for the last case.

The example deliberately contains no server-side send implementation. Demo mode
does not contact OpenPush; Live mode enables SDK operations only when the sample
has non-placeholder credentials and the optional host consent gate permits startup.

## What each file does

| File | Responsibility |
| --- | --- |
| `ExampleApplication.kt` | Applies the optional host consent gate, initializes OpenPush once per process, owns the credential store, and registers only when the installation is linked. |
| `ExampleMessagingService.kt` | Forwards rotated tokens, routes `live_notification` data through the native Live Update renderer before ordinary display, and posts `received` and `confirmed`. |
| `MainActivity.kt` | Hosts the responsive Compose feature lab and forwards notification taps. It forwards no permission result: `requestPermission` answers in OpenPush's own Activity. |
| `ExampleLabModel.kt` | Connects the UI to notification, subscription, identity, tag, alias, lifecycle-listener, in-app, and Live Update APIs. |
| `ExampleNotificationFixtures.kt` | Posts five host-rendered PNS fixtures and sends the Grouped pair directly through `OpenPushNotificationRenderer`. |
| `assets/iam_demo.html` | The in-app demo document. Self-contained, no network, and speaks the same `window.OpenPushAndroid.postMessage` bridge a server-rendered message speaks. |

## Registration and receipt behavior

`register` runs only when the credential closure returns a value, so an unlinked
device never attempts registration. `confirmed` is posted only when Android was
actually asked to present the notification — if notifications are disabled, the
push still reports `received` and nothing more, which is the inbox path rather
than a failure.

Every delivery goes through `OpenPush.Notifications.processForegroundMessage(data)`,
which posts `received` whether or not the app is in front. The host does not need
its own foreground check: the SDK observes the activity lifecycle from
`initialize`, so it consults the registered foreground listeners — the ones that
can suppress the host's own display by calling `preventDefault()` — only while
this process has a visible activity. A push that arrives in the background posts
its `received` receipt, reaches no listener, and is always displayed.

The subscription control writes the subscription's status (`-2` to stop sends, `1` to
resume) and does not delete the subscription or the provider token, so an
opted-out device keeps its row and can be opted back in. Identity, tag, and alias
controls exercise the public SDK locally; the example remains receive-only and
does not expose a remote send surface.

Registration callbacks are scoped to the credential that started the request, so
a response arriving after sign-out or a workspace switch is ignored.

The sample owns its Firebase service and ordinary notification renderer. Only the **Grouped** local
fixture calls `OpenPushNotificationRenderer(context).render(data)` directly: two children share
`op_android_group`, so the optional renderer adds its summary and records the children for **Remove
group**. **Clear posted fixtures** calls `OpenPush.Notifications.clearAll()` and clears the app shade.

## Android Live Update test payloads

Send these as ordinary OpenPush messages to this device. Use the same `collapse_id` for every
event in one lifecycle. The Android renderer also accepts `dismiss` as an alias for `end`.

```json
{"collapse_id":"delivery-42","ttl":120,"data":{"live_notification":{"key":"progress","instance_id":"delivery-42","event":"start","sequence":1,"event_attributes":{"title":"Delivery","body":"Courier assigned"},"event_updates":{"current_progress":10,"max_progress":100,"short_text":"10%"}}}}
```

```json
{"collapse_id":"delivery-42","ttl":120,"data":{"live_notification":{"key":"progress","instance_id":"delivery-42","event":"update","sequence":2,"event_attributes":{"title":"Delivery"},"event_updates":{"current_progress":70,"max_progress":100,"short_text":"70%"}}}}
```

```json
{"collapse_id":"delivery-42","data":{"live_notification":{"key":"progress","instance_id":"delivery-42","event":"end","sequence":3}}}
```

Android 16 promotion is system-controlled and requires an ongoing, user-initiated, time-sensitive
use case. Older Android versions still update one quiet ongoing notification in place. Unpinning or
dismissing it prevents later updates from resurrecting that instance; a new `start` begins a new
lifecycle.

## Building against the published artifact

`settings.gradle.kts` substitutes the core, FCM, in-app, and Live Updates artifacts with projects
from the surrounding checkout. The artifacts are not yet on Maven Central; once they are, remove the
`includeBuild` block to resolve them from there instead.

## Sign-out

The host app owns unlinking. Retire the server-side delivery address first, then
use **Wipe credential** and **Clear token** on the Status workspace. Those buttons
call `DeviceCredentialStore.wipe()` and `OpenPush.clearToken()`. A local-only
wipe leaves the provider token owned by the previous workspace and makes the next
registration fail with a conflict.
