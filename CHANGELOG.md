# Changelog

All notable changes to `openpush-android` are documented here. The project uses [Semantic Versioning](https://semver.org/).

## 1.2.2

First public release.

OpenPush for Android is a receive-only SDK: it registers a device with OpenPush, reports what
happens to each notification, and never sends messages itself.

- Device registration with a provider token supplied by the app or fetched by the optional
  Firebase Cloud Messaging module, with users, tags, aliases and subscription state kept in sync.
- Durable delivery and click receipts, custom events and session tracking that survive
  offline periods and process restarts.
- An optional Firebase module that renders notifications (images, action buttons, grouping,
  replacing a shown notification, launch URLs) and handles taps, plus background data-only pushes.
- Optional in-app messages and Android Live Updates.
- Email addresses, SMS and WhatsApp numbers, and phone verification codes on the user.
- A notification permission prompt that needs no Activity result forwarding.
- Callbacks delivered on the main thread, in order; the foreground listener runs on the push
  thread so it can decide the display ([Threading](API.md#threading)).
- The `ai.openpush.setup` Gradle plugin, which finds the push setup an app already has and wires
  OpenPush in around it.
- An optional compatibility layer for apps moving from another push SDK, with a migration
  guide and a migrated example app.

### Reliability

- An in-app message fetch whose 2xx answer is not a message list (an empty body, a proxy's HTML page)
  is a failed fetch: logged and retried once. A failure inside the SDK's background work is logged
  through `onLog` instead of ending the app's process.
- Receipts the server refuses with a 4xx other than 429 are dropped instead of blocking the queue; a
  receipt is also given up after 7 days owed or 10 server failures (at most one counted per hour).
  Network failures never count, and receipts already queued on a device drain as before.
- With the Firebase module, `Notifications.remove` and `removeGroup` work from process start, before
  anything has been rendered in that process. A manifest-named `OpenPushBackgroundMessageHandler`
  is kept through R8 by the SDK's consumer rules.

### Identity and internal API

- `register(token, externalId)` is deprecated. An identity is attached only through `login`: a new
  id passed here is logged in first, without an auth hash, instead of riding one registration with
  the current login's hash. The current id, `null` and `""` register exactly as `register(token)`.
- `@OpenPushInternalApi` is now an error-level opt-in, and `OpenPushBridge.requestHeaders()` and
  `request(…)` are Kotlin-only. These seams were never public API.

### Overloads

- `initialize(context, appId, "…")` binds a third positional string as the SDK key in Kotlin, as it
  always did in Java. The manifest-key form takes its extras by name (`serverURL = …`); a positional
  `http(s)://` value is logged and still read as the server URL with the manifest key.
- `Notifications.requestPermission` keeps its 1.2.1 shapes. In a coroutine,
  `val granted = requestPermission()` (or `requestPermission(fallbackToSettings)`) suspends until the
  user answers. Outside a coroutine, such as in `onCreate`, pass a callback:
  `requestPermission { granted -> … }`. Java calls `requestPermission(false, granted -> Unit.INSTANCE)`.

### Fixed

- In-app messages: the dim behind a center, full-screen or carousel message now covers the camera
  cutout and the status and navigation bars, while the message itself stays clear of them.
  Banners are unchanged.
