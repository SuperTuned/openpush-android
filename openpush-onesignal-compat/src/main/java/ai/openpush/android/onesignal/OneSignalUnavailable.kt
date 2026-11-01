package ai.openpush.android.onesignal

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushBridge
import ai.openpush.android.OpenPushInternalApi
import ai.openpush.android.inappmessages.InAppMessageClickEvent
import ai.openpush.android.inappmessages.InAppMessageLifecycleEvent
import ai.openpush.android.inappmessages.OpenPushInAppMessages
import android.content.Context
import java.util.UUID

// Every OneSignal v5 symbol OpenPush does not implement is declared here at
// DeprecationLevel.ERROR, so a migrating host learns the whole gap from one build
// with the replacement named at the call site. Nothing here runs.

// NOTE: `initWithContext(context, appId)` is NOT refused — Task A0 made it work by
// moving the SDK key into manifest meta-data. It is implemented in OneSignal.kt.
// Do not add a refusal for it here.

@Deprecated(
    message =
    "OpenPush initialization is synchronous. Call OneSignal.initWithContext(context, appId); " +
        "use OpenPush.awaitRegistration() only when you need to wait for registration.",
    level = DeprecationLevel.ERROR,
)
suspend fun OneSignal.initWithContextSuspend(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") appId: String? = null,
): Nothing = throw UnsupportedOperationException()

@Deprecated(
    message = "Use the non-suspend OneSignal.User member.",
    level = DeprecationLevel.ERROR,
)
suspend fun OneSignal.getUserSuspend(): Nothing = throw UnsupportedOperationException()

@Deprecated(
    message = "Use the non-suspend OneSignal.Session member.",
    level = DeprecationLevel.ERROR,
)
suspend fun OneSignal.getSessionSuspend(): Nothing = throw UnsupportedOperationException()

@Deprecated(
    message = "Use the non-suspend OneSignal.Notifications member.",
    level = DeprecationLevel.ERROR,
)
suspend fun OneSignal.getNotificationsSuspend(): Nothing = throw UnsupportedOperationException()

@Deprecated(
    message = "Use the non-suspend OneSignal.Location member.",
    level = DeprecationLevel.ERROR,
)
suspend fun OneSignal.getLocationSuspend(): Nothing = throw UnsupportedOperationException()

@Deprecated(
    message = "Use the non-suspend OneSignal.InAppMessages member.",
    level = DeprecationLevel.ERROR,
)
suspend fun OneSignal.getInAppMessagesSuspend(): Nothing = throw UnsupportedOperationException()

@Deprecated(
    message = "Use the non-suspend OneSignal.consentRequired member.",
    level = DeprecationLevel.ERROR,
)
suspend fun OneSignal.getConsentRequiredSuspend(): Nothing = throw UnsupportedOperationException()

@Deprecated(
    message = "Use the non-suspend OneSignal.consentRequired member.",
    level = DeprecationLevel.ERROR,
)
suspend fun OneSignal.setConsentRequiredSuspend(@Suppress("UNUSED_PARAMETER") required: Boolean): Nothing =
    throw UnsupportedOperationException()

@Deprecated(
    message = "Use the non-suspend OneSignal.consentGiven member.",
    level = DeprecationLevel.ERROR,
)
suspend fun OneSignal.getConsentGivenSuspend(): Nothing = throw UnsupportedOperationException()

@Deprecated(
    message = "Use the non-suspend OneSignal.consentGiven member.",
    level = DeprecationLevel.ERROR,
)
suspend fun OneSignal.setConsentGivenSuspend(@Suppress("UNUSED_PARAMETER") value: Boolean): Nothing =
    throw UnsupportedOperationException()

@Deprecated(
    message = "Use the non-suspend OneSignal.disableGMSMissingPrompt member.",
    level = DeprecationLevel.ERROR,
)
suspend fun OneSignal.getDisableGMSMissingPromptSuspend(): Nothing = throw UnsupportedOperationException()

@Deprecated(
    message = "Use the non-suspend OneSignal.disableGMSMissingPrompt member.",
    level = DeprecationLevel.ERROR,
)
suspend fun OneSignal.setDisableGMSMissingPromptSuspend(@Suppress("UNUSED_PARAMETER") value: Boolean): Nothing =
    throw UnsupportedOperationException()

@Deprecated(
    message =
    "OpenPush verifies identity with login(externalId, authHash); re-supply it from " +
        "OpenPush.onIdentityVerificationRequired.",
    level = DeprecationLevel.ERROR,
)
fun OneSignal.updateUserJwt(
    @Suppress("UNUSED_PARAMETER") externalId: String,
    @Suppress("UNUSED_PARAMETER") token: String,
): Nothing = throw UnsupportedOperationException()

@Deprecated(
    message =
    "OpenPush verifies identity with loginSuspend(externalId, authHash); re-supply it from " +
        "OpenPush.onIdentityVerificationRequired.",
    level = DeprecationLevel.ERROR,
)
suspend fun OneSignal.updateUserJwtSuspend(
    @Suppress("UNUSED_PARAMETER") externalId: String,
    @Suppress("UNUSED_PARAMETER") token: String,
): Nothing = throw UnsupportedOperationException()

@Deprecated(
    message =
    "OpenPush verifies identity with login(externalId, authHash); re-supply it from " +
        "OpenPush.onIdentityVerificationRequired.",
    level = DeprecationLevel.ERROR,
)
fun OneSignal.addUserJwtInvalidatedListener(@Suppress("UNUSED_PARAMETER") listener: Any): Nothing =
    throw UnsupportedOperationException()

@Deprecated(
    message =
    "OpenPush verifies identity with login(externalId, authHash); re-supply it from " +
        "OpenPush.onIdentityVerificationRequired.",
    level = DeprecationLevel.ERROR,
)
fun OneSignal.removeUserJwtInvalidatedListener(@Suppress("UNUSED_PARAMETER") listener: Any): Nothing =
    throw UnsupportedOperationException()

@Deprecated(
    message = "OpenPush shows no dialogs.",
    level = DeprecationLevel.ERROR,
)
var OneSignal.disableGMSMissingPrompt: Boolean
    get() = throw UnsupportedOperationException()
    set(@Suppress("UNUSED_PARAMETER") value) = throw UnsupportedOperationException()

@Deprecated(
    message =
    "OpenPush has no consent gate. Gate your own call to OneSignal.initialize(...) instead — " +
        "until you call it, nothing is collected and no request is made; to stop after initialize, " +
        "call optOut() and logout(); a full stop requires not initializing.",
    level = DeprecationLevel.ERROR,
)
var OneSignal.consentRequired: Boolean
    get() = throw UnsupportedOperationException()
    set(@Suppress("UNUSED_PARAMETER") value) = throw UnsupportedOperationException()

@Deprecated(
    message =
    "OpenPush has no consent gate. Call OneSignal.initialize(...) once the user has consented; " +
        "to stop after initialize, call optOut() and logout(); a full stop requires not initializing.",
    level = DeprecationLevel.ERROR,
)
var OneSignal.consentGiven: Boolean
    get() = throw UnsupportedOperationException()
    set(@Suppress("UNUSED_PARAMETER") value) = throw UnsupportedOperationException()

// Kotlin cannot add a nested `object` to `OneSignal` from another file, so the four
// v5 groups OpenPush does not implement are declared below as top-level objects. The
// four extension properties that follow them exist so that the *v5 spelling* —
// `OneSignal.Debug`, `OneSignal.InAppMessages`, `OneSignal.Session`,
// `OneSignal.Location` — resolves to the matching object instead of failing as a bare
// unresolved reference. The member access then fires that member's
// DeprecationLevel.ERROR message, which is the whole point: a migrating host reads the
// replacement off the compile error at the untouched v5 call site. The properties
// themselves are not deprecated — they carry no message and reaching one alone is
// harmless; only their members refuse.

object OneSignalDebug {
    @JvmStatic
    @Deprecated(
        message = "OpenPush has one diagnostic sink instead of log levels: set OpenPush.onLog and filter yourself.",
        level = DeprecationLevel.ERROR,
    )
    var logLevel: Int
        get() = throw UnsupportedOperationException()
        set(@Suppress("UNUSED_PARAMETER") value) = throw UnsupportedOperationException()

    @JvmStatic
    @Deprecated(
        message =
        "OpenPush has one diagnostic sink instead of alert levels: set OpenPush.onLog and " +
            "present alerts yourself.",
        level = DeprecationLevel.ERROR,
    )
    var alertLevel: Int
        get() = throw UnsupportedOperationException()
        set(@Suppress("UNUSED_PARAMETER") value) = throw UnsupportedOperationException()

    @JvmStatic
    @Deprecated(
        message =
        "OpenPush has one diagnostic sink instead of log listeners: set OpenPush.onLog and " +
            "compose multiple listeners yourself.",
        level = DeprecationLevel.ERROR,
    )
    fun addLogListener(@Suppress("UNUSED_PARAMETER") listener: Any): Nothing = throw UnsupportedOperationException()

    @JvmStatic
    @Deprecated(
        message =
        "OpenPush has one diagnostic sink instead of log listeners: set OpenPush.onLog and " +
            "compose multiple listeners yourself.",
        level = DeprecationLevel.ERROR,
    )
    fun removeLogListener(@Suppress("UNUSED_PARAMETER") listener: Any): Nothing = throw UnsupportedOperationException()
}

object OneSignalInAppMessages {
    @JvmStatic
    @Deprecated(
        message =
        "OpenPush pause hides and requeues the current message without emitting dismissal " +
            "lifecycle callbacks or consuming redisplay state.",
        level = DeprecationLevel.WARNING,
    )
    var paused: Boolean
        get() = OpenPushInAppMessages.paused
        set(value) {
            OpenPushInAppMessages.paused = value
        }

    @JvmStatic
    fun addTrigger(key: String, value: String) = OpenPushInAppMessages.addTrigger(key, value)

    @JvmStatic
    fun addTriggers(values: Map<String, Any>) = OpenPushInAppMessages.addTriggers(values)

    @JvmStatic
    fun removeTrigger(key: String) = OpenPushInAppMessages.removeTrigger(key)

    @JvmStatic
    fun removeTriggers(keys: Collection<String>) = OpenPushInAppMessages.removeTriggers(keys)

    @JvmStatic
    fun clearTriggers() = OpenPushInAppMessages.clearTriggers()

    // v5 hands a listener object back and takes it back by reference; OpenPush hands back a UUID
    // and takes that. The handle is the only difference — keep the returned value and pass it to
    // the matching remove call.
    @JvmStatic
    fun addLifecycleListener(listener: (InAppMessageLifecycleEvent) -> Unit): UUID =
        OpenPushInAppMessages.addLifecycleListener(listener)

    @JvmStatic
    fun removeLifecycleListener(id: UUID) = OpenPushInAppMessages.removeLifecycleListener(id)

    @JvmStatic
    fun addClickListener(listener: (InAppMessageClickEvent) -> Unit): UUID =
        OpenPushInAppMessages.addClickListener(listener)

    @JvmStatic
    fun removeClickListener(id: UUID) = OpenPushInAppMessages.removeClickListener(id)
}

object OneSignalSession {
    @JvmStatic
    @Deprecated(
        message = "OpenPush records a custom event without notification-influence attribution.",
        level = DeprecationLevel.WARNING,
    )
    fun addOutcome(name: String) = OpenPush.trackEvent(name)

    /**
     * Sends [name] once per device however many times it is called: the event carries a
     * device-scoped idempotency key, so the server keeps the first and collapses every repeat into
     * it. Nothing is deduplicated on the device, so the call is safe from any thread and survives a
     * relaunch — the key is derived, not remembered.
     *
     * See [uniqueOutcomeKey] for the scope the key is built from.
     */
    @JvmStatic
    @Deprecated(
        message =
        "OpenPush records an installation-scoped idempotent custom event without " +
            "notification-influence attribution.",
        level = DeprecationLevel.WARNING,
    )
    fun addUniqueOutcome(name: String) = OpenPush.trackEvent(name, emptyMap(), uniqueOutcomeKey(name))

    /**
     * Sends [name] with the number under the property `value`.
     *
     * The metric convention this writes, and the one to configure on the server: `tracking = value`
     * with `value_property = "value"`. A metric set up any other way will count the events and miss
     * the numbers.
     */
    @JvmStatic
    @Deprecated(
        message = "OpenPush records a valued custom event without notification-influence attribution.",
        level = DeprecationLevel.WARNING,
    )
    fun addOutcomeWithValue(name: String, value: Float) = OpenPush.trackEvent(name, mapOf("value" to value))
}

/**
 * `unique:<name>:<scope>` — what makes a unique outcome unique.
 *
 * The scope is a persistent random installation id. It does not change when a provider token is
 * rotated or a subscription id first becomes available.
 */
internal fun uniqueOutcomeKey(name: String): String = "unique:$name:${uniqueOutcomeScope()}"

@OptIn(OpenPushInternalApi::class)
private fun uniqueOutcomeScope(): String {
    val context = OpenPushBridge.context
    if (context == null) {
        return synchronized(uniqueOutcomeScopeLock) {
            uniqueOutcomeScopeInProcess ?: UUID.randomUUID().toString().also { uniqueOutcomeScopeInProcess = it }
        }
    }
    return synchronized(uniqueOutcomeScopeLock) {
        val preferences = context.getSharedPreferences("openpush-onesignal-compat", Context.MODE_PRIVATE)
        val scope =
            preferences.getString("unique_outcome_scope", null)
                ?: uniqueOutcomeScopeInProcess
                ?: UUID.randomUUID().toString()
        preferences.edit().putString("unique_outcome_scope", scope).apply()
        scope.also { uniqueOutcomeScopeInProcess = it }
    }
}

private val uniqueOutcomeScopeLock = Any()

@Volatile private var uniqueOutcomeScopeInProcess: String? = null

object OneSignalLocation {
    @JvmStatic
    @Deprecated(
        message =
        "OpenPush never collects location. Send a tag or alias you compute yourself if you " +
            "need geo targeting.",
        level = DeprecationLevel.ERROR,
    )
    var isShared: Boolean
        get() = throw UnsupportedOperationException()
        set(@Suppress("UNUSED_PARAMETER") value) = throw UnsupportedOperationException()

    @JvmStatic
    @Deprecated(
        message =
        "OpenPush never collects location. Send a tag or alias you compute yourself if you " +
            "need geo targeting.",
        level = DeprecationLevel.ERROR,
    )
    fun requestPermission(): Nothing = throw UnsupportedOperationException()
}

/** Resolves v5's `OneSignal.Debug.…`; every member of it refuses at compile time. */
val OneSignal.Debug: OneSignalDebug get() = OneSignalDebug

/** Resolves v5's `OneSignal.Location.…`; every member of it refuses at compile time. */
val OneSignal.Location: OneSignalLocation get() = OneSignalLocation

@Deprecated(
    message =
    "The user id exists server-side and will be returned in a later server release. Until then " +
        "use OneSignal.User.externalId, or OneSignal.User.pushSubscription.id for this installation.",
    level = DeprecationLevel.ERROR,
)
val OneSignal.User.onesignalId: String?
    get() = throw UnsupportedOperationException()

@Deprecated(
    message =
    "OpenPush sends the primary language subtag, remaps iw to he, in to id, and ji to yi, and " +
        "preserves the region only for zh; language cannot be overridden.",
    level = DeprecationLevel.ERROR,
)
fun OneSignal.User.setLanguage(@Suppress("UNUSED_PARAMETER") value: String): Nothing =
    throw UnsupportedOperationException()

// NOTE: `User.addSms` / `removeSms` and `User.addEmail` / `removeEmail` are NOT
// refused — they forward to OpenPush.User in OneSignal.kt. Do not add refusals for them here.

@Deprecated(
    message =
    "OpenPush has no user-state observer. Observe the subscription instead: " +
        "OneSignal.User.pushSubscription.addObserver { state -> … }, which reports id, token and optedIn.",
    level = DeprecationLevel.ERROR,
)
fun OneSignal.User.addObserver(observer: (Any) -> Unit): Nothing = throw UnsupportedOperationException()

@Deprecated(
    message =
    "OpenPush has no user-state observer. Observe the subscription instead: " +
        "OneSignal.User.pushSubscription.addObserver { state -> … }, which reports id, token and optedIn.",
    level = DeprecationLevel.ERROR,
)
fun OneSignal.User.removeObserver(@Suppress("UNUSED_PARAMETER") observer: Any): Nothing =
    throw UnsupportedOperationException()

/** Retains the earlier extension's JVM symbol; source callers resolve the member on Notifications. */
@Deprecated("Use the Notifications member.", level = DeprecationLevel.HIDDEN)
fun OneSignal.Notifications.clearAllNotifications(): Nothing {
    clearAllNotifications()
    error("unreachable compatibility bridge")
}

/** Retains the earlier extension's JVM symbol; source callers resolve the member on Notifications. */
@Deprecated("Use the Notifications member.", level = DeprecationLevel.HIDDEN)
fun OneSignal.Notifications.removeNotification(id: Int): Nothing {
    removeNotification(id)
    error("unreachable compatibility bridge")
}

/** Retains the earlier extension's JVM symbol; source callers resolve the member on Notifications. */
@Deprecated("Use the Notifications member.", level = DeprecationLevel.HIDDEN)
fun OneSignal.Notifications.removeGroupedNotifications(group: String): Nothing {
    removeGroupedNotifications(group)
    error("unreachable compatibility bridge")
}
