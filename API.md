# Public API — 1.2.2

The SDK
registers Android provider tokens, identifies users,
carries session tags and aliases, synchronizes subscription state, requests
notification permission, reports notification events, posts delivery receipts,
parses OpenPush payloads, and stores an installation credential in Android
Keystore. It contains no send surface. Hosts using the optional compatibility facade can find its
complete forwarded and refused symbol maps in the [migration guide](MIGRATING-FROM-ONESIGNAL.md).

## In-app messages

Add `ai.openpush:openpush-android-in-app-messages:1.2.2`. Its manifest provider
starts the engine at process start; there is no base class to extend and no
initialization call to make.

### Surface

`OpenPush.InAppMessages` (the object is also reachable as
`OpenPushInAppMessages`) exposes:

- `paused` — suspends and resumes display, described below.
- `addTrigger(key, value)`, `addTriggers(map)`, `removeTrigger(key)`,
  `removeTriggers(keys)`, `clearTriggers()` — the trigger values this device
  matches against. A value may be a `String`, a number or a `Boolean`.
- `addLifecycleListener(listener): UUID` / `removeLifecycleListener(id)` and
  `addClickListener(listener): UUID` / `removeClickListener(id)` — registration
  returns the handle that removes it again.
- `fetch()` — asks for this subscription's messages now. A failed answer, including a 2xx whose
  body is not a message list, is logged and retried once; the messages already loaded stay.
- `previewLocalDocument(activity, assetPath)` — an `@OpenPushInternalApi` development hook that
  renders an app-bundled HTML document through the production presenter without recording receipts,
  impressions, dismissal, redisplay, or generation state.

`InAppMessageLifecycleEvent(messageId, phase, stage)` carries the same fact
twice: `phase` is one of `"willDisplay"`, `"didDisplay"`, `"willDismiss"`,
`"didDismiss"`, and `stage` is the matching `InAppLifecycleStage` constant
(`WILL_DISPLAY`, `DID_DISPLAY`, `WILL_DISMISS`, `DID_DISMISS`). The retained
two-argument constructor accepts phase text from newer SDKs and maps an unknown
value to `UNKNOWN`, so exhaustive `when` expressions must include that constant.

`InAppMessageClickEvent(messageId, actionId, url, urlTarget, closingMessage,
firstClick, pageId)` describes one tap. `firstClick` is about the message, not
the button: it is true only for the very first click id recorded against that
message. `pageId` is the page a multi-page document last reported moving to, or
`null` for a single-page document. The two trailing fields are defaulted, so
five-argument construction still compiles.

`RegistrationAck`, `InAppMessageClickEvent`, and `InAppMessageLifecycleEvent`
keep their shorter JVM constructors, `componentN` methods, `copy` methods, and
`copy$default` helpers alongside the full ones. Every field participates in equality,
hashing, text, destructuring, and the full copy overloads. A copy call through a
shorter overload keeps the values of the fields it does not name.

### Display

Messages are fetched after the subscription id arrives and at the start of every
new session (a return to the foreground after 30 s away). A fetch is throttled to
one request per 30 s — the session-start fetch is not — refused while the app is
in the background, and retried exactly once, 30 s later, when the server refuses
it. A fetch replaces the whole definition list: a message the server no longer
returns is gone, and one it returns twice is kept once.

Nothing is discarded for being ineligible. A message whose triggers have not
fired, whose redisplay delay has not elapsed, or which is behind another message
stays a definition and is reconsidered every time anything eligibility depends on
changes. A message past its `end_time` is never queued.

The document is shown in a `WebView` inside a full-screen `PopupWindow`, and the
document itself decides how that surface looks by sending `rendering_complete`:

| `displayLocation` | surface |
| --- | --- |
| `top` / `bottom` | a banner the height the document reported, at that edge, not focusable and outside-touchable — so the rest of the screen still belongs to your app |
| `center` | the document centred over a `#BB000000` scrim, focusable |
| `full`, `carousel`, anything else | full-screen over the scrim, with the immersive system-UI flags set |

A banner is sized to itself rather than kept full-screen and made transparent,
because a touch outside a window reaches the app behind it and a touch on an
empty part of a full-screen window does not.

`paused = true` takes the message on screen down and gives it back to the head of
the queue, recording nothing: no impression, no dismissal, no redisplay count, no
lifecycle events. `paused = false` puts that same message back up before anything
else in the queue. Pausing is not a display.

### What reaches the server

Three receipts, all `POST /v1/ingest` and all driven by what the document
reported:

- `iam_impression`, once per message, on `rendering_complete` — not when the
  message was put on screen. A document that never renders is never counted.
- `iam_click`, once per click id, carrying `click_id`, `block_key` when the
  document sent one, and `first_click`.
- `iam_page_impression`, once per page of a multi-page document, carrying
  `page_id`.

Each is recorded locally before the request and un-recorded when the server
refuses it, so a transient failure is retried rather than lost. The click
*listener* is deliberately out of step with the click *receipt*: the listener
fires for every action carrying an id, including a repeat of a tap the server has
already counted, because a second tap is a second thing the user did.

### Actions

An `action_taken` envelope can ask for four things, and the engine — never the
JavaScript bridge — does them, on the main thread:

- a **`url`**. `url_target = "webview"` opens a Custom Tab when the device has a
  browser that provides one, and otherwise an ordinary `ACTION_VIEW`; any other
  target opens `ACTION_VIEW`. Only `http` and `https` are opened; anything else
  is logged and ignored.
- a **push permission prompt**. The queue is held while the system dialog is up —
  a second message must not appear behind or on top of it — and released by the
  first of the request's own callback, the SDK's permission-result listener, or a
  60 s backstop. A message asks at most once per process.
- **tags**, applied through `OpenPush.User.addTags`.
- **outcomes**, tracked through `OpenPush.trackEvent`.

An action with `close: true` ends the display. That records the display but is
**not** a dismissal: only a back press, a tap outside the surface or a system
teardown writes the message into the dismissed set and burns a one-shot.

### Triggers

A message's triggers are an OR of AND-groups. Alongside the values the host sets,
two kinds are satisfied by the clock alone: `session_time` (seconds since this
session began) and `min_time_since` (seconds since the last message came down;
"never" counts as infinitely long ago). The engine schedules exactly one wake-up,
at the earliest instant an unmet `greater` or `greater_or_equal` clock condition
could become true, and re-evaluates there — so a message gated on "60 seconds into
the session" appears without the host touching anything. Time passing is not a
trigger change: a message already displayed in this generation still waits for a
real trigger mutation or a new session.

The `in` operator is membership, not substring: a device value that is a
collection is tested element by element, a `String` is split on commas and
trimmed, and anything else is compared whole. Numbers compare numerically when
both sides parse as numbers.

### Redisplay

A message with the default redisplay limit of 1 is a one-shot: once the user
dismisses it, it does not come back. A message allowed to repeat comes back only
when all three hold — the trigger generation has moved since its last display (a
trigger mutation or a new session), its redisplay delay has elapsed, and its
display count is still under its limit. The generation rule is what stops a
message that is allowed to repeat from reappearing the instant it is dismissed.

### State and hardening

Dismissals, impression ids, click ids, page views, redisplay counts and
generation stamps live in `SharedPreferences` under `openpush.iam`, in an entry
keyed by the configuration's token key — so two workspaces in one process, or one
app switching workspaces, never read each other's state. Records older than 180
days are purged at each attach.

The document's `WebView` has JavaScript on and everything else shut: no file
access, no content access, no cross-origin reads from file URLs, and no plaintext
sub-resource on an https page. Remote debugging is enabled only in a debuggable
app. The bridge object (`window.OpenPushAndroid`) parses envelopes and does
nothing else — it holds no reference to the SDK's own surfaces — and a link the
user follows leaves for the browser rather than replacing the message.

## Threading

Your callbacks run on the main thread. That covers the subscription and permission observers,
the `requestPermission`, `login`, `logout`, `addEmail`, `removeEmail`, `Verify` and `receipt`
callbacks, `onRegistered`, `onRegistrationFailed`, `onIdentityVerificationRequired`,
`onIdentityRejected`, and the in-app lifecycle and click listeners.

- They are posted, even when the call that caused them was made on the main thread, so a callback
  never runs inside that call. They arrive in the order the SDK raised them.
- Notification click listeners also run on the main thread. A click from the notification tap
  reaches them at once, before the app opens. A click held from a cold start is delivered inside
  `addClickListener` when you call it on the main thread.
- Foreground listeners (`addForegroundLifecycleListener`) run on the thread that delivered the
  push, not the main thread, because `preventDefault()` must be decided before the push is drawn.
  Decide quickly, and post to the main thread for UI work.
- Background-message listeners and the manifest background handler also run on the delivering
  thread.
- `onLog` runs on the thread that wrote the line.
- The suspend forms (`loginSuspend`, `logoutSuspend`, `awaitRegistration`, the suspend
  `requestPermission` and the `Verify` calls) resume in your coroutine's context.

Network, storage and registration work stays off the main thread. Move heavy work off the main
thread inside your callback.

## OpenPush

- `Request` / `Response` — transport values used by the injectable seam.
  `Response(status, body, headers)` also has a two-argument JVM constructor and
  `copy(status, body)` descriptors; the additive header-aware form defaults to an empty map.
  The built-in transport exposes response header names lower-cased; custom transports may supply any
  casing, and `Retry-After` lookup is case-insensitive.
- `DEFAULT_SERVER_URL` — the server `initialize` uses when none is given.
- `META_APP_ID` / `META_SDK_KEY` — the manifest metadata names read by the manifest-only and
  manifest-key initialization forms.
- `X_OP_SDK` / `X_OP_SDK_WRAPPER` — the request-header names used for native and wrapper identity.
- `sdkWrapper` — optional wrapper identity such as `"unity/1.2.2"`. Set it before initialization;
  non-blank values are sent as `X-OP-SDK-Wrapper` while `X-OP-SDK: android/<version>` identifies
  this SDK on every request.
- `onLog` — bounded operational messages that never contain credentials or full tokens. Its default
  sink writes to Logcat only for a debuggable host application; assigning a sink receives diagnostics
  in every build.
- `onRegistered` — called after the subscription endpoint accepts the provider token.
- `onRegistrationFailed` — called with a bounded registration failure description.
- `onIdentityVerificationRequired` — called with the external ID when a verified
  identity needs its auth hash re-supplied after a relaunch; call
  `login(externalId, authHash)` again from the hook. It fires **once per
  process**: a host that ignores it gets no second warning until the app is
  restarted. Installing the hook after the request was raised still delivers it.
- `onIdentityRejected` — called with the server's note whenever a registration
  comes back refusing this device's external identity, including one sent by a
  tag or alias flush rather than by `login`.
- `currentToken` — the cached provider token.
- `isInitialized` / `sdkVersion` — initialization state and the Gradle-sourced SDK version.
- `initialize(context)` — reads both `ai.openpush.app_id` and `ai.openpush.sdk_key` from manifest
  meta-data and refuses with the required manifest snippet when either value is absent.
- `initialize(context, appId, sdkKey, serverURL, credential, transport)` — configures the client with the OpenPush-hosted defaults.
- A third positional string is always the SDK key: `initialize(context, appId, "pk_live_…")` in
  Kotlin and Java. With the key in manifest meta-data, Kotlin names the extras:
  `initialize(context, appId, serverURL = "https://…", credential = …)`, and a trailing lambda is
  the transport. A third argument that starts with `http://` or `https://` cannot be a key: it is
  logged and treated as `serverURL` with the manifest key, as a positional server URL always was.
  Java exposes `initialize(context)`, `initialize(context, appId)`, and the explicit-key overloads
  generated from `initialize(context, appId, sdkKey, …)`; the manifest-key custom-`serverURL` form
  is Kotlin-only.
- `observeLifecycle(application)` — idempotently installs Activity lifecycle observation; call it
  from a process initializer only when the host disables the merged AndroidX Startup provider.
- `attach(context, configuration, credential, transport)` — the lower-level form, still supported; `initialize` wraps it.
- `register(token)` — registers the token for the configured app. The registration carries the
  identity the last `login` set, with that login's auth hash.
- `register(token, externalId)` — **deprecated**; use `login(externalId, authHash)` and
  `register(token)`. An identity is attached only through `login`, so a non-empty `externalId`
  other than the current identity is logged in first, as `login(externalId)` would, without an auth
  hash. The current identity, `null` or an empty string registers exactly as `register(token)`.
- `tokenProvider` — an optional `OpenPushTokenProvider` that fetches the provider token so the host
  does not forward it. The SDK asks after every attach, and again on the next foreground while the
  device has no accepted registration; only one request is in flight at a time, and a token already
  registered in this workspace is not sent again. `OpenPushTokenCallback.onToken(token)` registers
  it; `onUnavailable(reason)` logs the reason through `onLog`. A provider that throws is treated as
  unavailable. The FCM integration installs one at process start; `null` (the core default) leaves
  forwarding to the host.
- `trackEvent(name, properties, idempotencyKey)` — persists a validated Custom
  Event, attributes it to the current external ID or registered subscription,
  and sends it in batches of up to 50. Every event is stamped with an
  `idempotency_key` at track time and keeps it across retries and relaunches, so
  a resent batch is the same batch to the server. Pass `idempotencyKey` to supply
  that key yourself when the event's identity is already known and every repeat
  of it is the same occurrence; the server then keeps the first and collapses the
  rest. Pending events survive process restarts. A transient
  failure retries at 2 s, 8 s, then 30 s and stays at 30 s; a batch the server
  refuses with a 4xx other than 429 is **dropped** with a log naming the status,
  so one unacceptable batch cannot wedge the queue behind it. The queue is capped
  at 500 events and drops the oldest, logged once per process. A property value
  that is not JSON-safe drops the event with a log rather than being coerced.
  `CharSequence` implementations such as `StringBuilder` are encoded as JSON
  strings.
- `login(externalId, authHash, callback)` / `logout(callback)` — see below.
- `receipt(type, messageId, then)` — posts received, confirmed, or clicked evidence.
- `messageId(data)` — extracts `op_message_id` from a data payload.
- `actions(data)` — parses the composer’s ordered iOS/Android action buttons.
- `clearToken()` — removes the cached provider token.
- `shortToken(token)` — returns a display-safe abbreviated token.
- `deviceModel()` — returns a user-readable manufacturer/model name.
- `onAppForegrounded()` — recomputes notification permission and updates the
  subscription only if the last accepted value changed. Application contexts
  are observed automatically. Attach and foreground transitions also POST
  `{"token":"…"}` to `/v1/apps/{app}/sessions` at most once every 30 minutes;
  the server applies the same gap before counting a new session.
- `onAppBackgrounded()` — closes coalesced writes, accumulates foreground duration, queues its
  duration report, and schedules the background drain when durable work remains.
- `@OpenPushInternalApi` marks the seams OpenPush's own modules share. They are **not public API**
  and can change in any release. Using one is a compile error until the call site opts in with
  `@OptIn(OpenPushInternalApi::class)`. `OpenPushBridge.requestHeaders()` and `request(…)`, which
  carry the SDK key and the device credential, are Kotlin-only and not visible from Java.
- `OpenPushBridge.receivedMessageLog` — an `@OpenPushInternalApi` replacement for the record
  `beginReceived` consults (a `ReceivedMessageLog` with `contains(messageId)` and `record(messageId)`);
  null uses the default.
- `OpenPushBridge.diagnostics()` — an `@OpenPushInternalApi` read-only snapshot for optional modules
  and integration labs. It reports `receiptsQueued`, `customEventsQueued`,
  `pendingSessionSeconds`, and `lastDrainAt` without exposing queued payloads or credentials.

### Java

Public object members are Java static methods and accessors; callers do not use Kotlin's
`INSTANCE` field. Operations with default arguments expose Java overloads, including
`initialize`, `register`, `login`, `logout`, `trackEvent`, `receipt`, and the callback permission
forms. For example:

```java
OpenPush.setSdkWrapper("my-wrapper/1.0.0");
OpenPush.initialize(context, "your-app-id", "pk_live_...");
OpenPush.User.addTag("plan", "pro");
```

`sdkWrapper` is optional and is normally set only by a wrapper SDK. Set it before initialization;
Java uses `OpenPush.setSdkWrapper(...)` and Kotlin uses `OpenPush.sdkWrapper = ...`.

### Coroutine forms

- `awaitRegistration()` — returns immediately when this process has already accepted a
  registration; otherwise suspends until the next accepted registration or final registration
  failure and returns `true` or `false`.
- `loginSuspend(externalId, authHash = null)` / `logoutSuspend()` — return the same
  `IdentityResult` values as the callback forms. Cancelling the caller stops waiting but does not
  roll back a local identity write or cancel transport already in progress.
- `Notifications.requestPermission(fallbackToSettings = false)` — the suspend permission form:
  `val granted = requestPermission()` inside a coroutine. It resumes with the answer from
  OpenPush's permission Activity; no forwarding is needed. With no Activity resumed it waits for the
  prompt that starts at the next Activity resume. Kotlin picks this form whenever no callback is
  passed, so outside a coroutine pass one: `requestPermission { granted -> … }`. Java calls the
  callback form, `requestPermission(false, granted -> Unit.INSTANCE)`.

Initialization refuses an app id that is blank or contains whitespace. Registration sends the
device language as the lower-case primary subtag, remapping Android's legacy `iw`, `in`, and `ji`
codes to `he`, `id`, and `yi`. Only Chinese retains its upper-case region, for example `zh-TW`;
`pt-BR` is sent as `pt`.

A second `initialize` or `attach` is read against the configuration already in force. `OpenPushConfiguration` compares **by value** over `appID`, `sdkKey`, `serverURL`, `platform` and `tokenKey`, which gives three cases:

- **The same configuration again** — nothing is cleared. Tags, aliases, the identity, the opt-out, the subscription id, the coalescing window, a pending registration retry and the queued Custom Events all survive, so a host that calls `initialize` from more than one entry point does not lose state. The seams (context, credential, transport) are still refreshed.
- **A different app id, key, server, platform or token key** — a workspace switch. It resets the pending identity, the tag and alias maps, the subscription status, the subscription id, the cached permission and the queued Custom Events, and wipes the persisted session of both the previous and the incoming configuration.
- **The first attach in a process** — resets only the subscription id and the cached permission, because tags, aliases, an identity or a status set before `initialize` are logged as stored and riding the next registration, and they do.

No form clears listeners, which are process-lifetime host wiring.

Registration responses are applied only when the linked credential still matches the credential that started the request. Receipt deduplication occurs only after a successful ingest response, so failures remain retryable. `login`, `logout`, tag writes, alias writes, `optIn`, and `optOut` all travel the same registration path and therefore inherit that guard.

Transient registration failures and 5xx responses retry at +2 seconds and +8 seconds. A 4xx response does not retry. `onRegistrationFailed` fires only after the final attempt and may therefore arrive about 10 seconds after the initiating call. Any newer registration supersedes a pending retry, and retry delays never occupy the serial request queue.

HTTP 429 is retryable for registration and Custom Events. A `Retry-After` delta in seconds or RFC
1123 date becomes the minimum delay; the SDK takes the larger of that value and its scheduled
backoff, then adds 0–1,000 ms of jitter. The default transport returns all response headers through
`Response.headers`, allowing a host-supplied transport to preserve the same behavior.

Tags, aliases, external ID, explicit detach state, subscription status, the
subscription id, whether the stored identity was verified with a hash, and the
last app-session ping time persist in the app-private `openpush` preferences
file under the configuration's `tokenKey` namespace. Staged pre-initialization
values win during restoration. A workspace switch wipes both the previous and
incoming session namespaces. Authentication hashes stay memory-only and any
legacy persisted hash is removed — which is why a verified identity needs its
hash re-supplied after a relaunch (see `onIdentityVerificationRequired`).

## Transport and durable background work

Receipts are written to app-private storage before the SDK tries `/v1/ingest`. A batch that fails
for a reason that can pass (a 5xx, a 429 or no network) stays queued across process death and drains
on the next accepted registration, foreground transition, or background job. A batch the server
refuses outright (any other 4xx) is dropped and the drain continues with the next one. A receipt is
also given up when a drain fails after it has been owed for more than 7 days, or after 10 server
failures counted at most once an hour; network failures never count. Requests contain at most 50
receipts sharing one app and provider token. Both the pending queue and the successful-receipt
dedupe history retain at most 500 entries, dropping the oldest first. A workspace switch clears the
queue because its receipts belong to the old app. Receipts already queued by an earlier build keep
their place and drain the same way.

The SDK's background work runs on its own coroutine scopes. A failure that escapes one of them is
logged through `onLog` and never reaches the app's uncaught-exception handler.

Custom Events are also durable, capped at 500, and stored as a circular queue so one enqueue writes
one slot rather than reserializing the entire list. Up to 50 valid events tracked before
`initialize` are staged in memory, then merged into the workspace queue on first attach. Stored
session state carries schema version 2 so the legacy subscription-status migration runs only once.

When an app backgrounds with pending registration, receipt, Custom Event, or whole-second session
duration work, the SDK schedules the non-exported `OpenPushSyncJobService`. The job requires any
network, starts no earlier than one second, has a 60-second override deadline, and uses the SDK's
single-consumer request channel to drain without racing foreground work. A transient failure leaves
the durable work in place and requests rescheduling.

Foreground duration is accumulated in milliseconds, persisted, and sent as whole seconds with a
stable idempotency key to `/v1/apps/{app}/sessions`. One report is capped at 86,400 seconds. A 2xx
acknowledgement subtracts only the snapshot it accepted, so time accumulated while the request was
in flight remains for a later report. Retryable failures keep the same key; a terminal 4xx drops the
refused snapshot. Sub-second remainders stay durable until later activity produces a wire-valid
positive duration.

The core manifest merges `INTERNET`, `POST_NOTIFICATIONS`, the
`androidx.startup.InitializationProvider` metadata for `OpenPushLifecycleInitializer`, and
`OpenPushSyncJobService` protected by `BIND_JOB_SERVICE`. The Startup initializer observes Activity
lifecycle from process start without an app id and without network work. If a host removes the
provider, it must call `OpenPush.observeLifecycle(application)` from its own process initializer.

## Identity

- `login(externalId, authHash = null, callback = null)` — identifies this device. With a provider token held, it re-registers immediately carrying `external_id` (and `external_id_auth_hash` when given) plus the current tag map. Without a token the identity is stored and rides every later registration, and the callback reports success-with-deferral.
- `logout(callback = null)` — clears the pending identity and re-registers with an explicit empty `external_id`. Present-and-empty means detach; a body with the key absent would leave the existing attachment in place.

`login("")` — or any id that trims to empty — is **refused**: the callback reports `Failed`, nothing goes on the wire and nothing local changes. Call `logout()` to detach.

Both calls are idempotent. A `login` with the same id, no new hash and nothing outstanding reports `Success` and sends nothing; a `logout` while already detached does the same. Anything that is genuinely new — a different id, a hash the SDK does not yet hold, or an identity the server has not yet accepted — still registers.

The callback runs on the main thread, after the call returns, both for a local refusal or no-op and for an answer that waited for registration. See [Threading](#threading).

`User.externalId` reports the identity the SDK believes is in force, not the last one the host asked for. It becomes `null` when the server rejects the claim, so a host reading it after a rejection sees the anonymous truth.

Logging in **as a different user clears this device's tags and aliases locally** before the body is built, and `logout` clears them and sends only the detach. Data collected while anonymous still carries forward into the first `login`. See [OpenPush.User](#openpushuser).

`IdentityResult`:

- `IdentityResult.Success` — accepted, already in force, or stored for the next registration.
- `IdentityResult.IdentityRejected(note)` — the server refused the identity claim and the device is registered **anonymously**. This is never reported as success.
- `IdentityResult.Failed(message)` — the request did not complete, or the call was refused locally (an empty external ID). A completion callback also fails rather than hanging when the credential changed mid-flight.

A rejected identity is additionally logged through `onLog` as `⚠ identity rejected — this device is registered anonymously · <note>`, and `onIdentityRejected` fires with the note — including for a rejection that came back on a tag or alias flush, which has no `IdentityResult` callback to report to.

### Re-supplying an auth hash after a relaunch

Auth hashes are never persisted. A session that was verified with one therefore comes back from disk knowing it owes a hash it no longer holds, and the SDK will **not** re-assert that identity unverified: the registration body omits `external_id` — leaving the server's existing link untouched rather than downgrading it — and `onIdentityVerificationRequired` fires with the id.

```kotlin
OpenPush.onIdentityVerificationRequired = { externalId ->
    yourBackend.hashFor(externalId) { hash -> OpenPush.login(externalId, hash) }
}
```

Install the hook before `initialize` where you can; installing it later still delivers a request already raised. A session stored without a verification flag reads as unverified, so a host on hashes may see one more unverified assertion on the next launch; from then on the flag is written on every login.

## OpenPush.User

- `addTag(key, value)` — adds or replaces one tag and re-registers when a token is held.
- `addTags(tags)` — the same for several tags in a single request.
- `removeTag(key)` — sends `""` for the key; the server reads an empty value as a delete.
- `getTags()` — returns a copy of the tags this user **has**. A key whose deletion is still riding the wire is already gone from this map.
- `removeTags(keys)` — removes several tags in one write.
- `addAlias(label, id)` — adds or replaces one alias: a second label the server can address this user by, next to `external_id`.
- `addAliases(aliases)` — the same for several aliases in a single request.
- `removeAlias(label)` — sends `""` for the label; the server reads an empty value as a delete.
- `removeAliases(labels)` — removes several aliases in one write.
- `externalId` — the currently claimed external identity, or null.
- `pushSubscription` — a live `LivePushSubscription` view with `id`, `token`, `optedIn`, `optIn()`, `optOut()`, and `snapshot()`.
- `addSms(number): Boolean` / `addWhatsApp(number): Boolean` — registers a number under platform `sms` / `whatsapp`, normalised to E.164 as described below. The server holds it as not verified (status `0`) until a phone code is approved; no status is sent. The body carries the current `external_id`, with `external_id_auth_hash` under identity verification, as an email write does; a 2xx with `identity_rejected` is logged as stored without a user. Returns `false`, logs, and sends nothing for a number that does not normalise.
- `removeSms(number): Boolean` / `removeWhatsApp(number): Boolean` — opts the number out with status `-2`.
- `addEmail(address, callback: ((OpenPushEmailResult) -> Unit)? = null): Boolean` — subscribes the address under platform `email` at once, unless this app's suppression list has it. Returns `false`, logs, reports `INVALID_EMAIL` and sends nothing for an address that fails the local check below.
- `removeEmail(address, callback = null): Boolean` — removes the email subscription with status `-2`. It is not an unsubscribe: no suppression is written, and a later `addEmail` subscribes the address again.

The tag map rides every registration body as `tags`, and the alias map as `aliases`. Each is omitted entirely while empty, so a host that never tags and never aliases sends a body with neither key. A removed tag or alias keeps riding as `""` — so a token-refresh re-registration in between cannot resurrect it — until a registration carrying that deletion is accepted, at which point the key is pruned from the map.

Tags and aliases belong to the identity that set them. `login` as a **different** user clears both maps locally before the new identity's body is built, and `logout` clears them and sends only the detach. Writes made while anonymous carry forward into the first `login`, which is the case where they are the same person.

Tag and alias mutations coalesce into a fixed 300 ms window from the first pending write. The window never restarts. An explicit registration, identity change, or subscription status change absorbs the values into its own request; application backgrounding flushes them immediately. With no token, no window opens.

### Write rules

An out-of-shape entry is dropped locally, with `onLog` naming the rule, and the rest of the write proceeds — one bad key never fails the registration:

| Rule | Applies to |
| --- | --- |
| a tag key cannot be empty | `addTag`, `addTags`, and the removal calls |
| a tag key cannot contain whitespace | `addTag`, `addTags` |
| a tag key is limited to 60 characters | `addTag`, `addTags` |
| a tag value is limited to 300 characters | `addTag`, `addTags` |
| an alias label cannot be empty | all alias calls |
| the label is one the server reserves (`external_id` among them) | all alias calls |
| an alias id cannot be empty — use `removeAlias` | `addAlias`, `addAliases` |

Limits are inclusive: a 60-character key with a 300-character value is accepted. An empty tag **value** is not an invalid value, it is the delete signal.

`removeTag` / `removeTags` bypass the whitespace and length rules, so a malformed key an older build managed to store is still removable; the non-empty-key rule is not bypassed, because an empty key names nothing. `addTags(mapOf(key to ""))` is therefore not a universal delete spelling — it still deletes any key the write rules accept, but `removeTag` is the call that always works.

The server caps aliases per user at 10 and refuses reserved labels, naming them in an `aliases_refused` note on the 2xx response rather than failing the request; the SDK logs that refusal loudly. The SDK does not enforce the cap itself — the server owns it — but it warns once per process (`⚠ N alias labels are held — the server keeps at most 10 per user`) when the labels it holds go over. Alias writes ride the same identity gate as tags: with identity verification enabled, a write without a valid auth hash comes back `identity_rejected`, is logged loudly, and fires `onIdentityRejected`.

### Phone numbers

A phone write is `POST /v1/apps/{app_id}/subscriptions` with body `{"token": "+E164", "platform": "sms" | "whatsapp", "external_id"?}`, plus `"status": -2` for a removal; the `external_id` is the current one, omitted while anonymous. A number is normalised exactly as the server does it: every character that is not a digit or `+` is removed (`+1 (415) 555-0123` → `+14155550123`), a leading `00` becomes `+`, and the result must be `+` and then 8 to 15 digits only, the first not `0` — so a second `+`, a missing `+`/`00` (`415`) or a leading zero (`+0123456789`) is refused. Writes run on the registration queue, in call order. Each is persisted (per app id) until the server answers it, so a write made offline or before `initialize` is sent at the next attach, and only the newest write per (platform, number) is kept. A transport failure, 429 or 5xx retries on 2 s, 8 s, then every 30 s, never sooner than `Retry-After`; any other 4xx drops that write with a log. The `id` of each 2xx response is stored per (platform, number).

### Email addresses

An email write is `POST /v1/apps/{app_id}/subscriptions` with body `{"token": "<address>", "platform": "email", "external_id"?, "external_id_auth_hash"?}`, plus `"status": -2` for a removal. `external_id` is the current one, with its auth hash under identity verification, and is omitted while anonymous or while a required hash is missing, as on registration. The address is trimmed and lower-cased, and must have exactly one `@`, a non-empty name before it, a dot in the domain, and at most 254 characters. Writes share the phone-number queue, store (keyed `email:<address>`) and retry rules: persisted per app id, newest write per address kept, a transport failure, 429 or 5xx retried on 2 s, 8 s, then every 30 s.

The response is `{"id", "status", "status_name", "created"}`. On `addEmail`, status `1` is subscribed (new or re-added) and `-2` means the suppression list has the address: that is reported, never retried. Any other 4xx (a 400 names why in `detail`) is reported and dropped.

`OpenPushEmailResult` — `Success(subscriptionId: String?, removed: Boolean)` or `Failure(error: OpenPushEmailError)`. The callback runs once, on the main thread, with the server's final answer; an invalid address is reported at once, also posted to the main thread. It is held in memory only, so a write resent after process death reports to nobody, and it is dropped when a newer write for the same address replaces its write.

`OpenPushEmailError` (an `Exception`) — `code`, `message` (the server's words when it gave them), `status` (null for a local refusal) and `subscriptionId` (set for `SUPPRESSED`). `code` is `INVALID_EMAIL` (refused on the device, nothing sent), `SUPPRESSED` (status `-2` on an add) or `REFUSED` (a non-retryable 4xx).

## OpenPush.Verify

Phone codes. Each call is one request on the SDK transport — same base URL, `X-OP-SDK-Key` and SDK headers as registration — with no queue and no retry.

- `suspend start(phone, channel = OpenPushVerifyChannel.AUTO, locale: String? = null): OpenPushVerification` — `POST /v1/apps/{app_id}/verify` with `{"to", "channel", "locale"?}`.
- `suspend check(verification, code): OpenPushVerification` — `POST …/verify/{id}/check` with `{"to", "code"}`. A wrong code returns the verification still `PENDING` with `attempts` counted up; it is not an error.
- `suspend resend(verification, channel = verification.channel): OpenPushVerification` — `POST …/verify/{id}/resend` with `{"to", "channel"}`; the server allows it 30 s after the last send.
- Each has a callback overload taking `(OpenPushVerifyResult) -> Unit` last, reporting `OpenPushVerifyResult.Success(verification)` or `Failure(error)` on the main thread.
- `smsRetrieverHashFor(context): String?` — the 11-character SMS Retriever app hash for this app's package and current signing certificate, or null when the certificate cannot be read. Needs no Google Play services library.

`OpenPushVerification` — `id`, `maskedNumber`, `channel`, `status` (`OpenPushVerificationStatus.PENDING`, `APPROVED`, `EXPIRED`, `MAX_ATTEMPTS`), `attempts`, `expiresAt: Date?`. The full number is held in memory for `check` and `resend` only: the class is neither `Parcelable` nor `Serializable` and is never persisted.

`OpenPushVerifyChannel` — `AUTO`, `SMS`, `WHATSAPP`, `CALL`.

`OpenPushVerifyError` (an `Exception`) — `code`, `message`, and `status` (the HTTP status, null when nothing came back). A `{"detail": {"code", "message"}}` refusal maps its `code`: `rate_limited` → `RATE_LIMITED`, `too_soon` → `TOO_SOON`, `not_pending` → `NOT_PENDING`, `provider` → `PROVIDER`, `opted_out` → `OPTED_OUT` (409: the number replied STOP or opted out; never retry), `credit` → `NO_CREDIT` (402: the workspace has no text credit left), `no_rate` → `NO_RATE` (409: texts to this number's country are not priced or not allowed), `refused` → `REFUSED`, and any code this SDK does not know → `REFUSED`. Without a code the status decides: 429 → `RATE_LIMITED`, 5xx or a request that never reached the server → `NETWORK`, any other 4xx → `REFUSED`, with a plain-string `detail` as the message. A number that does not normalise fails with `INVALID_NUMBER` before anything is sent; a 2xx body that is not a verification is `PROVIDER`; a call before `initialize` is `REFUSED`.

## OpenPush.PushSubscription

- `optIn()` — clears the opt-out and requests notification permission from the
  resumed Activity when needed.
- `optIn(activity)` — the explicit Activity form for hosts that do not attach
  with an Application context.
- `optOut()` — records status `-2` and re-registers. The server stops sending to this device without deleting the subscription or the provider token.
- `optedIn` — the same value as `User.pushSubscription.optedIn`.
- `addObserver(observer)` / `removeObserver(handle)` — change-only subscription
  observers, called with the new `PushSubscriptionState`.
- `addChangeObserver(observer)` / `removeChangeObserver(handle)` — the same
  change-only stream, called with a `PushSubscriptionChange` that carries the
  **previous** state alongside the current one. Both spellings are also on
  `User.pushSubscription` and share one dispatch, so an observer of either kind
  sees exactly the same changes.

`data class PushSubscriptionChange(previous: PushSubscriptionState?, current: PushSubscriptionState)`.
`previous` is null for the first change a process observes. Observers fire when
an accepted registration brings the subscription id in, so a host that wires one
at startup learns the id as soon as it exists rather than having to poll.

## Subscription status (`notification_types`)

Every registration carries `status`. Android reports `1` when notification
permission is granted and `0` for the whole not-granted space; any positive
value is Subscribed. `-2` is the retained user opt-out. It takes precedence
over device permission and survives a cold start, while the true device value
is retained underneath so `optIn()` restores it rather than guessing `1`.

The `status` on an accepted response is **not** an echo of the value just sent:
the server can move it on its own (a dashboard unsubscribe, a bounce). The SDK
adopts it on the opt-out axis only — `-2` sets the local opt-out, any positive
value clears one — because `0` belongs to the device-permission axis, which the
server does not own. Any other negative is logged through `onLog` with its
name and never adopted. `not_collected` fields named on the response are logged
once per accepted registration.

`optIn()` now prompts, closing the previous compatibility-facade behavior gap. It
uses the resumed Activity when available, accepts an explicit Activity, and
falls back to system notification Settings after the runtime prompt limit.
Without any Activity it sends the honest current status and logs how the host
can supply one.

## PushSubscriptionState

`data class PushSubscriptionState(id, token, optedIn)`:

- `id` — the subscription identifier from the last accepted registration. It is
  persisted, so it is already known on the next launch rather than null until the
  first registration of that process comes back.
- `token` — the current provider token.
- `optedIn` — true only when `Notifications.permission` is true, a provider token is held, **and** the host has not called `optOut()`.

Reading `pushSubscription` does no OS I/O: `optedIn` reads the same permission **cache** `Notifications.permission` reads, refreshed only at `initialize`, an actual app foreground transition, `requestPermission`, and `onPermissionResult`. So a permission change made in system settings is reflected the next time one of those runs, not on the next read. Receiving a push does not refresh permission or rewrite the subscription status. This is the same rule in all three SDKs.

`clearToken()` deliberately leaves `id` in place: the server-side subscription still exists after a local token wipe.

## OpenPush.Notifications

- `PERMISSION_REQUEST_CODE` — the request code for `onPermissionResult`, and for the legacy in-Activity prompt used only when the merged manifest lacks `OpenPushPermissionActivity`.
- `permission` — the last known notification permission. It is a cache, re-read at `initialize`, an actual app foreground transition, `requestPermission`, and `onPermissionResult`.
- `canRequestPermission` — whether Android can still show the notification permission prompt. It is false when permission is already granted or below API 33. Before the first request it is true without an Activity; after a denial it uses the resumed Activity's rationale result, and conservatively returns false (with one log) when no resumed Activity is available.
- `requestPermission(activity, fallbackToSettings = false, callback = null)` — asks the user through OpenPush's transparent permission Activity (Activity Result API), so the host forwards nothing. On API 32 and below, and when the permission can no longer be requested, it reports `false` and — with `fallbackToSettings` — opens the system notification settings.
- `requestPermission(fallbackToSettings = false, callback = null)` — uses the resumed Activity. With none (a call from `onCreate`, a service or the Application) the request is deferred and the prompt starts when the next Activity resumes; every call made meanwhile shares that prompt and its answer, and `fallbackToSettings` applies if any of them set it. It stays pending until an Activity resumes. An already granted permission, and API 32 and below, answer at once.
- `onPermissionResult(requestCode, grantResults)` — hands OpenPush a `POST_NOTIFICATIONS` answer from the host's own prompt; other request codes are ignored. The permission Activity's result never reaches the host's `onRequestPermissionsResult`, so legacy forwarding never double-fires; a forward that arrives while a request is in flight completes it.
- `addPermissionObserver(observer)` / `removePermissionObserver(handle)` —
  change-only permission observers.
- `addClickListener(listener)` / `removeClickListener(handle)` — notification opens. A click that
  starts a cold process before any listener exists is held in memory and delivered synchronously to
  the first listener added. The `clicked` receipt is posted immediately; up to ten events are held,
  with the oldest dropped after that.
- `addForegroundLifecycleListener(listener)` / `removeForegroundLifecycleListener(handle)` — pushes that arrive while foregrounded.
- `addBackgroundMessageListener(listener)` / `removeBackgroundMessageListener(handle)` — background
  (data-only) pushes: `op_background` = `"1"` with no title or body. They are never displayed. The
  listener receives a `BackgroundMessageEvent(messageId, data, notification)` on the thread that
  delivered the push, foregrounded or not, so keep it short and hand longer work to WorkManager. The
  `received` receipt is posted as for any push; with no listener registered the push is logged and
  dropped. Background pushes never reach the foreground listeners.
- `beginReceived(data): Boolean` / `finishReceived(data, handled = true)` — handle each OpenPush
  message once per device. A host-owned `FirebaseMessagingService` calls `beginReceived` first, before
  `processForegroundMessage`, and drops the delivery when it returns `false`: another copy of that
  `op_message_id` is still being handled, or one was handled here within the last seven days. The
  server re-sends a push whose delivery it could not confirm, so a second copy can arrive. After a
  `true` result, call `finishReceived` in a `finally` block once the push has been handled, shown or
  not; that records the id and releases the in-progress guard. Pass `handled = false` when handling
  failed before the push could be shown, so a later copy can still show it. `beginReceived` returns
  `true` for a push without `op_message_id`, for a Live Update (`live_notification`), and when the
  record cannot be read or saved; `finishReceived` ignores the first two. Ids are kept in app-private
  preferences for seven days from when they were recorded, up to the 1,000 most recent. The optional
  FCM integration calls both for you.
- `processForegroundMessage(data)` / `processForegroundMessage(notification)` — posts the `received`
  receipt on every delivery, and returns `true` when the app was foregrounded and a listener
  called `preventDefault()`, or when the push is a background push (`isBackground`), which it hands to
  the background-message listeners instead; either way the host must not display it. The parsed-model overload lets a host renderer reuse its existing parse.
  Forward **every** delivery here. The receipt is posted for all of them, but the foreground listeners run — and `preventDefault()`
  can return `true` — only while the app actually has a visible Activity. `onMessageReceived` also fires with the app backgrounded
  or killed, and a listener must never be able to suppress a notification the user never saw. With no `Application` context the SDK
  has no lifecycle to observe, so it trusts the host and keeps dispatching (see `onAppForegrounded`).
- `processDisplayed(data)` / `processDisplayed(notification)` — posts the
  `confirmed` receipt for a host-owned renderer. Call it immediately after
  `NotificationManager.notify` succeeds. The optional FCM renderer does this
  automatically; suppressed and failed displays must not call it.
- `processClick(data)` / `processClick(notification)` — posts the `clicked` receipt and dispatches to
  the click listeners. The parsed-model overload preserves the provider collapse key supplied by the host.
- `clearAll()` — after initialization, cancels every notification posted by the app. With the optional FCM renderer loaded,
  it also clears the renderer's posted-id registry; without that module it falls back to
  `NotificationManager.cancelAll()`.
- `remove(notificationId)` — cancels one notification previously posted by the optional FCM renderer.
  Use the id returned by `render(notification)`, or derive it with `notificationIdFor(messageId)` —
  or `replaceNotificationIdFor(collapseKey)` for a push sent with `op_android_replace`.
- `removeGroup(group)` — cancels every tracked child whose `op_android_group` exactly matches `group`,
  along with the renderer's generated summary. Targeted removal requires the optional FCM renderer,
  because core does not track notification ids by itself. All three operations require `initialize`
  so the SDK has an application context.

`ListenerHandle` is the opaque token the `add*` calls return; keep it to remove the listener. A listener that throws is logged and does not stop the others.

`OpenPushNotification.fromData(data, collapseKey)` is the shared parsed model used by notification
events and the FCM renderer. It exposes `messageId`, `title`, `body`, `imageUrl`, `launchUrl`,
`channelId`, `accentColor`, `largeIconUrl`, `bigPictureUrl`, `group`, `category`, `visibility`,
`sound`, `smallIcon`, `ledColor`, `actions`, `collapseKey`, `additionalData`, and `rawData`.
`isOpenPush` is true only when `op_message_id` is present. `isBackground` is true when
`op_background` (`OpenPushNotification.KEY_BACKGROUND`) is `"1"` and there is no title or body.
`OpenPushAction(id, label, icon, systemIcon, url)` is one button; `url` is null when the button has
none. `additionalData` contains app-defined
keys after rendered keys and every `op_*` control key have been removed; `rawData` is untouched.
Malformed colours and visibility values become null rather than throwing.

`ClickEvent(messageId, data, notification, actionId)` carries that model and the action-button id,
or a null `actionId` for a body tap. `actionUrl` is the tapped button's `url`, or null for a body tap
or a button without one. `launchUrlOpened` is true when the SDK already opened the push's launch
URL, or the tapped button's `url`, so a listener should not open it again. A host-owned renderer should put
`OpenPush.ACTION_ID_KEY` (`op_action_id`) on action intents. `ForegroundEvent(messageId, data,
notification)` carries the same model. `ForegroundEvent.preventDefault()` sets `defaultPrevented`,
which is what `processForegroundMessage` returns.

The library declares `android.permission.POST_NOTIFICATIONS` in its own manifest, because `requestPermissions` for an undeclared permission is denied immediately and silently.

`onAppForegrounded()` refreshes permission and re-registers only when
`notification_types` changed. `onAppBackgrounded()` flushes coalesced writes.
Application lifecycle callbacks drive both automatically when the supplied
context is an `Application`; hosts with a different context should call these
hooks themselves.

## Optional FCM integration

`ai.openpush:openpush-android-fcm:1.2.2` supplies `OpenPushMessagingService`,
`OpenPushNotificationRenderer`, `OpenPushClickActivity`, `OpenPushFirebaseTokenProvider`, and
`OpenPushFcmInitializer`. The initializer joins the merged AndroidX Startup provider and installs
`OpenPushFirebaseTokenProvider` as `OpenPush.tokenProvider` unless the host already set one, so every
`initialize` fetches the FCM token and registers it. The merged service registers rotated
FCM tokens, posts `received`, renders the composer fields below, and posts `confirmed` only after
Android accepts the notification. A `live_notification` data object is consumed by the Android Live
Update renderer before ordinary notification display; malformed Live Updates are dropped rather
than displayed as an unrelated alert.

Each OpenPush message id is handled once per device. `OpenPushMessagingService` and
`OpenPushMessagingService.handle(context, message)` call `Notifications.beginReceived` before
foreground listeners, receipts or rendering, and drop a copy of an id that is still being handled or
was already handled (logged as `duplicate push <id> ignored`) without a listener call, a receipt or a
notification. The id is recorded once handling finishes — shown, prevented by a listener, or not
displayable — so a delivery that fails midway leaves it free for a later copy. Live Updates are not
recorded; their sequence check already drops a repeated frame. If the record cannot be read or saved,
the push is handled as usual.

The renderer requires `op_message_id` and at least one of `title` or `body`. A message without the
id is foreign to OpenPush and is neither rendered nor reported. Rendering follows these rules:

| Data field | Parsed/rendered value and precedence |
| --- | --- |
| `op_message_id` | Required OpenPush message id; also determines the stable Android notification id. Without it, the message is foreign and is not rendered or reported. |
| `op_app` | OpenPush routing control retained in `rawData`; it has no visual effect and is excluded from `additionalData`. |
| `title` | Title; null or empty falls back to the application label when `body` is present. |
| `body` | Content text and expanded `BigTextStyle`; also the summary under a big picture. |
| `image_url` | `imageUrl`, and the big-picture URL when `op_android_big_picture` is absent. |
| `deep_link` | `launchUrl`. A tap opens it with `ACTION_VIEW` instead of starting the app, and the click carries `launchUrlOpened = true`. With `ai.openpush.suppress_launch_urls` set to `true`, or when nothing on the device handles it, the app starts instead and the URL rides the click event and launch Activity extras. |
| `op_actions` | Up to three `{id,label,icon,system_icon,url}` buttons in order. A system icon resolves in Android's drawable package; another icon resolves in the host package; a tap carries `op_action_id`. A button with `url` opens it instead of `deep_link` on tap (same opt-out and fallback as `deep_link`) and the click reports it as `actionUrl`; a button without `url` behaves like the body. |
| `op_android_channel` | Non-blank payload id, then `ai.openpush.notification_channel_id` manifest metadata, then `openpush_default`. A missing channel is created at default importance; a host-created channel is never recreated. |
| `op_android_accent` | Notification accent colour; `RRGGBB` becomes opaque and `AARRGGBB` is parsed as ARGB, with or without `#`. Android draws notification accents opaque, so the alpha has no visible effect. |
| `op_android_large_icon` | An HTTPS image, or the name of a host drawable or mipmap. When it is absent or cannot be shown, the `ai.openpush.notification_large_icon` manifest resource, then a bundled `ic_openpush_large_icon_default`, is used. Any large icon is scaled down to the platform's large-icon size. |
| `op_android_big_picture` | HTTPS expanded picture; takes precedence over `image_url`. Failed loading falls back to the text notification. |
| `op_android_group` | Passed to `setGroup`. At two active children, the renderer adds an `InboxStyle` summary with the five most recently posted child bodies and child-only alerts. |
| `op_android_category` | Passed through as the Android notification category. |
| `op_android_visibility` | `public`, `private`, `secret`, or the platform numeric value `1`, `0`, `-1`. |
| `op_android_sound` | Host `raw` resource name; an unknown name uses the device default. With no value, the channel decides. |
| `op_android_small_icon` | Host drawable or mipmap, then `ai.openpush.notification_icon` manifest resource, then a bundled `ic_stat_openpush_default`, then the app icon, then a platform fallback. |
| `op_android_replace` | `"1"` with a collapse key (the provider's collapse key, or `op_collapse_id` in the data) posts the notification with the collapse key as its tag and `replaceNotificationIdFor(collapseKey)` as its id, so a newer push with the same key replaces the shown one. Otherwise each message keeps its own id and pushes stack. |
| `op_background` | `"1"` with no title or body: a background push. It is not rendered; see `addBackgroundMessageListener`. |
| `op_badge_increment` | iOS only; ignored on Android, where launchers own app-icon badges. |
| `op_android_led` | LED colour with the same hex parsing as the accent, using a 500 ms on / 2 s off pattern. |

Remote images must use HTTPS and are capped at 5 MiB by both declared and actual size. A fetch or
decode failure leaves a usable text notification. Both image lookups in one render share a ten-second
deadline. `OpenPushImageLoader.load(url)` is open so a host renderer can reuse its own cache while
keeping `OpenPushNotificationRenderer`; `render(notification)` accepts an already parsed model and
returns the posted notification ID. On Android 8.0 and later, an existing channel's
sound wins over `op_android_sound`; create and version channels in the host when sound is important.

`OpenPushMessagingService.onForeignMessage(message)` is the protected coexistence hook for a
subclassed service; its default implementation logs once. A host that already owns a
`FirebaseMessagingService` sets the `ai.openpush.fcm.delivery` manifest value to `forward` (or
removes the merged service) and calls `OpenPushMessagingService.handle(context, message)` for
OpenPush messages. In `forward` the initializer disables the merged service at startup; back in
`automatic`, the default, it re-enables only a service it disabled itself. The host still forwards
new tokens to `OpenPush.register` and keeps its own branch for messages without `op_message_id`.

Token fetching reads the default Firebase app. When `google-services.json` did not create one, the
initializer creates it from the `ai.openpush.fcm.project_id`, `ai.openpush.fcm.application_id` and
`ai.openpush.fcm.api_key` manifest values; the sender id is derived from the application id. With
neither, the token is unavailable and the SDK asks again on the next foreground. Set
`ai.openpush.fcm.automatic_token` to `false` to keep the module but forward tokens yourself.

- `OpenPushNotificationRenderer.replaceNotificationIdFor(collapseKey: String): Int` — the id a push sent with `op_android_replace` (`OpenPushNotificationRenderer.KEY_REPLACE`) posts under, with the collapse key as its tag. Cancel it with `NotificationManagerCompat.from(context).cancel(collapseKey, OpenPushNotificationRenderer.replaceNotificationIdFor(collapseKey))`, or with `OpenPush.Notifications.remove(id)`.
- `OpenPushNotificationRenderer.notificationIdFor(messageId: String): Int` — the notification id the renderer posts a message under. It is derived from the message id alone and never changes, so a host can cancel or replace what the SDK posted: `NotificationManagerCompat.from(context).cancel(OpenPushNotificationRenderer.notificationIdFor(messageId))`. Stable for one message, not guaranteed unique across all of them.

The renderer stores up to 100 posted child ids in app-private preferences and prunes entries Android
no longer reports in the shade. Generated summaries do not enter that registry. `removeGroup(group)`
cancels its tracked children and deterministic summary; `clearAll()` cancels the entire app shade. If
users dismiss children directly, the renderer removes a now-unneeded summary the next time it renders.

## Android Live Updates

`ai.openpush:openpush-android-live-updates:1.2.2` is a Java 8, Firebase-free renderer for Android
Live Updates. `openpush-android-fcm` includes it transitively. A host that owns its own
`FirebaseMessagingService` adds the artifact directly and forwards the data map and provider
collapse key:

```kotlin
val result = OpenPushLiveUpdates.handle(
    this,
    remoteMessage.data,
    remoteMessage.collapseKey,
    R.drawable.ic_notification,
    hostCreatedContentIntent,
)
```

The result is `NOT_LIVE_UPDATE`, `DISPLAYED`, `ENDED`, `DISMISSED`, `STALE`, or `INVALID`; every result
except `NOT_LIVE_UPDATE` is handled and must not fall through to ordinary display. The only
built-in renderer key is `progress`. Events are `start`, `update`, and `end`; `dismiss` is accepted
as an alias for `end`. `instance_id` falls back to the provider
collapse key and then to `key`.

`STALE` means the frame repeated or preceded the sequence already applied, so it was dropped
without touching the posted notification. Its `isHandled()` value is `true`: the payload was
consumed and must not be rendered as an ordinary notification.

`sequence` is reserved for a future server field; today frames are applied in arrival order.
`start` clears an earlier dismissal, `update` requires active state, and `end` idempotently cancels
the stable notification ID and deletes lifecycle state. The SDK stores only the instance ID,
template key, dismissal flag, last sequence, notification ID, reservation time, and timeout in
app-private preferences. An unused notification-ID reservation expires after ten minutes.

The Unpin action title comes from the overridable, translatable
`openpush_live_update_unpin` string resource. A host can provide the same resource name in its app
to localize or replace the label.

`OpenPushLiveUpdates.capability(context)` returns `FALLBACK_ONGOING`, `ELIGIBLE`, or
`PROMOTION_DISABLED`. `hasPromotableCharacteristics(notification)` exposes Android 16's structural
eligibility result, while `openPromotionSettings(context)` opens the app-specific system screen
only when it resolves. Android 16 uses `Notification.ProgressStyle` and requests promoted ongoing
treatment; older releases render the same lifecycle as one quiet ongoing notification.

## OpenPushConfiguration

Stores `appID`, public `sdkKey`, `serverURL` (defaulted to `DEFAULT_SERVER_URL`), `platform`, and the provider-token preference key. Construct it as `OpenPushConfiguration(appID, sdkKey, serverURL, platform, tokenKey)`; trailing slashes are removed from an explicit server URL. It compares **by value** over those five fields, which is what decides whether a second `initialize` is the same configuration again or a workspace switch — so `"https://app.openpush.ai/"` and the default equal each other.

## OpenPushPayload

- `messageId(data)` — extracts a non-empty OpenPush message ID.
- `actions(data)` — returns up to three `OpenPushAction` values from `op_actions`.
- `receiptBody(types, messageId, token, appId, extra = emptyMap())` — constructs the `/v1/ingest`
  event body. Every `extra` entry is merged into each event after the required receipt fields; the
  in-app module uses this for `click_id`, `block_key`, `first_click`, and `page_id`.
- `registrationAck(body)` — parses a registration response into `RegistrationAck(subscriptionId, identityRejection, refusedAliases, status, notCollected)`. `status` is the server's own subscription status or null when the response omits it; `notCollected` names the fields the server did not record. Both are defaulted, so existing construction sites are unaffected. Unknown fields are ignored, a malformed body yields an empty ack, and only a truthy `identity_rejected` counts as a rejection.
- `statusName(code)` — a readable name for a subscription status (`unsubscribed`, `no permission`, `subscribed`, `invalid token`, or `status <code>` for one it does not know).
- `appSessionBody(token)` / `appSessionDurationBody(token, seconds, key)` — construct the session
  observation and idempotent duration-report bodies used by the core pipeline.

## DeviceCredentialStore

AES-GCM credential storage backed by Android Keystore:

- `save(value)` — returns `false` if secure encryption is unavailable.
- `load()` — decrypts the current value or returns `null`.
- `wipe()` — removes encrypted data and its initialization vector.

## Not included

Inbox is not in this release because its server capability does not exist yet.
Custom Events are available through `trackEvent`.

iOS Live Activities remain outside this Android SDK. Their Android analogue is implemented here as
Android Live Updates: app-rendered ongoing notifications delivered over ordinary FCM, not an
ActivityKit API.
