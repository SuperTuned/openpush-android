package ai.openpush.example

import ai.openpush.android.OpenPush
import ai.openpush.android.liveupdates.OpenPushLiveUpdates
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartButton
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
fun StatusScreen(model: ExampleLabModel, modifier: Modifier = Modifier) {
    var customEditorOpen by rememberSaveable { mutableStateOf(false) }
    var credential by rememberSaveable { mutableStateOf("") }
    FeatureScreen(modifier) {
        item {
            LabIntro(
                summary = "A runnable map of the Android SDK—state first, actions second, receipts visible.",
                mode = model.mode,
            )
        }
        item {
            MetricGrid(
                listOf(
                    Metric("Permission", model.permissionLabel, "Android notification access", model.permissionGranted),
                    Metric("Push", model.subscriptionLabel, "provider token retained", model.pushEnabled),
                    Metric(
                        "Identity",
                        model.identityLabel,
                        if (model.externalId == null) "install-level user" else "external ID attached",
                    ),
                    Metric(
                        "SDK",
                        OpenPush.sdkVersion,
                        if (model.mode == RuntimeMode.DEMO) "simulated service writes" else model.sdkStartupLabel,
                    ),
                ),
            )
        }
        if (model.liveActionsBlocked) item { ConsentGateNotice() }
        item {
            LabPanel("Notifications", "permission + subscription") {
                TelemetryRow(
                    "Authorization",
                    model.permissionLabel,
                    if (model.permissionGranted) MaterialTheme.colorScheme.primary else warningColor(),
                )
                Hairline()
                TelemetryRow(
                    "Can request permission",
                    if (model.canRequestPermission) "Yes" else "No",
                )
                Hairline()
                LabSwitchRow(
                    title = "Push enabled",
                    detail = "Opting out preserves the provider token while excluding this subscription from delivery.",
                    checked = model.pushEnabled,
                    enabled = !model.liveActionsBlocked,
                    onCheckedChange = model::updatePushOptIn,
                )
                Hairline()
                TelemetryRow("Subscription changes", model.lastSubscriptionChange)
                Hairline()
                TelemetryRow("Last push title", model.lastPushTitle ?: "None received")
                Hairline()
                TelemetryRow("Last push op_message_id", model.lastPushMessageId ?: "None received")
                if (!model.permissionGranted) {
                    PrimaryLabButton("Request notification permission", model::requestPermission)
                }
            }
        }
        item {
            LabPanel("PNS examples", "6 local fixtures") {
                Text(
                    "This sample owns normal notification rendering. The Grouped fixture explicitly sends " +
                        "two local payloads through OpenPushNotificationRenderer so its group summary and " +
                        "shade-management registry are visible.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TelemetryRow("Last request", model.lastNotificationReceipt)
                NotificationExample.entries.forEachIndexed { index, example ->
                    NotificationExampleRow(example, notificationIcon(example)) {
                        if (example == NotificationExample.CUSTOM) {
                            customEditorOpen = true
                        } else {
                            model.postNotification(example)
                        }
                    }
                    if (index != NotificationExample.entries.lastIndex) Hairline()
                }
                SecondaryLabButton(
                    "Remove group",
                    model::removeNotificationGroup,
                    icon = Icons.Outlined.DeleteSweep,
                )
                SecondaryLabButton(
                    "Clear posted fixtures",
                    model::clearNotifications,
                    icon = Icons.Outlined.DeleteSweep,
                )
            }
        }
        item {
            LabPanel("Privacy consent", "host-app gate") {
                Text(
                    "OpenPush does not own a consent API. This sample stores the host app's decision " +
                        "and delays SDK initialization until consent is granted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LabSwitchRow(
                    title = "Require consent before SDK start",
                    checked = model.consentRequired,
                    onCheckedChange = model::updateConsentRequirement,
                )
                Hairline()
                TelemetryRow("Saved decision", model.consentStatusLabel)
                Hairline()
                TelemetryRow("SDK startup", model.sdkStartupLabel)
                when {
                    model.consentRequired && !model.consentGranted ->
                        PrimaryLabButton("Grant consent & start SDK", model::grantConsent)
                    model.consentRequired ->
                        DestructiveLabButton("Reset saved consent", model::resetConsent)
                }
                if (model.consentChangeNeedsRelaunch) {
                    Text(
                        "This process already initialized OpenPush. Relaunch to test the waiting state; " +
                            "resetting consent cannot undo data already sent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = warningColor(),
                    )
                }
            }
        }
        item {
            LabPanel("Device registration", model.mode.label) {
                TelemetryRow("Subscription ID", model.shortSubscriptionId)
                Hairline()
                TelemetryRow("Provider token", model.shortToken)
                Hairline()
                TelemetryRow("Device", model.deviceName)
                Hairline()
                TelemetryRow("Wire status", model.subscriptionStatusName)
                Hairline()
                TelemetryRow(
                    "Credential",
                    if (model.deviceCredentialAvailable) "Stored with Android Keystore" else "Not stored",
                )
                if (model.mode == RuntimeMode.LIVE) {
                    Hairline()
                    OutlinedTextField(
                        value = credential,
                        onValueChange = { credential = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Device credential") },
                        placeholder = { Text("Returned by your server") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                    )
                    PrimaryLabButton(
                        "Save credential",
                        {
                            model.saveCredential(credential)
                            if (model.deviceCredentialAvailable) credential = ""
                        },
                        enabled = credential.isNotBlank(),
                    )
                    DestructiveLabButton("Wipe credential", model::wipeCredential)
                    DestructiveLabButton("Clear token", model::clearToken)
                    Text(
                        "Retire the delivery address on your server before wiping the credential and " +
                            "clearing the local provider token.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            LabPanel("Firebase service", "receipt + action path") {
                TelemetryRow("Message routing", "FirebaseMessagingService")
                Hairline()
                TelemetryRow("Receipts", "received · confirmed · clicked")
                Text(
                    "Remote deliveries pass through the bundled messaging service. Confirmed is posted " +
                        "only after Android accepts display; action IDs return through the Activity intent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            LabPanel("Pipeline", "durable background drain") {
                TelemetryRow("Receipts queued", model.receiptsQueued.toString())
                Hairline()
                TelemetryRow("Custom Events queued", model.customEventsQueued.toString())
                Hairline()
                TelemetryRow("Pending session", "${model.pendingSessionSeconds} s")
                Hairline()
                TelemetryRow(
                    "Last completed drain",
                    model.lastDrainAt?.let {
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
                    } ?: "Not yet",
                )
                model.sdkWrapperHeader?.let { wrapper ->
                    Hairline()
                    TelemetryRow(OpenPush.X_OP_SDK_WRAPPER, wrapper)
                }
                Hairline()
                TelemetryRow(OpenPush.X_OP_SDK, "android/${OpenPush.sdkVersion}")
                SecondaryLabButton("Drain now", model::drainNow, icon = Icons.Outlined.Refresh)
            }
        }
    }
    if (customEditorOpen) {
        CustomNotificationEditorSheet(
            onDismiss = { customEditorOpen = false },
            onPost = {
                if (model.postCustomNotification(it)) customEditorOpen = false
            },
        )
    }
}

private enum class EditorKind { TAG, ALIAS }
private enum class EditorMode { ADD_ONE, ADD_SEVERAL, REMOVE_SEVERAL }
private data class UserEditor(val kind: EditorKind, val mode: EditorMode)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScreen(model: ExampleLabModel, modifier: Modifier = Modifier) {
    var externalId by remember { mutableStateOf("demo-user") }
    var authHash by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf<UserEditor?>(null) }

    FeatureScreen(modifier) {
        item {
            LabIntro(
                "Attach this install to your user model, then shape targeting data with tags and aliases.",
                model.mode,
            )
        }
        if (model.liveActionsBlocked) item { ConsentGateNotice() }
        item {
            LabPanel("Identity", if (model.externalId == null) "anonymous" else "identified") {
                TelemetryRow("Current user", model.identityLabel)
                Hairline()
                OutlinedTextField(
                    value = externalId,
                    onValueChange = { externalId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("External ID") },
                    placeholder = { Text("player-1042") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                )
                OutlinedTextField(
                    value = authHash,
                    onValueChange = { authHash = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Auth hash (optional)") },
                    placeholder = { Text("from your server") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                )
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 500.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryLabButton("Log in", { model.login(externalId, authHash) }, Modifier.weight(1f))
                            DestructiveLabButton("Log out", model::logout, Modifier.weight(1f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryLabButton("Log in", { model.login(externalId, authHash) })
                            DestructiveLabButton("Log out", model::logout)
                        }
                    }
                }
                Text(
                    "Generate identity verification hashes on your server—never in the app. The hash is " +
                        "never persisted, so after a relaunch the SDK asks for it again through " +
                        "onIdentityVerificationRequired instead of asserting the identity unverified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            LabPanel("Tags", "targeting state") {
                DataRows(model.tags, "No tags in this session", model::removeTag)
                SecondaryLabButton(
                    "Add tag",
                    { editor = UserEditor(EditorKind.TAG, EditorMode.ADD_ONE) },
                    icon = Icons.Outlined.Add,
                )
                SecondaryLabButton(
                    "Add several tags",
                    { editor = UserEditor(EditorKind.TAG, EditorMode.ADD_SEVERAL) },
                    icon = Icons.Outlined.Code,
                )
                SecondaryLabButton(
                    "Remove several tags",
                    { editor = UserEditor(EditorKind.TAG, EditorMode.REMOVE_SEVERAL) },
                    icon = Icons.Outlined.DeleteSweep,
                )
            }
        }
        item {
            LabPanel("Aliases", "10 labels max") {
                DataRows(model.aliases, "No aliases in this session", model::removeAlias)
                SecondaryLabButton(
                    "Add alias",
                    { editor = UserEditor(EditorKind.ALIAS, EditorMode.ADD_ONE) },
                    icon = Icons.Outlined.Add,
                )
                SecondaryLabButton(
                    "Add several aliases",
                    { editor = UserEditor(EditorKind.ALIAS, EditorMode.ADD_SEVERAL) },
                    icon = Icons.Outlined.Code,
                )
                SecondaryLabButton(
                    "Remove several aliases",
                    { editor = UserEditor(EditorKind.ALIAS, EditorMode.REMOVE_SEVERAL) },
                    icon = Icons.Outlined.DeleteSweep,
                )
                Text(
                    "Aliases add lookup keys; they do not merge users. Use login for external_id.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    editor?.let { request ->
        val isTag = request.kind == EditorKind.TAG
        KeyValueEditorSheet(
            title = when (request.mode) {
                EditorMode.ADD_ONE -> if (isTag) "Add tag" else "Add alias"
                EditorMode.ADD_SEVERAL -> if (isTag) "Add several tags" else "Add several aliases"
                EditorMode.REMOVE_SEVERAL -> if (isTag) "Remove several tags" else "Remove several aliases"
            },
            keyLabel = if (isTag) "Key" else "Label",
            keyPlaceholder = if (isTag) "plan" else "player_id",
            valueLabel = if (isTag) "Value" else "ID",
            valuePlaceholder = if (isTag) "pro" else "p-1042",
            multiRow = request.mode != EditorMode.ADD_ONE,
            keysOnly = request.mode == EditorMode.REMOVE_SEVERAL,
            onDismiss = { editor = null },
            onSave = { key, value ->
                if (isTag) model.addTag(key, value) else model.addAlias(key, value)
                editor = null
            },
            onSaveSeveral = { values ->
                if (isTag) model.addTags(values) else model.addAliases(values)
                editor = null
            },
            onRemoveSeveral = { keys ->
                if (isTag) model.removeTags(keys) else model.removeAliases(keys)
                editor = null
            },
        )
    }
}

@Composable
fun InAppScreen(model: ExampleLabModel, modifier: Modifier = Modifier) {
    var triggerEditorOpen by rememberSaveable { mutableStateOf(false) }
    var multiEditorOpen by rememberSaveable { mutableStateOf(false) }
    var removeSeveralOpen by rememberSaveable { mutableStateOf(false) }

    FeatureScreen(modifier) {
        item {
            LabIntro(
                "Set the trigger values the engine matches against, then watch a real message " +
                    "display and report itself.",
                model.mode,
            )
        }
        item {
            LabPanel("Display", if (model.inAppPaused) "paused" else "running") {
                LabSwitchRow(
                    title = "Pause in-app display",
                    detail = "Pausing takes the message on screen down and gives it back to the " +
                        "head of the queue. No impression, no dismissal, no redisplay count: " +
                        "resuming shows the same message again.",
                    checked = model.inAppPaused,
                    onCheckedChange = model::updateInAppPaused,
                )
                Hairline()
                TelemetryRow("Last lifecycle", model.lastInAppLifecycle)
                Hairline()
                TelemetryRow("Last click", model.lastInAppClick)
                SecondaryLabButton(
                    "Fetch messages",
                    model::fetchInAppMessages,
                    icon = Icons.Outlined.Refresh,
                )
            }
        }
        item {
            LabPanel("Triggers", "${model.inAppTriggers.size} set") {
                DataRows(model.inAppTriggers, "No triggers in this session", model::removeInAppTrigger)
                SecondaryLabButton("Add trigger", { triggerEditorOpen = true }, icon = Icons.Outlined.Add)
                SecondaryLabButton(
                    "Multiple triggers",
                    { multiEditorOpen = true },
                    icon = Icons.Outlined.Code,
                )
                if (model.inAppTriggers.isNotEmpty()) {
                    SecondaryLabButton(
                        "Remove several",
                        { removeSeveralOpen = true },
                        icon = Icons.Outlined.DeleteSweep,
                    )
                    DestructiveLabButton("Clear triggers", model::clearInAppTriggers)
                }
                Text(
                    "Every trigger change re-evaluates the whole definition list, so a message " +
                        "whose moment has come is shown at once. A value can be a string or a " +
                        "number, and a comma-separated value is a list for the in operator.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            LabPanel("Demo document", "no workspace needed") {
                Text(
                    "Shows the bundled assets/iam_demo.html through the shipping presenter: the " +
                        "same WebView and its hardening, the same JavaScript bridge, the same " +
                        "layout rules, the same link handling. Nothing is recorded — no receipt " +
                        "is posted and no display state is written — so it is safe to open as " +
                        "often as you like.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryLabButton("Show the demo document", model::previewInAppDocument)
                Text(
                    "Got it posts an action_taken that closes the message; Open link posts one " +
                        "carrying a url with url_target = browser.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            LabPanel("What the engine reports", "listener contract") {
                TelemetryRow("Lifecycle", "willDisplay · didDisplay · willDismiss · didDismiss")
                Hairline()
                TelemetryRow("Impression", "on the document's rendering_complete, not on show")
                Hairline()
                TelemetryRow("Click", "every action with an id; firstClick is per message")
                Hairline()
                TelemetryRow("Handles", "UUIDs; this screen removes both in close()")
                Text(
                    "Listener events land in the event history on the Events workspace.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (triggerEditorOpen) {
        KeyValueEditorSheet(
            title = "Add trigger",
            keyLabel = "Key",
            keyPlaceholder = "level",
            valueLabel = "Value",
            valuePlaceholder = "12",
            onDismiss = { triggerEditorOpen = false },
            onSave = { key, value ->
                model.addInAppTrigger(key, value)
                triggerEditorOpen = false
            },
        )
    }

    if (multiEditorOpen) {
        MultiTriggerEditorSheet(
            onDismiss = { multiEditorOpen = false },
            onSave = { values ->
                model.addInAppTriggers(values)
                multiEditorOpen = false
            },
        )
    }

    if (removeSeveralOpen) {
        KeyValueEditorSheet(
            title = "Remove triggers",
            keyLabel = "key",
            keyPlaceholder = "plan",
            valueLabel = "value",
            valuePlaceholder = "unused",
            multiRow = true,
            keysOnly = true,
            onDismiss = { removeSeveralOpen = false },
            onSave = { _, _ -> },
            onRemoveSeveral = { keys ->
                model.removeInAppTriggers(keys)
                removeSeveralOpen = false
            },
        )
    }
}

@Composable
fun EventsScreen(model: ExampleLabModel, modifier: Modifier = Modifier) {
    var eventName by rememberSaveable { mutableStateOf("checkout_completed") }
    var eventProperties by rememberSaveable {
        mutableStateOf("{\n  \"value\": 42,\n  \"currency\": \"USD\"\n}")
    }
    FeatureScreen(modifier) {
        item {
            LabIntro(
                "Observe the SDK lifecycle as permission, subscription, foreground, click, identity, " +
                    "and data events arrive.",
                model.mode,
            )
        }
        item {
            LabPanel("Custom event", "durable SDK queue") {
                OutlinedTextField(
                    value = eventName,
                    onValueChange = { eventName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    placeholder = { Text("checkout_completed") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                )
                OutlinedTextField(
                    value = eventProperties,
                    onValueChange = { eventProperties = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Properties JSON") },
                    placeholder = { Text("{\"value\": 42}") },
                    minLines = 4,
                    shape = RoundedCornerShape(8.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                PrimaryLabButton(
                    "Send",
                    { model.trackEvent(eventName, eventProperties) },
                    enabled = eventName.isNotBlank() && eventProperties.isNotBlank(),
                )
                Text(
                    "Properties must be a JSON object no larger than 2,048 bytes. The SDK persists " +
                        "Live-mode delivery across retries.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            LabPanel("Lifecycle observers", "live stream") {
                LabSwitchRow(
                    title = "Prevent foreground display",
                    detail = "The listener calls preventDefault() while this process has a visible Activity.",
                    checked = model.preventForegroundPresentation,
                    onCheckedChange = { model.preventForegroundPresentation = it },
                )
                PrimaryLabButton("Preview a foreground event", model::previewForegroundEvent)
                SecondaryLabButton("Preview a click event", model::previewClickEvent)
            }
        }
        item {
            LabPanel("Event history", "${model.events.size} retained") {
                if (model.events.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("No events yet", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Use the controls above or another workspace to produce one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    model.events.forEachIndexed { index, event ->
                        EventRow(event)
                        if (index != model.events.lastIndex) Hairline()
                    }
                    SecondaryLabButton("Clear event history", model::clearEvents, icon = Icons.Outlined.ClearAll)
                }
            }
        }
    }
}

@Composable
fun ActivityScreen(model: ExampleLabModel, modifier: Modifier = Modifier) {
    FeatureScreen(modifier) {
        item {
            LabIntro(
                "Render the same Android Live Update payload lifecycle the messaging service handles " +
                    "for remote delivery.",
                model.mode,
            )
        }
        item {
            MetricGrid(
                listOf(
                    Metric(
                        "Capability",
                        model.liveUpdateCapabilityLabel,
                        "device + promotion setting",
                        model.liveUpdateActive,
                    ),
                    Metric(
                        "Lifecycle",
                        if (model.liveUpdateActive) "Active" else "Idle",
                        "instance delivery-feature-lab",
                        model.liveUpdateActive,
                    ),
                ),
            )
        }
        item {
            LabPanel("Delivery Live Update", "native notification") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "DELIVERY",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                if (model.liveUpdateActive) "LIVE" else "PREVIEW",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            model.liveUpdateStage,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "delivery-feature-lab",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            progress = { model.liveUpdateProgress / 100f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Text(
                            "${model.liveUpdateProgress}%",
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (model.liveUpdateActive) {
                    SecondaryLabButton("Update Live Update", model::updateLiveUpdate)
                    DestructiveLabButton("End Live Update", model::endLiveUpdate)
                } else {
                    PrimaryLabButton("Start Live Update", model::startLiveUpdate)
                }
                if (OpenPushLiveUpdates.capability(androidx.compose.ui.platform.LocalContext.current) ==
                    OpenPushLiveUpdates.Capability.PROMOTION_DISABLED
                ) {
                    SecondaryLabButton(
                        "Open promoted notification settings",
                        model::openLiveUpdateSettings,
                        icon = Icons.Outlined.OpenInNew,
                    )
                }
            }
        }
        item {
            LabPanel("Payload lifecycle", "start · update · end") {
                TelemetryRow("Instance", "delivery-feature-lab")
                Hairline()
                TelemetryRow("Sequence", if (model.liveUpdateActive) "monotonic" else "not started")
                Hairline()
                TelemetryRow("Fallback", "quiet ongoing notification")
                Text(
                    "Android 16 promotion remains system-controlled. Older releases update one ongoing " +
                        "notification in place; ending removes it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureLabSettings(model: ExampleLabModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Feature Lab",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            SettingsSection("Runtime") {
                ChoiceSegments(
                    values = RuntimeMode.entries,
                    selected = model.mode,
                    label = RuntimeMode::label,
                    enabled = { it != RuntimeMode.LIVE || model.liveAvailable },
                    onSelect = model::selectMode,
                )
                if (!model.liveAvailable) {
                    Text(
                        "Add App ID and SDK key, then relaunch to unlock Live mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SettingsSection("Appearance") {
                ChoiceSegments(
                    values = AppAppearance.entries,
                    selected = model.appearance,
                    label = AppAppearance::label,
                    onSelect = model::selectAppearance,
                )
            }
            SettingsSection("Configuration") {
                TelemetryRow("App ID", if (model.liveAvailable) ExampleConfiguration.APP_ID else "Not configured")
                Hairline()
                TelemetryRow("SDK key", if (model.liveAvailable) "Configured" else "Not configured")
                Hairline()
                TelemetryRow("Device credential", if (model.deviceCredentialAvailable) "Stored" else "Not stored")
                Hairline()
                TelemetryRow("Host consent", model.consentStatusLabel)
                Hairline()
                TelemetryRow("SDK startup", model.sdkStartupLabel)
                Hairline()
                TelemetryRow("SDK", OpenPush.sdkVersion)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyValueEditorSheet(
    title: String,
    keyLabel: String,
    keyPlaceholder: String,
    valueLabel: String,
    valuePlaceholder: String,
    multiRow: Boolean = false,
    keysOnly: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onSaveSeveral: (Map<String, String>) -> Unit = {},
    onRemoveSeveral: (List<String>) -> Unit = {},
) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var rows by remember {
        mutableStateOf(
            when {
                keysOnly -> "$keyPlaceholder\nexample_key"
                multiRow -> "$keyPlaceholder=$valuePlaceholder\nexample_key=example_value"
                else -> ""
            },
        )
    }
    val parsedPairs = rows.lines().mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        val parsedKey = line.substring(0, separator).trim()
        val parsedValue = line.substring(separator + 1).trim()
        if (parsedKey.isEmpty() || parsedValue.isEmpty()) null else parsedKey to parsedValue
    }.toMap()
    val parsedKeys = rows.lines().map(String::trim).filter(String::isNotEmpty).distinct()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
            if (multiRow) {
                OutlinedTextField(
                    rows,
                    { rows = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (keysOnly) "One $keyLabel per line" else "One $keyLabel=$valueLabel per line") },
                    placeholder = { Text(if (keysOnly) keyPlaceholder else "$keyPlaceholder=$valuePlaceholder") },
                    minLines = 4,
                    shape = RoundedCornerShape(8.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                PrimaryLabButton(
                    if (keysOnly) "Remove" else "Add",
                    {
                        if (keysOnly) onRemoveSeveral(parsedKeys) else onSaveSeveral(parsedPairs)
                    },
                    enabled = if (keysOnly) parsedKeys.isNotEmpty() else parsedPairs.isNotEmpty(),
                )
            } else {
                OutlinedTextField(
                    key,
                    { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(keyLabel) },
                    placeholder = { Text(keyPlaceholder) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                )
                OutlinedTextField(
                    value,
                    { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(valueLabel) },
                    placeholder = { Text(valuePlaceholder) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                )
                PrimaryLabButton("Add", { onSave(key, value) }, enabled = key.isNotBlank() && value.isNotBlank())
            }
        }
    }
}

/**
 * The one-call trigger form. `addTriggers(map)` re-evaluates once for the whole map rather than
 * once per key, so this sheet takes several pairs at a time to make that difference visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiTriggerEditorSheet(onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    var text by remember { mutableStateOf("plan=pro\nonboarded=true") }
    val parsed = text.lines()
        .mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) return@mapNotNull null
            val key = line.substring(0, index).trim()
            val value = line.substring(index + 1).trim()
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }
        .toMap()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Multiple triggers",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
            OutlinedTextField(
                text,
                { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("One key=value per line") },
                placeholder = { Text("plan=pro") },
                minLines = 3,
                shape = RoundedCornerShape(8.dp),
            )
            Text(
                if (parsed.isEmpty()) {
                    "Nothing to set yet."
                } else {
                    "Sets ${parsed.size} trigger${if (parsed.size == 1) "" else "s"} in one call: " +
                        parsed.keys.joinToString(", ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryLabButton("Set triggers", { onSave(parsed) }, enabled = parsed.isNotEmpty())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomNotificationEditorSheet(onDismiss: () -> Unit, onPost: (CustomNotificationDraft) -> Unit) {
    var title by rememberSaveable { mutableStateOf(CustomNotificationDraft.Example.title) }
    var description by rememberSaveable { mutableStateOf(CustomNotificationDraft.Example.description) }
    var dataKey by rememberSaveable { mutableStateOf(CustomNotificationDraft.Example.dataKey) }
    var dataValue by rememberSaveable { mutableStateOf(CustomNotificationDraft.Example.dataValue) }
    val draft = CustomNotificationDraft(title, description, dataKey, dataValue)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Custom notification",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
            Text(
                "Notification",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(CustomNotificationDraft.TITLE_LIMIT) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title") },
                supportingText = { Text("${title.length}/${CustomNotificationDraft.TITLE_LIMIT}") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(CustomNotificationDraft.DESCRIPTION_LIMIT) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Description") },
                supportingText = { Text("${description.length}/${CustomNotificationDraft.DESCRIPTION_LIMIT}") },
                minLines = 3,
                maxLines = 5,
                shape = RoundedCornerShape(8.dp),
            )
            Text(
                "Custom data",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = dataKey,
                onValueChange = { dataKey = it.take(CustomNotificationDraft.DATA_KEY_LIMIT) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Data key") },
                supportingText = {
                    if (CustomNotificationDraft.isReservedDataKey(dataKey)) {
                        Text("Use an app-defined key; OpenPush reserves this name.")
                    }
                },
                isError = CustomNotificationDraft.isReservedDataKey(dataKey),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
            )
            OutlinedTextField(
                value = dataValue,
                onValueChange = { dataValue = it.take(CustomNotificationDraft.DATA_VALUE_LIMIT) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Data value") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
            )
            Text(
                "This key-value pair is included in the app-defined payload delivered with the notification.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryLabButton(
                "Post notification",
                onClick = { onPost(draft.normalized()) },
                enabled = draft.isValid,
            )
        }
    }
}

@Composable
private fun FeatureScreen(modifier: Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().widthIn(max = 940.dp).align(Alignment.TopCenter),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 18.dp,
                bottom = 36.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}

@Composable
private fun EventRow(event: LabEvent) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EventIcon(event.kind)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    event.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            event.detail.lineSequence().forEach { field ->
                Text(
                    field,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceSegments(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        values.forEachIndexed { index, value ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                enabled = enabled(value),
                shape = SegmentedButtonDefaults.itemShape(index, values.size),
                label = { Text(label(value)) },
            )
        }
    }
}

@Composable
private fun notificationIcon(example: NotificationExample): ImageVector = when (example) {
    NotificationExample.SIMPLE -> Icons.Outlined.Notifications
    NotificationExample.IMAGE -> Icons.Outlined.Photo
    NotificationExample.BUTTON -> Icons.Outlined.SmartButton
    NotificationExample.CUSTOM -> Icons.Outlined.Code
    NotificationExample.SOUND -> Icons.Outlined.VolumeUp
    NotificationExample.GROUPED -> Icons.Outlined.Notifications
}

@Composable
private fun LabSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

@Composable
private fun warningColor(): Color = MaterialTheme.colorScheme.tertiary
