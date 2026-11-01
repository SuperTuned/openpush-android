# Existing FCM service example

A game that already has its own `FirebaseMessagingService` and adds OpenPush without giving it up.
It uses the `ai.openpush.setup` Gradle plugin in **Forward** mode.

## What this proves

- **The game's service keeps every push.** `GameMessagingService` stays the only enabled receiver
  of `com.google.firebase.MESSAGING_EVENT`. OpenPush's merged `OpenPushMessagingService` is still
  in the merged manifest, but the plugin sets `ai.openpush.fcm.delivery` to `forward`, so the SDK
  disables it at startup and delivery is never split between two services.
- **OpenPush pushes are routed by one branch.** A push carrying `op_message_id` goes to
  `OpenPushMessagingService.handle`; anything else falls through to the game's own code.
- **The manifest has no OpenPush entries.** `openpush-setup.json` holds the choices
  (`android_delivery` forward, `icons` reuse); `openpush.properties` holds the keys. The plugin
  merges both into every variant through a generated manifest.
- **The build guards the setup.** Delete the `android_delivery` choice and the build fails, naming
  both services and the two ways to fix it.

## Run it

```bash
cp app/openpush.properties.example app/openpush.properties   # paste the console's values
./gradlew :app:openpushDoctor   # lists what the app already has
./gradlew :app:assembleDebug
```

Add your Firebase project's `google-services.json` to `app/`; the Google Services plugin is applied
only when the file is present, so the example builds without it and simply receives nothing.

## Try it on a device

1. `app/openpush.properties` holds `ai.openpush.app_id=<your app id>` and
   `ai.openpush.sdk_key=<your public SDK key>`. `app/google-services.json` is your Firebase
   project's file with an Android app for `ai.openpush.example.existing`. Neither is committed.
2. `./gradlew :app:installDebug`, then open **Existing FCM service** and allow notifications.
3. The screen should show:
   - **Delivery mode: forward** and **OpenPushMessagingService: disabled**. Anything else means
     Forward is not applied and pushes can be split between two services.
   - a **Subscription id**, the **FCM token**, and **Notification permission: allowed**.
   - after an OpenPush send to this device: **Last push** with its title and `op_message_id`, and
     **received by: GameMessagingService (host)**. A Firebase console message without
     `op_message_id` shows up the same way, with `op_message_id: —`, and is not forwarded.
     **received by: OpenPushMessagingService (SDK)** means the SDK's service took a push directly,
     which Forward mode must never allow. With the app closed, that case shows up only once the
     notification is tapped.

To set up a game of your own the same way, apply the plugin and run
`./gradlew :app:openpushSetup`. It asks once per choice on a terminal, takes the Reuse defaults
with `-Popenpush.yes=true`, and prints the forwarding lines for your service to
`app/build/openpush/forward.patch` (`-Popenpush.applyPatch=true` inserts them).
