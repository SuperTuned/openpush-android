# openpush-android

Receive-only Android library for registering a device with OpenPush.ai and posting notification delivery receipts. It intentionally contains no message creation, campaign management, or send API.

> This SDK has no send surface: sending stays in OP Console.

## Requirements

- `minSdk 24` (Android 7.0) or newer
- Android SDK 36 for compilation
- JDK 17 must be installed (Gradle uses it as the build toolchain; a newer JDK can run Gradle, but JDK 17 must be present)
- an OpenPush.ai App ID and public SDK key
- a provider token, supplied by the host app or fetched by the optional FCM integration

The core `:openpush` artifact does not depend on Firebase. You can keep provider integration in your
app or add the separate `openpush-android-fcm` integration described below.

The core library merges these manifest entries into the host:

- `android.permission.INTERNET`, used by registration, receipts, events, sessions, and in-app fetches.
- `android.permission.POST_NOTIFICATIONS`, which Android 13+ still requires the host to request at runtime.
- A non-exported `OpenPushSyncJobService`, protected by `android.permission.BIND_JOB_SERVICE`, for
  draining durable work with network access after the app backgrounds.
- The shared `androidx.startup.InitializationProvider` metadata for
  `OpenPushLifecycleInitializer`, which begins observing Activity lifecycle at process start without
  an app id or network work. The optional in-app module adds its initializer to the same provider.

If your host removes or disables the Startup provider, call
`OpenPush.observeLifecycle(application)` from your own process initializer before the first Activity
starts. Initialization itself remains explicit; the provider only observes lifecycle.

See [Getting started](GettingStarted.md) for Firebase setup, both integration paths, and common
troubleshooting steps.

## Installation

The SDK is not yet available on Maven Central. Until it is, build it from source: clone this
repository at the `1.2.2` tag next to your app and include it as a Gradle composite build. No
account or credentials are needed.

```sh
git clone --branch 1.2.2 --depth 1 https://github.com/SuperTuned/openpush-android.git
```

In your app's `settings.gradle.kts`, substitute every module you depend on (the FCM integration also
brings in Live Updates; the compatibility layer brings in the core, FCM and in-app modules):

```kotlin
includeBuild("../openpush-android") {
    dependencySubstitution {
        substitute(module("ai.openpush:openpush-android")).using(project(":openpush"))
        substitute(module("ai.openpush:openpush-android-fcm")).using(project(":openpush-fcm"))
        substitute(module("ai.openpush:openpush-android-live-updates")).using(project(":openpush-live-updates"))
        substitute(module("ai.openpush:openpush-android-in-app-messages")).using(project(":openpush-in-app-messages"))
        substitute(module("ai.openpush:openpush-android-onesignal")).using(project(":openpush-onesignal-compat"))
    }
}
```

Then declare the dependencies with their Maven coordinates as usual; Gradle builds them from the
checkout:

```kotlin
implementation("ai.openpush:openpush-android:1.2.2")
implementation("ai.openpush:openpush-android-in-app-messages:1.2.2")
```

Once the artifacts are on Maven Central, remove the `includeBuild` block and keep the same
coordinates.

Apps migrating from the OneSignal SDK can use the optional compatibility layer
(`ai.openpush:openpush-android-onesignal`), which keeps the v5 call sites compiling against OpenPush.
See the [migration guide](MIGRATING-FROM-ONESIGNAL.md).

## Setup plugin

The `ai.openpush.setup` Gradle plugin finds the push plumbing your app already has and sets OpenPush up around it, without editing your `AndroidManifest.xml`. It needs Java 17 and AGP 8.7 or newer (Gradle 8.9 or newer, as AGP 8.7 requires): its Variant API calls are present from AGP 8.7, and it is built and tested with AGP 8.12.2 on Gradle 8.13. It is not yet on the Gradle Plugin Portal; include it from the same checkout in `settings.gradle.kts`, then apply it next to the Android plugin:

```kotlin
// settings.gradle.kts
pluginManagement { includeBuild("../openpush-android/gradle-plugin") }

// build.gradle.kts (root): declare it beside AGP so both load together
plugins {
    id("com.android.application") version "8.12.2" apply false
    id("ai.openpush.setup") apply false
}

// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("ai.openpush.setup")
}
```

- `./gradlew :app:openpushDoctor` reads every variant's merged manifest and the module's files and writes what it found to `app/openpush-setup.json`: services on `com.google.firebase.MESSAGING_EVENT` (editable when the class is in your sources), `c2dm RECEIVE` receivers, HMS services, the Firebase app and its `google-services.json`, the Firebase notification icon and channel defaults, and `ic_stat_*` drawables. The file's format is shared with the iOS and Unity setup tools.
- `./gradlew :app:openpushSetup` records a Reuse / Create new answer for each choice the findings raise: `firebase`, `android_delivery` and `icons`. It asks on a terminal; `-Popenpush.yes=true` takes the Reuse defaults; `-Popenpush.firebase=…`, `-Popenpush.android_delivery=forward|automatic` and `-Popenpush.icons=…` answer one directly. In CI it never prompts, and fails naming any choice that has no answer.
- Every build applies the answers through a manifest the plugin generates for each variant: `ai.openpush.app_id` and `ai.openpush.sdk_key` from `app/openpush.properties` (keep it out of version control), the Firebase values for Create new, the delivery mode, `tools:node="remove"` for the services Automatic replaces, and the reused icon and channel.
- Every build checks the merged manifest against the `android_delivery` choice (`openpushCheck<Variant>`, the last step of the manifest pipeline) and fails, naming the services, when delivery would be split: with no choice, more than one service receives `MESSAGING_EVENT`; with Forward, a service other than yours (and OpenPush's own, which the SDK disables) remains; with Automatic, anything but OpenPush's service remains. Forward and Automatic list the services they remove under `remove`, whether they come from your sources or a dependency.

With Forward, `openpushSetup` writes the lines your service needs to `app/build/openpush/forward.patch` and prints them; `-Popenpush.applyPatch=true` inserts them into the source instead. The [existing FCM service example](examples/existing-fcm-service-example) is a host set up this way.

## Examples

- [Feature Lab](examples/receive-only-example) — host-owned Firebase messaging and the complete SDK surface.
- [FCM module example](examples/fcm-module-example) — manifest-merged messaging, rendering, clicks, and an R8 build.
- [Existing FCM service example](examples/existing-fcm-service-example) — a host that keeps its own messaging service, set up with the Gradle plugin in Forward mode.
- [Migration example](examples/migrated-from-onesignal) — a compatibility-facade integration with a reproducible before/after diff.

## App setup

Initialize once during process initialization. The credential closure must return the currently linked installation credential, or `null` before linking.

```kotlin
OpenPush.initialize(
    applicationContext,
    appId = "your-app-id",
    credential = { deviceCredentialStore.load() },
)
```

Put your public SDK key in the manifest and `initialize` needs only the app id:

```xml
<meta-data android:name="ai.openpush.sdk_key" android:value="pk_live_…" />
```

You can put both values in the manifest and use the manifest-only overload:

```xml
<meta-data android:name="ai.openpush.app_id" android:value="your-app-id" />
<meta-data android:name="ai.openpush.sdk_key" android:value="pk_live_…" />
```

```kotlin
OpenPush.initialize(applicationContext)
```

To keep the key in code instead, pass `sdkKey = "…"` — an explicit argument always
wins over the manifest value. With no key in either place, `initialize` refuses
through `onLog` and `onRegistrationFailed` rather than leaving the SDK
half-configured.

Hosts switching from another provider can use the optional compatibility facade; the complete
forwarded and refused symbol maps are in the [migration guide](MIGRATING-FROM-ONESIGNAL.md).

`serverURL` defaults to `OpenPush.DEFAULT_SERVER_URL`. `OpenPush.attach(context, OpenPushConfiguration(appId, sdkKey), credential)` remains the supported lower-level form; `initialize` wraps it.

## In-app messages

Adding `openpush-android-in-app-messages` starts the receive engine automatically;
no Activity base class or extra initialization call is required. It fetches after
subscription registration and at the start of every new session, and shows the
message in a hardened WebView inside a popup.

```kotlin
OpenPush.InAppMessages.addTrigger("level", 12)
OpenPush.InAppMessages.addTriggers(mapOf("plan" to "pro", "onboarded" to true))
OpenPush.InAppMessages.paused = false

val clicks = OpenPush.InAppMessages.addClickListener { event ->
    // event.actionId, event.url, event.firstClick, event.pageId
}
OpenPush.InAppMessages.removeClickListener(clicks)
```

The document decides its own shape. A `top` or `bottom` message is a banner sized
to the height it reports, so touches anywhere else still reach your app; a
`center` message sits over a scrim; a `full` message takes the screen. The
impression is posted when the document reports that it rendered, not when the SDK
put it on screen, and each page of a multi-page document is worth one page
impression of its own.

URL, notification-permission, tag, and outcome/custom-event actions cross the
native bridge and are carried out by the SDK rather than the document. A `url`
action with `url_target = "webview"` opens a Custom Tab where one is available.
While a permission prompt is up the queue waits, so a second message cannot appear
over the system dialog. Location permission remains host-owned and is logged as
unsupported.

Lifecycle and click listener registration returns a `UUID` handle for explicit
removal. Lifecycle events carry both the `phase` string and a typed
`InAppLifecycleStage`.

Triggers can also be satisfied by the clock: a message gated on seconds into the
session, or seconds since the last message came down, is displayed by a wake-up
the engine schedules for itself. `paused = true` takes the message on screen down
and hands it back to the queue without counting a display, and `paused = false`
puts that same one back up.

Dismissals, impression and click ids, page views and redisplay counters persist in
`SharedPreferences`, keyed by the configuration's token key, so two workspaces in
one process never read each other's state. Records older than 180 days are purged
at attach.

## Subscription status (`notification_types`)

Every registration reports Android `notification_types` (`1` when permitted,
`0` otherwise, or retained opt-out `-2`). The SDK recomputes it at launch and
when the app enters the foreground, sending an update only when the accepted
value changed. An Application context installs this lifecycle observation
automatically; with another context, call `OpenPush.onAppForegrounded()` from
the host lifecycle hook.

## Provider token forwarding

With the optional FCM integration there is nothing to forward: it fetches the token after every
`initialize` and registers rotations itself. See [Path 2](#path-2--the-optional-fcm-integration).

Otherwise, forward the provider token only after the device is linked:

```kotlin
OpenPush.register(fcmToken)
```

The registration carries the identity set by `OpenPush.login(externalId, authHash)`.
`register(token, externalId)` still compiles but is deprecated: it logs a new id in first, without an
auth hash.

To plug in another provider, install an `OpenPushTokenProvider` before `initialize`. The SDK asks it
after every attach, and again on the next foreground while the device has no accepted registration;
a token it already registered is not sent twice.

```kotlin
OpenPush.tokenProvider = OpenPushTokenProvider { context, callback ->
    myProvider.fetchToken(context, onSuccess = callback::onToken, onError = callback::onUnavailable)
}
```

## Delivery receipts

Post evidence as the host observes it:

```kotlin
val messageId = OpenPush.messageId(message.data)
OpenPush.receipt("received", messageId)
OpenPush.receipt("confirmed", messageId)
OpenPush.receipt("clicked", messageId)
```

## Notification actions

`OpenPush.actions(message.data)` returns the action IDs, labels, and optional
icon metadata configured in the dashboard. Add them to the same
`NotificationCompat.Builder` used to render the notification; the example app
shows the complete `PendingIntent` wiring.

## Registration and receipt durability

Registration callbacks are scoped to the credential that initiated the request. A late response after sign-out or workspace switching is ignored. Failed receipt requests stay in the durable queue across process restarts; only accepted receipts enter the bounded persisted dedupe history.

Transient registration failures retry at +2 seconds and +8 seconds, for three attempts total. A 4xx response is final. `onRegistrationFailed` runs only after the final attempt, so it may arrive about 10 seconds after the initiating call. A newer registration cancels an older pending retry.

## Two integration paths

### Path 1 — your app owns Firebase messaging

Keep your existing `FirebaseMessagingService`, call `OpenPush.register(token)` from `onNewToken`, and forward messages and clicks through `processForegroundMessage` and `processClick`. This gives your app full control over notification display. Add your Firebase project's `google-services.json` to the app module; [Getting started](GettingStarted.md#path-1--your-app-owns-firebase-messaging) shows the manifest and forwarding setup. The [receive-only example](examples/receive-only-example) deliberately uses this path.

### Path 2 — the optional FCM integration

If your app has no messaging service, add:

```kotlin
implementation("ai.openpush:openpush-android-fcm:1.2.2")
```

The module fetches the FCM token and registers it after every `initialize`, registers rotated tokens
from `onNewToken`, renders every Android composer field (images, actions,
channel, icons, colours, sound, category, visibility and grouping), creates a notification channel
when needed, posts received/confirmed/clicked receipts, and forwards the parsed notification and
action id to listeners. A named `op_android_group` gains an `InboxStyle` summary when its second
active child is posted; the summary lists up to the five newest child bodies. Configure its display
defaults in the application manifest:

```xml
<meta-data android:name="ai.openpush.notification_channel_id" android:value="messages" />
<meta-data android:name="ai.openpush.notification_icon" android:resource="@drawable/ic_notification" />
```

The token comes from the default Firebase app, which `google-services.json` creates. Without that
file, give the module your Firebase app's values in the manifest and it creates the default app at
process start:

```xml
<meta-data android:name="ai.openpush.fcm.project_id" android:value="your-project-id" />
<meta-data android:name="ai.openpush.fcm.application_id" android:value="1:1234567890:android:abc123" />
<meta-data android:name="ai.openpush.fcm.api_key" android:value="AIza…" />
```

With neither, the token is reported unavailable through `OpenPush.onLog` and nothing registers. To
forward tokens yourself while keeping the module, turn the fetch off:

```xml
<meta-data android:name="ai.openpush.fcm.automatic_token" android:value="false" />
```

The minimal [FCM module example](examples/fcm-module-example) proves this path with no host-written
messaging service, notification builder, tap Activity, or Firebase call, including a minified release
build.

If your app already declares a `FirebaseMessagingService`, two services would filter the same FCM
intent and delivery to either service is unspecified. Use **Forward** mode, which keeps your service
as the only receiver and the module's token fetch, rendering and click handling:

```xml
<meta-data android:name="ai.openpush.fcm.delivery" android:value="forward" />
```

In `forward` the module disables its merged `OpenPushMessagingService` at startup, so pushes cannot
be split between two services even if the manifest still declares it. The default, `automatic`,
lets the module's service receive pushes; switching back re-enables a service the SDK disabled, and
leaves alone one your app disabled itself. Removing the merged service with
`<service android:name="ai.openpush.android.fcm.OpenPushMessagingService" tools:node="remove" />`
also works. The [setup plugin](#setup-plugin) records this choice and sets the value for you.

Then route OpenPush messages through the coexistence entry point and keep handling your own data
messages in the host service:

```kotlin
override fun onMessageReceived(message: RemoteMessage) {
    if (OpenPush.messageId(message.data) == null) {
        handleHostMessage(message)
        return
    }
    OpenPushMessagingService.handle(this, message)
}

override fun onNewToken(token: String) {
    OpenPush.register(token)
}
```

Real FCM delivery, notification appearance, and click forwarding still require verification on a device using the host Firebase project.

## Java hosts

Public object members are Java static methods and accessors, so Java callers use
`OpenPush.initialize(...)` and `OpenPush.User.addTag(...)` without Kotlin's `INSTANCE` field.
Operations with default arguments expose Java overloads for the common forms. See the
[Java API notes](API.md#java) and the compile-checked
[Java integration example](examples/fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java).

## ProGuard and R8

Host apps do not need to add keep rules for OpenPush. Core models are parsed directly rather than
through reflection, and Android's manifest processing keeps the service, Activity, initializer,
job service, and receiver entry points. The in-app artifact ships the rule required for its
`@JavascriptInterface` bridge, and the Live Updates artifact ships the rule for its manifest receiver.
The core artifact keeps every `OpenPushBackgroundMessageHandler` implementation and its no-argument
constructor, because the handler is named only in manifest meta-data and created by reflection.
The [FCM module example](examples/fcm-module-example) proves the merged components and a manifest-named
background handler survive a minified release build.

## Android Live Updates

The optional FCM module recognizes a structured `live_notification` data value and updates one
ongoing notification for its lifecycle. Android 16 can promote an eligible notification to the
lock screen and status-bar chip; older Android versions use the same stable notification ID and
show a quiet ongoing fallback.

```json
{
  "collapse_id": "delivery-42",
  "ttl": 120,
  "data": {
    "live_notification": {
      "key": "progress",
      "instance_id": "delivery-42",
      "event": "update",
      "sequence": 2,
      "event_attributes": { "title": "Delivery" },
      "event_updates": {
        "current_progress": 70,
        "max_progress": 100,
        "short_text": "70%"
      }
    }
  }
}
```

Send `start`, then any number of `update` events, then `end` using the same `collapse_id` and
`instance_id`. `dismiss` is also accepted as an alias for `end`.
Updates with duplicate or older sequences are ignored. If the user dismisses or Unpins the
notification, later updates do not resurrect it; a new `start` explicitly begins a new lifecycle.

If your app owns Firebase messaging, add
`ai.openpush:openpush-android-live-updates:1.2.2` and call
`OpenPushLiveUpdates.handle(context, message.data, message.collapseKey)` before ordinary rendering.
Do not add a second `FirebaseMessagingService`. The
[receive-only example](examples/receive-only-example) demonstrates the custom-service route and
shows the current fallback/promotion capability.

Promotion is reserved for ongoing, user-initiated, time-sensitive activity—not advertising, chat,
or ambient information. It requires normal notification permission plus the non-runtime promoted
notification permission declared by the renderer. Android and the device manufacturer retain the
final promotion decision.

## Identity

```kotlin
OpenPush.login("user-7", authHash = serverComputedHash) { result ->
    when (result) {
        is IdentityResult.IdentityRejected -> Log.w(TAG, "anonymous: ${result.note}")
        is IdentityResult.Failed -> Log.w(TAG, result.message)
        IdentityResult.Success -> Unit
    }
}

OpenPush.logout()
```

`login` before a provider token exists stores the identity and applies it to every later registration, including token refreshes. A rejected identity is reported as `IdentityRejected`, never as success, and is logged loudly — the device is registered *anonymously* in that case.

`logout` re-registers with a present-and-empty `external_id`, which means detach. A server that does not yet implement that rule ignores the field rather than misreading it.

## Tags

```kotlin
OpenPush.User.addTag("plan", "pro")
OpenPush.User.addTags(mapOf("plan" to "pro", "seats" to "3"))   // one request
OpenPush.User.removeTag("plan")                                  // sends ""
OpenPush.User.removeTags(listOf("plan", "seats"))
val localTags = OpenPush.User.getTags()                           // a copy
```

Tag and alias writes join a fixed 300 ms window measured from the first pending write. The window does not restart when another write arrives. `register`, `login`, `logout`, `optIn`, and `optOut` absorb pending writes into their own registration; backgrounding flushes immediately. Without a provider token, values simply accumulate and ride the first registration.

The tag map rides every registration and is omitted while empty. A removed tag keeps riding as `""`, so a token-refresh re-registration cannot resurrect it. With identity verification enabled for the app, a tag write without a valid in-memory auth hash comes back `identity_rejected` and is logged loudly.

## Journey events

```kotlin
OpenPush.trackEvent("onboarding_started", mapOf("source" to "welcome"))
```

Events are validated locally, persisted while offline, and sent in batches of
up to 50 after the same 300 ms coalescing window. Call `initialize` first; each
event is attributed to the current external ID or registered subscription.
Switching workspaces clears queued events so one account's activity cannot be
reported in another workspace.

## Aliases

```kotlin
OpenPush.User.addAlias("crm_id", "c-42")
OpenPush.User.addAliases(mapOf("crm_id" to "c-42", "loyalty_id" to "l-7"))   // one request
OpenPush.User.removeAlias("crm_id")                                          // sends ""
OpenPush.User.removeAliases(listOf("crm_id", "loyalty_id"))
```

An alias is a second label the server can address this user by, next to `external_id`. The alias map rides every registration and is omitted while empty, and a removed alias keeps riding as `""` for the rest of the process session, exactly like a removed tag.

The server caps aliases at 10 per user and refuses reserved labels such as `external_id`. It names any refused labels in the response instead of failing the request, and the SDK logs them loudly rather than keeping a second copy of a server rule that can change. Alias writes ride the same identity gate as tags: with identity verification enabled, a write without a valid auth hash comes back `identity_rejected` and is logged loudly.

## Phone numbers and codes

A user can hold phone numbers next to their push subscription, for SMS and for WhatsApp, and the SDK
can send a one-time code to prove a number is theirs.

```kotlin
OpenPush.User.addSms("+1 (415) 555-0123")   // sent as +14155550123; false, and nothing sent, if invalid
OpenPush.User.addWhatsApp("+14155550123")
OpenPush.User.removeSms("+14155550123")      // opts the number out (status -2)
OpenPush.User.removeWhatsApp("+14155550123")
```

Numbers are normalised exactly as the server normalises them: every character that is not a digit
or `+` is removed, so `+1 (415) 555-0123` and `+1-415-555-0123` both become `+14155550123`; a
leading `00` then becomes `+`; and the result must be `+` followed by 8 to 15 digits only (a second
`+` fails), the first of them not `0`. A number with no `+` or `00`, such as `415 555 0123`, is
refused rather than guessed at, since only the app knows which country it belongs to. A refused
number returns `false` with a log line saying why. Each write is a
`POST /v1/apps/{app_id}/subscriptions` with the number as its `token` and `sms` or `whatsapp` as its
`platform`, on the same serialized queue as registration, so a number added right after `login`
carries that login's `external_id` (with `external_id_auth_hash` under identity verification). If
the server refuses the auth hash it still stores the number, without a user, and the SDK logs that.

A new number is **not verified** (status `0`) until a phone code for it is approved; the device never
sends a positive status, and a removal sends `-2`, the one status it may. Writes are offline-first,
like tags: one made before `initialize`, or while the server cannot be reached, is persisted and
sent at the next attach, and only the newest write per number and platform is kept. A transport
failure, 429 or 5xx is retried on a 2 s, 8 s, 30 s backoff that holds at 30 s and never comes back
sooner than `Retry-After`; any other 4xx is logged and dropped. Pending writes are kept per app id,
so after switching workspaces they wait for their own app rather than going to the new one.

### Phone codes

```kotlin
try {
    var verification = OpenPush.Verify.start("+14155550123")             // channel AUTO
    // Show verification.maskedNumber ("+1415•••0123") and ask for the code.
    verification = OpenPush.Verify.check(verification, typedCode)
    when (verification.status) {
        OpenPushVerificationStatus.APPROVED -> Unit                        // the number is subscribed
        OpenPushVerificationStatus.PENDING -> Unit                         // wrong code; attempts counted
        OpenPushVerificationStatus.EXPIRED,
        OpenPushVerificationStatus.MAX_ATTEMPTS -> Unit                    // start again
    }
    // Texts not arriving? 30 s after the last send:
    verification = OpenPush.Verify.resend(verification, OpenPushVerifyChannel.CALL)
} catch (error: OpenPushVerifyError) {
    // error.code: RATE_LIMITED, TOO_SOON, NOT_PENDING, PROVIDER, OPTED_OUT, NO_CREDIT, NO_RATE, REFUSED, INVALID_NUMBER, NETWORK
    // error.message: the server's explanation when it gave one
}
```

`start(phone, channel = AUTO, locale = null)` sends a code over `AUTO`, `SMS`, `WHATSAPP` or `CALL`;
`locale` picks the message language. `check` returns the verification with its new status: a wrong
code is not an error, it comes back `PENDING` with `attempts` counted up. An approved code is what
marks the number's SMS and WhatsApp subscriptions subscribed on the server. Each call is a single
request with no retry, because a person is waiting on it. Java and callback callers use the
overloads that take a `(OpenPushVerifyResult) -> Unit` as their last argument; the result is
`Success(verification)` or `Failure(error)` and arrives on the main thread.

An `OpenPushVerification` carries `id`, `maskedNumber`, `channel`, `status`, `attempts` and
`expiresAt`. It also holds the full number, in memory only, so `check` and `resend` can name it:
it is not `Parcelable` or `Serializable`, is never persisted, and does not survive process death.

**SMS autofill.** `OpenPush.Verify.smsRetrieverHashFor(context)` returns the 11-character app hash the
[SMS Retriever API](https://developers.google.com/identity/sms-retriever/overview) matches a code
message against, computed from the package name and signing certificate, so the SDK itself needs no
Google Play services library. To read the code automatically, add
`com.google.android.gms:play-services-auth-api-phone` to your app, call
`SmsRetriever.getClient(context).startSmsRetriever()` before `start`, and give the hash to whoever
writes the message template. Debug and release signing give different hashes; under Play App
Signing, use the one from the Play-signed build. Apps that do not call it carry no extra dependency.
WhatsApp one-tap and zero-tap autofill are not in this release.

## Email addresses

```kotlin
OpenPush.User.addEmail(" Ana@Example.com ")        // sent as ana@example.com; false, and nothing sent, if invalid
OpenPush.User.removeEmail("ana@example.com")       // removes the subscription; not an unsubscribe

OpenPush.User.addEmail("ana@example.com") { result ->
    when (result) {
        is OpenPushEmailResult.Success -> Unit     // subscribed; result.subscriptionId
        is OpenPushEmailResult.Failure -> Unit     // result.error.code: INVALID_EMAIL, SUPPRESSED, REFUSED
    }
}
```

`addEmail` subscribes the address at once, unless this app's suppression list has it (it
unsubscribed, bounced or complained): the server then stores it at status `-2` and never emails it,
and the write is reported as `SUPPRESSED` and not retried. `removeEmail` removes the subscription
(status `-2`). **It is not an unsubscribe**: no suppression is written, and a later `addEmail`
subscribes the address again. A person who asks to stop receiving email unsubscribes through the
link in the email itself.

The address is trimmed and lower-cased, and must then have exactly one `@`, a name before it, a
dot in the domain after it, and at most 254 characters; anything else returns `false`, logs why,
reports `INVALID_EMAIL`, and sends nothing. Each write is a `POST /v1/apps/{app_id}/subscriptions`
with the address as its `token` and `email` as its `platform`, the current `external_id` (with
`external_id_auth_hash` under identity verification), on the same serialized queue as
registration. Writes are persisted and retried exactly like phone numbers: offline or before
`initialize` they wait for the next attach, only the newest write per address is kept, and a
transport failure, 429 or 5xx is retried on the same backoff. A 400 carries the server's words
(email off for the app, or an address it does not accept): it is reported as `REFUSED` with those
words and not retried. The optional callback hears the final answer once, on the main thread; it
lives for this process only.

## Push subscription

```kotlin
val state = OpenPush.User.pushSubscription
// state.id, state.token, state.optedIn

state.optOut()                              // status -2: the server stops sending to this device
state.optIn()                               // restores device status and prompts if needed
val frozen = state.snapshot()
OpenPush.PushSubscription.optOut()          // equivalent alias
OpenPush.PushSubscription.optIn(activity)   // explicit prompt source
val receiving = OpenPush.PushSubscription.optedIn
```

`optIn()` requests the OS permission from the currently resumed Activity and
falls back to notification Settings after the prompt limit. Use
`optIn(activity)` when the host wants to provide the Activity explicitly.

Every registration carries the resolved status: `1` with permission, `0`
without it, and `-2` while opted out. The opt-out and underlying device value
survive a cold start. `optOut()` does not delete the subscription or provider
token, and `clearToken()` leaves `state.id` in place because the server-side
subscription still exists after a local token wipe.

## Notification permission

```kotlin
// In a coroutine: suspends until the user answers.
lifecycleScope.launch {
    val granted = OpenPush.Notifications.requestPermission()
}

// Anywhere else, such as onCreate: pass a callback.
OpenPush.Notifications.requestPermission(activity) { granted -> /* … */ }
OpenPush.Notifications.requestPermission(fallbackToSettings = true) { granted -> /* … */ }
```

```java
// Java: the callback form.
OpenPush.Notifications.requestPermission(false, granted -> Unit.INSTANCE);
```

Without a callback, Kotlin picks the `suspend` form, so `requestPermission()` and
`requestPermission(true)` compile only inside a coroutine.

The prompt runs in a transparent Activity that `:openpush` declares in its manifest, through the Activity Result API, so the answer reaches the callback (and the `suspend` form) without your Activity forwarding anything. Code that still forwards `onRequestPermissionsResult` into `OpenPush.Notifications.onPermissionResult(requestCode, grantResults)` keeps working and never causes a second callback: the prompt's result goes to OpenPush's Activity, never to yours, so there is nothing for that code to forward twice. If the Activity cannot be shown (the host is finishing, or the app is in the background and Android drops the start), the request completes with the permission the OS reports. Call `onPermissionResult` yourself only for a `POST_NOTIFICATIONS` answer from your own prompt, with request code `OpenPush.Notifications.PERMISSION_REQUEST_CODE`. If a manifest rule removes `ai.openpush.android.OpenPushPermissionActivity`, the SDK falls back to prompting in your Activity with that request code, and the forward is required again.

The overload without an `Activity` uses the currently resumed activity tracked from the application lifecycle. With none resumed, such as a call from `onCreate`, the request is deferred: the prompt starts when the next Activity resumes and the callback (or the `suspend` form) gets that answer. Calls made before then share the one prompt. An app that never shows an Activity keeps the request pending until one resumes. An already granted permission, and API 32 and below, answer at once. `Notifications.permission` is a cache refreshed at initialization, actual app foreground transitions, and permission calls. Receiving a foreground message does not refresh permission or rewrite the subscription status. `:openpush` declares `POST_NOTIFICATIONS` in its own manifest.

## Notification events

```kotlin
val clicks = OpenPush.Notifications.addClickListener { event -> route(event.data) }
val foreground = OpenPush.Notifications.addForegroundLifecycleListener { event ->
    event.preventDefault()   // the host will not display its own notification
}

// In FirebaseMessagingService.onMessageReceived — every delivery, not only foreground ones.
// beginReceived is false for a second copy of a push already handled; finishReceived records it.
if (!OpenPush.Notifications.beginReceived(message.data)) return
try {
    if (OpenPush.Notifications.processForegroundMessage(message.data)) return
    // ... your own display
} finally {
    OpenPush.Notifications.finishReceived(message.data)
}

// After your own NotificationManager.notify(...) succeeds:
OpenPush.Notifications.processDisplayed(message.data)

// From the notification-tap trampoline:
OpenPush.Notifications.processClick(mapOf("op_message_id" to messageId))
```

`beginReceived` and `finishReceived` drop a repeated delivery of one push: the server re-sends a
push whose delivery it could not confirm. `processForegroundMessage` posts the `received` receipt on every delivery;
`processDisplayed` posts `confirmed` after a successful host-owned display; and
`processClick` posts `clicked`. Keep the returned `ListenerHandle` to remove a
listener. Listeners are not cleared by `initialize`.

### Action button links

An action button may carry its own `url`. The click reports it as `event.actionUrl` (null for a body
tap or a button without one). With the FCM integration's launch-URL opening on (the default), a tap
on that button opens its `url` instead of the push's `deep_link`, and `launchUrlOpened` is true; the
`ai.openpush.suppress_launch_urls` opt-out applies to button links too. A button without `url`
behaves as before.

```kotlin
OpenPush.Notifications.addClickListener { event ->
    if (!event.launchUrlOpened) route(event.actionUrl ?: event.notification.launchUrl)
}
```

### Background (data-only) pushes

A push sent as a background push for Android arrives with `op_background` = `"1"` and no title or
body. It is never displayed; register a listener to receive its data:

```kotlin
val background = OpenPush.Notifications.addBackgroundMessageListener { event ->
    // event.data is the delivered map; event.notification.additionalData drops the op_ keys
    WorkManager.getInstance(context).enqueue(syncRequestFor(event.data))
}
```

The listener runs on the thread that delivered the push, whether or not the app is in the
foreground; keep it short and hand longer work to WorkManager. The `received` receipt is posted as for
any push. Without a listener the push is logged and dropped. A host that forwards deliveries itself
needs nothing new: `processForegroundMessage` hands a background push to these listeners and returns
`true`, so the host's own display is skipped.

A listener only exists once your code has added it. A background push can also start the app's
process from nothing — the user swiped the app away, or Android reclaimed it — and in that fresh
process nothing has called `OpenPush.initialize` yet. To act on those pushes, declare a handler in
the manifest; the SDK instantiates it by name for every background push, whether or not the app has
initialised, and runtime listeners still run beside it:

```xml
<meta-data
    android:name="ai.openpush.background_message_handler"
    android:value="com.example.app.SyncOnPush" />
```

```kotlin
class SyncOnPush : OpenPushBackgroundMessageHandler {
    override fun onBackgroundMessage(context: Context, event: BackgroundMessageEvent) {
        WorkManager.getInstance(context).enqueue(syncRequestFor(event.data))
    }
}
```

The class needs a public no-argument constructor. The SDK's consumer rules keep it through R8, so no
keep rule of your own is needed. A force-stopped app (Settings → Force stop, or
some manufacturers' swipe-away) receives nothing on any SDK: Android withholds the push itself.

### Replacing a shown notification

A push sent with Android's replace option and a collapse key arrives with `op_android_replace` =
`"1"`. The FCM renderer then posts it with the collapse key as its tag and
`OpenPushNotificationRenderer.replaceNotificationIdFor(collapseKey)` as its id, so each newer push
with that key replaces the one on screen instead of stacking. Pushes without the option keep one
notification per message, as before.

The notification object also manages notifications posted through the optional FCM renderer:

```kotlin
OpenPush.Notifications.remove(notificationId)
OpenPush.Notifications.removeGroup("account-updates")
OpenPush.Notifications.clearAll()
```

`remove` takes the id returned by `OpenPushNotificationRenderer.render(notification)`, or the stable
id from `notificationIdFor(messageId)` (`replaceNotificationIdFor(collapseKey)` for a replacing push,
below). `removeGroup` cancels tracked children and their generated
summary. After initialization, `clearAll` can clear the app's notification shade even without the FCM
module; targeted removal requires the renderer because its bounded posted-id registry supplies the
message and group lookup.

Forward **every** message through `processForegroundMessage`, backgrounded or not — `onMessageReceived` also fires with the app backgrounded or killed. The foreground listeners run, and `preventDefault()` can return `true`, only while the app actually has a visible Activity; a background delivery still posts its receipt, still returns `false`, and still renders. That is what stops a `preventDefault()` from suppressing a notification the user never saw. The SDK reads foregroundedness from the `Application.ActivityLifecycleCallbacks` it already registers at `initialize` — no extra dependency. If you initialize with a `Context` that is not an `Application` there is no lifecycle to observe, so the SDK trusts you and keeps dispatching; call `OpenPush.onAppForegrounded()` and `OpenPush.onAppBackgrounded()` from your own hooks in that case.

One narrow case is worth knowing if you pass an `Activity` to `initialize` rather than an `Application`: attaching from an Activity adopts it as the visible one, because the lifecycle callbacks register too late to have counted its `onStart`. That adoption is retired the moment the Activity pauses, stops or is destroyed — but an Activity that had **already** stopped before the call cannot be recognised as stopped, since Android exposes no way to ask, so the process looks foregrounded until that Activity is destroyed. Initialize from `Application.onCreate` or from a live Activity and this cannot arise; if you initialize lazily from an Activity that may already be stopped, pass `applicationContext` instead.

The [receive-only-example](examples/receive-only-example) directory contains a sanitized app project with the complete application, messaging-service, and notification-tap wiring.

## Credential storage and sign-out

`DeviceCredentialStore` encrypts values with AES-GCM using Android Keystore. If the Keystore is unavailable, saving fails closed; credentials are never written as plaintext.

The host app owns account linking and unlinking. Retire the server-side delivery address before clearing local state. If unlink cannot complete, securely queue that credential and block another account from linking until the queue drains.

Tags, aliases, external ID, detach state, and subscription status persist in the app-private `openpush` `SharedPreferences`, namespaced by `tokenKey`. Values staged before the first initialization win over restored values. A later `initialize`/`attach` is treated as a workspace switch and erases session state for both configurations. The identity authentication hash is deliberately memory-only, is never persisted, and any legacy stored hash is removed during restoration.

## Security boundary

- The SDK key is public and may ship in the app.
- Device credentials and provider tokens must not enter analytics or diagnostic logs.
- The SDK exposes no send or console-management surface.
- Workspace switching must retire the prior delivery address rather than transferring its history.

Report vulnerabilities using [SECURITY.md](SECURITY.md).

## Verification

```sh
./gradlew testDebugUnitTest assembleRelease
```

See [API.md](API.md) for the complete `1.2.2` public surface, and its
[Threading](API.md#threading) section for which thread each callback runs on.

## License and brand

Licensed under the [Apache License 2.0](LICENSE). Copyright and third-party notices, including the
trademark notice for the compatibility layer, are in [NOTICE](NOTICE). The license does not grant
permission to use the OpenPush name, marks, or branding for forks or derivatives.

## UTM link tracking

Link tracking is configured on the OpenPush server. After personalization, it adds
missing `utm_source`, `utm_medium`, and `utm_campaign` parameters to HTTP(S)
`deep_link` URLs. Existing values (including blank values), query parameters and
fragments are preserved; custom app schemes are unchanged.

The SDK already delivers the complete URL through `event.notification.launchUrl`.
Use that URL as received when routing a click: do not rebuild it from its host/path,
decode and re-encode its query, or append another set of UTM tags. With the FCM
integration, a tapped push opens its launch URL directly (without starting the app) and
the click arrives with `launchUrlOpened = true`; set the `ai.openpush.suppress_launch_urls`
manifest meta-data to `true` to open the app instead and route the URL yourself. The SDK
does not send UTM fields as separate custom events. HTTPS is recommended for
web destinations; the store samples intentionally reject plain HTTP.

The Feature Lab custom notification demonstrates a tagged web link with a
fragment. Regression tests cover encoded query values, duplicate/blank parameters
and click forwarding.
