package ai.openpush.example

import ai.openpush.android.IdentityResult
import ai.openpush.android.ListenerHandle
import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushBridge
import ai.openpush.android.OpenPushInternalApi
import ai.openpush.android.OpenPushNotification
import ai.openpush.android.OpenPushPayload
import ai.openpush.android.PushSubscriptionState
import ai.openpush.android.inappmessages.InAppLifecycleStage
import ai.openpush.android.inappmessages.InAppMessages
import ai.openpush.android.inappmessages.OpenPushInAppMessages
import ai.openpush.android.liveupdates.OpenPushLiveUpdateResult
import ai.openpush.android.liveupdates.OpenPushLiveUpdates
import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

data class UiMessage(val id: Long, val text: String)

@OptIn(OpenPushInternalApi::class)
class ExampleLabModel(private val activity: ComponentActivity) {
    private val context = activity.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    var mode by mutableStateOf(
        if (ExampleConfiguration.hasLiveCredentials) RuntimeMode.LIVE else RuntimeMode.DEMO,
    )
        private set
    var appearance by mutableStateOf(ExamplePreferences.appearance(context))
        private set
    var selectedDestination by mutableStateOf(FeatureDestination.STATUS)
    var settingsOpen by mutableStateOf(false)

    var permissionGranted by mutableStateOf(false)
        private set
    var canRequestPermission by mutableStateOf(false)
        private set
    var subscriptionId by mutableStateOf<String?>(null)
        private set
    var providerToken by mutableStateOf<String?>(null)
        private set
    var pushEnabled by mutableStateOf(false)
        private set
    var externalId by mutableStateOf<String?>(null)
        private set
    val tags = mutableStateMapOf<String, String>()
    val aliases = mutableStateMapOf<String, String>()
    val events = mutableStateListOf<LabEvent>()

    var consentRequired by mutableStateOf(ExampleConsentGate.isRequired(context))
        private set
    var consentGranted by mutableStateOf(ExampleConsentGate.isGranted(context))
        private set
    var preventForegroundPresentation by mutableStateOf(false)
    var lastNotificationReceipt by mutableStateOf("No local fixture posted")
        private set
    var lastPushTitle by mutableStateOf<String?>(null)
        private set
    var lastPushMessageId by mutableStateOf<String?>(null)
        private set
    var liveUpdateStage by mutableStateOf("Preparing")
        private set
    var liveUpdateProgress by mutableStateOf(18)
        private set
    var liveUpdateActive by mutableStateOf(false)
        private set
    var lastSubscriptionChange by mutableStateOf("No change observed")
        private set
    var pipelineDiagnostics by mutableStateOf(OpenPushBridge.diagnostics())
        private set

    /** In-app display is suspended while this is true; the engine keeps the message it took down. */
    var inAppPaused by mutableStateOf(OpenPush.InAppMessages.paused)
        private set

    /** The trigger values this process has set, as the engine holds them. */
    val inAppTriggers = mutableStateMapOf<String, String>()
    var lastInAppLifecycle by mutableStateOf("No lifecycle event")
        private set
    var lastInAppClick by mutableStateOf("No click")
        private set
    var message by mutableStateOf<UiMessage?>(null)
        private set

    private var messageId by mutableLongStateOf(0L)
    private var pendingPushEnable = false

    // Demo mode without credentials has no SDK to prompt through, so it asks with the Activity Result API.
    // Registered here, while the Activity is being created, as the API requires.
    private val systemPermissionPrompt =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission(), ::onPermissionAnswered)
    private var permissionObserver: ListenerHandle? = null
    private var subscriptionObserver: ListenerHandle? = null
    private var subscriptionChangeObserver: ListenerHandle? = null
    private var inAppLifecycleHandle: UUID? = null
    private var inAppClickHandle: UUID? = null
    private var foregroundListener: ListenerHandle? = null
    private var clickListener: ListenerHandle? = null
    private var started = false
    private var localEventId = 10_000L
    private var liveUpdateSequence = 0

    val liveAvailable: Boolean get() = ExampleConfiguration.hasLiveCredentials
    var deviceCredentialAvailable by mutableStateOf(ExampleApplication.isLinked())
        private set
    val permissionLabel: String get() = if (permissionGranted) "Allowed" else "Not allowed"
    val subscriptionLabel: String get() = if (pushEnabled) "Subscribed" else "Not subscribed"
    val identityLabel: String get() = externalId ?: "Anonymous"
    val shortSubscriptionId: String get() = subscriptionId ?: "Pending registration"
    val shortToken: String get() = providerToken?.let(OpenPush::shortToken) ?: "Not issued"
    val deviceName: String get() = OpenPush.deviceModel()
    val subscriptionStatusName: String
        get() = OpenPushPayload.statusName(if (pushEnabled) 1 else 0)
    val liveActionsBlocked: Boolean get() = mode == RuntimeMode.LIVE && !OpenPush.isInitialized
    val receiptsQueued: Int get() = (pipelineDiagnostics["receiptsQueued"] as? Number)?.toInt() ?: 0
    val customEventsQueued: Int get() =
        (pipelineDiagnostics["customEventsQueued"] as? Number)?.toInt() ?: 0
    val pendingSessionSeconds: Long get() =
        (pipelineDiagnostics["pendingSessionSeconds"] as? Number)?.toLong() ?: 0L
    val lastDrainAt: Long? get() = (pipelineDiagnostics["lastDrainAt"] as? Number)?.toLong()
    val sdkWrapperHeader: String?
        get() = OpenPush.sdkWrapper?.trim()?.takeIf(String::isNotEmpty)
    val consentStatusLabel: String
        get() = when {
            !consentRequired -> "Not required"
            consentGranted -> "Granted"
            else -> "Not granted"
        }
    val sdkStartupLabel: String
        get() = when {
            !liveAvailable -> "Demo · no network"
            OpenPush.isInitialized && consentRequired && !consentGranted -> "Active until relaunch"
            OpenPush.isInitialized -> "Initialized"
            consentRequired && !consentGranted -> "Waiting for consent"
            else -> "Not initialized"
        }
    val consentChangeNeedsRelaunch: Boolean
        get() = liveAvailable && OpenPush.isInitialized && consentRequired && !consentGranted
    val liveUpdateCapabilityLabel: String
        get() = when (OpenPushLiveUpdates.capability(context)) {
            OpenPushLiveUpdates.Capability.FALLBACK_ONGOING -> "Ongoing fallback"
            OpenPushLiveUpdates.Capability.ELIGIBLE -> "Promotion eligible"
            OpenPushLiveUpdates.Capability.PROMOTION_DISABLED -> "Promotion disabled"
        }

    fun start() {
        if (started) return
        started = true
        ExampleEventRelay.attach { event ->
            onMain {
                append(event)
                refreshLastPush()
            }
        }
        installSdkObservers()
        refreshPermission()
        refreshLastPush()
        if (mode == RuntimeMode.DEMO) installDemoSnapshot() else refreshFromSdk()
        refreshPipelineDiagnostics()
        append(
            LabEventKind.SDK,
            if (liveActionsBlocked) "Live workspace waiting for consent" else "${mode.label} workspace ready",
            when {
                liveActionsBlocked ->
                    "No OpenPush network work starts until the host consent gate permits initialization."
                mode == RuntimeMode.DEMO ->
                    "Synthetic subscription data is active; " +
                        "Android notifications and Live Updates remain native."
                else -> "SDK observers are armed for the configured OpenPush app."
            },
        )
    }

    fun close() {
        permissionObserver?.let(OpenPush.Notifications::removePermissionObserver)
        subscriptionObserver?.let(OpenPush.PushSubscription::removeObserver)
        subscriptionChangeObserver?.let(OpenPush.PushSubscription::removeChangeObserver)
        // The in-app listeners are held by UUID rather than by a ListenerHandle, so they are
        // removed by the id the engine handed back when they were registered.
        inAppLifecycleHandle?.let(OpenPushInAppMessages::removeLifecycleListener)
        inAppClickHandle?.let(OpenPushInAppMessages::removeClickListener)
        inAppLifecycleHandle = null
        inAppClickHandle = null
        foregroundListener?.let(OpenPush.Notifications::removeForegroundLifecycleListener)
        clickListener?.let(OpenPush.Notifications::removeClickListener)
        // The identity hooks are single-valued properties rather than handles, so they are cleared
        // rather than removed; leaving them set would hold this Activity-scoped model after teardown.
        OpenPush.onIdentityVerificationRequired = null
        OpenPush.onIdentityRejected = null
        ExampleEventRelay.detach()
    }

    fun onResume() {
        refreshPermission()
        refreshLastPush()
        if (OpenPush.isInitialized) {
            OpenPush.onAppForegrounded()
            refreshFromSdk()
        }
        refreshPipelineDiagnostics()
    }

    fun drainNow() {
        if (mode == RuntimeMode.DEMO) {
            refreshPipelineDiagnostics()
            showMessage("Pipeline draining is available in Live mode.")
            return
        }
        if (!ensureLiveReady("drain queued pipeline work")) return
        OpenPush.onAppBackgrounded()
        refreshPipelineDiagnostics()
        // The JobService has a one-second minimum latency. Refresh after it has had a chance to run,
        // and once more for a slower network response, so the completion timestamp becomes visible.
        mainHandler.postDelayed(::refreshPipelineDiagnostics, 1_500L)
        mainHandler.postDelayed(::refreshPipelineDiagnostics, 5_000L)
        append(
            LabEventKind.SDK,
            "Pipeline drain scheduled",
            "Durable receipts, Custom Events, registration writes, and session duration will drain " +
                "with network access.",
        )
    }

    private fun onPermissionAnswered(granted: Boolean) {
        refreshPermission()
        if (pendingPushEnable) {
            pendingPushEnable = false
            pushEnabled = permissionGranted
        }
        append(LabEventKind.PERMISSION, "Permission callback fired", "allowed = $granted")
    }

    fun selectMode(value: RuntimeMode) {
        if (value == RuntimeMode.LIVE && !liveAvailable) {
            showMessage("Add an App ID and SDK key to unlock Live mode.")
            return
        }
        mode = value
        if (value == RuntimeMode.LIVE) {
            ExampleApplication.startIfPermitted()
            refreshFromSdk()
        } else {
            installDemoSnapshot()
        }
        append(LabEventKind.SDK, "Runtime changed", "${value.label} workspace selected.")
    }

    fun selectAppearance(value: AppAppearance) {
        appearance = value
        ExamplePreferences.setAppearance(context, value)
    }

    fun requestPermission() {
        if (permissionGranted) {
            showMessage("Notification permission is already allowed.")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            refreshPermission()
            if (!permissionGranted) openNotificationSettings()
            return
        }
        if (OpenPush.isInitialized) {
            // The documented path: the prompt runs in OpenPush's own Activity and the answer comes back
            // here, so MainActivity forwards no onRequestPermissionsResult.
            activity.lifecycleScope.launch {
                val granted = OpenPush.Notifications.requestPermission(fallbackToSettings = true)
                onMain { onPermissionAnswered(granted) }
            }
        } else {
            systemPermissionPrompt.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun updatePushOptIn(enabled: Boolean) {
        if (enabled && !permissionGranted) {
            pendingPushEnable = true
            requestPermission()
            return
        }
        if (mode == RuntimeMode.DEMO) {
            pushEnabled = enabled
        } else if (ensureLiveReady(if (enabled) "opt in" else "opt out")) {
            if (enabled) OpenPush.PushSubscription.optIn(activity) else OpenPush.PushSubscription.optOut()
            refreshFromSdk()
        }
        append(
            LabEventKind.SUBSCRIPTION,
            if (enabled) "Push subscription opted in" else "Push subscription opted out",
            if (enabled) {
                "The installation is eligible for delivery when a provider token is registered."
            } else {
                "The provider token is retained while the subscription is excluded from sends."
            },
        )
    }

    fun login(value: String, authHash: String? = null) {
        val external = value.trim()
        val hash = authHash?.trim()?.takeIf(String::isNotEmpty)
        if (external.isEmpty()) {
            append(LabEventKind.ERROR, "External ID is required", "Enter the ID your backend uses.")
            return
        }
        if (mode == RuntimeMode.DEMO) {
            externalId = external
            append(
                LabEventKind.IDENTITY,
                "User identified",
                if (hash == null) {
                    "Demo install linked to $external."
                } else {
                    "Demo install linked to $external with a verification hash."
                },
            )
            return
        }
        if (!ensureLiveReady("log in")) return
        activity.lifecycleScope.launch {
            val result = OpenPush.loginSuspend(external, hash)
            onMain {
                when (result) {
                    IdentityResult.Success -> {
                        externalId = external
                        append(LabEventKind.IDENTITY, "User identified", "OpenPush accepted $external.")
                    }
                    is IdentityResult.IdentityRejected -> {
                        externalId = null
                        append(LabEventKind.ERROR, "Identity rejected", result.note)
                    }
                    is IdentityResult.Failed -> append(LabEventKind.ERROR, "Login failed", result.message)
                }
            }
        }
    }

    fun logout() {
        if (mode == RuntimeMode.DEMO) {
            externalId = null
            append(LabEventKind.IDENTITY, "Install is anonymous", "The demo identity was detached.")
            return
        }
        if (!ensureLiveReady("log out")) return
        activity.lifecycleScope.launch {
            val result = OpenPush.logoutSuspend()
            onMain {
                when (result) {
                    IdentityResult.Success -> {
                        externalId = null
                        append(LabEventKind.IDENTITY, "Install is anonymous", "OpenPush detached the external ID.")
                    }
                    is IdentityResult.IdentityRejected ->
                        append(LabEventKind.ERROR, "Logout not applied", result.note)
                    is IdentityResult.Failed ->
                        append(LabEventKind.ERROR, "Logout not applied", result.message)
                }
            }
        }
    }

    fun addTag(key: String, value: String) {
        val pair = cleanPair(key, value) ?: return
        if (mode == RuntimeMode.LIVE && !ensureLiveReady("add a tag")) return
        tags[pair.first] = pair.second
        if (mode == RuntimeMode.LIVE) OpenPush.User.addTag(pair.first, pair.second)
        append(LabEventKind.DATA, "Tag updated", "${pair.first} = ${pair.second}")
    }

    fun removeTag(key: String) {
        if (mode == RuntimeMode.LIVE && !ensureLiveReady("remove a tag")) return
        tags.remove(key)
        if (mode == RuntimeMode.LIVE) OpenPush.User.removeTag(key)
        append(LabEventKind.DATA, "Tag removed", key)
    }

    fun addAlias(label: String, id: String) {
        val pair = cleanPair(label, id) ?: return
        if (mode == RuntimeMode.LIVE && !ensureLiveReady("add an alias")) return
        aliases[pair.first] = pair.second
        if (mode == RuntimeMode.LIVE) OpenPush.User.addAlias(pair.first, pair.second)
        append(LabEventKind.DATA, "Alias updated", "${pair.first} = ${pair.second}")
    }

    fun removeAlias(label: String) {
        if (mode == RuntimeMode.LIVE && !ensureLiveReady("remove an alias")) return
        aliases.remove(label)
        if (mode == RuntimeMode.LIVE) OpenPush.User.removeAlias(label)
        append(LabEventKind.DATA, "Alias removed", label)
    }

    fun addTags(values: Map<String, String>) {
        val cleaned = cleanPairs(values, "tags") ?: return
        if (mode == RuntimeMode.LIVE && !ensureLiveReady("add several tags")) return
        tags.putAll(cleaned)
        if (mode == RuntimeMode.LIVE) OpenPush.User.addTags(cleaned)
        append(
            LabEventKind.DATA,
            "${cleaned.size} tags updated",
            cleaned.entries.joinToString(" · ") { "${it.key} = ${it.value}" },
        )
    }

    fun removeTags(keys: Collection<String>) {
        val cleaned = cleanKeys(keys, "tags") ?: return
        if (mode == RuntimeMode.LIVE && !ensureLiveReady("remove several tags")) return
        cleaned.forEach(tags::remove)
        if (mode == RuntimeMode.LIVE) OpenPush.User.removeTags(cleaned)
        append(LabEventKind.DATA, "${cleaned.size} tags removed", cleaned.joinToString(" · "))
    }

    fun addAliases(values: Map<String, String>) {
        val cleaned = cleanPairs(values, "aliases") ?: return
        if (mode == RuntimeMode.LIVE && !ensureLiveReady("add several aliases")) return
        aliases.putAll(cleaned)
        if (mode == RuntimeMode.LIVE) OpenPush.User.addAliases(cleaned)
        append(
            LabEventKind.DATA,
            "${cleaned.size} aliases updated",
            cleaned.entries.joinToString(" · ") { "${it.key} = ${it.value}" },
        )
    }

    fun removeAliases(labels: Collection<String>) {
        val cleaned = cleanKeys(labels, "aliases") ?: return
        if (mode == RuntimeMode.LIVE && !ensureLiveReady("remove several aliases")) return
        cleaned.forEach(aliases::remove)
        if (mode == RuntimeMode.LIVE) OpenPush.User.removeAliases(cleaned)
        append(LabEventKind.DATA, "${cleaned.size} aliases removed", cleaned.joinToString(" · "))
    }

    fun trackEvent(name: String, propertiesJson: String) {
        val cleanName = name.trim()
        if (
            cleanName.isEmpty() ||
            cleanName.length > 128 ||
            !cleanName.matches(Regex("[a-zA-Z0-9_\\-. ]+"))
        ) {
            append(
                LabEventKind.ERROR,
                "Custom event not tracked",
                "Name must be 1–128 characters using letters, numbers, spaces, _, -, or .",
            )
            return
        }
        val source = propertiesJson.trim().ifEmpty { "{}" }
        val properties = runCatching {
            val json = JSONObject(source)
            buildMap<String, Any?> {
                json.keys().forEach { key -> put(key, json.get(key)) }
            }
        }.getOrElse { error ->
            append(
                LabEventKind.ERROR,
                "Custom event JSON is invalid",
                error.message ?: "Properties must be a JSON object.",
            )
            return
        }
        if (source.toByteArray().size > 2_048) {
            append(
                LabEventKind.ERROR,
                "Custom event not tracked",
                "Properties must be no larger than 2,048 bytes.",
            )
            return
        }
        OpenPush.trackEvent(cleanName, properties)
        refreshPipelineDiagnostics()
        append(
            LabEventKind.DATA,
            "Custom event tracked",
            "$cleanName · ${properties.size} properties · durable delivery is handled by the SDK.",
        )
    }

    fun saveCredential(value: String) {
        val credential = value.trim()
        if (credential.isEmpty()) {
            append(LabEventKind.ERROR, "Credential not saved", "Enter the device credential returned by your server.")
            return
        }
        if (!ExampleApplication.credentialStore.save(credential)) {
            append(LabEventKind.ERROR, "Credential not saved", "Android Keystore encryption was unavailable.")
            return
        }
        deviceCredentialAvailable = true
        ExampleApplication.registerIfLinked()
        activity.lifecycleScope.launch {
            val accepted = OpenPush.awaitRegistration()
            onMain {
                append(
                    if (accepted) LabEventKind.SUBSCRIPTION else LabEventKind.ERROR,
                    if (accepted) "Registration accepted" else "Registration failed",
                    "The coroutine registration observer completed without replacing the app callbacks.",
                )
                refreshFromSdk()
            }
        }
        append(
            LabEventKind.SDK,
            "Device credential saved",
            "Encrypted with Android Keystore; provider-token registration has been requested.",
        )
    }

    fun wipeCredential() {
        ExampleApplication.credentialStore.wipe()
        deviceCredentialAvailable = false
        append(
            LabEventKind.SDK,
            "Device credential wiped",
            "Encrypted credential data was removed. Clear the token separately after retiring the delivery address.",
        )
    }

    fun clearToken() {
        OpenPush.clearToken()
        providerToken = null
        pushEnabled = false
        append(
            LabEventKind.SUBSCRIPTION,
            "Provider token cleared",
            "The server-side subscription id remains available; register again after linking this installation.",
        )
    }

    fun postNotification(example: NotificationExample): Boolean = postNotification(example, null)

    fun postCustomNotification(draft: CustomNotificationDraft): Boolean =
        postNotification(NotificationExample.CUSTOM, draft)

    private fun postNotification(example: NotificationExample, customDraft: CustomNotificationDraft?): Boolean {
        if (!permissionGranted) {
            showMessage("Allow notifications before posting a ${example.title} fixture.")
            requestPermission()
            return false
        }
        return ExampleNotificationFixtures.post(context, example, customDraft).fold(
            onSuccess = { receipt ->
                lastNotificationReceipt = receipt
                append(
                    LabEventKind.SDK,
                    "${example.title} notification posted",
                    "Open the notification shade and interact with the native Android fixture.",
                )
                showMessage(receipt)
                true
            },
            onFailure = { error ->
                append(LabEventKind.ERROR, "Notification not posted", error.message.orEmpty())
                false
            },
        )
    }

    fun clearNotifications() {
        OpenPush.Notifications.clearAll()
        lastNotificationReceipt = "Local fixtures cleared"
        showMessage("Feature lab notifications cleared.")
    }

    fun removeNotificationGroup() {
        OpenPush.Notifications.removeGroup(ExampleNotificationFixtures.GROUP_KEY)
        lastNotificationReceipt = "Grouped fixture removed"
        showMessage("Grouped fixture notifications removed.")
    }

    fun previewForegroundEvent() {
        val data = mapOf(
            "op_message_id" to "preview-${System.currentTimeMillis()}",
            "title" to "Foreground preview",
            "body" to "A synthetic payload passed through the SDK lifecycle listener.",
        )
        val prevented = OpenPush.Notifications.processForegroundMessage(OpenPushNotification.fromData(data))
        showMessage(if (prevented) "Foreground display prevented." else "Foreground observer fired.")
    }

    fun previewClickEvent() {
        OpenPush.Notifications.processClick(
            OpenPushNotification.fromData(
                mapOf("op_message_id" to "click-${System.currentTimeMillis()}", "op_action_id" to "preview"),
            ),
        )
    }

    fun updateInAppPaused(value: Boolean) {
        OpenPushInAppMessages.paused = value
        inAppPaused = OpenPushInAppMessages.paused
        append(
            LabEventKind.IN_APP,
            if (value) "In-app display paused" else "In-app display resumed",
            if (value) {
                "A message on screen comes down and goes back to the head of the queue. Nothing is " +
                    "recorded: no impression, no dismissal, no redisplay count."
            } else {
                "The message a pause took down is presented again before anything else in the queue."
            },
        )
    }

    fun addInAppTrigger(key: String, value: String) {
        val pair = cleanPair(key, value) ?: return
        inAppTriggers[pair.first] = pair.second
        OpenPushInAppMessages.addTrigger(pair.first, pair.second)
        append(
            LabEventKind.IN_APP,
            "Trigger set",
            "${pair.first} = ${pair.second} · every trigger change re-evaluates the whole definition list.",
        )
    }

    /**
     * The one-call form. `addTriggers` re-evaluates once for the whole map rather than once per
     * key, which is what a host setting several values at a screen boundary wants.
     */
    fun addInAppTriggers(values: Map<String, String>) {
        val cleaned = values
            .map { (key, value) -> key.trim() to value.trim() }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .toMap()
        if (cleaned.isEmpty()) {
            append(LabEventKind.ERROR, "No triggers to set", "Write one key=value pair per line.")
            return
        }
        inAppTriggers.putAll(cleaned)
        OpenPushInAppMessages.addTriggers(cleaned)
        append(
            LabEventKind.IN_APP,
            "${cleaned.size} triggers set",
            cleaned.entries.joinToString(" · ") { "${it.key} = ${it.value}" },
        )
    }

    fun removeInAppTrigger(key: String) {
        inAppTriggers.remove(key)
        OpenPushInAppMessages.removeTrigger(key)
        append(LabEventKind.IN_APP, "Trigger removed", key)
    }

    fun removeInAppTriggers(keys: Collection<String>) {
        val cleaned = keys.map(String::trim).filter(String::isNotEmpty).distinct()
        if (cleaned.isEmpty()) {
            showMessage("Name at least one trigger to remove.")
            return
        }
        cleaned.forEach(inAppTriggers::remove)
        OpenPushInAppMessages.removeTriggers(cleaned)
        append(LabEventKind.IN_APP, "${cleaned.size} triggers removed", cleaned.joinToString(" · "))
    }

    fun clearInAppTriggers() {
        if (inAppTriggers.isEmpty()) {
            showMessage("No triggers to clear.")
            return
        }
        inAppTriggers.clear()
        OpenPushInAppMessages.clearTriggers()
        append(LabEventKind.IN_APP, "Triggers cleared", "Every trigger value this process set is gone.")
    }

    fun fetchInAppMessages() {
        if (mode == RuntimeMode.DEMO) {
            append(
                LabEventKind.IN_APP,
                "Fetch needs a workspace",
                "The message list comes from the server. Use the demo document below to exercise " +
                    "the presenter without one.",
            )
            return
        }
        if (!ensureLiveReady("fetch in-app messages")) return
        OpenPushInAppMessages.fetch()
        append(
            LabEventKind.IN_APP,
            "In-app fetch requested",
            "Throttled to one request per fetch interval, and skipped while the app is backgrounded.",
        )
    }

    /**
     * Shows the bundled `assets/iam_demo.html` through the shipping presenter. No workspace, no
     * subscription and no network are involved, and nothing is recorded — see
     * `OpenPushInAppMessages.previewLocalDocument`.
     */
    fun previewInAppDocument() {
        OpenPushInAppMessages.previewLocalDocument(activity, DEMO_DOCUMENT)
        append(
            LabEventKind.IN_APP,
            "Demo document shown",
            "The real WebView, bridge, layout and link handling — with no receipt posted and no " +
                "display state written.",
        )
    }

    fun clearEvents() {
        events.clear()
        showMessage("Event history cleared.")
    }

    fun updateConsentRequirement(required: Boolean) {
        ExampleConsentGate.setRequired(context, required)
        consentRequired = required
        consentGranted = ExampleConsentGate.isGranted(context)
        append(
            LabEventKind.SDK,
            if (required) "Host consent gate enabled" else "Host consent gate disabled",
            if (required) {
                "A cold start now waits for a saved consent grant before SDK initialization."
            } else {
                "OpenPush may initialize on the next eligible app start."
            },
        )
    }

    fun grantConsent() {
        if (!consentRequired) return
        ExampleConsentGate.setGranted(context, true)
        consentGranted = true
        if (mode == RuntimeMode.LIVE) ExampleApplication.startIfPermitted()
        refreshFromSdk()
        append(LabEventKind.SDK, "Host consent granted", "The host app may now initialize OpenPush.")
    }

    fun resetConsent() {
        if (!consentRequired) return
        ExampleConsentGate.setGranted(context, false)
        consentGranted = false
        append(
            LabEventKind.SDK,
            "Saved consent reset",
            if (OpenPush.isInitialized) {
                "This process remains active; relaunch to restore the startup gate."
            } else {
                "OpenPush initialization is waiting for a new host consent decision."
            },
        )
    }

    fun startLiveUpdate() {
        if (!permissionGranted) {
            showMessage("Allow notifications before starting an Android Live Update.")
            requestPermission()
            return
        }
        liveUpdateSequence = 1
        liveUpdateProgress = 18
        liveUpdateStage = "Courier assigned"
        val result = postLiveUpdate("start", liveUpdateSequence, liveUpdateProgress)
        liveUpdateActive = result == OpenPushLiveUpdateResult.DISPLAYED
        recordLiveUpdateResult("start", result)
    }

    fun updateLiveUpdate() {
        if (!liveUpdateActive) {
            showMessage("Start the Live Update before sending an update.")
            return
        }
        liveUpdateSequence += 1
        liveUpdateProgress = (liveUpdateProgress + 24).coerceAtMost(96)
        liveUpdateStage = when {
            liveUpdateProgress < 50 -> "Courier en route"
            liveUpdateProgress < 85 -> "Arriving soon"
            else -> "At your door"
        }
        recordLiveUpdateResult(
            "update",
            postLiveUpdate("update", liveUpdateSequence, liveUpdateProgress),
        )
    }

    fun endLiveUpdate() {
        if (!liveUpdateActive) return
        liveUpdateSequence += 1
        val result = postLiveUpdate("end", liveUpdateSequence, 100)
        liveUpdateActive = false
        liveUpdateProgress = 100
        liveUpdateStage = "Delivered"
        recordLiveUpdateResult("end", result)
    }

    fun openLiveUpdateSettings() {
        if (!OpenPushLiveUpdates.openPromotionSettings(context)) {
            showMessage("Promoted Live Update settings are unavailable on this device.")
        }
    }

    fun handleNotificationIntent(messageId: String, actionId: String?, extras: Map<String, String>) {
        val payload = buildMap {
            putAll(extras)
            put("op_message_id", messageId)
            actionId?.let { put("op_action_id", it) }
        }
        OpenPush.Notifications.processClick(payload)
    }

    fun consumeMessage(id: Long) {
        if (message?.id == id) message = null
    }

    private fun installDemoSnapshot() {
        subscriptionId = "demo-sub-a41f"
        providerToken = "7f91c0a84bd94c11b8af47e92c45a6237f91c0a84bd94c11b8af47e92c45a623"
        pushEnabled = permissionGranted
        externalId = null
        tags.clear()
        tags["example_build"] = "feature-lab"
        aliases.clear()
    }

    private fun installSdkObservers() {
        permissionObserver = OpenPush.Notifications.addPermissionObserver { granted ->
            onMain {
                permissionGranted = granted
                append(LabEventKind.PERMISSION, "Permission observer fired", "allowed = $granted")
            }
        }
        subscriptionObserver = OpenPush.User.pushSubscription.addObserver { state ->
            onMain {
                subscriptionId = state.id
                providerToken = state.token
                pushEnabled = state.optedIn
                append(
                    LabEventKind.SUBSCRIPTION,
                    "Subscription observer fired",
                    "optedIn = ${state.optedIn} · token = ${state.token?.let(OpenPush::shortToken) ?: "—"}",
                )
            }
        }
        subscriptionChangeObserver = OpenPush.PushSubscription.addChangeObserver { change ->
            onMain {
                val summary = "${describe(change.previous)} → ${describe(change.current)}"
                lastSubscriptionChange = summary
                append(LabEventKind.SUBSCRIPTION, "Subscription changed", summary)
            }
        }
        OpenPush.onIdentityVerificationRequired = { id ->
            onMain {
                append(
                    LabEventKind.IDENTITY,
                    "Auth hash required for $id",
                    "Auth hashes are never persisted. Re-supply one from your server and log in again; " +
                        "until then the registration omits external_id rather than asserting it unverified.",
                )
            }
        }
        OpenPush.onIdentityRejected = { note ->
            onMain {
                externalId = null
                append(LabEventKind.ERROR, "Identity rejected", note)
            }
        }
        inAppLifecycleHandle = OpenPushInAppMessages.addLifecycleListener { event ->
            onMain {
                val stage = when (event.stage) {
                    InAppLifecycleStage.WILL_DISPLAY -> "about to display"
                    InAppLifecycleStage.DID_DISPLAY -> "rendered"
                    InAppLifecycleStage.WILL_DISMISS -> "about to dismiss"
                    InAppLifecycleStage.DID_DISMISS -> "dismissed"
                    InAppLifecycleStage.UNKNOWN -> "unknown phase"
                }
                lastInAppLifecycle = "${event.messageId} · $stage"
                append(
                    LabEventKind.IN_APP,
                    "In-app ${event.phase}",
                    "message = ${event.messageId} · stage = ${event.stage.name}",
                )
            }
        }
        inAppClickHandle = OpenPushInAppMessages.addClickListener { event ->
            onMain {
                lastInAppClick = "${event.actionId ?: "action"} · ${if (event.firstClick) "first" else "repeat"}"
                append(
                    LabEventKind.IN_APP,
                    "In-app click",
                    buildString {
                        append("message = ${event.messageId}")
                        append(" · action = ${event.actionId ?: "—"}")
                        append(" · firstClick = ${event.firstClick}")
                        append(" · closes = ${event.closingMessage}")
                        event.url?.let { append(" · url = $it (${event.urlTarget ?: "default"})") }
                        event.pageId?.let { append(" · page = $it") }
                    },
                )
            }
        }
        foregroundListener = OpenPush.Notifications.addForegroundLifecycleListener { event ->
            if (preventForegroundPresentation) event.preventDefault()
            onMain {
                append(
                    LabEventKind.FOREGROUND,
                    "Foreground listener fired",
                    notificationFields(
                        event.notification,
                        messageId = event.messageId,
                        rawEntryCount = event.data.size,
                        defaultPrevented = event.defaultPrevented,
                    ),
                )
            }
        }
        clickListener = OpenPush.Notifications.addClickListener { event ->
            onMain {
                append(
                    LabEventKind.CLICK,
                    "Click listener fired",
                    notificationFields(
                        event.notification,
                        event.actionId,
                        includeAction = true,
                        messageId = event.messageId,
                        rawEntryCount = event.data.size,
                    ),
                )
            }
        }
    }

    private fun describe(state: PushSubscriptionState?): String {
        if (state == null) return "none"
        val id = state.id ?: "no id"
        val opted = if (state.optedIn) "opted in" else "opted out"
        return "$id · $opted"
    }

    private fun notificationFields(
        notification: OpenPushNotification,
        actionId: String? = null,
        includeAction: Boolean = false,
        messageId: String? = notification.messageId,
        rawEntryCount: Int = notification.rawData.size,
        defaultPrevented: Boolean? = null,
    ): String = buildString {
        append("messageId = ${messageId ?: "—"}")
        append("\nisOpenPush = ${notification.isOpenPush}")
        append("\ntitle = ${notification.title ?: "—"}")
        append("\nbody = ${notification.body ?: "—"}")
        if (includeAction) {
            append("\nactionId = ${actionId ?: "body"}")
        }
        append("\nlaunchUrl = ${notification.launchUrl ?: "—"}")
        append("\nimageUrl = ${notification.imageUrl ?: "—"}")
        append("\nchannelId = ${notification.channelId ?: "—"}")
        append("\naccentColor = ${notification.accentColor ?: "—"}")
        append("\nlargeIconUrl = ${notification.largeIconUrl ?: "—"}")
        append("\nbigPictureUrl = ${notification.bigPictureUrl ?: "—"}")
        append("\ngroup = ${notification.group ?: "—"}")
        append("\ncategory = ${notification.category ?: "—"}")
        append("\nvisibility = ${notification.visibility ?: "—"}")
        append("\nsound = ${notification.sound ?: "—"}")
        append("\nsmallIcon = ${notification.smallIcon ?: "—"}")
        append("\nledColor = ${notification.ledColor ?: "—"}")
        append("\nactions = ${notification.actions.joinToString { it.id }}")
        append("\ncollapseKey = ${notification.collapseKey ?: "—"}")
        append("\nadditionalData = ")
        append(
            notification.additionalData.entries
                .sortedBy { it.key }
                .joinToString(prefix = "{", postfix = "}") { (key, value) -> "$key=$value" },
        )
        append("\nrawData = $rawEntryCount entries")
        defaultPrevented?.let { append("\ndefaultPrevented = $it") }
    }

    private fun refreshFromSdk() {
        if (mode != RuntimeMode.LIVE || !OpenPush.isInitialized) return
        refreshPermission()
        val subscription = OpenPush.User.pushSubscription.snapshot()
        subscriptionId = subscription.id
        providerToken = OpenPush.currentToken ?: subscription.token
        pushEnabled = OpenPush.PushSubscription.optedIn
        externalId = OpenPush.User.externalId
        tags.clear()
        tags.putAll(OpenPush.User.getTags().filterValues { it.isNotEmpty() })
        refreshPipelineDiagnostics()
    }

    private fun refreshPipelineDiagnostics() {
        pipelineDiagnostics = OpenPushBridge.diagnostics()
    }

    private fun refreshLastPush() {
        lastPushTitle = ExampleLastPush.title(context)
        lastPushMessageId = ExampleLastPush.messageId(context)
    }

    private fun refreshPermission() {
        // Demo mode, or a Live workspace still waiting for consent, may prompt through the Activity
        // Result API with the SDK never initialized, so the SDK's cached permission would stay stale.
        // Those read the OS; an initialized Live workspace reads the SDK.
        val readOs = mode == RuntimeMode.DEMO || !OpenPush.isInitialized
        permissionGranted = if (readOs) osPermissionGranted() else OpenPush.Notifications.permission
        canRequestPermission = !permissionGranted && OpenPush.Notifications.canRequestPermission
        if (mode == RuntimeMode.DEMO && !permissionGranted) pushEnabled = false
    }

    private fun osPermissionGranted(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun openNotificationSettings() {
        // The per-app notification screen is API 26+; below it the app details screen holds the toggle.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null),
            )
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun ensureLiveReady(action: String): Boolean {
        if (mode != RuntimeMode.LIVE || OpenPush.isInitialized) return true
        append(
            LabEventKind.ERROR,
            "SDK waiting for consent",
            "Grant host consent before you $action.",
        )
        return false
    }

    private fun cleanPair(key: String, value: String): Pair<String, String>? {
        val cleanedKey = key.trim()
        val cleanedValue = value.trim()
        if (cleanedKey.isEmpty() || cleanedValue.isEmpty()) {
            append(LabEventKind.ERROR, "Both fields are required", "Keys, labels, and values cannot be empty.")
            return null
        }
        return cleanedKey to cleanedValue
    }

    private fun cleanPairs(values: Map<String, String>, noun: String): Map<String, String>? {
        val cleaned = values.entries
            .map { (key, value) -> key.trim() to value.trim() }
            .filter { (key, value) -> key.isNotEmpty() && value.isNotEmpty() }
            .toMap()
        if (cleaned.isEmpty()) {
            append(LabEventKind.ERROR, "No $noun to add", "Write one key=value pair per line.")
            return null
        }
        return cleaned
    }

    private fun cleanKeys(values: Collection<String>, noun: String): List<String>? {
        val cleaned = values.map(String::trim).filter(String::isNotEmpty).distinct()
        if (cleaned.isEmpty()) {
            append(LabEventKind.ERROR, "No $noun to remove", "Write one key or label per line.")
            return null
        }
        return cleaned
    }

    private fun postLiveUpdate(event: String, sequence: Int, progress: Int): OpenPushLiveUpdateResult {
        val attributes = JSONObject()
            .put("title", "Delivery")
            .put("body", liveUpdateStage)
        val updates = JSONObject()
            .put("current_progress", progress)
            .put("max_progress", 100)
            .put("short_text", "$progress%")
        val payload = JSONObject()
            .put("key", "progress")
            .put("instance_id", LIVE_UPDATE_ID)
            .put("event", event)
            .put("sequence", sequence)
            .put("event_attributes", attributes)
            .put("event_updates", updates)

        val tap = PendingIntent.getActivity(
            context,
            LIVE_UPDATE_ID.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(ExampleMessagingService.EXTRA_MESSAGE_ID, LIVE_UPDATE_ID)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return OpenPushLiveUpdates.handle(
            context,
            mapOf("live_notification" to payload.toString()),
            LIVE_UPDATE_ID,
            R.drawable.ic_stat_example,
            tap,
        )
    }

    private fun recordLiveUpdateResult(event: String, result: OpenPushLiveUpdateResult) {
        val title = "Live Update ${event.replaceFirstChar(Char::uppercase)}"
        when (result) {
            OpenPushLiveUpdateResult.NOT_LIVE_UPDATE ->
                append(LabEventKind.ERROR, "$title was not a Live Update", "ordinary display may continue")
            OpenPushLiveUpdateResult.DISPLAYED ->
                append(LabEventKind.ACTIVITY, title, "The ongoing notification was displayed.")
            OpenPushLiveUpdateResult.ENDED ->
                append(LabEventKind.ACTIVITY, title, "The ongoing notification was ended.")
            OpenPushLiveUpdateResult.DISMISSED ->
                append(LabEventKind.ERROR, "$title remained dismissed", "the user dismissal gate consumed it")
            OpenPushLiveUpdateResult.STALE ->
                append(LabEventKind.ACTIVITY, title, "A repeated or older frame was consumed as stale.")
            OpenPushLiveUpdateResult.INVALID ->
                append(LabEventKind.ERROR, "$title was invalid", "the payload was consumed without display")
        }
        showMessage("$title · ${result.name.lowercase()}")
    }

    private fun showMessage(text: String) {
        messageId += 1
        message = UiMessage(messageId, text)
    }

    private fun append(kind: LabEventKind, title: String, detail: String) {
        append(LabEvent(localEventId++, System.currentTimeMillis(), kind, title, detail))
    }

    private fun append(event: LabEvent) {
        events.add(0, event)
        while (events.size > 80) events.removeAt(events.lastIndex)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        const val LIVE_UPDATE_ID = "delivery-feature-lab"
        const val DEMO_DOCUMENT = "iam_demo.html"
    }
}
