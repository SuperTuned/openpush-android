package ai.openpush.android.onesignal

import ai.openpush.android.ClickEvent
import ai.openpush.android.ForegroundEvent
import ai.openpush.android.IdentityResult
import ai.openpush.android.ListenerHandle
import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushInternalApi
import ai.openpush.android.OpenPushMainThread
import ai.openpush.android.inappmessages.InAppMessageClickEvent
import ai.openpush.android.inappmessages.InAppMessageLifecycleEvent
import android.app.Activity
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class PushSubscriptionState(val id: String, val token: String, val optedIn: Boolean)

data class PushSubscriptionChange(val previous: PushSubscriptionState?, val current: PushSubscriptionState)

// The core SDK already delivers observers, permission changes and clicks on the main thread, through
// OpenPushMainThread. These run inline there, so the facade adds no second hop and no reordering; the
// guard only matters for a call that somehow arrives off the main thread.
@OptIn(OpenPushInternalApi::class)
private fun onMain(block: () -> Unit) = OpenPushMainThread.run(block)

// Foreground listeners stay on the push-delivery thread in the core SDK. The facade alone waits,
// bounded, for the main thread, so a migrated listener that touches UI keeps working.
@OptIn(OpenPushInternalApi::class)
private fun onMainBlocking(block: () -> Unit) {
    if (OpenPushMainThread.isCurrent) {
        block()
        return
    }
    val completed = CountDownLatch(1)
    val executed = AtomicBoolean()
    val runnable = Runnable {
        try {
            if (executed.compareAndSet(false, true)) block()
        } finally {
            completed.countDown()
        }
    }
    if (!OpenPushMainThread.post(runnable::run) || !completed.await(5L, TimeUnit.SECONDS)) runnable.run()
}

private fun ai.openpush.android.PushSubscriptionState.compat() =
    PushSubscriptionState(id.orEmpty(), token.orEmpty(), optedIn)

/**
 * Drop-in OneSignal v5 name alias for OpenPush. Change `import com.onesignal.OneSignal`
 * to `import ai.openpush.android.onesignal.*` and existing call sites keep compiling —
 * except v5's listeners and observers, which are lambdas returning a `ListenerHandle`
 * here rather than conforming objects, so those call sites are a type error until you
 * rewrite them (see MIGRATING-FROM-ONESIGNAL.md §1.1).
 *
 * Import the package, not just this class: the refused v5 members in
 * `OneSignalUnavailable.kt` are extension declarations, and a Kotlin extension resolves
 * only where it is in scope. Under a single-class import they fail as bare
 * `unresolved reference` errors instead of quoting their replacement.
 *
 * Names only — no behavior, no state, no OneSignal implementation code. The package
 * differs from `com.onesignal`, so this object can coexist with the real SDK during a
 * staged migration.
 */
object OneSignal {

    const val DEFAULT_SERVER_URL = OpenPush.DEFAULT_SERVER_URL

    /**
     * Keeps the v5 call signature. The SDK key comes from manifest meta-data
     * (`ai.openpush.sdk_key`), so an existing OneSignal call site compiles unchanged.
     */
    @JvmStatic
    fun initWithContext(context: Context, appId: String) = OpenPush.initialize(context, appId)

    @JvmStatic
    @JvmOverloads
    fun initialize(
        context: Context,
        appId: String,
        sdkKey: String,
        serverURL: String = DEFAULT_SERVER_URL,
        credential: () -> String? = { null },
        transport: (suspend (OpenPush.Request) -> OpenPush.Response)? = null,
    ) = OpenPush.initialize(context, appId, sdkKey, serverURL, credential, transport)

    /** Java entry point for setup with the public SDK key in manifest meta-data. */
    @JvmStatic
    fun initialize(context: Context, appId: String) = OpenPush.initialize(context, appId)

    /**
     * The app-id-only form with OpenPush's extras; the SDK key comes from manifest meta-data.
     * Kotlin-only; name the arguments: `initialize(context, appId, serverURL = …)`. A third positional
     * string is the SDK key and goes to the explicit-key overload above.
     */
    @JvmStatic
    @JvmSynthetic
    fun initialize(
        context: Context,
        appId: String,
        credential: () -> String? = { null },
        serverURL: String = DEFAULT_SERVER_URL,
        transport: (suspend (OpenPush.Request) -> OpenPush.Response)? = null,
    ) = OpenPush.initialize(context, appId, credential = credential, serverURL = serverURL, transport = transport)

    /** The earlier parameter order, kept so Kotlin code compiled against it still links. */
    @Deprecated("Name the server URL: initialize(context, appId, serverURL = …).", level = DeprecationLevel.HIDDEN)
    @JvmStatic
    @JvmSynthetic
    fun initialize(
        context: Context,
        appId: String,
        serverURL: String = DEFAULT_SERVER_URL,
        credential: () -> String? = { null },
        transport: (suspend (OpenPush.Request) -> OpenPush.Response)? = null,
    ) = OpenPush.initialize(context, appId, credential = credential, serverURL = serverURL, transport = transport)

    @JvmStatic
    val isInitialized: Boolean get() = OpenPush.isInitialized

    @JvmStatic
    val sdkVersion: String get() = OpenPush.sdkVersion

    @JvmStatic
    @JvmOverloads
    fun login(externalId: String, authHash: String? = null, callback: ((IdentityResult) -> Unit)? = null) =
        OpenPush.login(externalId, authHash, callback)

    @JvmStatic
    @JvmOverloads
    suspend fun loginSuspend(externalId: String, authHash: String? = null): IdentityResult =
        OpenPush.loginSuspend(externalId, authHash)

    @JvmStatic
    @JvmOverloads
    fun logout(callback: ((IdentityResult) -> Unit)? = null) = OpenPush.logout(callback)

    @JvmStatic
    suspend fun logoutSuspend(): IdentityResult = OpenPush.logoutSuspend()

    @JvmStatic
    fun register(token: String) = OpenPush.register(token)

    /** `OpenPush.register(token, externalId)`: a new external id is logged in first, without an auth hash. */
    @Deprecated(
        "Attach an identity with login(externalId, authHash) and register the token with register(token).",
        ReplaceWith("register(token)"),
    )
    @JvmStatic
    @Suppress("DEPRECATION")
    fun register(token: String, externalId: String?) = OpenPush.register(token, externalId)

    object User {
        @JvmStatic
        fun addTag(key: String, value: String) = OpenPush.User.addTag(key, value)

        @JvmStatic
        fun addTags(tags: Map<String, String>) = OpenPush.User.addTags(tags)

        @JvmStatic
        fun removeTag(key: String) = OpenPush.User.removeTag(key)

        @JvmStatic
        fun removeTags(keys: Collection<String>) = OpenPush.User.removeTags(keys)

        @JvmStatic
        fun getTags(): Map<String, String> = OpenPush.User.getTags()

        @JvmStatic
        @JvmOverloads
        @Deprecated(
            "OpenPush custom-event names must match [A-Za-z][A-Za-z0-9_.-]{0,127}; " +
                "properties must be JSON-safe and serialize to at most 2048 bytes.",
            level = DeprecationLevel.WARNING,
        )
        fun trackEvent(name: String, properties: Map<String, Any?>? = null) =
            OpenPush.trackEvent(name, properties.orEmpty())

        @JvmStatic
        fun addAlias(label: String, id: String) = OpenPush.User.addAlias(label, id)

        @JvmStatic
        fun addAliases(aliases: Map<String, String>) = OpenPush.User.addAliases(aliases)

        @JvmStatic
        fun removeAlias(label: String) = OpenPush.User.removeAlias(label)

        @JvmStatic
        fun removeAliases(labels: Collection<String>) = OpenPush.User.removeAliases(labels)

        /**
         * Forwards to [OpenPush.User.addSms]. Separators are removed and `00` is read as `+`; a number
         * that is still not `+` and 8 to 15 digits returns false
         * and sends nothing. It stays not verified until a code from `OpenPush.Verify` is approved.
         */
        @JvmStatic
        fun addSms(sms: String): Boolean = OpenPush.User.addSms(sms)

        /** Forwards to [OpenPush.User.removeSms]: the number is opted out (status -2). */
        @JvmStatic
        fun removeSms(sms: String): Boolean = OpenPush.User.removeSms(sms)

        /**
         * Forwards to [OpenPush.User.addEmail]: the address is subscribed at once, unless this app's
         * suppression list has it. An address that is not one `@`, a name and a domain with a dot
         * returns false and sends nothing.
         */
        @JvmStatic
        fun addEmail(email: String): Boolean = OpenPush.User.addEmail(email)

        /** Forwards to [OpenPush.User.removeEmail]: the subscription is removed; it is not an unsubscribe. */
        @JvmStatic
        fun removeEmail(email: String): Boolean = OpenPush.User.removeEmail(email)

        @JvmStatic
        val externalId: String get() = OpenPush.User.externalId.orEmpty()

        @JvmStatic
        val pushSubscription: PushSubscription get() = PushSubscription
    }

    /** v5's merged shape: accessors, controls and the observer on one object. */
    object PushSubscription {
        @JvmStatic
        val id: String get() = OpenPush.User.pushSubscription.id.orEmpty()

        @JvmStatic
        val token: String get() = OpenPush.User.pushSubscription.token.orEmpty()

        @JvmStatic
        val optedIn: Boolean get() = OpenPush.User.pushSubscription.optedIn

        @JvmStatic
        fun optIn() = OpenPush.PushSubscription.optIn()

        @JvmStatic
        fun optIn(activity: Activity) = OpenPush.PushSubscription.optIn(activity)

        @JvmStatic
        fun optOut() = OpenPush.PushSubscription.optOut()

        @JvmStatic
        fun addObserver(observer: (PushSubscriptionState) -> Unit): ListenerHandle =
            OpenPush.PushSubscription.addObserver { state -> onMain { observer(state.compat()) } }

        @JvmStatic
        fun removeObserver(handle: ListenerHandle) = OpenPush.PushSubscription.removeObserver(handle)

        @JvmStatic
        fun addChangeObserver(observer: (PushSubscriptionChange) -> Unit): ListenerHandle =
            OpenPush.PushSubscription.addChangeObserver { change ->
                onMain {
                    observer(
                        PushSubscriptionChange(
                            change.previous?.compat(),
                            change.current.compat(),
                        ),
                    )
                }
            }

        @JvmStatic
        fun removeChangeObserver(handle: ListenerHandle) = OpenPush.PushSubscription.removeChangeObserver(handle)

        @JvmStatic
        fun snapshot(): PushSubscriptionState = OpenPush.User.pushSubscription.snapshot().compat()
    }

    object Notifications {
        const val PERMISSION_REQUEST_CODE = OpenPush.Notifications.PERMISSION_REQUEST_CODE

        @JvmStatic
        val permission: Boolean get() = OpenPush.Notifications.permission

        @JvmStatic
        val canRequestPermission: Boolean get() = OpenPush.Notifications.canRequestPermission

        /**
         * v5's suspend form: `val granted = requestPermission(fallbackToSettings)` inside a coroutine.
         * It resumes with the answer from OpenPush's permission Activity; the host forwards nothing.
         * Called with no Activity resumed yet, it suspends until the prompt that starts at the next
         * Activity resume is answered. Like the core suspend form it resumes in the caller's coroutine
         * context. Outside a coroutine, pass a callback to the form below.
         */
        @JvmStatic
        @JvmOverloads
        suspend fun requestPermission(fallbackToSettings: Boolean = false): Boolean =
            OpenPush.Notifications.requestPermission(fallbackToSettings)

        @JvmStatic
        @JvmOverloads
        fun requestPermission(fallbackToSettings: Boolean = false, callback: ((Boolean) -> Unit)? = null) =
            OpenPush.Notifications.requestPermission(fallbackToSettings, callback)

        @JvmStatic
        @JvmOverloads
        fun requestPermission(
            activity: Activity,
            fallbackToSettings: Boolean = false,
            callback: ((Boolean) -> Unit)? = null,
        ) = OpenPush.Notifications.requestPermission(activity, fallbackToSettings, callback)

        @JvmStatic
        fun onPermissionResult(requestCode: Int, grantResults: IntArray) =
            OpenPush.Notifications.onPermissionResult(requestCode, grantResults)

        @JvmStatic
        fun addPermissionObserver(observer: (Boolean) -> Unit): ListenerHandle =
            OpenPush.Notifications.addPermissionObserver { granted -> onMain { observer(granted) } }

        @JvmStatic
        fun removePermissionObserver(handle: ListenerHandle) = OpenPush.Notifications.removePermissionObserver(handle)

        @JvmStatic
        fun addClickListener(listener: (ClickEvent) -> Unit): ListenerHandle =
            OpenPush.Notifications.addClickListener { event -> onMain { listener(event) } }

        @JvmStatic
        fun removeClickListener(handle: ListenerHandle) = OpenPush.Notifications.removeClickListener(handle)

        @JvmStatic
        fun clearAllNotifications() = OpenPush.Notifications.clearAll()

        @JvmStatic
        fun removeNotification(id: Int) = OpenPush.Notifications.remove(id)

        @JvmStatic
        fun removeGroupedNotifications(group: String) = OpenPush.Notifications.removeGroup(group)

        @JvmStatic
        fun addForegroundLifecycleListener(listener: (ForegroundEvent) -> Unit): ListenerHandle =
            OpenPush.Notifications.addForegroundLifecycleListener { event -> onMainBlocking { listener(event) } }

        @JvmStatic
        fun removeForegroundLifecycleListener(handle: ListenerHandle) =
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)

        @JvmStatic
        fun beginReceived(data: Map<String, String>): Boolean = OpenPush.Notifications.beginReceived(data)

        @JvmStatic
        @JvmOverloads
        fun finishReceived(data: Map<String, String>, handled: Boolean = true) =
            OpenPush.Notifications.finishReceived(data, handled)

        @JvmStatic
        fun processForegroundMessage(data: Map<String, String>): Boolean =
            OpenPush.Notifications.processForegroundMessage(data)

        @JvmStatic
        fun processDisplayed(data: Map<String, String>) = OpenPush.Notifications.processDisplayed(data)

        @JvmStatic
        fun processClick(data: Map<String, String>) = OpenPush.Notifications.processClick(data)
    }

    object InAppMessages {
        @JvmStatic
        @Deprecated(
            "OpenPush pause hides and requeues the current message without emitting dismissal " +
                "lifecycle callbacks or consuming redisplay state.",
            level = DeprecationLevel.WARNING,
        )
        var paused: Boolean
            get() = OneSignalInAppMessages.paused
            set(value) {
                OneSignalInAppMessages.paused = value
            }

        @JvmStatic
        fun addTrigger(key: String, value: String) = OneSignalInAppMessages.addTrigger(key, value)

        @JvmStatic
        fun addTriggers(values: Map<String, Any>) = OneSignalInAppMessages.addTriggers(values)

        @JvmStatic
        fun removeTrigger(key: String) = OneSignalInAppMessages.removeTrigger(key)

        @JvmStatic
        fun removeTriggers(keys: Collection<String>) = OneSignalInAppMessages.removeTriggers(keys)

        @JvmStatic
        fun clearTriggers() = OneSignalInAppMessages.clearTriggers()

        @JvmStatic
        fun addLifecycleListener(listener: (InAppMessageLifecycleEvent) -> Unit) =
            OneSignalInAppMessages.addLifecycleListener(listener)

        @JvmStatic
        fun removeLifecycleListener(id: java.util.UUID) = OneSignalInAppMessages.removeLifecycleListener(id)

        @JvmStatic
        fun addClickListener(listener: (InAppMessageClickEvent) -> Unit) =
            OneSignalInAppMessages.addClickListener(listener)

        @JvmStatic
        fun removeClickListener(id: java.util.UUID) = OneSignalInAppMessages.removeClickListener(id)
    }

    object Session {
        @JvmStatic
        @Deprecated(
            "OpenPush records a custom event without notification-influence attribution.",
            level = DeprecationLevel.WARNING,
        )
        fun addOutcome(name: String) = OneSignalSession.addOutcome(name)

        @JvmStatic
        @Deprecated(
            "OpenPush records an installation-scoped idempotent custom event without " +
                "notification-influence attribution.",
            level = DeprecationLevel.WARNING,
        )
        fun addUniqueOutcome(name: String) = OneSignalSession.addUniqueOutcome(name)

        @JvmStatic
        @Deprecated(
            "OpenPush records a valued custom event without notification-influence attribution.",
            level = DeprecationLevel.WARNING,
        )
        fun addOutcomeWithValue(name: String, value: Float) = OneSignalSession.addOutcomeWithValue(name, value)
    }
}
