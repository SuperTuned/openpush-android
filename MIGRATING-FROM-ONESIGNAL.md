# Migrating from OneSignal v5 (Android)

> OneSignal is a trademark of OneSignal, Inc. OpenPush is not affiliated with, endorsed by, or
> sponsored by OneSignal, Inc. The OneSignal name appears only to describe the optional
> compatibility layer for apps migrating from the OneSignal SDK.

OpenPush 1.2.2 has `minSdk 24` and requires Android 7.0 or newer.

`ai.openpush:openpush-android-onesignal:1.2.2` is an **opt-in** module that gives OpenPush
the OneSignal v5 member names. It is names only: no behavior, no state, no OneSignal
implementation code. Every supported member is a one-line delegation to `OpenPush`.

Its package is `ai.openpush.android.onesignal`, not `com.onesignal`, so it can never collide
with the real OneSignal SDK — both can sit in one app while you migrate screen by screen.

Nothing is stubbed: there is no member that accepts the call and quietly does nothing. The v5
symbols enumerated in [section 3](#3-refused-symbols) are declared and **refused at compile
time**, with the replacement named at the call site, so one build reports that whole set. A v5
symbol outside that enumeration is not declared at all, so it fails as an unresolved reference
instead — still a compile error, but you read the replacement out of section 3 rather than off
the error.

It compiles drop-in: names and signatures match v5 across the whole supported surface, and the
behavior is OpenPush's, with the differences listed below. The foreground lifecycle listener, for
example, fires only for pushes that arrive while the app has a visible
Activity. See
[the note in section 2](#the-foreground-lifecycle-listener) for the mechanism and the
one case where the SDK hands the decision back to you. Listener and observer registration does
change shape — see [§1.1](#11-the-fourth-edit-listeners-and-observers-become-lambdas).

## 1. The change

Three edits: the module dependency and import, the SDK key (and, without `google-services.json`,
your Firebase app's values) in the manifest, and the permission-result forwarding described below.
The module fetches and registers the FCM token, renders notifications and reports opens
itself, so an app that never declared a `FirebaseMessagingService` writes no provider code. A fourth edit applies
only if you registered a listener or observer — see
[§1.1](#11-the-fourth-edit-listeners-and-observers-become-lambdas).

Add the module:

```kotlin
implementation("ai.openpush:openpush-android-onesignal:1.2.2")
```

The artifact is not yet on Maven Central; until it is, build it from a clone of this repository as
described under [Installation](README.md#installation), substituting
`ai.openpush:openpush-android-onesignal` with the `:openpush-onesignal-compat` project.

Then change the import and add the public SDK key to your manifest:

```diff
-import com.onesignal.OneSignal
+import ai.openpush.android.onesignal.*

 class App : Application() {
     override fun onCreate() {
         super.onCreate()
         OneSignal.initWithContext(this, "your-app-id")
     }
 }
```

Import the **package**, not just the class: `consentRequired`, `consentGiven`, the four
`OneSignal.Debug` / `InAppMessages` / `Session` / `Location` accessors and every refused `User` and
`Notifications` member are Kotlin **extension** declarations, and an extension only resolves where
it is in scope. Under `import ai.openpush.android.onesignal.OneSignal` alone those call sites fail
as bare `unresolved reference` errors with no message; under the wildcard they fail with the
replacement quoted at the call site, which is the whole point of section 3.

```diff
 <application …>
+    <meta-data android:name="ai.openpush.sdk_key" android:value="pk_live_…" />
 </application>
```

`initWithContext(context, appId)` keeps the v5 signature. OpenPush also needs a public SDK
key, which it reads from `ai.openpush.sdk_key` manifest meta-data — that is the only edit the
setup call requires. To keep the key in code instead, call
`OneSignal.initialize(context, appId, sdkKey)`; a third positional string is always the key. With no key in
either place, `initialize` refuses through `OpenPush.onLog` and `OpenPush.onRegistrationFailed`
rather than leaving the SDK half-configured.

### Java hosts

The SDK and the compatibility facade expose their object members as Java static methods and
accessors, so Java call sites use `OpenPush.initialize(...)`, `OpenPush.User.addTag(...)`, and
`OneSignal.initWithContext(...)` without Kotlin's `INSTANCE` field. Defaulted OpenPush operations
such as `register(token)`, `login(externalId)`, and `trackEvent(name)` also have Java overloads.

The facade's refused members are Kotlin extensions. Java cannot see extension syntax such as
`OneSignal.Debug` or `OneSignal.User.setLanguage(...)`, so those calls fail as unresolved symbols
instead of producing the Kotlin deprecation message. Section 3 lists every refused member and its
replacement for Java migrations.

### Android forwarding

The module brings `ai.openpush:openpush-android-fcm`, which does the messaging work for you:

- it fetches the FCM token after `initWithContext` and registers it, and registers every rotation;
- its merged `FirebaseMessagingService` posts receipts, runs your foreground listeners, and renders
  the notification;
- its click Activity reports the open and relaunches the app, and your click listener fires.

**Firebase configuration.** v5 read your Firebase project from its dashboard, so many v5 apps have
no `google-services.json`. Either add that file with the Google Services plugin, or put your
Firebase app's values in the manifest and the module creates the default Firebase app from them:

```xml
<meta-data android:name="ai.openpush.fcm.project_id" android:value="your-project-id" />
<meta-data android:name="ai.openpush.fcm.application_id" android:value="1:1234567890:android:abc123" />
<meta-data android:name="ai.openpush.fcm.api_key" android:value="AIza…" />
```

All three are in the Firebase console under Project settings → Your apps. With neither in place,
no token is fetched and `OpenPush.onLog` says why.

**If your app declares its own `FirebaseMessagingService`**, two services would filter the same
FCM intent. Remove the module's service and forward from yours:

```xml
<service
    xmlns:tools="http://schemas.android.com/tools"
    android:name="ai.openpush.android.fcm.OpenPushMessagingService"
    tools:node="remove" />
```

```kotlin
class MessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = OneSignal.register(token)

    override fun onMessageReceived(message: RemoteMessage) {
        // A copy of a push this device already handled: drop it.
        if (!OneSignal.Notifications.beginReceived(message.data)) return
        try {
            // Forward every delivery. Returns true only when the app was foregrounded and a
            // foreground listener called preventDefault().
            if (OneSignal.Notifications.processForegroundMessage(message.data)) return
            // …your own notification display…
        } finally {
            OneSignal.Notifications.finishReceived(message.data)
        }
    }
}
```

The launch-time token fetch keeps working with the service removed; `onNewToken` covers rotations
while the app runs. If you render your own notifications, forward opens from your click trampoline
with the notification's extras as a map:

```kotlin
OneSignal.Notifications.processClick(extras)
```

To forward every token yourself instead, turn the fetch off with
`<meta-data android:name="ai.openpush.fcm.automatic_token" android:value="false" />`.

**Permission results.** As with OneSignal 5, nothing needs forwarding: `requestPermission`
shows the prompt in OpenPush's own transparent Activity and reports the answer itself. A forward
of `onRequestPermissionsResult` into `OneSignal.Notifications.onPermissionResult` left over from
the OneSignal setup is harmless; it never causes a second callback.

### 1.1 The fourth edit: listeners and observers become lambdas

This is Android's counterpart of the rewrite the iOS guide names in its §1 and the Unity guide in
its §1.1, and it is the one thing in this migration a changed import cannot absorb. OneSignal v5
registers its hooks by handing over an **object implementing an interface**
— `INotificationClickListener`, `INotificationLifecycleListener`, `IPermissionObserver`,
`IPushSubscriptionObserver`. OpenPush takes a **lambda** and returns a `ListenerHandle` you pass
to the matching `remove…` call. So a v5 call site that passes a conforming object is a **type
error**, not a named refusal:

```diff
-OneSignal.Notifications.addClickListener(this)   // : INotificationClickListener
+val handle = OneSignal.Notifications.addClickListener { event -> … }
```

```diff
-override fun onClick(event: INotificationClickEvent) {
-    Log.d("app", event.notification.notificationId)
-}
+val handle = OneSignal.Notifications.addClickListener { event ->
+    Log.d("app", event.messageId ?: "")
+}
```

The payload types change with the shape, because these are OpenPush's own types and not aliases
for v5's:

| OneSignal v5 | OpenPush 1.2.2 |
| --- | --- |
| `INotificationClickListener` → `INotificationClickEvent` (`.notification.notificationId`) | `(ClickEvent) -> Unit`, where `ClickEvent` carries `messageId` and `data` |
| `INotificationLifecycleListener` → `INotificationWillDisplayEvent` | `(ForegroundEvent) -> Unit`, where `ForegroundEvent` carries `messageId`, `data` and `preventDefault()` |
| `IPermissionObserver` → `onNotificationPermissionChange(Boolean)` | `(Boolean) -> Unit` |
| `IPushSubscriptionObserver` → `onPushSubscriptionChange(PushSubscriptionChangedState)` | `(PushSubscriptionState) -> Unit`, a flat `id` / `token` / `optedIn` snapshot |

Unregistering changes with it: v5 passes the same object back to `removeClickListener(listener)`,
while OpenPush takes the `ListenerHandle` the `add…` call returned. Keep that handle if you ever
remove the listener. The rows in [section 2](#2-forwarded-symbols) flag every member affected.

### Nested objects and Java

`OneSignal.InAppMessages` and `OneSignal.Session` are real nested objects and are available from
both Kotlin and Java. The refused `Debug` and `Location` groups are Kotlin extension properties
backed by `OneSignalDebug` and `OneSignalLocation`, so Java callers must use the replacements in
section 3 directly.

With the wildcard import from §1, `OneSignal.Debug` and `OneSignal.Location` resolve as far as the
member and the member then refuses with its replacement message.
`OneSignal.Debug.logLevel = 5` reports *"OpenPush has one diagnostic sink instead of log levels:
set `OpenPush.onLog` and filter yourself"* on that line.
Only the declaration site differs from v5, not what you write.

`OneSignal.User`, `OneSignal.User.pushSubscription`, `OneSignal.Notifications`,
`OneSignal.InAppMessages`, and `OneSignal.Session` keep the nested spelling exactly.

### 1.2 Behaviors worth reading before you ship

These compile identically to v5. They are here because the *behavior* is worth knowing, not because
a call site changes.

**Identity scoping.** Tags and aliases belong to the user who set them. `OneSignal.login("b")` while
this device is identified as `"a"` clears `"a"`'s tags and aliases from the device before the new
identity is registered, and `OneSignal.logout()` clears them and sends only the detach — so the next
user of a shared device does not inherit the last one's targeting data. Tags written while the
install was anonymous *do* carry forward into the first `login`, which is the one case where the
anonymous install and the identified user are the same person. If your app relied on tags surviving a
user switch, set them again after the `login` callback reports `Success`.

**`optedIn` requires a provider token.** `OneSignal.User.pushSubscription.optedIn` is true only when
notification permission is granted, the host has not called `optOut()`, **and** a provider token is
held. It is therefore false in the window between `initialize` and the first token arriving from
Firebase, even with permission already granted. Observe the subscription rather than reading
`optedIn` once at startup: `OneSignal.User.pushSubscription.addObserver { state -> … }` fires when the
token and the subscription id arrive.

**Auth hashes are never persisted, so a verified identity asks for its hash again.** If you log in
with a hash (v5's `login(externalId, jwt)`), the hash lives in memory only. On the next launch the
SDK restores the identity but not the hash, and rather than re-asserting the identity unverified it
omits `external_id` from the registration — leaving the server's existing link intact — and calls
`OpenPush.onIdentityVerificationRequired` with the id. Supply the hash again from there:

```kotlin
OpenPush.onIdentityVerificationRequired = { externalId ->
    yourBackend.hashFor(externalId) { hash -> OneSignal.login(externalId, hash) }
}
```

The hook fires once per process and has no v5 equivalent — v5 kept re-sending the identity and let the
server refuse it. The companion hook `OpenPush.onIdentityRejected` fires with the server's note
whenever a registration comes back refusing the identity, including one sent by a tag or alias write
that has no callback of its own. Both live on `OpenPush` rather than on the alias, because the alias
adds no API of its own.

**Icons, launch URLs and permission prompts carry over.** Keep the notification icons you bundled
for v5: when a push names no icon, the renderer falls back to `ic_stat_onesignal_default` for the
status-bar icon and `ic_onesignal_large_icon_default` for the large icon (after the OpenPush names
`ic_stat_openpush_default` and `ic_openpush_large_icon_default`). `op_android_large_icon` takes an
HTTPS URL or a drawable name. A tapped push with a launch URL opens the URL
without starting the app; the OpenPush manifest key `ai.openpush.suppress_launch_urls`
replaces v5's `com.onesignal.suppressLaunchURLs`. A notification permission granted through another
prompt (your own `ActivityCompat.requestPermissions`, or a game engine's) is picked up on the next
resume, so the subscription becomes targetable without a relaunch.

## 2. Forwarded symbols

Every facade forward is listed below. Members marked **OpenPush addition** are useful while the
facade is installed but do not claim to be v5 members. Listener rows marked **shape change** take a
lambda and return a handle where v5 took a conforming object, so those call sites need the rewrite
in [§1.1](#11-the-fourth-edit-listeners-and-observers-become-lambdas).

| OneSignal v5 | Forwards to | Shape |
| --- | --- | --- |
| `OneSignal.initWithContext(context, appId)` | `OpenPush.initialize(context, appId)` | identical; SDK key from manifest meta-data |
| `OneSignal.initialize(context, appId, sdkKey, …)` | `OpenPush.initialize(…)` | OpenPush extra: `serverURL`, `credential`, `transport` |
| `OneSignal.login(externalId, authHash)` | `OpenPush.login(…)` | adds an optional `IdentityResult` callback |
| `OneSignal.logout()` | `OpenPush.logout(callback)` | adds an optional `IdentityResult` callback |
| `OneSignal.loginSuspend(externalId, authHash)` / `logoutSuspend()` | `OpenPush.loginSuspend(…)` / `logoutSuspend()` | suspend forms returning `IdentityResult` |
| `OneSignal.register(token, externalId)` | `OpenPush.register(…)` | **OpenPush addition**; the two-argument form is deprecated: use `login` for the identity and `register(token)` |
| `OneSignal.isInitialized` / `sdkVersion` / `DEFAULT_SERVER_URL` | the matching `OpenPush` member | **OpenPush additions** |
| `OneSignal.User.addTag` / `addTags` / `removeTag` / `removeTags` | `OpenPush.User.…` | same shape; an out-of-shape key or an over-long value is dropped with a log instead of failing the write, and `removeTag` bypasses those rules so a bad key is still removable |
| `OneSignal.User.getTags()` | `OpenPush.User.getTags()` | returns a copy of the tags the user has; a deletion still riding the wire is already absent from it |
| `OneSignal.User.trackEvent(name, properties)` | `OpenPush.trackEvent(…)` | properties are optional; event names must match `[A-Za-z][A-Za-z0-9_.-]{0,127}`, properties must be JSON-safe, and serialized properties must not exceed 2048 bytes |
| `OneSignal.User.addAlias` / `addAliases` / `removeAlias` / `removeAliases` | `OpenPush.User.…` | same shape |
| `OneSignal.User.addSms(sms)` / `removeSms(sms)` | `OpenPush.User.addSms` / `removeSms` | forwarded; separators are removed and `00` is read as `+`, then the number must be `+` and 8 to 15 digits, the first not `0`, or the call returns `false` and sends nothing; a new number stays not verified until a code from `OpenPush.Verify` is approved, and `removeSms` opts it out (status -2) |
| `OneSignal.User.addEmail(email)` / `removeEmail(email)` | `OpenPush.User.addEmail` / `removeEmail` | forwarded. addEmail subscribes the address at once, unless this app's suppression list has it. removeEmail removes the subscription; it is not an unsubscribe. The address is trimmed and lower-cased; one without a single `@`, a name and a domain with a dot returns `false` and sends nothing |
| `OneSignal.User.externalId` | `OpenPush.User.externalId` | same shape |
| `OneSignal.User.pushSubscription.id` / `token` / `optedIn` | `OpenPush.User.pushSubscription.…` | live view; `.snapshot()` freezes it |
| `OneSignal.User.pushSubscription.optIn()` / `optOut()` | `OpenPush.PushSubscription.…` | `optIn(activity)` also available |
| `OneSignal.User.pushSubscription.addObserver` / `removeObserver` | `OpenPush.PushSubscription.…` | **shape change** (§1.1): takes a `(PushSubscriptionState) -> Unit` lambda and returns a `ListenerHandle`; change-only |
| `OneSignal.User.pushSubscription.addChangeObserver` / `removeChangeObserver` | `OpenPush.PushSubscription.…` | **OpenPush addition** and **shape change**: takes `(PushSubscriptionChange) -> Unit`; the event carries nullable `previous` and non-null `current` states; returns a `ListenerHandle` |
| `OneSignal.User.pushSubscription.snapshot()` | `OpenPush.User.pushSubscription.snapshot()` | **OpenPush addition**; freezes the live view |
| `OneSignal.Notifications.permission` | `OpenPush.Notifications.permission` | cached value, not a live query |
| `OneSignal.Notifications.canRequestPermission` | `OpenPush.Notifications.canRequestPermission` | identical; after a denial, query while an Activity is resumed for Android's rationale result |
| `OneSignal.Notifications.requestPermission(fallbackToSettings)` | `OpenPush.Notifications.requestPermission(…)` | `suspend` and callback forms; Activity form added |
| `OneSignal.Notifications.PERMISSION_REQUEST_CODE` | `OpenPush.Notifications.PERMISSION_REQUEST_CODE` | only for answers from your own prompt; `requestPermission` needs no forward |
| `OneSignal.Notifications.addClickListener` / `removeClickListener` | `OpenPush.Notifications.…` | **shape change** (§1.1): takes `(ClickEvent) -> Unit` and returns a `ListenerHandle` |
| `OneSignal.Notifications.addForegroundLifecycleListener` / `removeForegroundLifecycleListener` | `OpenPush.Notifications.…` | **shape change** (§1.1): takes `(ForegroundEvent) -> Unit` and returns a `ListenerHandle`; fires only for foreground arrivals — see [below](#the-foreground-lifecycle-listener) |
| `OneSignal.Notifications.addPermissionObserver` / `removePermissionObserver` | `OpenPush.Notifications.…` | **OpenPush addition**; takes `(Boolean) -> Unit` and returns a `ListenerHandle` |
| `OneSignal.Notifications.onPermissionResult` / `processForegroundMessage` / `processClick` | `OpenPush.Notifications.…` | **OpenPush additions** for host-owned Android forwarding (`onPermissionResult` is optional) |
| `OneSignal.Notifications.beginReceived` / `finishReceived` | `OpenPush.Notifications.…` | **OpenPush additions**: a host-owned service's duplicate check; drop the delivery when `beginReceived` returns `false` |
| `OneSignal.Notifications.clearAllNotifications()` | `OpenPush.Notifications.clearAll()` | same app-shade behavior; also clears tracked renderer entries |
| `OneSignal.Notifications.removeNotification(id)` | `OpenPush.Notifications.remove(id)` | same shape; the id must come from the optional FCM renderer |
| `OneSignal.Notifications.removeGroupedNotifications(group)` | `OpenPush.Notifications.removeGroup(group)` | same shape; cancels tracked children and the generated summary |
| `OneSignal.InAppMessages.paused` | `OpenPushInAppMessages.paused` | requires the optional in-app module; pausing hides and requeues the current message without dismissal callbacks or consuming redisplay state |
| `OneSignal.InAppMessages.addTrigger` / `addTriggers` / `removeTrigger` / `removeTriggers` / `clearTriggers` | the matching `OpenPushInAppMessages` member | same trigger operations; requires the optional in-app module |
| `OneSignal.InAppMessages.addLifecycleListener` / `removeLifecycleListener` | `OpenPushInAppMessages.…` | **shape change**: takes an `(InAppMessageLifecycleEvent) -> Unit` lambda and returns a `UUID` handle |
| `OneSignal.InAppMessages.addClickListener` / `removeClickListener` | `OpenPushInAppMessages.…` | **shape change**: takes an `(InAppMessageClickEvent) -> Unit` lambda and returns a `UUID` handle |
| `OneSignal.Session.addOutcome(name)` | `OpenPush.trackEvent(name)` | custom event only; notification-influence attribution is not reproduced |
| `OneSignal.Session.addUniqueOutcome(name)` | `OpenPush.trackEvent(…)` with an idempotency key | deduplicated by a persistent installation scope; notification-influence attribution is not reproduced |
| `OneSignal.Session.addOutcomeWithValue(name, value)` | `OpenPush.trackEvent(name, mapOf("value" to value))` | configure the server metric with `tracking = value` and `value_property = "value"`; notification-influence attribution is not reproduced |

The compatibility facade returns an empty string when `externalId`, `pushSubscription.id`, or
`pushSubscription.token` is absent. The direct OpenPush API uses nullable strings. An id is absent
until an accepted registration supplies it; a token is absent before provider registration and
after `OpenPush.clearToken()`.

Both subscription observer spellings are change-only and share one dispatch. `addObserver` receives
the new flat `PushSubscriptionState(id, token, optedIn)`. `addChangeObserver` receives
`PushSubscriptionChange(previous, current)`; `previous` is null for the first observed change.
`optedIn` is true only when notification permission is granted, a provider token is held, and the
host has not called `optOut()`.

Compatibility-facade subscription, permission, notification-click, and foreground callbacks run
on the Android main thread. Direct OpenPush callbacks run on the main thread too, except the
foreground listener, which runs on the push-delivery thread; the facade waits a bounded time for
the main thread so a migrated foreground listener keeps working. See
[Threading](API.md#threading).

### The foreground lifecycle listener

Everything above is a name-for-name forward, so — once the two **shape change** rows are rewritten
as lambdas (§1.1) — the module **compiles drop-in**.

OpenPush's `addForegroundLifecycleListener` fires only for a notification that arrives
while the app is in the **foreground**; a push that arrives in the background or with the app
killed goes straight to the shade without consulting it. Forward **every** message through
`Notifications.processForegroundMessage` — `onMessageReceived` fires backgrounded and killed too —
and the SDK sorts them out: it dispatches to your listeners, and lets `preventDefault()` return
`true`, only while the process actually has a visible Activity. A background delivery still posts
its `received` receipt, still returns `false`, and still renders, so a `preventDefault()` can
never suppress a notification the user never saw
(`OpenPush.processForegroundMessage`, `openpush/src/main/java/ai/openpush/android/OpenPush.kt`).

Foregroundedness is read from the `Application.ActivityLifecycleCallbacks` the SDK already
registers at `initialize` — no `androidx.lifecycle-process`, no extra dependency.

**The one case that is still yours.** If you initialize with a `Context` that is not an
`Application`, there is no lifecycle to observe, so the SDK trusts you and keeps dispatching every
message (`initialize` already logs this). In that setup, either call `OpenPush.onAppForegrounded()`
and `OpenPush.onAppBackgrounded()` from your own hooks, or guard `preventDefault()` behind your own
foreground flag. Hosts that initialize from an `Application` or an `Activity` — which is nearly
everyone, since v5's `initWithContext` took either — need none of this.

The **OpenPush addition** rows above are convenience forwards on the facade; they are not v5
compatibility claims.

## 3. Refused symbols

Each of these is a **compile error** carrying the message below. Nothing here is a no-op.
The `OneSignalInAppMessages` trigger/pause surface is forwarded when the optional IAM
module is installed, so it is not refused.

| OneSignal v5 | What to do instead |
| --- | --- |
| `OneSignal.initWithContextSuspend` | Initialization is synchronous. Call `initWithContext`, then use `OpenPush.awaitRegistration()` only when you need to wait for registration. |
| `OneSignal.getUserSuspend` / `getSessionSuspend` / `getNotificationsSuspend` / `getLocationSuspend` / `getInAppMessagesSuspend` | Use the corresponding non-suspend member. |
| `OneSignal.getConsentRequiredSuspend` / `setConsentRequiredSuspend` / `getConsentGivenSuspend` / `setConsentGivenSuspend` | Use the corresponding non-suspend member, whose consent-gate refusal below explains the supported host-owned gate. |
| `OneSignal.getDisableGMSMissingPromptSuspend` / `setDisableGMSMissingPromptSuspend` | Use the non-suspend member, whose refusal below explains that OpenPush shows no such dialog. |
| `OneSignal.updateUserJwt` / `updateUserJwtSuspend` / `addUserJwtInvalidatedListener` / `removeUserJwtInvalidatedListener` | Verify with `login(externalId, authHash)` or `loginSuspend`, and re-supply the hash from `OpenPush.onIdentityVerificationRequired`. |
| `OneSignal.disableGMSMissingPrompt` | OpenPush shows no dialogs. |
| `OneSignal.consentRequired` / `consentGiven` | OpenPush has no runtime consent gate. Initialize only after consent; to withdraw after initialization call `optOut()` and `logout()`. A full stop requires not initializing. |
| `OneSignal.Debug.logLevel` / `alertLevel` / `addLogListener` / `removeLogListener` | Set `OpenPush.onLog`; filter messages, present alerts, or compose listeners in the host. |
| `OneSignal.Location.isShared` / `requestPermission` | OpenPush never collects location. Send a tag or alias you compute yourself if you need geo targeting. |
| `OneSignal.User.onesignalId` | The user id exists server-side and will be returned in a later server release. Until then use `OneSignal.User.externalId`, or `OneSignal.User.pushSubscription.id` for this installation. |
| `OneSignal.User.setLanguage(value)` | OpenPush sends the primary language subtag, remaps `iw→he`, `in→id`, and `ji→yi`, and preserves the region only for `zh`; it cannot be overridden. |
| `OneSignal.User.addObserver` / `removeObserver` | OpenPush has no user-state observer. Observe `OneSignal.User.pushSubscription` instead; its lambda receives id, token, and optedIn. |

## 4. When the migration is done

The alias module is a bridge, not a destination. Once the call sites compile and the app
registers, drop the dependency and replace `OneSignal.` with `OpenPush.` — every supported v5
member keeps its name on `OpenPush` itself, including `initWithContext`, the instance
`pushSubscription.addObserver`, and the `suspend requestPermission`. That is a mechanical
find-and-replace with no signature changes.

## 5. A worked example

`examples/migrated-from-onesignal/README.md` shows an entire app migrating, as a
diff you can regenerate.
