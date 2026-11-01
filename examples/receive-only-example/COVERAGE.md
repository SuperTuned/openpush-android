# Sample capability coverage

This matrix maps the complete public surface documented in [`API.md`](../../API.md) to source that is built in one of the three samples. It also calls out the additive Java, coroutine, diagnostics, parsed-notification, FCM, in-app-message, Live Update, and compatibility-facade surfaces.

`yes` means the member is either exercised by the running sample or appears in an intentionally compile-checked recipe. Recipe functions group calls that would be contradictory or destructive if an app ran all of them at startup; every sample build still type-checks those calls, so an incompatible API change breaks the sample. The four `n/a` rows are the advanced seams explicitly excluded from sample execution: `attach`, raw transport values/injection, and the two wire helpers `receiptBody` and `registrationAck`.

The evidence links are relative to this file and use one-based line anchors. After editing a sample, verify every link, status, and row count from the repository root with:

```bash
ruby -ruri -e 'doc="examples/receive-only-example/COVERAGE.md"; base=File.dirname(doc); rows=File.readlines(doc).grep(/^\| [A-Z][0-9][0-9] /); bad=[]; rows.each { |row| links=row.scan(/\]\(([^)#]+)#L([0-9]+)\)/); status=row.split("|")[3].to_s.strip; bad << "missing evidence: #{row}" if status == "yes" && links.empty?; links.each { |path,line| decoded=URI::DEFAULT_PARSER.unescape(path); file=File.expand_path(decoded,base); bad << "missing #{path}" unless File.file?(file); bad << "bad anchor #{path}#L#{line}" if File.file?(file) && line.to_i > File.foreach(file).count } }; abort bad.join("\n") unless bad.empty?; counts=rows.each_with_object(Hash.new(0)) { |r,h| h[r.split("|")[3].strip] += 1 }; puts "#{rows.length} rows: #{counts}"'
```

## Core initialization, lifecycle, delivery, and diagnostics

| ID | Public member or capability | Status | Sample evidence |
| --- | --- | --- | --- |
| C01 | `OpenPush.DEFAULT_SERVER_URL` | yes | [ExampleConfiguration.kt:103](app/src/main/java/ai/openpush/example/ExampleConfiguration.kt#L97) |
| C02 | `OpenPush.META_APP_ID`, `META_SDK_KEY` | yes | [JavaHostIntegration.java:65](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L65) |
| C03 | `OpenPush.ACTION_ID_KEY` | yes | [ExampleNotificationFixtures.kt:198](app/src/main/java/ai/openpush/example/ExampleNotificationFixtures.kt#L192) |
| C04 | `OpenPush.X_OP_SDK`, `X_OP_SDK_WRAPPER` | yes | [FeatureLabScreens.kt:244](app/src/main/java/ai/openpush/example/FeatureLabScreens.kt#L252), [FeatureLabScreens.kt:247](app/src/main/java/ai/openpush/example/FeatureLabScreens.kt#L255) |
| C05 | `OpenPush.Request`, `Response`, and raw `Transport` injection | n/a — advanced seam | Deliberately documented rather than executed; samples use the production transport. |
| C06 | `OpenPush.sdkWrapper` getter and Java setter | yes | [ExampleLabModel.kt:131](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L130), [JavaHostIntegration.java:25](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L25) |
| C07 | `OpenPush.onLog` | yes | [ExampleApplication.kt:24](app/src/main/java/ai/openpush/example/ExampleApplication.kt#L24) |
| C08 | `OpenPush.onRegistered` | yes | [ExampleApplication.kt:28](app/src/main/java/ai/openpush/example/ExampleApplication.kt#L28) |
| C09 | `OpenPush.onRegistrationFailed` | yes | [ExampleApplication.kt:36](app/src/main/java/ai/openpush/example/ExampleApplication.kt#L36) |
| C10 | `OpenPush.onIdentityVerificationRequired` | yes | [ExampleLabModel.kt:839](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L846) |
| C11 | `OpenPush.onIdentityRejected` | yes | [ExampleLabModel.kt:849](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L856) |
| C12 | `OpenPush.currentToken` | yes | [ExampleLabModel.kt:971](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L978) |
| C13 | `OpenPush.isInitialized` | yes | [ExampleApplication.kt:70](app/src/main/java/ai/openpush/example/ExampleApplication.kt#L70) |
| C14 | `OpenPush.sdkVersion` / Java `getSdkVersion()` | yes | [FeatureLabScreens.kt:89](app/src/main/java/ai/openpush/example/FeatureLabScreens.kt#L86), [JavaHostIntegration.java:20](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L20) |
| C15 | `OpenPush.initialize(context)` manifest form | yes | [JavaHostIntegration.java:35](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L35) |
| C16 | `OpenPush.initialize(context, appId[, serverURL])` manifest-key forms | yes | [JavaHostIntegration.java:36](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L36), [FcmApiCoverage.kt:25](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L25) |
| C17 | `OpenPush.initialize(context, appId, sdkKey, serverURL, credential, transport)` defaulted explicit-key family | yes | [ExampleApplication.kt:77](app/src/main/java/ai/openpush/example/ExampleApplication.kt#L77), [JavaHostIntegration.java:16](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L16) |
| C18 | `OpenPush.initWithContext(context, appId)` | yes | [JavaHostIntegration.java:37](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L37) |
| C19 | `OpenPush.observeLifecycle(application)` | yes | [JavaHostIntegration.java:38](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L38) |
| C20 | `OpenPush.attach(context, configuration, credential, transport)` | n/a — advanced seam | Lower-level attachment is documented; all samples use `initialize`. |
| C21 | `OpenPush.register(token)`, and the deprecated `register(token, externalId)` from Java | yes | [ExampleApplication.kt:100](app/src/main/java/ai/openpush/example/ExampleApplication.kt#L100), [JavaHostIntegration.java:48](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L48) |
| C22 | `OpenPush.trackEvent(name, properties, idempotencyKey)` and Java overloads | yes | [ExampleLabModel.kt:479](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L483), [JavaHostIntegration.java:49](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L49) |
| C23 | `OpenPush.receipt(type, messageId, then)` and Java overloads | yes | [ExampleMessagingService.kt:59](app/src/main/java/ai/openpush/example/ExampleMessagingService.kt#L59), [JavaHostIntegration.java:53](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L53) |
| C24 | `OpenPush.messageId(data)` | yes | [ExampleMessagingService.kt:32](app/src/main/java/ai/openpush/example/ExampleMessagingService.kt#L32) |
| C25 | `OpenPush.actions(data)` | yes | [ExampleMessagingService.kt:97](app/src/main/java/ai/openpush/example/ExampleMessagingService.kt#L97) |
| C26 | `OpenPush.clearToken()` | yes | [ExampleLabModel.kt:529](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L533) |
| C27 | `OpenPush.shortToken(token)` | yes | [ExampleLabModel.kt:119](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L118) |
| C28 | `OpenPush.deviceModel()` | yes | [ExampleLabModel.kt:120](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L119) |
| C29 | `OpenPush.onAppForegrounded()` | yes | [ExampleLabModel.kt:196](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L198) |
| C30 | `OpenPush.onAppBackgrounded()` | yes | [ExampleLabModel.kt:209](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L211) |
| C31 | Java static object surface, including nested `User`, with no `INSTANCE` | yes | [JavaHostIntegration.java:16](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L16), [JavaHostIntegration.java:30](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L30) |
| C32 | `OpenPush.awaitRegistration()` | yes | [ExampleLabModel.kt:501](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L505) |
| C33 | `OpenPush.loginSuspend(externalId, authHash)` | yes | [ExampleLabModel.kt:333](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L336) |
| C34 | `OpenPush.logoutSuspend()` | yes | [ExampleLabModel.kt:358](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L361) |
| C35 | suspend `OpenPush.Notifications.requestPermission(fallbackToSettings)` | yes | [ExampleLabModel.kt:287](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L287) |
| C36 | opt-in `OpenPushBridge.diagnostics()` snapshot | yes | [ExampleLabModel.kt:980](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L987) |

## Identity, user data, and subscription state

| ID | Public member or capability | Status | Sample evidence |
| --- | --- | --- | --- |
| I01 | callback `OpenPush.login(externalId, authHash, callback)` and Java overloads | yes | [JavaHostIntegration.java:55](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L55) |
| I02 | callback `OpenPush.logout(callback)` and Java overloads | yes | [JavaHostIntegration.java:57](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L57) |
| I03 | `IdentityResult.Success` | yes | [ExampleLabModel.kt:336](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L339) |
| I04 | `IdentityResult.IdentityRejected.note` | yes | [ExampleLabModel.kt:340](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L343) |
| I05 | `IdentityResult.Failed.message` | yes | [ExampleLabModel.kt:344](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L347) |
| U01 | `OpenPush.User.addTag` | yes | [ExampleLabModel.kt:378](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L381) |
| U02 | `OpenPush.User.addTags` | yes | [ExampleLabModel.kt:408](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L411) |
| U03 | `OpenPush.User.removeTag` | yes | [ExampleLabModel.kt:385](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L388) |
| U04 | `OpenPush.User.removeTags` | yes | [ExampleLabModel.kt:420](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L423) |
| U05 | `OpenPush.User.getTags` | yes | [ExampleLabModel.kt:975](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L982) |
| U06 | `OpenPush.User.addAlias` | yes | [ExampleLabModel.kt:393](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L396) |
| U07 | `OpenPush.User.addAliases` | yes | [ExampleLabModel.kt:428](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L431) |
| U08 | `OpenPush.User.removeAlias` | yes | [ExampleLabModel.kt:400](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L403) |
| U09 | `OpenPush.User.removeAliases` | yes | [ExampleLabModel.kt:440](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L443) |
| U10 | `OpenPush.User.externalId` | yes | [ExampleLabModel.kt:973](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L980) |
| U11 | `OpenPush.User.pushSubscription` | yes | [ExampleLabModel.kt:969](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L976) |
| S01 | `LivePushSubscription.id` | yes | [FcmApiCoverage.kt:48](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L48) |
| S02 | `LivePushSubscription.token` | yes | [FcmApiCoverage.kt:49](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L49) |
| S03 | `LivePushSubscription.optedIn` | yes | [FcmApiCoverage.kt:50](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L50) |
| S04 | `LivePushSubscription.snapshot()` | yes | [FcmApiCoverage.kt:51](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L51) |
| S05 | no-Activity `LivePushSubscription.optIn()` and `PushSubscription.optIn()` | yes | [FcmApiCoverage.kt:52](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L52), [FcmApiCoverage.kt:60](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L60) |
| S06 | `OpenPush.PushSubscription.optIn(activity)` | yes | [ExampleLabModel.kt:297](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L300) |
| S07 | `LivePushSubscription.optOut()` and `PushSubscription.optOut()` | yes | [FcmApiCoverage.kt:53](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L53), [FcmApiCoverage.kt:62](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L62) |
| S08 | `OpenPush.PushSubscription.optedIn` alias | yes | [ExampleLabModel.kt:972](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L979) |
| S09 | both subscription `addObserver` / `removeObserver` spellings | yes | [FcmApiCoverage.kt:54](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L54), [FcmApiCoverage.kt:63](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L63) |
| S10 | both subscription `addChangeObserver` / `removeChangeObserver` spellings | yes | [FcmApiCoverage.kt:56](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L56), [FcmApiCoverage.kt:65](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L65) |
| S11 | `PushSubscriptionState.id`, `token`, `optedIn` | yes | [ExampleLabModel.kt:822](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L829) |
| S12 | `PushSubscriptionChange.previous`, `current` | yes | [ExampleLabModel.kt:834](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L841) |

## Notification permission, events, and parsed model

| ID | Public member or capability | Status | Sample evidence |
| --- | --- | --- | --- |
| N01 | `Notifications.PERMISSION_REQUEST_CODE` | yes | [FcmApiCoverage.kt:52](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L52) |
| N02 | `Notifications.permission` | yes | [ExampleLabModel.kt:1004](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L1004) |
| N03 | `Notifications.canRequestPermission` | yes | [ExampleLabModel.kt:1005](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L1005) |
| N04 | `requestPermission(activity, fallbackToSettings, callback)` | yes | [MainActivity.kt:31](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/MainActivity.kt#L31) |
| N05 | resumed-Activity callback `requestPermission(fallbackToSettings, callback)` and Java overloads | yes | [JavaHostIntegration.java:59](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/JavaHostIntegration.java#L59) |
| N06 | resumed-Activity suspend `requestPermission(fallbackToSettings)` | yes | [ExampleLabModel.kt:287](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L287) |
| N07 | `Notifications.onPermissionResult` | yes | [FcmApiCoverage.kt:53](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L53) |
| N08 | `addPermissionObserver` / `removePermissionObserver` | yes | [ExampleLabModel.kt:828](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L828), [ExampleLabModel.kt:192](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L192) |
| N09 | `addClickListener` / `removeClickListener` | yes | [ExampleLabModel.kt:903](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L910), [ExampleLabModel.kt:185](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L187) |
| N10 | `addForegroundLifecycleListener` / `removeForegroundLifecycleListener` | yes | [ExampleLabModel.kt:888](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L895), [ExampleLabModel.kt:184](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L186) |
| N11 | `processForegroundMessage(data)` | yes | [ExampleMessagingService.kt:60](app/src/main/java/ai/openpush/example/ExampleMessagingService.kt#L60) |
| N12 | shade-independent `processForegroundMessage(notification)` | yes | [ExampleLabModel.kt:583](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L584) |
| N13 | `processClick(data)` | yes | [ExampleLabModel.kt:796](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L803) |
| N14 | shade-independent `processClick(notification)` | yes | [ExampleLabModel.kt:588](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L589) |
| N15 | opaque `ListenerHandle` removal token | yes | [ExampleLabModel.kt:101](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L100) |
| N16 | `OpenPushNotification.fromData(data[, collapseKey])` and `KEY_LAUNCH_URL` | yes | [ExampleNotificationFixtures.kt:66](app/src/main/java/ai/openpush/example/ExampleNotificationFixtures.kt#L66), [FcmApiCoverage.kt:33](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L33), [FcmApiCoverage.kt:39](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L39) |
| N17 | `OpenPushNotification.messageId`, `title`, `body`, `imageUrl`, `launchUrl` | yes | [ExampleLabModel.kt:931](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L938) |
| N18 | `channelId`, `accentColor`, `largeIconUrl`, `bigPictureUrl`, `group`, `category`, `visibility`, `sound`, `smallIcon`, `ledColor` | yes | [ExampleLabModel.kt:944](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L951) |
| N19 | `actions`, `collapseKey`, `additionalData`, `rawData`, `isOpenPush`, plus `OpenPushAction.id`, `label`, `icon`, `systemIcon` | yes | [ExampleLabModel.kt:936](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L943), [ExampleLabModel.kt:954](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L961), [ExampleMessagingService.kt:97](app/src/main/java/ai/openpush/example/ExampleMessagingService.kt#L97) |
| N20 | `ClickEvent.messageId`, `data`, `notification`, `actionId` | yes | [ExampleLabModel.kt:903](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L910) |
| N21 | `ForegroundEvent.messageId`, `data`, `notification`, `defaultPrevented`, `preventDefault()` | yes | [ExampleLabModel.kt:888](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L895) |
| N22 | `Notifications.clearAll()` | yes | [ExampleLabModel.kt](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L573) |
| N23 | `Notifications.remove(notificationId)` | yes | [FcmApiCoverage.kt](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L36) |
| N24 | `Notifications.removeGroup(group)` | yes | [ExampleLabModel.kt](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L579) |
| N25 | `Notifications.beginReceived(data)` / `finishReceived(data, handled)` | yes | [ExampleMessagingService.kt:34](app/src/main/java/ai/openpush/example/ExampleMessagingService.kt#L34), [ExampleMessagingService.kt:41](app/src/main/java/ai/openpush/example/ExampleMessagingService.kt#L41) |

## In-app messages

| ID | Public member or capability | Status | Sample evidence |
| --- | --- | --- | --- |
| A01 | `OpenPush.InAppMessages` extension and direct `OpenPushInAppMessages` object | yes | [ExampleLabModel.kt:87](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L86), [ExampleLabModel.kt:596](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L597) |
| A02 | `OpenPushInAppMessages.paused` getter/setter | yes | [ExampleLabModel.kt:596](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L597) |
| A03 | `addTrigger(key, value)` | yes | [ExampleLabModel.kt:613](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L614) |
| A04 | `addTriggers(values)` | yes | [ExampleLabModel.kt:635](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L636) |
| A05 | `removeTrigger(key)` | yes | [ExampleLabModel.kt:645](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L646) |
| A06 | `removeTriggers(keys)` | yes | [ExampleLabModel.kt:656](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L657) |
| A07 | `clearTriggers()` | yes | [ExampleLabModel.kt:666](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L667) |
| A08 | `addLifecycleListener` / `removeLifecycleListener` | yes | [ExampleLabModel.kt:855](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L862), [ExampleLabModel.kt:180](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L182) |
| A09 | `InAppMessageLifecycleEvent.messageId`, `phase`, `stage` and every `InAppLifecycleStage` | yes | [ExampleLabModel.kt:857](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L864) |
| A10 | `addClickListener` / `removeClickListener` | yes | [ExampleLabModel.kt:871](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L878), [ExampleLabModel.kt:181](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L183) |
| A11 | `InAppMessageClickEvent.messageId`, `actionId`, `url`, `urlTarget`, `closingMessage`, `firstClick`, `pageId` | yes | [ExampleLabModel.kt:873](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L880) |
| A12 | `OpenPushInAppMessages.fetch()` | yes | [ExampleLabModel.kt:681](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L682) |
| A13 | opt-in `previewLocalDocument(activity, assetPath)` | yes | [ExampleLabModel.kt:695](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L696) |

## Optional FCM module

| ID | Public member or capability | Status | Sample evidence |
| --- | --- | --- | --- |
| F01 | `openpush-android-fcm` artifact and transitively exposed core/Live Updates APIs | yes | [app/build.gradle.kts:92](../fcm-module-example/app/build.gradle.kts#L92) |
| F02 | merged `OpenPushMessagingService` and `OpenPushClickActivity`, with host renderer metadata | yes | [AndroidManifest.xml:12](../fcm-module-example/app/src/main/AndroidManifest.xml#L12), [AndroidManifest.xml:20](../fcm-module-example/app/src/main/AndroidManifest.xml#L20) |
| F03 | `OpenPushMessagingService.handle(context, message)` coexistence entry point | yes | [FcmApiCoverage.kt:23](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L23) |
| F04 | subclass hooks `renderer()`, `handleLiveUpdate`, `onForeignMessage` | yes | [FcmApiCoverage.kt:69](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L69) |
| F05 | `OpenPushNotificationRenderer(context[, imageLoader])` and `render(data)` | yes | [FcmApiCoverage.kt:29](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L29) |
| F06 | shade-independent `OpenPushNotificationRenderer.render(notification)` | yes | [FcmApiCoverage.kt:32](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L32) |
| F07 | `OpenPushImageLoader()` and overridable `load(url)` | yes | [FcmApiCoverage.kt:29](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L29), [FcmApiCoverage.kt:34](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L34) |
| F08 | `notificationIdFor`, `META_CHANNEL_ID`, `META_SMALL_ICON`, `DEFAULT_CHANNEL_ID` | yes | [FcmApiCoverage.kt:35](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L35) |
| F09 | module click trampoline dispatch and typed click event consumption | yes | [ExampleApplication.kt:44](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/ExampleApplication.kt#L44) |
| F10 | `op_android_group` children and generated summary rendering | yes | [ExampleNotificationFixtures.kt](app/src/main/java/ai/openpush/example/ExampleNotificationFixtures.kt#L178) |

## Android Live Updates

| ID | Public member or capability | Status | Sample evidence |
| --- | --- | --- | --- |
| L01 | three-argument `OpenPushLiveUpdates.handle(context, data, collapseKey)` | yes | [FcmApiCoverage.kt:24](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L24) |
| L02 | full `handle(context, data, collapseKey, smallIcon, contentIntent)` | yes | [ExampleLabModel.kt:1061](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L1068) |
| L03 | `OpenPushLiveUpdateResult.NOT_LIVE_UPDATE`, `DISPLAYED`, `ENDED`, `DISMISSED`, `STALE`, `INVALID`, and `isHandled` | yes | [ExampleLabModel.kt:1072](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L1079), [ExampleMessagingService.kt:57](app/src/main/java/ai/openpush/example/ExampleMessagingService.kt#L57) |
| L04 | `OpenPushLiveUpdates.capability(context)` and `Capability.FALLBACK_ONGOING`, `ELIGIBLE`, `PROMOTION_DISABLED` | yes | [ExampleLabModel.kt:149](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L148) |
| L05 | `hasPromotableCharacteristics(notification)` | yes | [ExampleMessagingService.kt:121](app/src/main/java/ai/openpush/example/ExampleMessagingService.kt#L121) |
| L06 | `openPromotionSettings(context)` | yes | [ExampleLabModel.kt:785](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L792) |
| L07 | `OpenPushLiveUpdates.CHANNEL_ID`, `DEFAULT_TIMEOUT_MILLIS` | yes | [FcmApiCoverage.kt:42](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L42) |

## Configuration, payload helpers, and credentials

| ID | Public member or capability | Status | Sample evidence |
| --- | --- | --- | --- |
| P01 | `OpenPushConfiguration(appID, sdkKey, serverURL, platform, tokenKey)` and its five value properties | yes | [ExampleConfiguration.kt:109](app/src/main/java/ai/openpush/example/ExampleConfiguration.kt#L103), [ExampleApplication.kt:71](app/src/main/java/ai/openpush/example/ExampleApplication.kt#L71) |
| P02 | `OpenPushPayload.messageId(data)` | yes | [ExampleNotificationFixtures.kt:67](app/src/main/java/ai/openpush/example/ExampleNotificationFixtures.kt#L67) |
| P03 | `OpenPushPayload.actions(data)` | yes | [ExampleNotificationFixtures.kt:68](app/src/main/java/ai/openpush/example/ExampleNotificationFixtures.kt#L68) |
| P04 | `OpenPushPayload.statusName`, `appSessionBody`, `appSessionDurationBody` | yes | [ExampleLabModel.kt:122](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L121), [FcmApiCoverage.kt:40](../fcm-module-example/app/src/main/java/ai/openpush/example/fcm/FcmApiCoverage.kt#L40) |
| P05 | `OpenPushPayload.receiptBody(...)` overloads | n/a — advanced seam | Wire-body construction is intentionally owned by the SDK and optional modules. |
| P06 | `OpenPushPayload.registrationAck(body)` and `RegistrationAck` wire model | n/a — advanced seam | Registration response parsing is intentionally owned by the SDK. |
| D01 | `DeviceCredentialStore(context, service)` | yes | [ExampleApplication.kt:21](app/src/main/java/ai/openpush/example/ExampleApplication.kt#L21) |
| D02 | `DeviceCredentialStore.save(value)` | yes | [ExampleLabModel.kt:494](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L498) |
| D03 | `DeviceCredentialStore.load()` | yes | [ExampleApplication.kt:52](app/src/main/java/ai/openpush/example/ExampleApplication.kt#L52) |
| D04 | `DeviceCredentialStore.wipe()` | yes | [ExampleLabModel.kt:519](app/src/main/java/ai/openpush/example/ExampleLabModel.kt#L523) |

## Compatibility facade

These rows enumerate every facade forward. The migration sample depends only on the compatibility artifact, so every cited call proves the facade itself remains sufficient.

| ID | Public member or capability | Status | Sample evidence |
| --- | --- | --- | --- |
| K01 | facade `DEFAULT_SERVER_URL`, `initWithContext` | yes | [ExampleApplication.kt:25](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/ExampleApplication.kt#L26), [FacadeApiCoverage.kt:17](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L17) |
| K02 | both facade `initialize` families, `isInitialized`, `sdkVersion` | yes | [FacadeApiCoverage.kt:17](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L17) |
| K03 | facade `login` and `loginSuspend` | yes | [FacadeApiCoverage.kt:30](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L30) |
| K04 | facade `logout` and `logoutSuspend` | yes | [FacadeApiCoverage.kt:32](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L32) |
| K05 | facade `register` | yes | [FacadeApiCoverage.kt:34](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L34) |
| K06 | facade `User.addTag`, `User.addTags` | yes | [FacadeApiCoverage.kt:38](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L38) |
| K07 | facade `User.removeTag`, `User.removeTags` | yes | [FacadeApiCoverage.kt:40](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L40) |
| K08 | facade `User.getTags`, `User.trackEvent` | yes | [FacadeApiCoverage.kt:42](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L42) |
| K09 | facade `User.addAlias`, `User.addAliases` | yes | [FacadeApiCoverage.kt:44](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L44) |
| K10 | facade `User.removeAlias`, `User.removeAliases` | yes | [FacadeApiCoverage.kt:46](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L46) |
| K11 | facade `User.externalId`, `User.pushSubscription` | yes | [FacadeApiCoverage.kt:48](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L48), [FacadeApiCoverage.kt:52](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L52) |
| K12 | facade subscription `id`, `token`, `optedIn`, `snapshot()` | yes | [FacadeApiCoverage.kt:53](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L53) |
| K13 | facade subscription `optIn()`, `optIn(activity)`, `optOut()` | yes | [FacadeApiCoverage.kt:57](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L57) |
| K14 | facade subscription `addObserver` / `removeObserver` | yes | [FacadeApiCoverage.kt:60](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L60) |
| K15 | facade subscription `addChangeObserver` / `removeChangeObserver` | yes | [FacadeApiCoverage.kt:62](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L62) |
| K16 | facade `Notifications.PERMISSION_REQUEST_CODE`, `permission`, `canRequestPermission` | yes | [FacadeApiCoverage.kt:67](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L67) |
| K17 | both facade `Notifications.requestPermission` callback forms | yes | [FacadeApiCoverage.kt:70](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L70) |
| K18 | suspend facade `Notifications.requestPermission` and `onPermissionResult` | yes | [FacadeApiCoverage.kt:72](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L72) |
| K19 | facade `Notifications.addPermissionObserver` / `removePermissionObserver` | yes | [FacadeApiCoverage.kt:75](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L75) |
| K20 | facade `Notifications.addClickListener` / `removeClickListener` | yes | [FacadeApiCoverage.kt:77](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L77) |
| K21 | facade `Notifications.addForegroundLifecycleListener`, `removeForegroundLifecycleListener`, `processForegroundMessage` | yes | [FacadeApiCoverage.kt:79](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L79) |
| K22 | facade `Notifications.processClick` | yes | [FacadeApiCoverage.kt:82](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L82) |
| K23 | facade `InAppMessages.paused`, `addTrigger`, `addTriggers` | yes | [FacadeApiCoverage.kt:86](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L86) |
| K24 | facade `InAppMessages.removeTrigger`, `removeTriggers`, `clearTriggers` | yes | [FacadeApiCoverage.kt:90](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L90) |
| K25 | facade `InAppMessages` lifecycle and click add/remove listener pairs | yes | [FacadeApiCoverage.kt:93](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L93) |
| K26 | facade `Session.addOutcome`, `addUniqueOutcome`, `addOutcomeWithValue` | yes | [FacadeApiCoverage.kt:100](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L100) |
| K27 | compile-time refusal path remains visible to migrating hosts | yes | [MIGRATING-FROM-ONESIGNAL.md §3](../../MIGRATING-FROM-ONESIGNAL.md#3-refused-symbols) |
| K28 | facade `clearAllNotifications`, `removeNotification`, `removeGroupedNotifications` | yes | [FacadeApiCoverage.kt](../migrated-from-onesignal/app/src/main/java/ai/openpush/example/migrated/FacadeApiCoverage.kt#L83) |
