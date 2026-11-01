// The SDK object is where the internal seams below are declared and driven from.
@file:OptIn(OpenPushInternalApi::class)

package ai.openpush.android

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

object OpenPush {
    /** Server used when [initialize] is called without an explicit `serverURL`. */
    const val DEFAULT_SERVER_URL = OpenPushConfiguration.DEFAULT_SERVER_URL

    /** Manifest meta-data names, following the ai.openpush.* convention the
     *  notification renderer already uses for its channel id and icon. */
    const val META_SDK_KEY = "ai.openpush.sdk_key"
    const val META_APP_ID = "ai.openpush.app_id"

    /** Manifest meta-data naming an [OpenPushBackgroundMessageHandler] class. */
    const val META_BACKGROUND_MESSAGE_HANDLER = "ai.openpush.background_message_handler"

    /**
     * Extra the notification renderer puts on an action button's intent, and the key
     * [ClickEvent.actionId] is read from. A host with its own renderer must write the same key for
     * its action buttons, or a click on one arrives with a null `actionId`.
     */
    const val ACTION_ID_KEY = "op_action_id"

    /**
     * Set to `"true"` in a click's data when the SDK already opened the push's launch URL, or the
     * tapped button's own `url`.
     */
    const val LAUNCH_URL_OPENED_KEY = "op_launch_url_opened"

    /** How many clicks are held for a listener that has not registered yet; the oldest is dropped. */
    internal const val PENDING_CLICK_LIMIT = 10

    /** The FCM click trampoline is never a host Activity that can present SDK UI. Kept as a class
     *  name so the core module does not depend on the optional FCM module. */
    internal const val CLICK_TRAMPOLINE = "ai.openpush.android.fcm.OpenPushClickActivity"

    /** Names this SDK and its version on every request it makes: `android/<sdkVersion>`. */
    const val X_OP_SDK = "X-OP-SDK"

    /** Names the wrapper SDK a host actually calls, when there is one. See [sdkWrapper]. */
    const val X_OP_SDK_WRAPPER = "X-OP-SDK-Wrapper"

    /** Socket tag for every request the default transport makes, so a host profiling its app can
     *  tell push traffic apart from its own in the data-usage breakdown. "OP" in ASCII. */
    internal const val SOCKET_TAG = 0x4F50

    data class Request(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    /**
     * One transport response. [headers] carries the response headers with **lower-cased names** —
     * the default transport lower-cases them, and [retryAfterMs] reads them case-insensitively so a
     * host transport that does not is still understood.
     *
     * This is written out rather than declared as a three-property data class to keep the
     * two-argument constructor and `copy(status, body)` JVM descriptors of a two-property data
     * class. Adding headers to that primary constructor would silently replace both descriptors and
     * break host transports built against them. The explicit surface keeps those descriptors and
     * adds the header-aware overload alongside them.
     */
    class Response @JvmOverloads constructor(
        val status: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
    ) {
        operator fun component1(): Int = status
        operator fun component2(): String = body
        operator fun component3(): Map<String, String> = headers

        /** The two-argument copy; existing headers remain attached. */
        fun copy(status: Int = this.status, body: String = this.body): Response = Response(status, body, headers)

        /** Header-aware copy, alongside the two-argument descriptor. */
        fun copy(status: Int = this.status, body: String = this.body, headers: Map<String, String>): Response =
            Response(status, body, headers)

        override fun equals(other: Any?): Boolean = other is Response &&
            status == other.status &&
            body == other.body &&
            headers == other.headers

        override fun hashCode(): Int = 31 * (31 * status + body.hashCode()) + headers.hashCode()

        override fun toString(): String = "Response(status=$status, body=$body, headers=$headers)"
    }

    /** Receives every SDK log line, on the thread that wrote it. Unlike the other hooks it is not
     *  moved to the main thread, so keep it cheap. */
    @JvmStatic
    var onLog: (String) -> Unit = ::defaultLogSink

    /** Fires on the main thread each time a registration is accepted. */
    @JvmStatic
    var onRegistered: (() -> Unit)? = null

    /** Fires on the main thread with the reason when a registration finally fails. */
    @JvmStatic
    var onRegistrationFailed: ((String) -> Unit)? = null

    // Suspend registration observers are separate from the host-owned callback properties above.
    // Replacing either callback here would make awaitRegistration steal an outcome from the host.
    private val registrationWaiterLock = Any()
    private val registrationWaiters = mutableSetOf<CancellableContinuation<Boolean>>()
    private var registrationAcceptedInProcess = false

    /** Fires once per process, on the main thread, with the external id when a verified identity needs
     *  its hash re-supplied after a relaunch. Call login(externalId, authHash) again from the hook. */
    @JvmStatic
    var onIdentityVerificationRequired: ((String) -> Unit)? = null
        set(value) {
            field = value
            deliverPendingVerification()
        }

    /** Fires on the main thread with the server's note whenever a registration comes back refusing this
     *  device's external identity — including one sent by a tag or alias flush rather than by [login].
     *  The device stays registered anonymously, so re-run [login] once the host can supply a valid
     *  auth hash. */
    @JvmStatic
    var onIdentityRejected: ((String) -> Unit)? = null

    @Volatile
    @JvmStatic
    var currentToken: String? = null
        private set

    /**
     * Fetches the provider token so [register] happens without the host forwarding it. The optional
     * FCM module installs one at process start; `null` leaves token forwarding to the host.
     */
    @Volatile
    @JvmStatic
    var tokenProvider: OpenPushTokenProvider? = null

    private val tokenRequestInFlight = AtomicBoolean(false)

    /** The provider token this workspace already handed to [register], so it is not sent twice. */
    @Volatile
    private var providerTokenRegistered: String? = null

    /** True once [initialize] or [attach] has run in this process. */
    @JvmStatic
    val isInitialized: Boolean get() = configuration != null

    /** SDK version sourced from the module's Gradle version. */
    @JvmStatic
    val sdkVersion: String get() = BuildConfig.OPENPUSH_VERSION

    /**
     * Set by a wrapper SDK that sits on top of this one — `"unity/1.2.2"`, for example — before it
     * calls [initialize]. Every request then carries [X_OP_SDK_WRAPPER] alongside [X_OP_SDK], so the
     * server sees both the native SDK doing the work and the surface the host actually wrote against.
     * Left null by an app integrating this SDK directly, and the header is then not sent at all.
     */
    @Volatile
    @JvmStatic
    var sdkWrapper: String? = null

    /** Identifier returned by the most recent accepted registration. */
    @Volatile
    internal var subscriptionId: String? = null
        private set

    @Volatile private var configuration: OpenPushConfiguration? = null

    @Volatile private var credential: () -> String? = { null }

    @Volatile private var transport: suspend (Request) -> Response = ::defaultTransport

    @Volatile internal var httpConnectionFactory: (URL) -> HttpURLConnection = ::openHttpConnection

    @Volatile private var appContext: Context? = null

    @Volatile private var appVersion: String? = null

    /** Where the previous push SDK's token and subscription id are read from. Replaced by tests. */
    @Volatile internal var priorInstallSource: (Context) -> PriorInstallSource = ::SharedPreferencesPriorInstallSource

    // The prior install still owed to the server, read at most once per process. Guarded by
    // priorInstallLock.
    private val priorInstallLock = Any()
    private var priorInstallLoaded = false
    private var priorInstall: PriorInstall? = null
    private val preferencesFailureLock = Any()
    private var preferencesFailureLogged = false

    @Volatile private var pendingExternalId: String? = null

    @Volatile private var pendingAuthHash: String? = null

    @Volatile private var identityDetached = false

    @Volatile private var authHashRequired = false

    // True once a registration that carried the current external_id came back 2xx without an
    // identity_rejected note. Until then the identity is only staged, so a repeated login must still
    // be sent: "already logged in" has to mean the server agrees, not that the SDK asked once.
    @Volatile private var identityDelivered = false

    private val pendingVerificationRequest = AtomicReference<String?>(null)

    @Volatile private var verificationRequested = false

    // Android notification_types: 1 when permitted, 0 for the whole not-granted
    // space, and -2 for the retained user opt-out.
    private const val STATUS_SUBSCRIBED = 1
    internal const val STATUS_NO_PERMISSION = 0
    private const val STATUS_OPTED_OUT = -2

    @Volatile private var notificationTypes = STATUS_NO_PERMISSION

    @Volatile private var optedOut = false

    @Volatile private var lastAcceptedStatus: Int? = null
    private val tags = linkedMapOf<String, String>()
    private val aliases = linkedMapOf<String, String>()

    // Receipts owed to the server, persisted so a process death cannot lose one. Rebuilt by every
    // attach that is not a repeat, which is what binds the queue to the workspace it belongs to.
    @Volatile private var receiptQueue: ReceiptQueue? = null
    private val receiptStateLock = Any()

    /** One atomically published receipt workspace. A call records against this exact queue. */
    private data class ReceiptBinding(
        val session: Long,
        val configuration: OpenPushConfiguration,
        val queue: ReceiptQueue,
        val credential: () -> String?,
    )

    /** Immutable context retained while a serialized drain crosses a network suspension. */
    private data class ReceiptDrainTarget(
        val session: Long,
        val configuration: OpenPushConfiguration,
        val queue: ReceiptQueue,
        val credential: () -> String?,
    )

    @Volatile private var receiptBinding: ReceiptBinding? = null

    // Receipts recorded before any attach, persisted through the Application the startup initializer
    // hands over. Replaced by resetForTests, which is how a test simulates a new process.
    @Volatile private var pendingReceipts = newPendingReceipts()

    /** Receipts waiting for an attach with a token. */
    internal fun stagedReceiptCount(): Int = pendingReceipts.size
    internal const val APP_SESSION_MIN_INTERVAL_MS = 30L * 60L * 1000L
    internal const val MAX_SESSION_DURATION_MS = 86_400L * 1000L

    @Volatile private var lastAppSessionAt = 0L
    private fun systemTimeMs(): Long = System.currentTimeMillis()
    internal var nowMs: () -> Long = ::systemTimeMs

    // A duration report keeps its key until the server accepts or refuses exactly the seconds sent
    // under that key. Time accumulated while the request is in flight becomes a new report.
    private val sessionDurationLock = Any()
    private var foregroundStartedAt: Long? = null
    private var pendingSessionDurationMs = 0L
    private var pendingSessionDurationKey: String? = null
    private var sessionDurationSendMarker: Any? = null

    @Volatile private var lastDrainAt: Long? = null

    // Guards the identity triple so a registration reads one consistent snapshot of it. The public
    // identity calls write it synchronously, before dispatching, so two consecutive calls cannot
    // reorder their effect on the session.
    private val sessionLock = Any()

    private val scope = sdkScope("OpenPush")

    /** Cancellation signal for work serialized onto [registrationQueue]. */
    private class DrainOperation {
        private val lock = Any()
        private val transportJobs = mutableSetOf<Job>()
        val cancelled = CompletableDeferred<Unit>()

        @Volatile var isCancelled = false
            private set

        fun cancel() {
            val jobs = synchronized(lock) {
                if (isCancelled) return
                isCancelled = true
                transportJobs.toList().also { transportJobs.clear() }
            }
            // A cooperative host transport stops with its Job. The default transport also has a
            // continuation cancellation hook that disconnects its HttpURLConnection. A blocking
            // custom transport may ignore both, so [cancelled] separately lets the serialized
            // caller detach and unwind without waiting for that host code to return.
            jobs.forEach(Job::cancel)
            cancelled.complete(Unit)
        }

        fun own(job: Job): Boolean {
            val accepted = synchronized(lock) {
                if (isCancelled) false else transportJobs.add(job)
            }
            if (!accepted) job.cancel()
            return accepted
        }

        fun release(job: Job) {
            synchronized(lock) { transportJobs.remove(job) }
        }
    }

    // Every call that ends in a registration POST is queued here and drained by one consumer, one at a
    // time, in the order the app made the calls. `Dispatchers.IO` is multi-threaded, so two `launch`es
    // give no ordering guarantee at all: `login("user-1")` followed by `logout()` could reach the server
    // as logout-then-login and leave the device attached to the account the user just signed out of.
    // Neither half is sufficient alone — a bare mutex still gets locked in whatever order the pool
    // happens to wake the coroutines in, and a single-threaded dispatcher lets the next coroutine start
    // the moment the current one suspends inside the network hop. One queue, one consumer, does both.
    private val registrationQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (work in registrationQueue) {
                runCatching { work() }.onFailure { onLog("a queued OpenPush call threw — ${it.message}") }
            }
        }
    }

    /**
     * The identity a subscription write may assert, with its auth hash: (null, null) while anonymous,
     * and also while identity verification needs a hash this device does not hold, since an unhashed
     * assertion would be refused where leaving it out keeps the server's existing link.
     */
    internal fun identityClaim(): Pair<String?, String?> = synchronized(sessionLock) {
        val identity = pendingExternalId?.takeIf(String::isNotEmpty)
        if (identity == null ||
            (authHashRequired && pendingAuthHash == null)
        ) {
            null to null
        } else {
            identity to pendingAuthHash
        }
    }

    internal fun enqueue(work: suspend () -> Unit) {
        registrationQueue.trySend(work)
    }

    internal fun interface Cancellable {
        fun cancel()
    }

    internal interface Scheduler {
        fun schedule(delayMillis: Long, action: () -> Unit): Cancellable
    }

    internal object RealScheduler : Scheduler {
        override fun schedule(delayMillis: Long, action: () -> Unit): Cancellable {
            val job = scope.launch {
                delay(delayMillis)
                action()
            }
            return Cancellable { job.cancel() }
        }
    }

    internal var scheduler: Scheduler = RealScheduler
    internal const val COALESCE_WINDOW_MS = 300L
    internal const val SESSION_PERSIST_WINDOW_MS = 50L
    private val windowLock = Any()
    private var windowTimer: Cancellable? = null
    private var windowNote: String? = null
    private val sessionPersistenceLock = Any()
    private var sessionPersistTimer: Cancellable? = null
    private var sessionPersistPending = false
    internal const val MAX_REGISTRATION_ATTEMPTS = 3
    internal val RETRY_DELAYS_MS = longArrayOf(2_000L, 8_000L)

    /**
     * Spread added to every retry delay. Without it a server that drops a million devices at once
     * gets all of them back in the same millisecond, twice, and the recovery is what knocks it over
     * again. The argument is the delay being spread, so a future curve can scale with it.
     */
    internal fun defaultJitter(@Suppress("UNUSED_PARAMETER") delayMillis: Long): Long = Random.nextLong(0, 1_001)

    /** Seam: tests pin this to a constant so a retry lands on a millisecond they can name. */
    internal var jitter: (Long) -> Long = ::defaultJitter

    @Volatile private var registrationGeneration = 0L

    @Volatile private var registrationSession = 0L
    private val retryLock = Any()
    private var retryTimer: Cancellable? = null
    private var pendingRegistrationGeneration: Long? = null
    private val customEventLock = Any()
    private val customEvents = ArrayDeque<JSONObject>()
    private val stagedCustomEvents = mutableListOf<JSONObject>()
    private var customEventHead = 0

    @Volatile private var customEventReady = false
    private var customEventTimer: Cancellable? = null
    private var customEventRetry: Cancellable? = null
    private var customEventGeneration = 0L

    // A14. The queue is bounded so a long offline stretch cannot grow SharedPreferences without limit,
    // and the newest events win: a Journey branches on what the user just did.
    internal const val CUSTOM_EVENT_QUEUE_CAP = 500
    internal const val STAGED_CUSTOM_EVENT_CAP = 50

    // A failing flush backs off and then holds at the last step, so a server that is down for an hour
    // is polled twice a minute rather than every two seconds.
    internal val CUSTOM_EVENT_RETRY_DELAYS_MS = longArrayOf(2_000L, 8_000L, 30_000L)

    @Volatile internal var customEventAttempt = 0

    /** Overflow is a host mistake, not an event: once per process is enough. */
    @Volatile private var customEventCapWarned = false

    @Volatile private var stagedCustomEventCapWarned = false

    // What the server accepts. Enforced here so a write it would refuse is named on the host's own
    // log line instead of vanishing into a 2xx the host cannot read anything out of.
    internal const val TAG_KEY_LIMIT = 60
    internal const val TAG_VALUE_LIMIT = 300
    internal const val ALIAS_LIMIT = 10

    // labels the server reserves
    internal val RESERVED_ALIAS_LABELS = setOf("external_id", "onesignal_id")

    /** The over-cap warning is a configuration mistake, not an event: once per process is enough. */
    @Volatile private var aliasCapWarned = false

    private const val TAGS_DEFERRED = "tags stored — they ride the next registration"
    private const val ALIASES_DEFERRED = "aliases stored — they ride the next registration"
    private const val STATUS_DEFERRED = "subscription status stored — it rides the next registration"
    private const val LOGIN_DEFERRED = "login stored — identity deferred until a provider token is registered"
    private const val LOGOUT_DEFERRED = "logout stored — no provider token to detach yet"

    @Volatile private var hasAttached = false

    /** What a call to [attach] means for the state this process already holds. */
    internal enum class AttachKind { FIRST, REPEATED, WORKSPACE_SWITCH }

    internal fun attachKindFor(new: OpenPushConfiguration): AttachKind = when {
        !hasAttached -> AttachKind.FIRST
        configuration == new -> AttachKind.REPEATED
        else -> AttachKind.WORKSPACE_SWITCH
    }

    @Volatile internal var currentActivity: WeakReference<Activity>? = null
    private var foregroundWatcher: ForegroundWatcher? = null
    private var foregroundApplication: Application? = null

    /** Started-Activity count, owned here rather than inside [ForegroundWatcher]
     *  so [isAppForegrounded] can read it. [ForegroundWatcher] maintains it; [attach] and
     *  [resetForTests] reset it to zero when they install a fresh watcher.
     *
     *  The increment/decrement is a non-atomic read-modify-write, which is safe because only the
     *  watcher mutates it and Android delivers Activity lifecycle callbacks on the main thread
     *  alone. `@Volatile` is for the *readers* — [isAppForegrounded] is called from the FCM
     *  messaging-service thread. A host that managed to call `initialize` off the main thread could
     *  in principle race the reset with a callback; the `currentActivity` clause of
     *  [isAppForegrounded] heals that, since a resumed Activity answers independently of the count. */
    @Volatile private var startedActivityCount = 0

    /** True when this process actually has a visible Activity.
     *  - No Application context: no lifecycle to observe — trust the host (the
     *    attach log already told it to call onAppForegrounded() itself).
     *  - A started Activity is the real answer.
     *  - A resumed Activity covers the attach-from-an-already-started-Activity
     *    window (the watcher registered after that Activity's onActivityStarted).
     *    This clause also carries the Activity [attach] adopted, which may never have been
     *    resumed at all — so it is retired by onActivityPaused, onActivityStopped *and*
     *    onActivityDestroyed, and the stop clear is the only one that can retire an adopted
     *    Activity that went straight from onStart to onStop. See the adoption site in [attach]
     *    for the one window this arm cannot close. */
    internal val isAppForegrounded: Boolean
        get() = foregroundApplication == null ||
            startedActivityCount > 0 ||
            currentActivity?.get() != null

    /**
     * Forgets that this process ever attached, and clears everything an [attach] clears. The SDK is a
     * process singleton, so the tests covering the first-attach rule have no other way to reach the
     * never-attached state twice in one JVM. Not part of the published surface.
     */
    @JvmStatic
    @JvmName("resetForTests")
    internal fun resetForTests() {
        absorbPendingWrites()
        supersedeRegistrationSession()
        hasAttached = false
        synchronized(sessionPersistenceLock) {
            discardPendingSessionPersistenceLocked()
            configuration = null
            appContext = null
        }
        httpConnectionFactory = ::openHttpConnection
        tokenRequestInFlight.set(false)
        providerTokenRegistered = null
        ReceivedMessages.resetForTests()
        synchronized(preferencesFailureLock) { preferencesFailureLogged = false }
        synchronized(receiptStateLock) {
            receiptBinding?.queue?.retire()
            receiptBinding = null
            receiptQueue = null
            currentToken = null
            pendingReceipts = newPendingReceipts()
        }
        subscriptionId = null
        notificationTypes = STATUS_NO_PERMISSION
        optedOut = false
        lastAcceptedStatus = null
        val abandonedRegistrationWaiters = synchronized(registrationWaiterLock) {
            registrationAcceptedInProcess = false
            registrationWaiters.toList().also { registrationWaiters.clear() }
        }
        abandonedRegistrationWaiters.forEach { continuation ->
            if (continuation.isActive) continuation.resume(false)
        }
        lastAppSessionAt = 0L
        synchronized(sessionDurationLock) {
            foregroundStartedAt = null
            pendingSessionDurationMs = 0L
            pendingSessionDurationKey = null
            sessionDurationSendMarker = null
        }
        lastDrainAt = null
        nowMs = ::systemTimeMs
        jitter = ::defaultJitter
        sdkWrapper = null
        priorInstallSource = ::SharedPreferencesPriorInstallSource
        synchronized(priorInstallLock) {
            priorInstallLoaded = false
            priorInstall = null
        }
        synchronized(sessionLock) {
            pendingExternalId = null
            pendingAuthHash = null
            identityDetached = false
            authHashRequired = false
            identityDelivered = false
        }
        pendingVerificationRequest.set(null)
        verificationRequested = false
        aliasCapWarned = false
        synchronized(tags) { tags.clear() }
        synchronized(aliases) { aliases.clear() }
        synchronized(customEventLock) {
            customEventTimer?.cancel()
            customEventRetry?.cancel()
            customEventTimer = null
            customEventRetry = null
            customEvents.clear()
            stagedCustomEvents.clear()
            customEventHead = 0
            customEventReady = false
            customEventGeneration++
        }
        customEventAttempt = 0
        customEventCapWarned = false
        stagedCustomEventCapWarned = false
        cachedPermission = false
        canRequestPermissionWithoutActivityWarned = false
        synchronized(permissionRequestLock) {
            permissionCallbacks.clear()
            permissionRequestInFlight = false
            permissionRequestDeferred = false
            deferredPermissionFallbackToSettings = false
            deferredPermissionCallbacks.clear()
        }
        PermissionPromptWatch.reset()
        synchronized(permissionSettingsLock) {
            permissionSettingsCallbacks.clear()
            awaitingPermissionSettings = false
        }
        currentActivity = null
        startedActivityCount = 0
        // Forget the watcher too, or a stale Application from an earlier test keeps
        // isAppForegrounded answering for a process that has since re-attached.
        foregroundWatcher?.let { foregroundApplication?.unregisterActivityLifecycleCallbacks(it) }
        foregroundWatcher = null
        foregroundApplication = null
        synchronized(observerLock) {
            lastObservedPermission = null
            lastObservedState = null
        }
        synchronized(permissionObservers) { permissionObservers.clear() }
        synchronized(subscriptionObservers) { subscriptionObservers.clear() }
        synchronized(subscriptionChangeObservers) { subscriptionChangeObservers.clear() }
        synchronized(clickListeners) { pendingClicks.clear() }
        PhoneSubscriptions.resetForTests()
    }

    /**
     * Blocks until every registration queued before this call has finished, write-back included.
     * The queue has one consumer and drains in order, so a no-op put behind the work under test
     * runs only once that work is done — which a latch counted down inside the transport cannot
     * say, because it fires while the response is still being applied. Not part of the published
     * surface.
     */
    internal fun settleForTests(timeoutMs: Long = 5_000L) {
        val settled = CountDownLatch(1)
        enqueue { settled.countDown() }
        if (!settled.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw AssertionError("the OpenPush registration queue did not settle in ${timeoutMs}ms")
        }
    }

    /** Factory-reset helper for tests that do not want to exercise cold-start restoration. */
    internal fun clearPersistedStateForTests() {
        prefs()?.edit()?.clear()?.apply()
    }

    @JvmStatic
    @JvmOverloads
    fun attach(
        ctx: Context,
        configuration: OpenPushConfiguration,
        credential: () -> String?,
        transport: (suspend (Request) -> Response)? = null,
    ) {
        // A *later* attach with a *different* configuration replaces one workspace with another, and
        // session state must not carry across it. The first-ever attach is not a switch: tags, aliases,
        // an identity, a status or a custom event the host set before `initialize` were stored for the
        // first registration, and clearing them here would make that contract a lie. A repeated attach with
        // the very same configuration is not a switch either — hosts call `initialize` from more than
        // one entry point, and that must not cost the process its state.
        val kind = attachKindFor(configuration)
        val previousConfiguration = this.configuration
        // Superseded before the configuration is swapped, not inside the branches below: a
        // registration whose response is in flight takes the same lock to write itself back, so it
        // either finishes wholly against the old session or is discarded. There is no window in
        // which it could write between this swap and the restore further down.
        if (kind != AttachKind.REPEATED) {
            supersedeRegistrationSession()
            customEventReady = false
            providerTokenRegistered = null
        }
        synchronized(sessionPersistenceLock) {
            if (kind != AttachKind.REPEATED) discardPendingSessionPersistenceLocked()
            appContext = ctx.applicationContext
            this.configuration = configuration
        }
        this.credential = credential
        this.transport = transport ?: ::defaultTransport
        appVersion = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        hasAttached = true
        when (kind) {
            AttachKind.REPEATED -> {
                // Same workspace: keep every piece of local state, the open write window and any
                // pending retry. Only the seams above were refreshed.
                onLog("attach repeated with the same configuration — local state kept")
                synchronized(receiptStateLock) {
                    val queue = receiptQueue ?: newReceiptQueue(configuration).also { receiptQueue = it }
                    receiptBinding = ReceiptBinding(
                        registrationSession,
                        configuration,
                        queue,
                        this.credential,
                    )
                }
                customEventReady = true
            }
            AttachKind.WORKSPACE_SWITCH -> {
                clearCustomEventsLocked()
                // Receipts are owed by a subscription in the workspace being left behind; the new
                // workspace has never heard of those messages, so retire the old queue before
                // clearing it. A response already in flight can then release its memory claim but
                // can never write the old queue back after this clear.
                synchronized(receiptStateLock) {
                    receiptBinding?.queue?.retireAndClear()
                    receiptBinding = null
                    receiptQueue = null
                    currentToken = prefs()?.getString(configuration.tokenKey, null)
                }
                subscriptionId = null
                clearTransientLocked()
                previousConfiguration?.let(::clearPersistedSession)
                clearPersistedSession(configuration)
                synchronized(receiptStateLock) {
                    val queue = newReceiptQueue(configuration).also(ReceiptQueue::clear)
                    receiptQueue = queue
                    receiptBinding = ReceiptBinding(
                        registrationSession,
                        configuration,
                        queue,
                        this.credential,
                    )
                }
                mergeStagedCustomEvents(configuration)
            }
            AttachKind.FIRST -> {
                clearCustomEventsLocked()
                // Loads whatever the previous process left owed, so the drain below can post it.
                synchronized(receiptStateLock) {
                    val queue = newReceiptQueue(configuration)
                    currentToken = prefs()?.getString(configuration.tokenKey, null)
                    receiptQueue = queue
                    receiptBinding = ReceiptBinding(
                        registrationSession,
                        configuration,
                        queue,
                        this.credential,
                    )
                }
                subscriptionId = null
                restoreSession(configuration)
                restoreCustomEvents(configuration)
                mergeStagedCustomEvents(configuration)
                // Write the merged result back so values staged before initialize become authoritative.
                persistSession()
                flushPersistSession()
            }
        }
        val application = appContext as? Application
        if (application == null) {
            onLog(
                "this Context is not an Application, so OpenPush cannot see foreground and " +
                    "background transitions — call OpenPush.onAppForegrounded() from your own hook",
            )
        } else {
            observeLifecycle(application)
        }
        // Attaching from an Activity means that Activity is already up; the watcher registered too
        // late to have counted its onActivityStarted, so adopt it as the visible one. The adoption is
        // retired by onActivityPaused, onActivityStopped or onActivityDestroyed, whichever comes first.
        //
        // Residual window, by design: an Activity whose onStop *already happened* before this attach
        // cannot be recognised as stopped. Android exposes no public "is this Activity started?"
        // predicate, and both signals this class owns — the counter and the reference — are fed by
        // callbacks that have already fired by then, so there is nothing left to read. Attaching from
        // a stopped-but-undestroyed Activity therefore looks foregrounded until that Activity is
        // destroyed. See README "Notification events" for the host-side note.
        (ctx as? Activity)?.let { currentActivity = WeakReference(it) }
        // Application.onCreate can be entered solely because a data push started a background
        // process. Refresh attach-time state in that case without pretending an Activity became
        // visible. A watcher-observed or explicitly adopted Activity is a real foreground.
        // Neither refresh asks for a provider token: the request at the end of attach covers both.
        if (startedActivityCount > 0 || currentActivity?.get() != null) {
            refreshForegroundState(beginForegroundInterval = true, requestToken = false)
        } else {
            refreshAfterAttach()
        }
        onLog(
            "attach · server ${configuration.server} · app ${configuration.appID} · " +
                "platform ${configuration.platform}",
        )
        OpenPushBridge.notifyAttached()
        // Phone-number writes recorded offline or before this attach.
        PhoneSubscriptions.onAttach(configuration.appID, prefs())
        // Receipts recorded before this attach — a cold-start tap, a delivery to a killed app.
        if (pendingReceipts.size > 0) drainReceipts()
        // Every attach asks, registered or not: the token may have rotated while no process had
        // OpenPush attached to hear about it.
        requestProviderToken()
    }

    /** Asks the installed [tokenProvider] for the current token and registers it when it is new. */
    private fun requestProviderToken() {
        val provider = tokenProvider ?: return
        val context = appContext ?: return
        if (!tokenRequestInFlight.compareAndSet(false, true)) return
        val callback = object : OpenPushTokenCallback {
            private val answered = AtomicBoolean(false)

            override fun onToken(token: String) {
                if (!answered.compareAndSet(false, true)) return
                tokenRequestInFlight.set(false)
                when {
                    token.isBlank() -> onLog("token provider returned an empty token — ignored")
                    !isInitialized -> Unit
                    token == providerTokenRegistered -> Unit
                    token == currentToken && subscriptionId != null -> Unit
                    else -> {
                        providerTokenRegistered = token
                        onLog("provider token fetched — registering this device")
                        register(token)
                    }
                }
            }

            override fun onUnavailable(reason: String) {
                if (!answered.compareAndSet(false, true)) return
                tokenRequestInFlight.set(false)
                onLog("provider token unavailable — $reason; asking again on the next foreground")
            }
        }
        try {
            provider.requestToken(context, callback)
        } catch (error: Exception) {
            callback.onUnavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Installs the process-wide Activity watcher once for this [Application]. Hosts that remove the
     * merged AndroidX Startup provider must call this from their own process initializer.
     */
    @Synchronized
    @JvmStatic
    fun observeLifecycle(application: Application?) {
        if (application == null || foregroundApplication === application) return
        foregroundWatcher?.let { foregroundApplication?.unregisterActivityLifecycleCallbacks(it) }
        // A fresh watcher has seen no onActivityStarted yet, so its count starts from zero.
        startedActivityCount = 0
        foregroundWatcher = ForegroundWatcher().also(application::registerActivityLifecycleCallbacks)
        foregroundApplication = application
    }

    /** Drops the session state a workspace switch must not carry across. */
    private fun clearTransientLocked() {
        synchronized(sessionLock) {
            pendingExternalId = null
            pendingAuthHash = null
            identityDetached = false
            authHashRequired = false
            identityDelivered = false
        }
        pendingVerificationRequest.set(null)
        verificationRequested = false
        notificationTypes = STATUS_NO_PERMISSION
        optedOut = false
        lastAcceptedStatus = null
        lastAppSessionAt = 0L
        synchronized(sessionDurationLock) {
            foregroundStartedAt = null
            pendingSessionDurationMs = 0L
            pendingSessionDurationKey = null
            sessionDurationSendMarker = null
        }
        synchronized(tags) { tags.clear() }
        synchronized(aliases) { aliases.clear() }
        // A click held for a workspace that is no longer attached belongs to nobody.
        synchronized(clickListeners) { pendingClicks.clear() }
    }

    /** Cancels the custom-event timers and drops the in-memory queue. */
    private fun clearCustomEventsLocked() = synchronized(customEventLock) {
        customEventTimer?.cancel()
        customEventRetry?.cancel()
        customEventTimer = null
        customEventRetry = null
        customEvents.clear()
        customEventHead = 0
        customEventGeneration++
        customEventAttempt = 0
    }

    /** Recomputes attach-time state without opening a foreground-duration interval. */
    private fun refreshAfterAttach() = refreshForegroundState(beginForegroundInterval = false, requestToken = false)

    /** Recomputes permission and updates the subscription only when the accepted value changed. */
    @JvmStatic
    fun onAppForegrounded() = refreshForegroundState(beginForegroundInterval = true)

    /**
     * Picks up a permission answered outside this SDK — another plugin's runtime-permission dialog,
     * or a toggle in Settings — on the resume that follows it. Those dialogs pause and resume the
     * host Activity without stopping it, so no foreground transition would otherwise notice.
     */
    private fun refreshPermissionIfChanged() {
        if (!isInitialized || readPermission() == cachedPermission) return
        onLog("notification permission changed outside the SDK — refreshing this subscription")
        refreshForegroundState(beginForegroundInterval = false)
    }

    private fun refreshForegroundState(beginForegroundInterval: Boolean, requestToken: Boolean = true) {
        if (beginForegroundInterval) {
            OpenPushBridge.notifyForegrounded()
            val foregroundedAt = nowMs()
            synchronized(sessionDurationLock) {
                if (foregroundStartedAt == null) foregroundStartedAt = foregroundedAt
            }
        }
        val previous = wireStatus()
        refreshPermission()
        val status = wireStatus()
        notifySubscriptionObservers()
        if (status != lastAcceptedStatus) {
            if (currentToken == null) {
                onLog("notification_types $status held — no provider token yet, so it rides the next registration")
            } else {
                onLog("notification_types changed to $status (was $previous) — updating this subscription")
                enqueueCurrentRegistration(STATUS_DEFERRED)
            }
        }
        if (requestToken && (currentToken == null || subscriptionId == null)) requestProviderToken()
        maybeAppSessionPing()
        drainReceipts()
    }

    /** Sends one fire-once app-session ping at most every 30 minutes. */
    private fun maybeAppSessionPing() {
        val config = configuration ?: return
        val token = currentToken ?: return
        val observedAt = nowMs()
        if (lastAppSessionAt > 0L && observedAt - lastAppSessionAt < APP_SESSION_MIN_INTERVAL_MS) return
        lastAppSessionAt = observedAt
        persistSession()
        enqueue {
            val result = post(
                config,
                "/v1/apps/${config.appID}/sessions",
                OpenPushPayload.appSessionBody(token),
                credential(),
            )
            onLog("app-session ping → ${result.status}")
        }
    }

    private class ForegroundWatcher : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            PermissionPromptWatch.onStarted(activity)
            // The notification click trampoline is an SDK implementation detail, not a visible
            // host Activity. Counting it would manufacture a foreground/background session when a
            // notification wakes an otherwise background-only process.
            if (activity.javaClass.name == CLICK_TRAMPOLINE) return
            if (startedActivityCount++ == 0) onAppForegrounded()
        }

        override fun onActivityStopped(activity: Activity) {
            PermissionPromptWatch.onStopped(activity)
            if (activity.javaClass.name == CLICK_TRAMPOLINE) return
            // Clear here as well as in onActivityPaused: an Activity adopted by [attach] during its
            // own onStart/onRestart is never resumed, so onActivityPaused never fires for it and this
            // is the only callback that can retire the adoption before onActivityDestroyed.
            if (currentActivity?.get() === activity) currentActivity = null
            if (startedActivityCount > 0 && --startedActivityCount == 0) onAppBackgrounded()
        }

        override fun onActivityResumed(activity: Activity) {
            if (activity.javaClass.name == CLICK_TRAMPOLINE) return
            currentActivity = WeakReference(activity)
            PermissionPromptWatch.onResumed(activity)
            completePermissionSettingsRequest()
            refreshPermissionIfChanged()
            launchDeferredPermissionRequest(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            PermissionPromptWatch.onPaused(activity)
            if (currentActivity?.get() === activity) currentActivity = null
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivity?.get() === activity) currentActivity = null
        }

        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    /**
     * Attaches the SDK with the OpenPush-hosted defaults. Equivalent to [attach] with an
     * [OpenPushConfiguration] built from [appId] and [sdkKey]; [attach] remains the lower-level form.
     */
    /** Compatibility setup name. The public SDK key comes from manifest meta-data. */
    @JvmStatic
    fun initWithContext(context: Context, appId: String) = initialize(context, appId)

    private fun manifestValue(context: Context, name: String): String? = runCatching {
        context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.get(name)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Setup using the app id and public SDK key declared in manifest meta-data.
     */
    @JvmStatic
    fun initialize(context: Context) {
        val appId = manifestValue(context, META_APP_ID)
        val sdkKey = manifestValue(context, META_SDK_KEY)
        if (appId == null || sdkKey == null) {
            val note = "\u2717 initialize refused — add both OpenPush values to your manifest:\n" +
                "<meta-data android:name=\"$META_APP_ID\" android:value=\"your-app-id\" />\n" +
                "<meta-data android:name=\"$META_SDK_KEY\" android:value=\"pk_live_…\" />"
            onLog(note)
            completeRegistrationWaiters(false)
            notifyRegistrationFailed(note)
            return
        }
        initialize(context, appId, sdkKey)
    }

    /** Java entry point for setup with the public SDK key in manifest meta-data. */
    @JvmStatic
    fun initialize(context: Context, appId: String) = initialize(context, appId, serverURL = DEFAULT_SERVER_URL)

    /**
     * Setup with the public SDK key in manifest meta-data (`ai.openpush.sdk_key`), so the call needs
     * only the app id. Kotlin-only; name the arguments: `initialize(context, appId, serverURL = …)`.
     * A third positional string is the SDK key and goes to the explicit-key overload, because
     * [credential] comes before [serverURL] here; [transport] stays last for a trailing lambda.
     *
     * Refuses and stays unconfigured when no key can be found anywhere: an SDK that
     * is half-configured never registers and never says why, which is worse than one
     * that plainly refused at launch.
     */
    @JvmStatic
    @JvmSynthetic
    fun initialize(
        context: Context,
        appId: String,
        credential: () -> String? = { null },
        serverURL: String = DEFAULT_SERVER_URL,
        transport: (suspend (Request) -> Response)? = null,
    ) = initializeWithManifestKey(context, appId, serverURL, credential, transport)

    /**
     * The earlier parameter order of the manifest-key form, where a third positional string was the
     * server URL. Hidden so that a positional string now binds as the SDK key; kept so Kotlin code
     * compiled against that order still links.
     */
    @Deprecated("Name the server URL: initialize(context, appId, serverURL = …).", level = DeprecationLevel.HIDDEN)
    @JvmStatic
    @JvmSynthetic
    fun initialize(
        context: Context,
        appId: String,
        serverURL: String = DEFAULT_SERVER_URL,
        credential: () -> String? = { null },
        transport: (suspend (Request) -> Response)? = null,
    ) = initializeWithManifestKey(context, appId, serverURL, credential, transport)

    private fun initializeWithManifestKey(
        context: Context,
        appId: String,
        serverURL: String,
        credential: () -> String?,
        transport: (suspend (Request) -> Response)?,
    ) {
        if (!validateAppId(appId)) return
        val key = manifestValue(context, META_SDK_KEY)
        if (key == null) {
            val note = "\u2717 initialize refused \u2014 no SDK key. Add a <meta-data " +
                "android:name=\"$META_SDK_KEY\" android:value=\"...\"/> to your " +
                "manifest, or call initialize(context, appId, sdkKey)."
            onLog(note)
            completeRegistrationWaiters(false)
            notifyRegistrationFailed(note)
            return
        }
        attach(context, OpenPushConfiguration(appId, key, serverURL), credential, transport)
    }

    /**
     * Setup with an explicit public [sdkKey]: `initialize(context, appId, sdkKey)` in Kotlin and Java.
     *
     * An `http://` or `https://` value with the default [serverURL] is no SDK key: it is the server
     * URL of a manifest-key call written positionally, as `initialize(context, appId, serverURL)` once
     * read. It is logged and set up that way, with the key from manifest meta-data.
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(
        context: Context,
        appId: String,
        sdkKey: String,
        serverURL: String = DEFAULT_SERVER_URL,
        credential: () -> String? = { null },
        transport: (suspend (Request) -> Response)? = null,
    ) {
        if (serverURL == DEFAULT_SERVER_URL && sdkKey.looksLikeServerUrl()) {
            onLog(
                "initialize(context, appId, \"$sdkKey\") reads a server URL as the SDK key — using it as " +
                    "serverURL with the manifest key; write initialize(context, appId, serverURL = …)",
            )
            return initializeWithManifestKey(context, appId, sdkKey, credential, transport)
        }
        if (!validateAppId(appId)) return
        attach(context, OpenPushConfiguration(appId, sdkKey, serverURL), credential, transport)
    }

    private fun String.looksLikeServerUrl(): Boolean {
        val value = trim()
        return value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
    }

    private fun validateAppId(appId: String): Boolean {
        if (appId.isNotBlank() && appId.none(Char::isWhitespace)) return true
        val note = "\u2717 initialize refused \u2014 this does not look like an app id"
        onLog(note)
        completeRegistrationWaiters(false)
        notifyRegistrationFailed(note)
        return false
    }

    /**
     * Registers the provider [token] for the configured app. The registration carries the identity
     * the last [login] set, with that login's auth hash.
     */
    @JvmStatic
    fun register(token: String) {
        absorbPendingWrites()
        val generation = newGeneration()
        val session = registrationSession
        enqueue { attempt(token, generation, session, 1, null) }
    }

    /**
     * [register] that also names an external id. An identity is attached only through [login], so
     * this goes through it: a non-empty [externalId] other than the current identity is first logged
     * in as `login(externalId)` would, without an auth hash, and the registration then carries it
     * like any other login. The current identity, `null` or an empty string registers exactly as
     * `register(token)`, keeping the current login and its auth hash.
     */
    @Deprecated(
        "Attach an identity with login(externalId, authHash) and register the token with register(token).",
        ReplaceWith("register(token)"),
    )
    @JvmStatic
    fun register(token: String, externalId: String?) {
        externalId?.trim()?.takeIf(String::isNotEmpty)?.let(::loginForRegister)
        register(token)
    }

    /** Stages [externalId] as the session's identity for a deprecated `register(token, externalId)`. */
    private fun loginForRegister(externalId: String) {
        val current = synchronized(sessionLock) { pendingExternalId == externalId && !identityDetached }
        if (current) return
        onLog(
            "register(token, externalId) logs in a new external id without an auth hash — " +
                "call login(externalId, authHash) instead",
        )
        absorbPendingWrites()
        recordLogin(externalId, null)
    }

    /**
     * Waits for the next registration outcome. Returns immediately with `true` after any
     * registration has already been accepted in this process; otherwise returns `true` for the next
     * accepted registration or `false` for the next final registration failure.
     *
     * This observer is independent of [onRegistered] and [onRegistrationFailed], so a host can keep
     * either callback installed while coroutine callers wait for the same outcome.
     */
    @JvmStatic
    suspend fun awaitRegistration(): Boolean = suspendCancellableCoroutine { continuation ->
        val alreadyRegistered = synchronized(registrationWaiterLock) {
            if (registrationAcceptedInProcess) {
                true
            } else {
                registrationWaiters += continuation
                false
            }
        }
        if (alreadyRegistered) {
            continuation.resume(true)
        } else {
            continuation.invokeOnCancellation {
                synchronized(registrationWaiterLock) { registrationWaiters -= continuation }
            }
        }
    }

    /** Records a [type] receipt for [messageId] and sends it; [then] runs on the main thread once the
     *  send attempt is over. */
    @JvmStatic
    @JvmOverloads
    fun receipt(type: String, messageId: String?, then: (() -> Unit)? = null) {
        // Persistence happens on the caller's thread. The channel owns only the network drain, so a
        // messaging callback can return (or a click listener can switch workspaces) without leaving
        // the sole copy of this owed receipt in an in-memory closure.
        val target = enqueueReceipt(type, messageId)
        enqueue {
            if (target != null) doDrainReceipts(target)
            then?.let { done -> deliverOnMain("a receipt callback", done) }
        }
    }

    /**
     * Records a case-sensitive custom event for Journey entry and branching.
     * Nearby calls share one request; calls made before initialization stage in memory for the first
     * registration, and unsent initialized events remain in SharedPreferences until connectivity returns.
     *
     * A14: every event is stamped with an `idempotency_key` here, at the moment it is tracked, and
     * keeps it across relaunches and retries — so a batch the server accepted but could not
     * acknowledge is recognised rather than counted twice. A [properties] value the wire cannot carry
     * drops the event with a named log line instead of arriving as a silent null, and the queue holds
     * at most [CUSTOM_EVENT_QUEUE_CAP] events, dropping the oldest past that.
     *
     * [idempotencyKey] replaces the generated one. Pass it when the event's identity is something the
     * caller already knows and every repeat of it is the same occurrence — a once-per-device outcome,
     * for instance — so the server keeps the first and collapses the rest. Two different occurrences
     * must never share a key: the second would be discarded.
     */
    @JvmStatic
    @JvmOverloads
    fun trackEvent(name: String, properties: Map<String, Any?> = emptyMap(), idempotencyKey: String? = null) {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 128 || !clean.matches(Regex("[a-zA-Z0-9_\\-. ]+"))) {
            onLog("custom event dropped — name must be 1..128 characters using letters, numbers, spaces, _, -, or .")
            return
        }
        // Walked before the JSONObject is built: `JSONObject.wrap` turns a value it does not know into
        // that value's `toString()` or into a null, so a host that hands us an object would otherwise
        // see "com.acme.Cart@3f1a" arrive at the server as a property and never learn why.
        val encodedProperties = if (properties.values.all(::isJsonSafe)) {
            runCatching {
                JSONObject(properties as Map<*, *>).toString()
            }.getOrNull()
        } else {
            null
        }
        if (encodedProperties == null) {
            onLog("custom event '$clean' dropped — properties are not JSON-safe")
            return
        }
        if (encodedProperties.toByteArray().size > 2_048) {
            onLog("custom event '$clean' dropped — properties exceed 2048 bytes")
            return
        }
        val props = JSONObject(encodedProperties)
        val event = JSONObject().put("name", clean).put("properties", props)
            .put("timestamp", nowMs() / 1000.0)
            .put("idempotency_key", idempotencyKey?.takeIf(String::isNotEmpty) ?: UUID.randomUUID().toString())
        synchronized(sessionLock) { pendingExternalId }?.let { event.put("external_id", it) }
            ?: subscriptionId?.let { event.put("subscription_id", it) }
        var overflowed = false
        var stagedOverflowed = false
        var queued = false
        synchronized(customEventLock) {
            if (!customEventReady || configuration == null) {
                stagedCustomEvents += event
                while (stagedCustomEvents.size > STAGED_CUSTOM_EVENT_CAP) {
                    stagedCustomEvents.removeAt(0)
                    if (!stagedCustomEventCapWarned) {
                        stagedCustomEventCapWarned = true
                        stagedOverflowed = true
                    }
                }
            } else {
                overflowed = enqueueCustomEventLocked(event)
                queued = true
            }
        }
        if (overflowed) onLog("custom events over $CUSTOM_EVENT_QUEUE_CAP queued — dropping the oldest")
        if (stagedOverflowed) {
            onLog("custom events over $STAGED_CUSTOM_EVENT_CAP staged before initialize — dropping the oldest")
        }
        if (queued) scheduleCustomEventFlush(COALESCE_WINDOW_MS)
    }

    /**
     * Whether [value] survives the round trip to JSON unchanged. `org.json`'s own types are JSON by
     * construction; every array shape is walked through reflection so an `IntArray` counts for as much as
     * an `Array<String>`. A number that is NaN or infinite passes here and is caught by the encode
     * that follows — `JSONObject.toString` refuses it, which is the same drop with the same message.
     */
    private fun isJsonSafe(value: Any?): Boolean = when {
        value == null || value === JSONObject.NULL -> true
        value is CharSequence || value is Boolean || value is Number -> true
        value is JSONObject || value is JSONArray -> true
        value is Map<*, *> -> value.all { (key, entry) -> key is String && isJsonSafe(entry) }
        value is Collection<*> -> value.all(::isJsonSafe)
        value.javaClass.isArray -> (0 until java.lang.reflect.Array.getLength(value))
            .all { isJsonSafe(java.lang.reflect.Array.get(value, it)) }
        else -> false
    }

    /**
     * Identifies this device as [externalId]. When identity verification is enabled for the app, pass the
     * server-computed [authHash]. The [callback] reports the outcome, including the rejection case where
     * the device stays registered anonymously.
     *
     * Calling this again with an identity the server has already accepted, and no new hash, sends
     * nothing and reports [IdentityResult.Success]; an empty [externalId] is refused — call [logout] to
     * detach. The callback runs on the main thread, after this call returns.
     */
    @JvmStatic
    @JvmOverloads
    fun login(externalId: String, authHash: String? = null, callback: ((IdentityResult) -> Unit)? = null) =
        startLogin(externalId, authHash, callback?.deliveredOnMain(IDENTITY_CALLBACK))

    /** [login] with [callback] run where the outcome lands; the suspend form resumes from here. */
    private fun startLogin(externalId: String, authHash: String?, callback: ((IdentityResult) -> Unit)?) {
        beginLogin(externalId, authHash)?.let {
            callback?.invoke(it)
            return
        }
        enqueueIdentity(LOGIN_DEFERRED, callback)
    }

    /** Coroutine form of [login]. Cancellation stops waiting for the result but does not undo a
     * session write or a registration that has already started. */
    @JvmStatic
    @JvmOverloads
    suspend fun loginSuspend(externalId: String, authHash: String? = null): IdentityResult =
        suspendCancellableCoroutine { continuation ->
            val waiter = AtomicReference<CancellableContinuation<IdentityResult>?>(continuation)
            continuation.invokeOnCancellation { waiter.compareAndSet(continuation, null) }
            startLogin(externalId, authHash) { result ->
                waiter.getAndSet(null)?.let { active ->
                    if (active.isActive) active.resume(result)
                }
            }
        }

    /**
     * Detaches this device from any external identity. A device that is already anonymous sends
     * nothing and reports [IdentityResult.Success]. The callback runs on the main thread, after this
     * call returns.
     */
    @JvmStatic
    @JvmOverloads
    fun logout(callback: ((IdentityResult) -> Unit)? = null) = startLogout(callback?.deliveredOnMain(IDENTITY_CALLBACK))

    /** [logout] with [callback] run where the outcome lands; the suspend form resumes from here. */
    private fun startLogout(callback: ((IdentityResult) -> Unit)?) {
        beginLogout()?.let {
            callback?.invoke(it)
            return
        }
        enqueueIdentity(LOGOUT_DEFERRED, callback)
    }

    /** Coroutine form of [logout]. Cancellation stops waiting for the result but does not undo a
     * session write or a registration that has already started. */
    @JvmStatic
    suspend fun logoutSuspend(): IdentityResult = suspendCancellableCoroutine { continuation ->
        val waiter = AtomicReference<CancellableContinuation<IdentityResult>?>(continuation)
        continuation.invokeOnCancellation { waiter.compareAndSet(continuation, null) }
        startLogout { result ->
            waiter.getAndSet(null)?.let { active ->
                if (active.isActive) active.resume(result)
            }
        }
    }

    /** Notification permission and the notification event surface. */
    object Notifications {
        /**
         * Request code of the legacy in-Activity POST_NOTIFICATIONS prompt, used only when the merged
         * manifest lacks OpenPush's permission Activity; forward that result with [onPermissionResult].
         */
        const val PERMISSION_REQUEST_CODE = 9110

        /**
         * Last known notification permission. Re-read at [initialize], actual app foreground
         * transitions, [requestPermission], and [onPermissionResult] — it is a cache, not a live
         * query, and the same cache [User.pushSubscription] reads.
         */
        @JvmStatic
        val permission: Boolean get() = cachedPermission

        /**
         * Whether Android can still show the notification permission prompt. Before the first
         * request this is known without an Activity. After a request, a resumed Activity is needed
         * to distinguish a temporary denial from a permanent one; without one this conservatively
         * reports false and logs once.
         */
        @JvmStatic
        val canRequestPermission: Boolean
            get() {
                if (cachedPermission || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
                val asked = prefs()?.getBoolean(PERMISSION_ASKED_KEY, false) ?: false
                if (!asked) return true
                val activity = currentActivity?.get()
                if (activity == null) {
                    if (!canRequestPermissionWithoutActivityWarned) {
                        canRequestPermissionWithoutActivityWarned = true
                        onLog(
                            "⚠ canRequestPermission cannot distinguish a temporary denial from a " +
                                "permanent denial without a resumed Activity — reporting false",
                        )
                    }
                    return false
                }
                return activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }

        /** Cancels every notification posted by this app and forgets tracked OpenPush entries. */
        @JvmStatic
        fun clearAll() {
            val context = appContext ?: run {
                onLog("clearAll() needs OpenPush.initialize() before it can reach NotificationManager")
                return
            }
            val manager = OpenPushBridge.shadeManager
            if (manager != null) {
                manager.clearAll(context)
            } else {
                context.getSystemService(NotificationManager::class.java)?.cancelAll()
            }
        }

        /** Cancels one notification previously posted by the optional FCM renderer module. */
        @JvmStatic
        fun remove(notificationId: Int) {
            val manager = OpenPushBridge.shadeManager
            val context = appContext
            if (manager == null || context == null) {
                onLog("remove() requires the FCM renderer module to track posted notification ids")
                return
            }
            manager.remove(context, notificationId)
        }

        /** Cancels notifications whose composer group exactly matches [group]. */
        @JvmStatic
        fun removeGroup(group: String) {
            val manager = OpenPushBridge.shadeManager
            val context = appContext
            if (manager == null || context == null) {
                onLog("removeGroup() requires the FCM renderer module to track posted notification ids")
                return
            }
            manager.removeGroup(context, group)
        }

        /**
         * Asks for the notification permission from the currently resumed Activity.
         *
         * With no Activity resumed — called from `onCreate`, before the first `onResume`, or from a
         * service or the Application — the request is deferred, not answered: it waits and the prompt
         * starts when the next app Activity resumes, and [callback] then gets the user's answer. Calls
         * made while a request is deferred or in flight share that one prompt and all get its answer;
         * [fallbackToSettings] applies when any of them asked for it. An app that never shows an
         * Activity keeps the request pending until one resumes; it does not time out. A permission
         * that is already granted, and API 32 and below (no runtime permission to ask for), answer at
         * once. Before OpenPush watches the app's Activities (from its AndroidX Startup provider,
         * [observeLifecycle] or [initialize]) nothing could start a deferred prompt, so it reports the
         * cached permission. [callback] runs on the main thread, after this call returns.
         */
        @JvmStatic
        @JvmOverloads
        fun requestPermission(fallbackToSettings: Boolean = false, callback: ((Boolean) -> Unit)? = null) =
            requestPermissionAnywhere(fallbackToSettings, callback?.deliveredOnMain(PERMISSION_CALLBACK))

        /** [requestPermission] with [callback] run where the answer lands; the suspend form uses it. */
        internal fun requestPermissionAnywhere(fallbackToSettings: Boolean, callback: ((Boolean) -> Unit)?) {
            currentActivity?.get()?.let { return requestPermissionFrom(it, fallbackToSettings, callback) }
            if (refreshPermission()) return callback?.invoke(true) ?: Unit
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onLog("notifications are disabled in system settings — the app cannot re-enable them")
                return callback?.invoke(false) ?: Unit
            }
            if (foregroundApplication == null) {
                onLog(
                    "⚠ requestPermission() found no resumed Activity and OpenPush is not watching the " +
                        "app's Activities (initialize OpenPush first) — reporting the cached permission",
                )
                return callback?.invoke(cachedPermission) ?: Unit
            }
            val firstDeferral = synchronized(permissionRequestLock) {
                if (permissionRequestInFlight) {
                    callback?.let(permissionCallbacks::add)
                    return
                }
                val first = !permissionRequestDeferred
                permissionRequestDeferred = true
                deferredPermissionFallbackToSettings = deferredPermissionFallbackToSettings || fallbackToSettings
                callback?.let(deferredPermissionCallbacks::add)
                first
            }
            if (firstDeferral) {
                onLog(
                    "requestPermission() found no resumed Activity — the request is deferred and the " +
                        "prompt starts when the next Activity resumes",
                )
            }
            // An Activity may have resumed on the main thread between the check above and the deferral.
            currentActivity?.get()?.let(::launchDeferredPermissionRequest)
        }

        /**
         * Asks the user for the notification permission. The prompt runs in OpenPush's own transparent
         * Activity, so the result reaches [callback] without the host forwarding anything; a host that
         * still forwards `onRequestPermissionsResult` into [onPermissionResult] sees no second callback.
         * When the permission can no longer be requested and [fallbackToSettings] is true, the system
         * notification settings open instead. [callback] runs on the main thread, after this call
         * returns.
         */
        @JvmStatic
        @JvmOverloads
        fun requestPermission(
            activity: Activity,
            fallbackToSettings: Boolean = false,
            callback: ((Boolean) -> Unit)? = null,
        ) = requestPermissionFrom(activity, fallbackToSettings, callback?.deliveredOnMain(PERMISSION_CALLBACK))

        /** The Activity form of [requestPermission] with [callback] run where the answer lands. */
        internal fun requestPermissionFrom(
            activity: Activity,
            fallbackToSettings: Boolean,
            callback: ((Boolean) -> Unit)?,
        ) {
            if (refreshPermission()) return callback?.invoke(true) ?: Unit
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onLog("notifications are disabled in system settings — the app cannot re-enable them")
                if (fallbackToSettings) return awaitPermissionSettings(activity, callback)
                return callback?.invoke(false) ?: Unit
            }
            val asked = prefs()?.getBoolean(PERMISSION_ASKED_KEY, false) ?: false
            val permanentlyDenied = asked &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            // Tri-state, decided under one lock: true starts the one platform request, false joins
            // the request already in flight, and null handles a permanent denial below. Keeping the
            // check and claim together prevents two concurrent first callers from both launching UI.
            val ownsRequest: Boolean? = synchronized(permissionRequestLock) {
                when {
                    permissionRequestInFlight -> {
                        callback?.let(permissionCallbacks::add)
                        false
                    }
                    permanentlyDenied -> null
                    else -> {
                        permissionRequestInFlight = true
                        permissionRequestGeneration++
                        callback?.let(permissionCallbacks::add)
                        true
                    }
                }
            }
            if (ownsRequest == false) return
            if (ownsRequest == null) {
                onLog("notification permission was denied for good — only system settings can change it")
                if (fallbackToSettings) return awaitPermissionSettings(activity, callback)
                return callback?.invoke(false) ?: Unit
            }
            prefs()?.edit()?.putBoolean(PERMISSION_ASKED_KEY, true)?.apply()
            val generation = synchronized(permissionRequestLock) { permissionRequestGeneration }
            if (activity.isFinishing || activity.isDestroyed) {
                // A prompt started from here would never show, and nothing would ever answer it.
                onLog("requestPermission() was given a finishing Activity — reporting the current permission")
                return completePermissionRequestFromSystem(generation)
            }
            if (OpenPushPermissionActivity.launch(activity, generation)) {
                onLog("→ requesting POST_NOTIFICATIONS")
                return
            }
            onLog(
                "→ requesting POST_NOTIFICATIONS (request code $PERMISSION_REQUEST_CODE) — the permission " +
                    "Activity is not in the merged manifest, so forward onRequestPermissionsResult",
            )
            runCatching {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST_CODE)
            }.onFailure {
                onLog("notification permission request failed — ${it.message}")
                completePermissionRequest(false)
            }
        }

        /**
         * Asks for the notification permission and suspends until it is answered:
         * `val granted = requestPermission()` inside a coroutine. It resumes with the answer from
         * OpenPush's permission Activity. With no Activity resumed it suspends until the deferred
         * prompt, started at the next Activity resume, is answered. It resumes in the caller's
         * coroutine context, which is not necessarily the main thread.
         *
         * Kotlin picks this form for `requestPermission()` and `requestPermission(true)`, so outside a
         * coroutine (in `onCreate`, for example) pass a callback instead:
         * `requestPermission { granted -> … }`. Java uses the callback form,
         * `requestPermission(false, granted -> Unit.INSTANCE)`.
         */
        @JvmStatic
        @JvmOverloads
        suspend fun requestPermission(fallbackToSettings: Boolean = false): Boolean =
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                requestPermissionAnywhere(fallbackToSettings) { granted ->
                    if (continuation.isActive) continuation.resumeWith(Result.success(granted))
                }
            }

        /**
         * Hands OpenPush a POST_NOTIFICATIONS answer the host received itself, such as a wrapper SDK's own
         * prompt; other request codes are ignored. Not needed for [requestPermission]: its prompt runs in
         * OpenPush's own Activity, whose result never reaches the host's `onRequestPermissionsResult`, so
         * forwarding code left in a host cannot deliver it twice. A forward that arrives while a request
         * is in flight completes that request; the Activity's later answer is then dropped.
         */
        @JvmStatic
        fun onPermissionResult(requestCode: Int, grantResults: IntArray) {
            if (requestCode != PERMISSION_REQUEST_CODE) return
            val callbacks = synchronized(permissionRequestLock) {
                if (permissionRequestInFlight) claimPermissionCallbacks() else emptyList()
            }
            deliverPermissionResult(
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED,
                callbacks,
            )
        }

        /** Observes permission changes; the observer runs on the main thread, after the change. */
        @JvmStatic
        fun addPermissionObserver(observer: (Boolean) -> Unit): ListenerHandle = register(permissionObservers, observer)

        @JvmStatic
        fun removePermissionObserver(handle: ListenerHandle) {
            synchronized(permissionObservers) { permissionObservers.remove(handle.id) }
        }

        /**
         * Registers a listener for notification opens; keep the handle to remove it. Clicks are
         * delivered on the main thread: at once when the click is reported on the main thread, as
         * the notification trampoline does before it opens the app, and posted otherwise.
         *
         * A click that arrived while no listener was registered — the cold start a tap on a
         * notification causes, where the process starts, the click is forwarded and only then does
         * the host reach its own registration — is held and handed to this listener. Called on the
         * main thread, this call **delivers it inside the call**, before it returns; called from
         * another thread, it is posted to the main thread. At most [PENDING_CLICK_LIMIT] clicks are
         * held; the oldest is dropped past that. Only the first listener to register sees them.
         */
        @JvmStatic
        fun addClickListener(listener: (ClickEvent) -> Unit): ListenerHandle {
            val id = java.util.UUID.randomUUID().toString()
            val held = synchronized(clickListeners) {
                clickListeners[id] = listener
                val drained = pendingClicks.toList()
                pendingClicks.clear()
                drained
            }
            if (held.isNotEmpty()) {
                onMainNowOrPosted {
                    held.forEach { event ->
                        runCatching { listener(event) }
                            .onFailure { onLog("a notification listener threw — ${it.message}") }
                    }
                }
            }
            return ListenerHandle(id)
        }

        @JvmStatic
        fun removeClickListener(handle: ListenerHandle) {
            synchronized(clickListeners) { clickListeners.remove(handle.id) }
        }

        /**
         * Registers a listener for pushes that arrive while the app is foregrounded. Unlike the other
         * listeners it runs on the thread that delivered the push — the messaging service's worker
         * thread, not the main thread — because [ForegroundEvent.preventDefault] must be decided
         * before this call returns. Decide synchronously, and post to the main thread for UI work.
         */
        @JvmStatic
        fun addForegroundLifecycleListener(listener: (ForegroundEvent) -> Unit): ListenerHandle =
            register(foregroundListeners, listener)

        @JvmStatic
        fun removeForegroundLifecycleListener(handle: ListenerHandle) {
            synchronized(foregroundListeners) { foregroundListeners.remove(handle.id) }
        }

        /**
         * Registers a listener for background (data-only) pushes — `op_background` = `"1"` with no
         * title or body — which are never displayed. It runs on the thread that delivered the push
         * (the messaging service's worker thread), whether the app is foregrounded or not, so keep it
         * short and hand longer work to WorkManager. A background push that arrives while no
         * listener is registered is logged and dropped; its `received` receipt is still posted.
         */
        @JvmStatic
        fun addBackgroundMessageListener(listener: (BackgroundMessageEvent) -> Unit): ListenerHandle =
            register(backgroundMessageListeners, listener)

        @JvmStatic
        fun removeBackgroundMessageListener(handle: ListenerHandle) {
            synchronized(backgroundMessageListeners) { backgroundMessageListeners.remove(handle.id) }
        }

        /**
         * Call first for every delivered data message, before [processForegroundMessage]. Returns false
         * when this push is a repeat — another copy of the same OpenPush message is still being
         * handled, or was handled on this device within the last seven days — and the copy should be
         * dropped without a listener call, a receipt or a notification. The server re-sends a push
         * whose delivery it could not confirm, so a second copy can arrive.
         *
         * Returns true for a first copy, and always for a push OpenPush did not send, a Live Update,
         * or when the record cannot be read. After a true result, call [finishReceived] once the push
         * has been handled, in a `finally` block, whether or not it was shown.
         */
        @JvmStatic
        fun beginReceived(data: Map<String, String>): Boolean =
            ReceivedMessages.begin(appContext ?: foregroundApplication, data)

        /**
         * Ends what [beginReceived] started. With [handled] true (the default) the message id is
         * recorded for seven days, so a later copy is refused; pass false when handling failed before
         * the push could be shown, so a later copy can still show it. Call only after [beginReceived]
         * returned true. A push OpenPush did not send is ignored.
         */
        @JvmStatic
        @JvmOverloads
        fun finishReceived(data: Map<String, String>, handled: Boolean = true) =
            ReceivedMessages.finish(appContext ?: foregroundApplication, data, handled)

        /**
         * Forward every `FirebaseMessagingService.onMessageReceived` here. The `received` receipt is posted
         * for every delivery, but foreground listeners run — and `preventDefault()` can return true — only
         * while the app is actually foregrounded. `onMessageReceived` also fires with the app backgrounded
         * or killed, and a listener must never be able to suppress a notification the user never saw.
         *
         * A background push ([OpenPushNotification.isBackground]) goes to the background-message
         * listeners instead of the foreground ones, and this returns true: it must not be displayed.
         */
        @JvmStatic
        fun processForegroundMessage(data: Map<String, String>): Boolean =
            processForegroundMessage(OpenPushNotification.fromData(data))

        /** Forward a provider-parsed notification so transport metadata such as collapseKey stays. */
        @JvmStatic
        fun processForegroundMessage(notification: OpenPushNotification): Boolean {
            val event = ForegroundEvent(notification.messageId, notification.rawData, notification)
            // A map without an op_message_id came from another sender sharing this provider
            // integration. It is not ours to report on or to let a listener suppress.
            if (!event.notification.isOpenPush) {
                onLog("a push OpenPush did not send — ignored")
                return false
            }
            if (notification.isBackground) {
                deliverBackgroundMessage(notification)
                receipt("received", event.messageId)
                return true
            }
            if (!isAppForegrounded) {
                // The message still arrived, so the receipt is still owed.
                receipt("received", event.messageId)
                return false
            }
            dispatch(foregroundListeners, event)
            receipt("received", event.messageId)
            return event.defaultPrevented
        }

        /**
         * Record that a host-owned renderer successfully posted this notification.
         * Call immediately after NotificationManager.notify succeeds, never merely
         * because the provider delivered the data message.
         */
        @JvmStatic
        fun processDisplayed(data: Map<String, String>) {
            processDisplayed(OpenPushNotification.fromData(data))
        }

        /** Record successful presentation while preserving the parsed notification model. */
        @JvmStatic
        fun processDisplayed(notification: OpenPushNotification) {
            if (!notification.isOpenPush) {
                onLog("a displayed notification OpenPush did not send — ignored")
                return
            }
            receipt("confirmed", notification.messageId)
        }

        /** Forward from the click trampoline with the notification's extras as a map. */
        @JvmStatic
        fun processClick(data: Map<String, String>) {
            processClick(OpenPushNotification.fromData(data))
        }

        /** Forward a provider-parsed click without adding transport metadata to [OpenPushNotification.rawData]. */
        @JvmStatic
        fun processClick(notification: OpenPushNotification) {
            val event = ClickEvent(
                notification.messageId,
                notification.rawData,
                notification,
                notification.rawData[ACTION_ID_KEY],
            )
            if (!event.notification.isOpenPush) {
                onLog("click on a notification OpenPush did not send — ignored")
                return
            }
            receipt("clicked", event.messageId)
            deliverOrHold(event)
        }
    }

    private val clickListeners = linkedMapOf<String, (ClickEvent) -> Unit>()

    // Clicks that arrived before any listener registered, held under the clickListeners lock so a
    // click cannot slip between "no listeners" and "held". Drained by the first addClickListener.
    private val pendingClicks = ArrayDeque<ClickEvent>()
    private val foregroundListeners = linkedMapOf<String, (ForegroundEvent) -> Unit>()
    private val backgroundMessageListeners = linkedMapOf<String, (BackgroundMessageEvent) -> Unit>()
    private val permissionObservers = linkedMapOf<String, (Boolean) -> Unit>()
    private val subscriptionObservers = linkedMapOf<String, (PushSubscriptionState) -> Unit>()
    private val subscriptionChangeObservers = linkedMapOf<String, (PushSubscriptionChange) -> Unit>()

    // Guards the read-compare-write of lastObservedState, so two threads publishing at once cannot
    // both see the same previous value and fire the change observers twice with it.
    private val observerLock = Any()

    @Volatile private var lastObservedPermission: Boolean? = null

    @Volatile private var lastObservedState: PushSubscriptionState? = null

    private fun <T> register(target: MutableMap<String, T>, listener: T): ListenerHandle {
        val id = java.util.UUID.randomUUID().toString()
        synchronized(target) { target[id] = listener }
        return ListenerHandle(id)
    }

    // Where host code runs. Every public callback reaches the main thread through
    // OpenPushMainThread, posted even from the main thread so it never runs inside the SDK call that
    // caused it, and in the order it was raised. Two kinds stay where the push arrived, because the
    // SDK needs their answer before the messaging service returns: foreground listeners
    // (preventDefault decides whether the push is drawn) and background-message listeners. Clicks
    // are on the main thread already and run there at once. One throwing listener must not swallow
    // the rest.

    /** Runs the listeners in [target] on the calling thread: foreground and background pushes only. */
    private fun <E> dispatch(target: MutableMap<String, (E) -> Unit>, event: E) {
        val listeners = synchronized(target) { target.values.toList() }
        listeners.forEach { listener ->
            runCatching { listener(event) }.onFailure { onLog("a notification listener threw — ${it.message}") }
        }
    }

    /**
     * Posts [event] to the listeners registered in [target] now. A listener removed before the post
     * runs is skipped, so nothing reaches a listener after its remove call has returned on the main
     * thread.
     */
    private fun <E> dispatchOnMain(target: MutableMap<String, (E) -> Unit>, event: E) {
        val listeners = synchronized(target) { target.entries.map { it.key to it.value } }
        if (listeners.isEmpty()) return
        OpenPushMainThread.post {
            listeners.forEach { (id, listener) ->
                if (synchronized(target) { target[id] === listener }) {
                    runCatching { listener(event) }
                        .onFailure { onLog("a notification listener threw — ${it.message}") }
                }
            }
        }
    }

    /** Posts [callback] to the main thread; a throw is logged under [what] and goes no further. */
    internal fun deliverOnMain(what: String, callback: () -> Unit) {
        OpenPushMainThread.post {
            runCatching(callback).onFailure { onLog("$what threw — ${it.message}") }
        }
    }

    /** This one-argument callback, delivered through [deliverOnMain] wherever it is invoked from. */
    internal fun <T> ((T) -> Unit).deliveredOnMain(what: String): (T) -> Unit {
        val callback = this
        return { value -> deliverOnMain(what) { callback(value) } }
    }

    /** Runs [action] at once on the main thread, or posts it there from any other thread. */
    private fun onMainNowOrPosted(action: () -> Unit) = OpenPushMainThread.run(action)

    private fun notifyRegistered() {
        val hook = onRegistered ?: return
        deliverOnMain("onRegistered") { hook() }
    }

    private fun notifyRegistrationFailed(message: String) {
        val hook = onRegistrationFailed ?: return
        deliverOnMain("onRegistrationFailed") { hook(message) }
    }

    private const val IDENTITY_CALLBACK = "an identity callback"
    private const val PERMISSION_CALLBACK = "a notification permission callback"

    /**
     * Delivers a click to the registered listeners, or holds it when there are none yet. The
     * decision and the hold are one step under the `clickListeners` lock, so a listener registering
     * concurrently either sees the click delivered here or drains it from the hold — never neither.
     */
    private fun deliverOrHold(event: ClickEvent) {
        val listeners = synchronized(clickListeners) {
            if (clickListeners.isEmpty()) {
                if (pendingClicks.size >= PENDING_CLICK_LIMIT) {
                    pendingClicks.removeFirst()
                    onLog("held click dropped — more than $PENDING_CLICK_LIMIT clicks arrived before a listener")
                }
                pendingClicks.addLast(event)
                return
            }
            clickListeners.values.toList()
        }
        onMainNowOrPosted {
            listeners.forEach { listener ->
                runCatching { listener(event) }.onFailure { onLog("a notification listener threw — ${it.message}") }
            }
        }
    }

    /**
     * Hands a background push to the manifest-declared handler and to the runtime listeners, or
     * logs that nothing was there to take it. The manifest handler is what makes a push delivered to
     * a cold process useful: it needs no [initialize] call to have happened first.
     */
    private fun deliverBackgroundMessage(notification: OpenPushNotification) {
        val event = BackgroundMessageEvent(notification.messageId, notification.rawData, notification)
        val handled = deliverToManifestHandler(event)
        if (synchronized(backgroundMessageListeners) { backgroundMessageListeners.isEmpty() }) {
            if (!handled) {
                onLog(
                    "background push ${notification.messageId} dropped — no listener; register one with " +
                        "OpenPush.Notifications.addBackgroundMessageListener or declare " +
                        "$META_BACKGROUND_MESSAGE_HANDLER in the manifest",
                )
            }
            return
        }
        dispatch(backgroundMessageListeners, event)
    }

    private fun deliverToManifestHandler(event: BackgroundMessageEvent): Boolean {
        // In a process FCM started from nothing, initialize has not run; the lifecycle initializer
        // (androidx.startup) has, so the Application is the context to read the manifest with.
        val context: Context = appContext ?: foregroundApplication ?: return false
        val handler = manifestBackgroundHandler(context) ?: return false
        runCatching { handler.onBackgroundMessage(context, event) }
            .onFailure { onLog("the background message handler threw — ${it.message}") }
        return true
    }

    private fun manifestBackgroundHandler(context: Context): OpenPushBackgroundMessageHandler? {
        val name = manifestValue(context, META_BACKGROUND_MESSAGE_HANDLER) ?: return null
        return runCatching {
            Class.forName(name).getDeclaredConstructor().newInstance() as OpenPushBackgroundMessageHandler
        }.getOrElse {
            onLog(
                "$META_BACKGROUND_MESSAGE_HANDLER names $name, which is not a public " +
                    "OpenPushBackgroundMessageHandler with a no-argument constructor — ${it.message}",
            )
            null
        }
    }

    // Posted under observerLock, so two threads publishing at once queue their events in the order
    // they recorded them. A post only queues; no host code runs under the lock.
    private fun notifyPermissionObservers() {
        val value = cachedPermission
        synchronized(observerLock) {
            if (lastObservedPermission == value) return
            lastObservedPermission = value
            dispatchOnMain(permissionObservers, value)
        }
    }

    private fun notifySubscriptionObservers() {
        val value = User.pushSubscription.snapshot()
        synchronized(observerLock) {
            if (lastObservedState == value) return
            val previous = lastObservedState
            lastObservedState = value
            dispatchOnMain(subscriptionObservers, value)
            dispatchOnMain(subscriptionChangeObservers, PushSubscriptionChange(previous, value))
        }
    }

    internal fun processForegroundMessageBlocking(data: Map<String, String>): Boolean =
        processForegroundMessageBlocking(OpenPushNotification.fromData(data))

    internal fun processForegroundMessageBlocking(notification: OpenPushNotification): Boolean {
        val event = ForegroundEvent(notification.messageId, notification.rawData, notification)
        if (!event.notification.isOpenPush) {
            onLog("a push OpenPush did not send — ignored")
            return false
        }
        if (notification.isBackground) {
            deliverBackgroundMessage(notification)
            receiptBlocking("received", event.messageId)
            return true
        }
        if (!isAppForegrounded) {
            // The message still arrived, so the receipt is still owed.
            receiptBlocking("received", event.messageId)
            return false
        }
        dispatch(foregroundListeners, event)
        receiptBlocking("received", event.messageId)
        return event.defaultPrevented
    }

    internal fun processClickBlocking(data: Map<String, String>) {
        processClickBlocking(OpenPushNotification.fromData(data))
    }

    internal fun processClickBlocking(notification: OpenPushNotification) {
        val event = ClickEvent(
            notification.messageId,
            notification.rawData,
            notification,
            notification.rawData[ACTION_ID_KEY],
        )
        if (!event.notification.isOpenPush) {
            onLog("click on a notification OpenPush did not send — ignored")
            return
        }
        receiptBlocking("clicked", event.messageId)
        deliverOrHold(event)
    }

    private const val PERMISSION_ASKED_KEY = "openpush.permissionRequested"

    @Volatile private var cachedPermission = false

    @Volatile private var canRequestPermissionWithoutActivityWarned = false

    private val permissionRequestLock = Any()
    private val permissionCallbacks = mutableListOf<(Boolean) -> Unit>()

    @Volatile private var permissionRequestInFlight = false
    private var permissionRequestGeneration = 0L

    // A no-Activity request waiting for the next Activity resume. Guarded by [permissionRequestLock].
    private var permissionRequestDeferred = false
    private var deferredPermissionFallbackToSettings = false
    private val deferredPermissionCallbacks = mutableListOf<(Boolean) -> Unit>()

    /**
     * Starts the request deferred by [Notifications.requestPermission] from [activity], which has just
     * resumed. The claim is atomic, so one deferral starts one prompt however many resumes race for it;
     * the request then joins any request already in flight through the Activity overload.
     */
    private fun launchDeferredPermissionRequest(activity: Activity) {
        if (activity is OpenPushPermissionActivity || activity.isFinishing || activity.isDestroyed) return
        val (fallbackToSettings, callbacks) = synchronized(permissionRequestLock) {
            if (!permissionRequestDeferred) return
            permissionRequestDeferred = false
            val fallback = deferredPermissionFallbackToSettings
            deferredPermissionFallbackToSettings = false
            fallback to deferredPermissionCallbacks.toList().also { deferredPermissionCallbacks.clear() }
        }
        onLog("→ starting the deferred notification permission request")
        // The waiting callbacks were wrapped for the main thread when they were handed in, so the
        // request itself takes them as they are rather than wrapping them a second time.
        Notifications.requestPermissionFrom(activity, fallbackToSettings) { granted ->
            runPermissionCallbacks(callbacks, granted)
        }
    }

    private val permissionSettingsLock = Any()
    private val permissionSettingsCallbacks = mutableListOf<(Boolean) -> Unit>()

    @Volatile private var awaitingPermissionSettings = false

    /**
     * The answer from [OpenPushPermissionActivity] for request [generation]. The in-flight check and the
     * claim of the waiting callbacks are one step, so a forwarded [Notifications.onPermissionResult]
     * cannot complete the same request too: a stale answer — the request already completed, or an
     * earlier request — is dropped.
     */
    internal fun onPermissionActivityResult(generation: Long, granted: Boolean) {
        val callbacks = synchronized(permissionRequestLock) {
            if (!isPermissionRequestInFlight(generation)) return
            claimPermissionCallbacks()
        }
        deliverPermissionResult(granted, callbacks)
    }

    /** Completes request [generation] with what the OS reports now, for a prompt that never answered. */
    internal fun completePermissionRequestFromSystem(generation: Long) {
        val callbacks = synchronized(permissionRequestLock) {
            if (!isPermissionRequestInFlight(generation)) return
            claimPermissionCallbacks()
        }
        // The effective permission is read below, so "granted" defers entirely to it.
        deliverPermissionResult(true, callbacks)
    }

    internal fun isPermissionRequestInFlight(generation: Long): Boolean = synchronized(permissionRequestLock) {
        permissionRequestInFlight && generation == permissionRequestGeneration
    }

    /** Ends the request in flight and hands back its waiting callbacks. Call under [permissionRequestLock]. */
    private fun claimPermissionCallbacks(): List<(Boolean) -> Unit> {
        permissionRequestInFlight = false
        return permissionCallbacks.toList().also { permissionCallbacks.clear() }
    }

    private fun deliverPermissionResult(granted: Boolean, callbacks: List<(Boolean) -> Unit>) {
        refreshPermission()
        // The effective answer, not the raw grant: a grant the user then disabled at the channel
        // or app level is not a permission to show anything.
        val effective = granted && cachedPermission
        runPermissionCallbacks(callbacks, effective)
        OpenPushBridge.notifyPermissionResult(effective)
    }

    /** Completes every caller waiting on the one platform permission dialog. */
    private fun completePermissionRequest(granted: Boolean) {
        val callbacks = synchronized(permissionRequestLock) { claimPermissionCallbacks() }
        runPermissionCallbacks(callbacks, granted)
    }

    private fun runPermissionCallbacks(callbacks: List<(Boolean) -> Unit>, granted: Boolean) {
        callbacks.forEach { callback ->
            runCatching { callback(granted) }
                .onFailure { onLog("a notification permission callback threw — ${it.message}") }
        }
    }

    /** Opens settings once and joins any later callers to the same resume-time result. */
    private fun awaitPermissionSettings(activity: Activity, callback: ((Boolean) -> Unit)?) {
        val shouldOpen = synchronized(permissionSettingsLock) {
            callback?.let(permissionSettingsCallbacks::add)
            if (awaitingPermissionSettings) {
                false
            } else {
                awaitingPermissionSettings = true
                true
            }
        }
        if (shouldOpen && !openNotificationSettings(activity)) completePermissionSettingsRequest(false)
    }

    /** Re-checks the effective permission after the host Activity returns from app settings. */
    private fun completePermissionSettingsRequest(granted: Boolean = readPermission()) {
        val callbacks = synchronized(permissionSettingsLock) {
            if (!awaitingPermissionSettings) return
            awaitingPermissionSettings = false
            permissionSettingsCallbacks.toList().also { permissionSettingsCallbacks.clear() }
        }
        refreshPermission()
        callbacks.forEach { callback ->
            runCatching { callback(granted) }
                .onFailure { onLog("a notification permission callback threw — ${it.message}") }
        }
    }

    private fun readPermission(): Boolean {
        val context = appContext ?: return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshPermission(): Boolean {
        val granted = readPermission()
        val changed = granted != cachedPermission
        val previousNotificationTypes = notificationTypes
        cachedPermission = granted
        notificationTypes = if (granted) STATUS_SUBSCRIBED else STATUS_NO_PERMISSION
        if (changed || notificationTypes != previousNotificationTypes) persistSession()
        if (changed) notifyPermissionObservers()
        return cachedPermission
    }

    internal fun wireStatus(): Int = if (optedOut) STATUS_OPTED_OUT else notificationTypes

    private fun setOptedOut(value: Boolean) {
        optedOut = value
        persistSession()
    }

    private fun openNotificationSettings(activity: Activity): Boolean {
        // The per-app notification screen is API 26+; below it the app details screen holds the toggle.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
        }
        return runCatching {
            activity.startActivity(intent)
            true
        }.getOrElse {
            onLog("could not open notification settings — ${it.message}")
            false
        }
    }

    /** The user this device belongs to: their tags, aliases, phone numbers and email addresses. */
    object User {
        /** Adds or replaces one tag; re-registers immediately when a provider token is held. */
        @JvmStatic
        fun addTag(key: String, value: String) = addTags(mapOf(key to value))

        /** Adds or replaces several tags in a single request. */
        @JvmStatic
        fun addTags(tags: Map<String, String>) {
            if (!recordTags(tags)) return
            scheduleFlush(TAGS_DEFERRED)
        }

        /**
         * Sends `""` for [key]; the server reads an empty value as a delete. A removal is held only to
         * the non-empty-key rule: a key an older build stored before the shape rules existed must
         * still be removable.
         */
        @JvmStatic
        fun removeTag(key: String) = removeTags(listOf(key))

        /**
         * Returns a copy of the tags this user has. A tag that has been removed rides the wire as an
         * empty value until the server accepts the removal; that is a deletion in flight, not a tag,
         * so it is not reported here.
         */
        @JvmStatic
        fun getTags(): Map<String, String> = synchronized(tags) { tags.filterValues(String::isNotEmpty) }

        /** Removes several tags in one coalesced registration. */
        @JvmStatic
        fun removeTags(keys: Collection<String>) {
            if (!recordTags(keys.associateWith { "" }, allowInvalidKeysForRemoval = true)) return
            scheduleFlush(TAGS_DEFERRED)
        }

        /**
         * Adds or replaces one alias — a second label the server can address this user by, next to
         * `external_id`. Re-registers immediately when a provider token is held. A label the server
         * reserves, an empty label and an empty id are dropped here with a log; the per-user cap stays
         * the server's rule, and going over it is warned about rather than enforced.
         */
        @JvmStatic
        fun addAlias(label: String, id: String) = addAliases(mapOf(label to id))

        /** Adds or replaces several aliases in a single request. */
        @JvmStatic
        fun addAliases(aliases: Map<String, String>) {
            if (!recordAliases(aliases)) return
            scheduleFlush(ALIASES_DEFERRED)
        }

        /** Sends `""` for [label]; the server reads an empty value as a delete. */
        @JvmStatic
        fun removeAlias(label: String) = removeAliases(listOf(label))

        /** Removes several aliases in one coalesced registration. */
        @JvmStatic
        fun removeAliases(labels: Collection<String>) {
            if (!recordAliases(labels.associateWith { "" }, deleting = true)) return
            scheduleFlush(ALIASES_DEFERRED)
        }

        /** External identity currently claimed by this device, or null when detached. */
        @JvmStatic
        val externalId: String? get() = synchronized(sessionLock) { pendingExternalId }

        /**
         * This device's push subscription. [clearToken] deliberately leaves
         * [PushSubscriptionState.id] in place: the server-side subscription still exists after a local
         * token wipe, and reporting `null` would misstate that.
         */
        @JvmStatic
        val pushSubscription: LivePushSubscription get() = LivePushSubscription

        /**
         * Registers [number] for SMS on this user. The server holds it as not verified (status 0)
         * until a phone code for it is approved through [Verify]; the device never sends a positive
         * status. The number is normalised the way the server does it: everything but digits and `+`
         * is removed, a leading `00` becomes `+`, and the result must be `+` and 8 to 15 digits, the
         * first not `0`. Anything else returns false, logs why, and sends nothing. An accepted write
         * is persisted,
         * sent on the registration queue with the current external id, and retried while the server
         * cannot be reached; one made before [initialize] waits for it.
         */
        @JvmStatic
        fun addSms(number: String): Boolean = PhoneSubscriptions.record(PhonePlatform.SMS, number, removal = false)

        /** Opts [number] out of SMS (status -2), offline-first like [addSms]; false when it is not a valid number. */
        @JvmStatic
        fun removeSms(number: String): Boolean = PhoneSubscriptions.record(PhonePlatform.SMS, number, removal = true)

        /** Registers [number] for WhatsApp, under the same rules as [addSms]. */
        @JvmStatic
        fun addWhatsApp(number: String): Boolean =
            PhoneSubscriptions.record(PhonePlatform.WHATSAPP, number, removal = false)

        /** Opts [number] out of WhatsApp (status -2), under the same rules as [removeSms]. */
        @JvmStatic
        fun removeWhatsApp(number: String): Boolean =
            PhoneSubscriptions.record(PhonePlatform.WHATSAPP, number, removal = true)

        /**
         * Subscribes [address] for email on this user, at once, unless this app's suppression list has
         * it (it unsubscribed, bounced or complained): then the server stores it at -2, never emails
         * it, and the write is reported as [OpenPushEmailError.Code.SUPPRESSED] and not retried. The
         * address is trimmed and lower-cased, and must have one `@`, a name before it, a dot in the
         * domain and at most 254 characters; anything else returns false, logs why, reports
         * [OpenPushEmailError.Code.INVALID_EMAIL] and sends nothing. An accepted write is persisted,
         * sent on the registration queue with the current external id (and its auth hash under
         * identity verification), and retried while the server cannot be reached or fails (5xx, 429);
         * one made before [initialize] waits for it. A refusal in words (400) is
         * [OpenPushEmailError.Code.REFUSED] and is not retried.
         *
         * [callback], when given, hears the server's final answer once, on the main thread (an
         * invalid address: posted at once). It lives for this process only, and is dropped when a
         * newer write for the same address replaces this one before it is sent.
         */
        @JvmStatic
        @JvmOverloads
        fun addEmail(address: String, callback: ((OpenPushEmailResult) -> Unit)? = null): Boolean =
            PhoneSubscriptions.record(PhonePlatform.EMAIL, address, removal = false, callback = callback)

        /**
         * Removes the email subscription for [address] (status -2), offline-first like [addEmail]. It
         * is not an unsubscribe: no suppression is written, and a later [addEmail] subscribes the
         * address again.
         */
        @JvmStatic
        @JvmOverloads
        fun removeEmail(address: String, callback: ((OpenPushEmailResult) -> Unit)? = null): Boolean =
            PhoneSubscriptions.record(PhonePlatform.EMAIL, address, removal = true, callback = callback)
    }

    /**
     * Phone codes: send a one-time code to a number and check what the user typed. An approved code
     * is what makes the number's SMS and WhatsApp subscriptions subscribed on the server.
     *
     * Each call is a single request with no retry, because a person is waiting on it. The suspend
     * forms throw [OpenPushVerifyError] and resume in the caller's coroutine context; the callback
     * forms report an [OpenPushVerifyResult] on the main thread. A number that is not
     * a valid international number after the same normalisation as [User.addSms] fails with
     * [OpenPushVerifyError.Code.INVALID_NUMBER] before anything is sent.
     */
    object Verify {
        /** Sends a code to [phone] over [channel]; [locale] (`"en"`, `"pt-BR"`) picks the message language. */
        @JvmStatic
        @JvmOverloads
        suspend fun start(
            phone: String,
            channel: OpenPushVerifyChannel = OpenPushVerifyChannel.AUTO,
            locale: String? = null,
        ): OpenPushVerification = PhoneVerifications.start(phone, channel, locale)

        /** Callback form of [start]. */
        @JvmStatic
        @JvmOverloads
        fun start(
            phone: String,
            channel: OpenPushVerifyChannel = OpenPushVerifyChannel.AUTO,
            locale: String? = null,
            callback: (OpenPushVerifyResult) -> Unit,
        ) = PhoneVerifications.deliver(callback) { PhoneVerifications.start(phone, channel, locale) }

        /**
         * Checks [code] against [verification]. A wrong code is not an error: it comes back still
         * [OpenPushVerificationStatus.PENDING] with [OpenPushVerification.attempts] counted up, so read
         * the returned status for the outcome.
         */
        @JvmStatic
        suspend fun check(verification: OpenPushVerification, code: String): OpenPushVerification =
            PhoneVerifications.check(verification, code)

        /** Callback form of [check]. */
        @JvmStatic
        fun check(verification: OpenPushVerification, code: String, callback: (OpenPushVerifyResult) -> Unit) =
            PhoneVerifications.deliver(callback) { PhoneVerifications.check(verification, code) }

        /**
         * Sends the code again over [channel], which defaults to the one the code went out on; pass
         * [OpenPushVerifyChannel.CALL] for a user whose texts are not arriving. The server allows a
         * resend 30 seconds after the last send; sooner fails with [OpenPushVerifyError.Code.TOO_SOON].
         */
        @JvmStatic
        @JvmOverloads
        suspend fun resend(
            verification: OpenPushVerification,
            channel: OpenPushVerifyChannel = verification.channel,
        ): OpenPushVerification = PhoneVerifications.resend(verification, channel)

        /** Callback form of [resend]. */
        @JvmStatic
        @JvmOverloads
        fun resend(
            verification: OpenPushVerification,
            channel: OpenPushVerifyChannel = verification.channel,
            callback: (OpenPushVerifyResult) -> Unit,
        ) = PhoneVerifications.deliver(callback) { PhoneVerifications.resend(verification, channel) }

        /**
         * The 11-character hash the SMS Retriever API needs at the end of the code message to hand
         * that message to this app, or null when the signing certificate cannot be read. It comes
         * from the package name and the signing certificate, so this SDK needs no Google Play
         * services library for it; `SmsRetriever.getClient(context).startSmsRetriever()` is the
         * app's own call, on its own `play-services-auth-api-phone` dependency. Debug and release
         * signing give different hashes; under Play App Signing, use the Play-signed build's.
         */
        @JvmStatic
        fun smsRetrieverHashFor(context: Context): String? = SmsRetrieverHash.forApp(context)
    }

    /** Live subscription view; call [snapshot] for an immutable value. */
    object LivePushSubscription {
        @JvmStatic
        val id: String? get() = subscriptionId

        @JvmStatic
        val token: String? get() = currentToken

        @JvmStatic
        val optedIn: Boolean get() = wireStatus() > 0 && currentToken != null

        @JvmStatic
        fun optIn() = PushSubscription.optIn()

        @JvmStatic
        fun optOut() = PushSubscription.optOut()

        @JvmStatic
        fun snapshot() = PushSubscriptionState(id, token, optedIn)

        /** Compatibility instance spelling for the observer declared on [PushSubscription]. Both
         *  spellings add to the same list, so one registered here is removable through either. */
        @JvmStatic
        fun addObserver(observer: (PushSubscriptionState) -> Unit): ListenerHandle =
            PushSubscription.addObserver(observer)

        @JvmStatic
        fun removeObserver(handle: ListenerHandle) = PushSubscription.removeObserver(handle)

        /** Previous-and-current spelling of [addObserver]; the same list as on [PushSubscription]. */
        @JvmStatic
        fun addChangeObserver(observer: (PushSubscriptionChange) -> Unit): ListenerHandle =
            PushSubscription.addChangeObserver(observer)

        @JvmStatic
        fun removeChangeObserver(handle: ListenerHandle) = PushSubscription.removeChangeObserver(handle)
    }

    /**
     * Opt-in/opt-out for this device's subscription. These write the subscription's status; they do not
     * delete it and they do not touch the provider token.
     */
    object PushSubscription {
        /** Clears the opt-out and prompts from the resumed Activity when permission is missing. */
        @JvmStatic
        fun optIn() = applyOptIn(currentActivity?.get())

        /** Explicit Activity overload for hosts that do not attach with an Application context. */
        @JvmStatic
        fun optIn(activity: Activity) = applyOptIn(activity)

        /** Retains the device permission value under status -2 and re-registers. */
        @JvmStatic
        fun optOut() {
            absorbPendingWrites()
            setOptedOut(true)
            enqueueCurrentRegistration(STATUS_DEFERRED)
            notifySubscriptionObservers()
        }

        /** Notification_types is positive and a provider token is held. */
        @JvmStatic
        val optedIn: Boolean get() = User.pushSubscription.optedIn

        /** Observes subscription changes; the observer runs on the main thread, after the change. */
        @JvmStatic
        fun addObserver(observer: (PushSubscriptionState) -> Unit): ListenerHandle =
            register(subscriptionObservers, observer)

        @JvmStatic
        fun removeObserver(handle: ListenerHandle) {
            synchronized(subscriptionObservers) { subscriptionObservers.remove(handle.id) }
        }

        /**
         * Same events as [addObserver], but each call carries the state before the change next to the
         * state after it. [PushSubscriptionChange.previous] is null on the first observation.
         */
        @JvmStatic
        fun addChangeObserver(observer: (PushSubscriptionChange) -> Unit): ListenerHandle =
            register(subscriptionChangeObservers, observer)

        @JvmStatic
        fun removeChangeObserver(handle: ListenerHandle) {
            synchronized(subscriptionChangeObservers) { subscriptionChangeObservers.remove(handle.id) }
        }
    }

    private fun applyOptIn(activity: Activity?) {
        absorbPendingWrites()
        setOptedOut(false)
        if (refreshPermission()) {
            enqueueCurrentRegistration(STATUS_DEFERRED)
            notifySubscriptionObservers()
            return
        }
        if (activity == null) {
            onLog(
                "optIn: notifications are not permitted and there is no foreground Activity " +
                    "to prompt from — call OpenPush.PushSubscription.optIn(activity), or " +
                    "OpenPush.Notifications.requestPermission(activity). The honest status " +
                    "(${wireStatus()}) is being sent.",
            )
            enqueueCurrentRegistration(STATUS_DEFERRED)
            notifySubscriptionObservers()
            return
        }
        // SDK work, not a host callback: it runs where the answer lands, as before.
        Notifications.requestPermissionFrom(activity, fallbackToSettings = true) {
            enqueueCurrentRegistration(STATUS_DEFERRED)
            notifySubscriptionObservers()
        }
    }

    internal fun optInBlocking() {
        absorbPendingWrites()
        setOptedOut(false)
        refreshPermission()
        runBlocking { pushRegistration(STATUS_DEFERRED) }
        notifySubscriptionObservers()
    }

    internal fun optOutBlocking() {
        absorbPendingWrites()
        setOptedOut(true)
        runBlocking { pushRegistration(STATUS_DEFERRED) }
        notifySubscriptionObservers()
    }

    // Session-state writes
    // These run synchronously on the caller's thread, so consecutive public calls apply in call order.
    // The queued half below only performs the network hop, reading the state as of its own start —
    // exactly the shape iOS (OpenPushIdentity.swift) and Unity (OpenPushClient.cs) already have.

    /**
     * Applies a login to the session, or answers for it outright. A non-null result means the call is
     * already complete and nothing goes on the wire: an empty external id is refused, and a login that
     * repeats an identity the server has already accepted, with no new hash, has nothing to say.
     * Null means the session now carries the new identity and the caller must register.
     */
    private fun beginLogin(externalId: String, authHash: String?): IdentityResult? {
        val id = externalId.trim()
        if (id.isEmpty()) {
            // Sending "" would detach the device — the opposite of what a login means. Refusing here
            // keeps an empty variable in the host's sign-in code from silently signing the user out.
            onLog("login refused — external id is empty; call logout() to detach")
            return IdentityResult.Failed("empty external id")
        }
        val redundant = synchronized(sessionLock) {
            val sameIdentity = pendingExternalId == id && !identityDetached
            val noNewHash = authHash == null || authHash == pendingAuthHash
            // A relaunched session owes its hash again (spec A5), so it is not settled either.
            val awaitingHash = authHashRequired && pendingAuthHash == null
            sameIdentity && noNewHash && identityDelivered && !awaitingHash
        }
        if (redundant) {
            onLog("already logged in as $id — nothing to send")
            return IdentityResult.Success
        }
        absorbPendingWrites()
        recordLogin(id, authHash)
        return null
    }

    /** Detaches the session, or answers for the call when the device is already anonymous. */
    private fun beginLogout(): IdentityResult? {
        if (synchronized(sessionLock) { pendingExternalId } != null) {
            absorbPendingWrites()
            recordLogout()
            return null
        }
        // Nothing to detach from, but the local user data is still this session's to clear — and the
        // open write window would otherwise carry it to the server after the host signed out.
        clearLocalUserData()
        absorbPendingWrites()
        persistSession()
        onLog("already anonymous — nothing to send")
        return IdentityResult.Success
    }

    /** Returns false when the login carries nothing to send — today every login that reaches here does. */
    private fun recordLogin(externalId: String, authHash: String?): Boolean {
        val clearUserData: Boolean
        synchronized(sessionLock) {
            val previous = pendingExternalId
            clearUserData = previous != null && previous != externalId
            pendingExternalId = externalId
            pendingAuthHash = authHash
            identityDetached = false
            authHashRequired = authHash != null
            // Only a login that will be sent reaches here, so the new identity is staged, not taken.
            identityDelivered = false
        }
        if (clearUserData) clearLocalUserData()
        persistSession()
        return true
    }

    private fun recordLogout() {
        synchronized(sessionLock) {
            pendingExternalId = null
            pendingAuthHash = null
            identityDetached = true
            authHashRequired = false
            identityDelivered = false
        }
        clearLocalUserData()
        persistSession()
    }

    /** Tags and aliases are scoped to the identity that set them (spec A4). */
    private fun clearLocalUserData() {
        synchronized(tags) { tags.clear() }
        synchronized(aliases) { aliases.clear() }
    }

    /** The hash is memory-only, so a relaunch leaves a verified identity unprovable. The body omits
     *  `external_id` — the server keeps the existing link — and the host is asked once per process to
     *  log in again with the hash (spec A5). */
    private fun requestVerification(externalId: String) {
        if (verificationRequested) return
        verificationRequested = true
        onLog(
            "identity $externalId needs its auth hash again — external_id omitted until " +
                "login(externalId, authHash) is called",
        )
        pendingVerificationRequest.set(externalId)
        deliverPendingVerification()
    }

    /** The request waits until a hook exists, so a host that installs one after `initialize` still sees it. */
    private fun deliverPendingVerification() {
        val hook = onIdentityVerificationRequired ?: return
        val id = pendingVerificationRequest.getAndSet(null) ?: return
        deliverOnMain("onIdentityVerificationRequired") { hook(id) }
    }

    /**
     * Records the tags the server would accept and names the rule for every one it would not.
     * Returns false when nothing survives, so no request goes out and no coalescing window opens.
     *
     * An empty value is the delete signal, never an invalid value. [allowInvalidKeysForRemoval] is
     * set only by [User.removeTag]/[User.removeTags]: a key stored by a build that predates these
     * rules must still be removable, so a removal answers to the non-empty-key rule alone.
     */
    private fun recordTags(update: Map<String, String>, allowInvalidKeysForRemoval: Boolean = false): Boolean {
        if (update.isEmpty()) return false
        val accepted = LinkedHashMap<String, String>(update.size)
        update.forEach { (key, value) ->
            val rule = when {
                key.isEmpty() -> "a tag key cannot be empty"
                allowInvalidKeysForRemoval && value.isEmpty() -> null
                key.any(Char::isWhitespace) -> "a tag key cannot contain whitespace"
                key.length > TAG_KEY_LIMIT -> "a tag key is limited to $TAG_KEY_LIMIT characters"
                value.length > TAG_VALUE_LIMIT -> "a tag value is limited to $TAG_VALUE_LIMIT characters"
                else -> null
            }
            if (rule != null) {
                onLog("tag '$key' dropped — $rule")
                return@forEach
            }
            accepted[key] = value
        }
        if (accepted.isEmpty()) return false
        synchronized(tags) { tags.putAll(accepted) }
        persistSession()
        return true
    }

    /**
     * Records the aliases the server would accept and names the rule for every one it would not.
     * Returns false when nothing survives, so no request goes out and no coalescing window opens.
     *
     * [deleting] is set only by [User.removeAlias]/[User.removeAliases]: through them an empty id is
     * the delete signal, where through [User.addAliases] it is a mistake that would quietly unset the
     * alias the host meant to write.
     */
    private fun recordAliases(update: Map<String, String>, deleting: Boolean = false): Boolean {
        if (update.isEmpty()) return false
        val accepted = LinkedHashMap<String, String>(update.size)
        update.forEach { (label, id) ->
            val rule = when {
                label.isEmpty() -> "an alias label cannot be empty"
                label in RESERVED_ALIAS_LABELS -> "the server reserves this label"
                id.isEmpty() && !deleting -> "an alias id cannot be empty; use removeAlias"
                else -> null
            }
            if (rule != null) {
                onLog("alias '$label' dropped — $rule")
                return@forEach
            }
            accepted[label] = id
        }
        if (accepted.isEmpty()) return false
        // A tombstone is a deletion in flight, not a label the server holds, so it is not counted.
        val live = synchronized(aliases) {
            aliases.putAll(accepted)
            aliases.count { it.value.isNotEmpty() }
        }
        persistSession()
        if (live > ALIAS_LIMIT && !aliasCapWarned) {
            aliasCapWarned = true
            onLog("⚠ $live alias labels are held — the server keeps at most $ALIAS_LIMIT per user")
        }
        return true
    }

    // The queued network halves

    /** Re-registers so the recorded session state reaches the server, or says it is being held. */
    private suspend fun pushRegistration(deferredNote: String) {
        val token = currentToken ?: return onLog(deferredNote)
        doRegister(token)
    }

    private suspend fun pushIdentity(deferredNote: String): IdentityResult {
        val token = currentToken ?: run {
            onLog(deferredNote)
            return IdentityResult.Success
        }
        return doRegister(token).asIdentityResult()
    }

    internal fun applyTagsBlocking(update: Map<String, String>) {
        absorbPendingWrites()
        if (!recordTags(update)) return
        runBlocking { pushRegistration(TAGS_DEFERRED) }
    }

    internal fun removeTagsBlocking(keys: Collection<String>) {
        absorbPendingWrites()
        if (!recordTags(keys.associateWith { "" }, allowInvalidKeysForRemoval = true)) return
        runBlocking { pushRegistration(TAGS_DEFERRED) }
    }

    internal fun applyAliasesBlocking(update: Map<String, String>) {
        absorbPendingWrites()
        if (!recordAliases(update)) return
        runBlocking { pushRegistration(ALIASES_DEFERRED) }
    }

    internal fun removeAliasesBlocking(labels: Collection<String>) {
        absorbPendingWrites()
        if (!recordAliases(labels.associateWith { "" }, deleting = true)) return
        runBlocking { pushRegistration(ALIASES_DEFERRED) }
    }

    internal fun registerBlocking(token: String, externalId: String? = null) {
        externalId?.trim()?.takeIf(String::isNotEmpty)?.let(::loginForRegister)
        absorbPendingWrites()
        runBlocking { doRegister(token) }
    }
    internal fun receiptBlocking(type: String, messageId: String?) {
        val target = enqueueReceipt(type, messageId)
        if (target != null) runBlocking { doDrainReceipts(target) }
    }

    internal fun flushCustomEventsBlocking() = runBlocking { doFlushCustomEvents() }

    internal fun loginBlocking(externalId: String, authHash: String? = null): IdentityResult =
        beginLogin(externalId, authHash) ?: runBlocking { pushIdentity(LOGIN_DEFERRED) }

    internal fun logoutBlocking(): IdentityResult = beginLogout() ?: runBlocking { pushIdentity(LOGOUT_DEFERRED) }

    private fun RegistrationOutcome.asIdentityResult(): IdentityResult = when (this) {
        RegistrationOutcome.Success -> IdentityResult.Success
        is RegistrationOutcome.Rejected -> IdentityResult.IdentityRejected(note)
        is RegistrationOutcome.Failed -> IdentityResult.Failed(message)
        RegistrationOutcome.Ignored -> IdentityResult.Failed("linked installation changed — identity not applied")
        RegistrationOutcome.Superseded -> IdentityResult.Failed("superseded by a newer registration")
    }

    private fun scheduleFlush(note: String) {
        if (currentToken == null) {
            enqueue { pushRegistration(note) }
            return
        }
        synchronized(windowLock) {
            if (windowTimer != null) return
            windowNote = note
            windowTimer = scheduler.schedule(COALESCE_WINDOW_MS, ::flushPendingWrites)
        }
    }

    private fun flushPendingWrites() {
        val note = synchronized(windowLock) {
            val open = windowTimer ?: return
            open.cancel()
            windowTimer = null
            windowNote.also { windowNote = null }
        } ?: return
        enqueueCurrentRegistration(note)
    }

    private fun absorbPendingWrites() = synchronized(windowLock) {
        windowTimer?.cancel()
        windowTimer = null
        windowNote = null
    }

    /** Flushes pending tag and alias writes when the app leaves the foreground. */
    @JvmStatic
    fun onAppBackgrounded() {
        val backgroundedAt = nowMs()
        OpenPushBridge.notifyBackgrounded()
        synchronized(sessionDurationLock) {
            foregroundStartedAt?.let { startedAt ->
                val elapsed = (backgroundedAt - startedAt).coerceAtLeast(0L)
                // Keep newly accrued time behind any full-size report currently in flight. The
                // endpoint caps one wire report at a day; the durable debt itself may briefly be
                // larger so accepting that day cannot erase seconds accrued while it was posting.
                pendingSessionDurationMs = if (Long.MAX_VALUE - pendingSessionDurationMs < elapsed) {
                    Long.MAX_VALUE
                } else {
                    pendingSessionDurationMs + elapsed
                }
                if (pendingSessionDurationMs > 0L && pendingSessionDurationKey == null) {
                    pendingSessionDurationKey = UUID.randomUUID().toString()
                }
            }
            foregroundStartedAt = null
        }
        persistSession()
        flushPersistSession()
        flushPendingWrites()
        flushCustomEvents()
        enqueuePendingSessionDuration()
        scheduleBackgroundDrain()
    }

    /** Queues at most one duration POST while an earlier background send is queued or in flight. */
    private fun enqueuePendingSessionDuration() {
        val marker = synchronized(sessionDurationLock) {
            if (
                sessionDurationSendMarker != null ||
                pendingSessionDurationKey == null ||
                pendingSessionDurationMs < 1_000L
            ) {
                null
            } else {
                Any().also { sessionDurationSendMarker = it }
            }
        }
        if (marker == null) return
        enqueue {
            try {
                maybeSendPendingSessionDuration()
            } finally {
                synchronized(sessionDurationLock) {
                    if (sessionDurationSendMarker === marker) sessionDurationSendMarker = null
                }
            }
        }
    }

    /** Runs the JobService duration path under the same one-send guard as background callbacks. */
    private suspend fun drainPendingSessionDuration(operation: DrainOperation? = null): Boolean {
        if (operation?.isCancelled == true) return false
        val marker = synchronized(sessionDurationLock) {
            if (pendingSessionDurationKey == null || pendingSessionDurationMs < 1_000L) return true
            if (sessionDurationSendMarker != null) return false
            Any().also { sessionDurationSendMarker = it }
        }
        return try {
            maybeSendPendingSessionDuration(operation)
        } finally {
            synchronized(sessionDurationLock) {
                if (sessionDurationSendMarker === marker) sessionDurationSendMarker = null
            }
        }
    }

    /** Posts one immutable duration ride. A retry reuses its key; newly accrued time does not. */
    private suspend fun maybeSendPendingSessionDuration(operation: DrainOperation? = null): Boolean {
        if (operation?.isCancelled == true) return false
        val snapshot = synchronized(sessionDurationLock) {
            val key = pendingSessionDurationKey ?: return true
            val seconds = (pendingSessionDurationMs / 1_000L)
                .coerceAtMost(MAX_SESSION_DURATION_MS / 1_000L)
            if (seconds <= 0L) return true
            Triple(key, seconds, seconds * 1_000L)
        }
        val config = configuration ?: return false
        val token = currentToken ?: return false
        val (key, seconds, sentMs) = snapshot
        val result = post(
            config,
            "/v1/apps/${config.appID}/sessions",
            OpenPushPayload.appSessionDurationBody(token, seconds, key),
            credential(),
            operation,
        )

        if (operation?.isCancelled == true) return false

        if (result.status in 200..299 || result.status in 400..499) {
            val changed = synchronized(sessionDurationLock) {
                // A workspace switch or reset can retire the report while its request is in flight.
                if (pendingSessionDurationKey != key) return@synchronized false
                pendingSessionDurationMs = (pendingSessionDurationMs - sentMs).coerceAtLeast(0L)
                pendingSessionDurationKey = if (pendingSessionDurationMs == 0L) {
                    null
                } else {
                    UUID.randomUUID().toString()
                }
                true
            }
            if (changed) {
                // Reusing the pre-response key after process death would replay already accepted
                // duration. Commit the subtraction/new key before this successful send completes.
                persistSession()
                flushPersistSession()
            }
            if (result.status in 400..499) {
                onLog("session duration refused (${result.status}) — dropping the report")
            } else {
                onLog("session duration → ${result.status}")
            }
            return true
        }

        onLog("session duration failed (${result.status}) — queued for retry")
        return false
    }

    private fun scheduleBackgroundDrain() {
        if (!hasPendingWork()) return
        appContext?.let(OpenPushSyncJobService::schedule)
    }

    private fun newGeneration(): Long = synchronized(retryLock) {
        retryTimer?.cancel()
        retryTimer = null
        (++registrationGeneration).also { pendingRegistrationGeneration = it }
    }

    private fun completeRegistration(generation: Long) = synchronized(retryLock) {
        if (pendingRegistrationGeneration == generation) pendingRegistrationGeneration = null
    }

    /** Completes the one-shot suspend observers without reading or replacing the host callbacks. */
    private fun completeRegistrationWaiters(accepted: Boolean) {
        val waiters = synchronized(registrationWaiterLock) {
            if (accepted) registrationAcceptedInProcess = true
            registrationWaiters.toList().also { registrationWaiters.clear() }
        }
        waiters.forEach { continuation ->
            if (continuation.isActive) continuation.resume(accepted)
        }
    }

    /** Cancels queued work from an old attach/reset, not an earlier call in this session. */
    private fun supersedeRegistrationSession() = synchronized(retryLock) {
        retryTimer?.cancel()
        retryTimer = null
        pendingRegistrationGeneration = null
        ++registrationGeneration
        ++registrationSession
    }

    /**
     * How long the server asked this device to wait, in milliseconds, or null when it did not ask or
     * asked in a way this SDK cannot read. Both RFC 9110 spellings are accepted: delta-seconds
     * (`Retry-After: 5`) and an HTTP-date (`Retry-After: Wed, 21 Oct 2026 07:28:00 GMT`), the latter
     * measured against [nowMs] and floored at zero so a date already past means "now", not a negative
     * delay. The lookup is case-insensitive: [defaultTransport] lower-cases header names, but a host
     * transport is under no obligation to.
     */
    internal fun retryAfterMs(response: Response): Long? {
        val raw = response.headers.entries
            .firstOrNull { it.key.equals("retry-after", ignoreCase = true) }
            ?.value?.trim()?.takeIf(String::isNotEmpty)
            ?: return null
        raw.toLongOrNull()?.let { return if (it < 0L) null else it * 1_000L }
        val parsed = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("GMT") }
                .parse(raw)
        }.getOrNull() ?: return null
        return (parsed.time - nowMs()).coerceAtLeast(0L)
    }

    /** The delay a failed request should wait: its own schedule, never less than the server asked
     *  for, plus jitter. */
    internal fun backoffDelay(scheduled: Long, retryAfter: Long?): Long {
        val floor = maxOf(scheduled, retryAfter ?: 0L)
        return floor + jitter(floor)
    }

    private fun enqueueCurrentRegistration(note: String) {
        val token = currentToken ?: return onLog(note)
        val generation = newGeneration()
        val session = registrationSession
        enqueue { attempt(token, generation, session, 1, null) }
    }

    private fun enqueueIdentity(note: String, callback: ((IdentityResult) -> Unit)?) {
        val generation = newGeneration()
        val session = registrationSession
        val token = currentToken
        if (token == null) {
            enqueue {
                onLog(note)
                completeRegistration(generation)
                callback?.invoke(IdentityResult.Success)
            }
            return
        }
        enqueue {
            attempt(token, generation, session, 1) {
                callback?.invoke(it.asIdentityResult())
            }
        }
    }

    private suspend fun attempt(
        token: String,
        generation: Long,
        session: Long,
        number: Int,
        onFinal: ((RegistrationOutcome) -> Unit)?,
    ) {
        if (session != registrationSession ||
            (number > 1 && generation != registrationGeneration)
        ) {
            onLog("registration dropped — a newer registration superseded it")
            completeRegistration(generation)
            onFinal?.invoke(RegistrationOutcome.Superseded)
            return
        }
        val outcome = doRegister(token)
        if (outcome is RegistrationOutcome.Failed &&
            outcome.status.isRetryableStatus() &&
            number < MAX_REGISTRATION_ATTEMPTS
        ) {
            val retryDelay = backoffDelay(RETRY_DELAYS_MS[number - 1], outcome.retryAfterMs)
            val superseded = synchronized(retryLock) {
                if (generation != registrationGeneration) {
                    true
                } else {
                    onLog("· registration attempt $number failed (${outcome.status}) — retrying in ${retryDelay}ms")
                    retryTimer = scheduler.schedule(retryDelay) {
                        enqueue {
                            attempt(token, generation, session, number + 1, onFinal)
                        }
                    }
                    false
                }
            }
            if (superseded) {
                onLog("registration dropped — a newer registration superseded it")
                completeRegistration(generation)
                onFinal?.invoke(RegistrationOutcome.Superseded)
            }
            return
        }
        completeRegistration(generation)
        if (outcome is RegistrationOutcome.Failed) {
            completeRegistrationWaiters(false)
            notifyRegistrationFailed(outcome.message)
        }
        onFinal?.invoke(outcome)
    }

    @JvmStatic
    fun messageId(data: Map<String, String>) = OpenPushPayload.messageId(data)

    @JvmStatic
    fun actions(data: Map<String, String>) = OpenPushPayload.actions(data)

    @JvmStatic
    fun clearToken() {
        synchronized(receiptStateLock) { currentToken = null }
        configuration?.let { prefs()?.edit()?.remove(it.tokenKey)?.apply() }
    }

    /** What a single registration attempt did — consumed by the identity calls. */
    internal sealed class RegistrationOutcome {
        object Success : RegistrationOutcome()
        data class Rejected(val note: String) : RegistrationOutcome()

        /** [retryAfterMs] is what the server asked for on this failure, when it asked at all; the
         *  retry waits at least that long. */
        data class Failed(val message: String, val status: Int, val retryAfterMs: Long? = null) : RegistrationOutcome()
        object Ignored : RegistrationOutcome()
        object Superseded : RegistrationOutcome()
    }

    private suspend fun doRegister(token: String): RegistrationOutcome {
        val config = configuration ?: run {
            onLog("registration ignored — attach OpenPush first")
            return RegistrationOutcome.Ignored
        }
        val credentialAtStart = credential()
        val sessionAtStart = registrationSession
        synchronized(receiptStateLock) { currentToken = token }
        prefs()?.edit()?.putString(config.tokenKey, token)?.apply()
        // A registration may be the last operation before process death. Make every local mutation
        // it is about to snapshot durable before building the body that carries that mutation.
        flushPersistSession()
        val body = JSONObject()
            .put("token", token)
            .put("platform", config.platform)
            .put("device", deviceModel())
            .put("language", languageTag())
            .put("timezone", TimeZone.getDefault().id)
            .put("os_version", Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())
            .put("sdk_version", sdkVersion)
            .put("rooted", rootedStatus())
        // The wrapper also rides every request as X-OP-SDK-Wrapper; the body copy matches the iOS
        // SDK so the server can persist it with the subscription.
        sdkWrapper?.takeIf(String::isNotBlank)?.let { body.put("sdk_wrapper", it) }
        Locale.getDefault().country.takeIf(String::isNotBlank)?.let {
            body.put("country", it.uppercase(Locale.ROOT))
        }
        appVersion?.let { body.put("app_version", it) }
        // Migrating apps: the previous push SDK's token rides every registration until one carrying
        // it succeeds, so the server can retire the subscription that token still holds.
        val reportedPriorInstall = pendingPriorInstall()
        reportedPriorInstall?.let { body.put("prior_install", it.toJson()) }
        // One snapshot of the identity triple, so a concurrent login/logout cannot split this body
        // across two states. The identity is only ever the session's: login is the one way in.
        val identity: String?
        val authHash: String?
        val detached: Boolean
        val needsHash: Boolean
        synchronized(sessionLock) {
            identity = pendingExternalId
            authHash = pendingAuthHash
            detached = identityDetached
            needsHash = authHashRequired
        }
        // The identity this body actually asserts, or null when it says nothing about one. Only an
        // asserted identity can be confirmed or refused by the response below.
        var assertedIdentity: String? = null
        when {
            // A verified identity is never asserted unproven: omitting the key leaves the server's
            // existing link alone, where an unhashed assertion would be refused.
            identity != null && needsHash && authHash == null -> requestVerification(identity)
            identity != null -> {
                body.put("external_id", identity)
                authHash?.let { body.put("external_id_auth_hash", it) }
                assertedIdentity = identity
            }
            detached -> body.put("external_id", "") // present-and-empty = detach
        }
        // A removed key rides as "" until a registration carrying it is accepted, then it is pruned;
        // a token-refresh re-registration in between still carries it. Omitted while the map is empty.
        val snapshot = synchronized(tags) { LinkedHashMap(tags) }
        if (snapshot.isNotEmpty()) {
            val json = JSONObject()
            snapshot.forEach { (key, value) -> json.put(key, value) }
            body.put("tags", json)
        }
        // A removed key rides as "" until a registration carrying it is accepted, then it is pruned;
        // a token-refresh re-registration in between still carries it. Identical rule to tags above.
        val aliasSnapshot = synchronized(aliases) { LinkedHashMap(aliases) }
        if (aliasSnapshot.isNotEmpty()) {
            val json = JSONObject()
            aliasSnapshot.forEach { (label, id) -> json.put(label, id) }
            body.put("aliases", json)
        }
        val status = wireStatus()
        body.put("status", status)
        onLog("→ POST /v1/apps/${config.appID}/subscriptions")
        val result = post(config, "/v1/apps/${config.appID}/subscriptions", body.toString(), credentialAtStart)
        if (credential() != credentialAtStart) {
            onLog("registration response ignored — linked installation changed")
            return RegistrationOutcome.Ignored
        }
        if (result.status in 200..299) {
            val ack = OpenPushPayload.registrationAck(result.body)
            // Everything this response writes back into the session happens inside the one lock a
            // reset or a workspace switch takes to supersede the session, with the session check
            // inside it. Check-and-write is therefore a single step: a supersede either happens
            // wholly before it — the check fails and nothing is written — or wholly after it. A
            // superseded registration can never edit the session a new attach has just restored.
            //
            // Nothing that calls back into the host runs from in here. The log lines, the observers
            // and the rejection hook below are delivered afterwards, from values captured inside.
            val logs = mutableListOf<String>()
            val rejection = ack.identityRejection
            var accepted = false
            synchronized(retryLock) {
                if (registrationSession == sessionAtStart) {
                    accepted = true
                    lastAcceptedStatus = status
                    // The server took the identity this body asserted, so a repeat of it is now a
                    // no-op. Still current? A login that landed while this request was in flight
                    // owns the session.
                    if (assertedIdentity != null && rejection == null) {
                        synchronized(sessionLock) {
                            if (pendingExternalId == assertedIdentity && !identityDetached) {
                                identityDelivered = true
                            }
                        }
                    }
                    // The server took the removals this body carried, so stop carrying them.
                    var mutated = false
                    synchronized(tags) {
                        snapshot.filterValues(String::isEmpty).keys.forEach {
                            if (tags[it] == "") {
                                tags.remove(it)
                                mutated = true
                            }
                        }
                    }
                    synchronized(aliases) {
                        aliasSnapshot.filterValues(String::isEmpty).keys.forEach {
                            if (aliases[it] == "") {
                                aliases.remove(it)
                                mutated = true
                            }
                        }
                    }
                    ack.subscriptionId?.let {
                        if (subscriptionId != it) {
                            subscriptionId = it
                            mutated = true
                        }
                    }
                    // The status is adopted on the opt-out axis only (spec A6): the server can have
                    // retired this subscription since the last registration, and that is the one
                    // thing its status says which the SDK does not already know.
                    ack.status?.let { adopted ->
                        when {
                            adopted == STATUS_OPTED_OUT && !optedOut -> {
                                logs += "server reports opt-out (-2) — adopting"
                                setOptedOut(true)
                            }
                            adopted > 0 && optedOut -> {
                                logs += "server reports status $adopted — clearing the local opt-out"
                                setOptedOut(false)
                            }
                            adopted < 0 && adopted != STATUS_OPTED_OUT ->
                                logs += "server reports status $adopted " +
                                    "(${OpenPushPayload.statusName(adopted)}) — reported, not adopted"
                        }
                    }
                    if (ack.notCollected.isNotEmpty()) {
                        logs += "not_collected — this app does not store ${ack.notCollected.joinToString(", ")}"
                    }
                    // Only a real change is written back: an ordinary registration must not race a
                    // cold start by rewriting a session it did not alter (the adoption above
                    // persists its own).
                    if (mutated) persistSession()
                    // A rejection is part of the same write-back — it clears the identity this body
                    // asserted — so it answers to the same session guard and the same lock.
                    if (rejection != null) {
                        var cleared = false
                        synchronized(sessionLock) {
                            if (assertedIdentity != null && pendingExternalId == assertedIdentity) {
                                pendingExternalId = null
                                pendingAuthHash = null
                                authHashRequired = false
                                identityDelivered = false
                                cleared = true
                            }
                        }
                        if (cleared) persistSession()
                    }
                }
            }
            logs.forEach { onLog(it) }
            if (accepted) {
                // The accepted id, tombstone pruning, status adoption and identity rejection are
                // request results rather than a burst of host writes; keep their old durability.
                flushPersistSession()
                notifySubscriptionObservers()
                completeRegistrationWaiters(true)
            } else {
                onLog("registration response not applied — a newer session owns this state")
            }
            OpenPushBridge.notifySubscriptionReady()
            onLog("✓ registered with OpenPush")
            notifyRegistered()
            scheduleCustomEventFlush(0)
            drainReceipts()
            if (ack.refusedAliases.isNotEmpty()) {
                onLog(
                    "⚠ aliases refused — the server did not store ${ack.refusedAliases.joinToString(", ")} " +
                        "(reserved label, or over the per-user cap)",
                )
            }
            if (rejection != null) {
                onLog("⚠ identity rejected — this device is registered anonymously · $rejection")
                // Fires for every rejection this session owns, including one a tag or alias flush
                // provoked, so a host that never called login still learns its identity link is
                // gone. A superseded registration speaks for nobody and fires nothing.
                if (accepted) {
                    onIdentityRejected?.let { hook -> deliverOnMain("onIdentityRejected") { hook(rejection) } }
                }
                return RegistrationOutcome.Rejected(rejection)
            }
            if (reportedPriorInstall != null) markPriorInstallReported()
            return RegistrationOutcome.Success
        }
        val detail = "registration failed (${result.status})"
        onLog("✗ $detail")
        onLog(hintFor(config, result.status))
        return RegistrationOutcome.Failed(detail, result.status, retryAfterMs(result))
    }

    internal fun languageTag(locale: Locale = Locale.getDefault()): String {
        val language = when (locale.language.lowercase(Locale.ROOT)) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            else -> locale.language.lowercase(Locale.ROOT)
        }
        val country = locale.country.uppercase(Locale.ROOT)
        return if (language == "zh" && country.isNotBlank()) "$language-$country" else language
    }

    private fun rootedStatus(): Boolean = Build.TAGS?.contains("test-keys") == true ||
        listOf("/system/app/Superuser.apk", "/system/xbin/su", "/system/bin/su", "/sbin/su")
            .any { File(it).exists() }

    private fun newPendingReceipts() = PendingReceipts(::stagingPrefs, now = { nowMs() })

    // Before attach there is no appContext, but the startup initializer has already handed over the
    // Application; the file is the same one attach later reads.
    private fun stagingPrefs() = prefs() ?: runCatching {
        foregroundApplication?.getSharedPreferences("openpush", Context.MODE_PRIVATE)
    }.getOrNull()

    private fun newReceiptQueue(config: OpenPushConfiguration) = ReceiptQueue(
        prefs(),
        sessionKey(config, "receipts"),
        sessionKey(config, "receiptsSent"),
        { nowMs() },
    )

    /** True while the server still owes an acknowledgement for at least one receipt. */
    internal fun hasPendingReceipts(): Boolean = (receiptQueue?.size ?: 0) > 0

    /** Work a background job can make progress on without inventing a new SDK operation. */
    internal fun hasPendingWork(): Boolean {
        if (hasPendingReceipts()) return true
        if (synchronized(customEventLock) { customEvents.isNotEmpty() }) return true
        if (synchronized(sessionDurationLock) { pendingSessionDurationMs >= 1_000L }) return true
        if (synchronized(windowLock) { windowTimer != null }) return true
        return synchronized(retryLock) { pendingRegistrationGeneration != null }
    }

    /** Whole foreground seconds waiting for a duration acknowledgement. */
    internal fun pendingSessionDurationSeconds(): Long =
        synchronized(sessionDurationLock) { pendingSessionDurationMs / 1_000L }

    /** A read-only snapshot for optional modules and the integration lab. */
    internal fun diagnostics(): Map<String, Any?> = linkedMapOf(
        "receiptsQueued" to (receiptQueue?.size ?: 0),
        "customEventsQueued" to synchronized(customEventLock) {
            customEvents.size + stagedCustomEvents.size
        },
        "pendingSessionSeconds" to pendingSessionDurationSeconds(),
        "lastDrainAt" to lastDrainAt,
    )

    /**
     * Serializes a complete background drain behind work already queued by [onAppBackgrounded].
     * The Boolean tells [OpenPushSyncJobService] whether Android can retire the job. Durable queues
     * stay populated on a transient failure, and a registration retry remains marked pending.
     */
    internal suspend fun drainAll(): Boolean {
        // A JobService can be started by an already-scheduled job while a fresh coalescing window is
        // open. Put that write on the registration channel before the drain's barrier goes behind it.
        flushPendingWrites()
        return suspendCancellableCoroutine { continuation ->
            val operation = DrainOperation()
            continuation.invokeOnCancellation { operation.cancel() }
            enqueue drain@{
                if (operation.isCancelled) return@drain
                val succeeded = runCatching {
                    val receiptsSucceeded = currentReceiptDrainTarget()?.let {
                        doDrainReceipts(it, operation)
                    } ?: !hasPendingReceipts()
                    var eventsSucceeded = true
                    while (!operation.isCancelled && synchronized(customEventLock) { customEvents.isNotEmpty() }) {
                        if (!doFlushCustomEvents(scheduleNext = false, operation = operation)) {
                            eventsSucceeded = false
                            break
                        }
                    }
                    val durationSucceeded = drainPendingSessionDuration(operation)
                    !operation.isCancelled &&
                        receiptsSucceeded &&
                        eventsSucceeded &&
                        durationSucceeded &&
                        !hasPendingWork()
                }.getOrElse {
                    onLog("background drain failed — ${it.message}")
                    false
                }
                if (operation.isCancelled) return@drain
                // This is the completion timestamp, not the time a lifecycle callback merely
                // scheduled the job. A stopped job never records a completion it did not own.
                lastDrainAt = nowMs()
                if (continuation.isActive) continuation.resume(succeeded)
            }
        }
    }

    /**
     * Records the receipt as owed and returns the exact workspace drain must use. A duplicate still
     * returns that target so an existing durable entry gets another delivery opportunity; a missing
     * message id, workspace, or provider token returns null.
     */
    private fun enqueueReceipt(type: String, messageId: String?): ReceiptDrainTarget? {
        if (messageId.isNullOrEmpty()) {
            onLog("receipt $type dropped — no op_message_id")
            return null
        }
        var staged: Boolean? = null
        val recorded = synchronized(receiptStateLock) {
            val binding = receiptBinding
            if (binding == null) {
                // Not attached yet: a cold-start tap or a delivery to a killed app. Held until
                // initialize instead of dropped, and moved into the queue by the first attach.
                staged = pendingReceipts.stage(type, messageId)
                return@synchronized null
            }
            absorbStagedReceiptsLocked(binding)
            val token = currentToken ?: return@synchronized null
            val target = ReceiptDrainTarget(
                binding.session,
                binding.configuration,
                binding.queue,
                binding.credential,
            )
            binding.queue.enqueue(type, messageId, token, binding.configuration.appID) to target
        }
        when (staged) {
            true -> onLog("staged receipt $type for $messageId until initialize")
            false -> onLog("· receipt $type · $messageId already staged until initialize")
            null -> Unit
        }
        if (recorded == null) {
            if (staged == null && currentToken == null) onLog("receipt $type dropped — no device token")
            return null
        }
        if (!recorded.first) onLog("· receipt $type · $messageId already queued or acknowledged")
        return recorded.second
    }

    /**
     * Asks for a drain on the registration channel, so it is serialized with registrations rather
     * than racing one. Called on every foreground and after every accepted registration — the two
     * moments the device has both a network and a subscription the server will recognise.
     */
    internal fun drainReceipts() {
        val target = currentReceiptDrainTarget() ?: return
        if (target.queue.size == 0) return
        enqueue { doDrainReceipts(target) }
    }

    /** Drains on the caller's thread. For the messaging-service paths and for tests. */
    internal fun drainReceiptsBlocking() {
        val target = currentReceiptDrainTarget() ?: return
        runBlocking { doDrainReceipts(target) }
    }

    private fun currentReceiptDrainTarget(): ReceiptDrainTarget? = synchronized(receiptStateLock) {
        val binding = receiptBinding ?: return@synchronized null
        absorbStagedReceiptsLocked(binding)
        ReceiptDrainTarget(
            binding.session,
            binding.configuration,
            binding.queue,
            binding.credential,
        )
    }

    /**
     * Moves receipts staged before attach into [binding]'s durable queue once a token is known, keeping
     * the moment each was recorded. The wire format has no timestamp, so they post in recorded order.
     * Without a token they stay staged for the drain that follows the first accepted registration.
     */
    private fun absorbStagedReceiptsLocked(binding: ReceiptBinding) {
        val token = currentToken ?: return
        pendingReceipts.drain { entry ->
            binding.queue.enqueue(entry.type, entry.messageId, token, binding.configuration.appID, entry.recordedAt)
        }
    }

    private fun ownsReceiptTarget(target: ReceiptDrainTarget): Boolean = synchronized(receiptStateLock) {
        val binding = receiptBinding
        registrationSession == target.session &&
            binding?.session == target.session &&
            binding.queue === target.queue
    }

    /**
     * Posts every owed receipt, one `/v1/ingest` request per batch of at most
     * [ReceiptQueue.BATCH_LIMIT] sharing a token and an app. A batch that failed for a reason that
     * can pass (5xx, 429, no network) stays on the queue and stops the drain: the receipts behind it
     * are no more likely to land, and hammering a server that just answered 503 helps nobody. A batch
     * the server refused outright (any other 4xx) is dropped and the drain goes on to the next one,
     * so one bad batch cannot hold every later receipt back.
     */
    private suspend fun doDrainReceipts(target: ReceiptDrainTarget, operation: DrainOperation? = null): Boolean {
        val config = target.configuration
        val queue = target.queue
        while (true) {
            if (operation?.isCancelled == true || !ownsReceiptTarget(target)) return false
            val batch = queue.nextBatch()
            if (batch.isEmpty()) return true
            var batchSettled = false
            try {
                val result = post(
                    config,
                    "/v1/ingest",
                    OpenPushPayload.receiptBody(
                        batch.map { it.type to it.messageId },
                        batch.first().token,
                        batch.first().appId,
                    ),
                    target.credential(),
                    operation,
                )
                if (operation?.isCancelled == true || !ownsReceiptTarget(target)) return false
                if (result.status in 200..299) {
                    queue.markSent(batch)
                    batchSettled = true
                    val first = batch.first()
                    if (batch.size == 1) {
                        onLog("✓ receipt ${first.type} · ${first.messageId}")
                    } else {
                        onLog("✓ receipts sent (${batch.size})")
                    }
                } else if (!result.status.isRetryableStatus()) {
                    queue.drop(batch)
                    batchSettled = true
                    onLog("✗ receipts dropped (${result.status}) — the server refused ${batch.size}, not retrying them")
                } else {
                    val evicted = queue.markFailed(batch, serverAnswered = result.status > 0)
                    batchSettled = true
                    if (evicted > 0) {
                        onLog(
                            "✗ receipts dropped ($evicted) — owed for over " +
                                "${ReceiptQueue.MAX_AGE_MS / 86_400_000L} days or failed " +
                                "${ReceiptQueue.MAX_SERVER_FAILURES} times",
                        )
                    }
                    // Never "failed" on its own: the receipt is not lost, and an operator reading this
                    // log has to be able to tell a dropped receipt from one that is still owed.
                    onLog(
                        "· receipts queued for retry (${result.status}) — ${queue.size} held, " +
                            "sending again on the next foreground or registration",
                    )
                    return false
                }
            } finally {
                // This executes as soon as a stopped job's serialized wrapper detaches from a
                // non-cooperative host transport. The durable records remain owed and can be
                // claimed by the rescheduled job without waiting for that old transport to return.
                if (!batchSettled) queue.releaseClaims(batch)
            }
        }
    }

    private suspend fun post(
        config: OpenPushConfiguration,
        path: String,
        body: String,
        deviceCredential: String?,
        operation: DrainOperation? = null,
    ): Response {
        val headers = mutableMapOf("X-OP-SDK-Key" to config.sdkKey, "Content-Type" to "application/json")
        deviceCredential?.let { headers["X-OP-Device-Key"] = it }
        headers.putSdkHeaders()
        return try {
            callTransport(Request("POST", config.url(path), headers, body), operation)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Response(-1, error.message ?: "network error")
        }
    }

    /**
     * Gives a background drain ownership of the transport attempt without making the registration
     * consumer wait for a custom suspend function that ignores cancellation. The attempt runs in a
     * separate Job; cancellation asks it to stop and races its result against [DrainOperation]. The
     * default transport cooperates by disconnecting its socket, while non-cooperative host code may
     * finish later and has no path back into the retired drain.
     */
    private suspend fun callTransport(request: Request, operation: DrainOperation?): Response {
        val transportAtStart = transport
        if (operation == null) return transportAtStart(request)
        if (operation.isCancelled) throw CancellationException("background drain stopped")

        val outcome = CompletableDeferred<Result<Response>>()
        val attempt = scope.launch {
            outcome.complete(runCatching { transportAtStart(request) })
        }
        if (!operation.own(attempt)) throw CancellationException("background drain stopped")
        return try {
            select {
                outcome.onAwait { it.getOrThrow() }
                operation.cancelled.onAwait {
                    throw CancellationException("background drain stopped")
                }
            }
        } finally {
            operation.release(attempt)
            if (!attempt.isCompleted) attempt.cancel()
        }
    }

    internal suspend fun bridgeRequest(method: String, path: String, body: String?): Response {
        val config = configuration ?: return Response(-1, "OpenPush is not initialized")
        val headers = mutableMapOf("X-OP-SDK-Key" to config.sdkKey)
        if (body != null) headers["Content-Type"] = "application/json"
        credential()?.let { headers["X-OP-Device-Key"] = it }
        headers.putSdkHeaders()
        return try {
            transport(Request(method, config.url(path), headers, body))
        } catch (error: Exception) {
            Response(-1, error.message ?: "network error")
        }
    }

    /** Stamps the SDK identity onto a request's headers. Every request this SDK makes carries
     *  [X_OP_SDK]; only a host that set [sdkWrapper] also carries [X_OP_SDK_WRAPPER]. */
    private fun MutableMap<String, String>.putSdkHeaders() {
        put(X_OP_SDK, "android/$sdkVersion")
        sdkWrapper?.trim()?.takeIf(String::isNotEmpty)?.let { put(X_OP_SDK_WRAPPER, it) }
    }

    internal fun bridgeAppId(): String? = configuration?.appID
    internal fun bridgeContext(): Context? = appContext
    internal fun bridgeTokenKey(): String? = configuration?.tokenKey
    internal fun bridgeIsDebuggable(): Boolean =
        (appContext?.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0
    internal fun bridgeUrl(path: String): String? = configuration?.url(path)
    internal fun bridgeHeaders(): Map<String, String> = buildMap {
        configuration?.sdkKey?.let { put("X-OP-SDK-Key", it) }
        credential()?.let { put("X-OP-Device-Key", it) }
        putSdkHeaders()
    }

    @JvmStatic
    fun shortToken(token: String) = if (token.length <= 20) token else token.take(12) + "…(${token.length} chars)"

    private fun defaultLogSink(message: String) {
        if (bridgeIsDebuggable()) android.util.Log.i("OpenPush", message)
    }

    @JvmStatic
    fun deviceModel(): String {
        val model = Build.MODEL ?: "Android"
        val maker = Build.MANUFACTURER ?: ""
        return if (maker.isEmpty() || model.startsWith(maker, true)) {
            model
        } else {
            "${maker.replaceFirstChar { it.uppercase() }} $model"
        }
    }

    private fun hintFor(config: OpenPushConfiguration, code: Int) = when (code) {
        401 -> "  ↳ this device is not linked — sign in first."
        404 -> "  ↳ app '${config.appID}' was not found on this server."
        -1 -> "  ↳ the request did not reach the server. Check the URL and network."
        else -> "  ↳ inspect the matching server request for details."
    }

    private fun prefs() = runCatching {
        appContext?.getSharedPreferences("openpush", Context.MODE_PRIVATE)
    }.onFailure { failure ->
        val shouldLog = synchronized(preferencesFailureLock) {
            if (preferencesFailureLogged) {
                false
            } else {
                preferencesFailureLogged = true
                true
            }
        }
        if (shouldLog) onLog("OpenPush preferences unavailable — ${failure.message ?: "locked storage"}")
    }.getOrNull()

    /**
     * The prior install this registration should report, or null. Read at most once per process. An
     * empty read is recorded and never repeated; a found one is read again each launch until a
     * registration carrying it succeeds. Without preferences nothing can be recorded, so nothing is
     * read either.
     */
    private fun pendingPriorInstall(): PriorInstall? {
        val logs = mutableListOf<String>()
        val found = synchronized(priorInstallLock) {
            if (!priorInstallLoaded) {
                val preferences = prefs() ?: return null
                val context = appContext ?: return null
                priorInstallLoaded = true
                if (!preferences.contains(PRIOR_INSTALL_KEY)) {
                    priorInstall = runCatching { priorInstallSource(context).read() }.getOrNull()
                    if (priorInstall == null) {
                        preferences.edit().putString(PRIOR_INSTALL_KEY, PRIOR_INSTALL_NONE).apply()
                    } else {
                        logs += "prior install found — reporting it with the next registration"
                    }
                }
            }
            priorInstall
        }
        logs.forEach { onLog(it) }
        return found
    }

    private fun markPriorInstallReported() {
        synchronized(priorInstallLock) {
            if (priorInstall == null) return
            priorInstall = null
            prefs()?.edit()?.putString(PRIOR_INSTALL_KEY, PRIOR_INSTALL_REPORTED)?.apply()
        }
        onLog("prior install reported")
    }

    // Install-wide rather than per session: it describes the device, not a workspace. Absent while
    // nothing is settled (not read yet, or found and still owed), then one of the two values below.
    private const val PRIOR_INSTALL_KEY = "openpush.priorInstall"
    private const val PRIOR_INSTALL_NONE = "none"
    private const val PRIOR_INSTALL_REPORTED = "reported"

    // Session values are namespaced by tokenKey so custom token stores cannot overlap. The external
    // identity auth hash is deliberately absent: it is proof material, not durable session state.
    private fun sessionKey(config: OpenPushConfiguration, suffix: String) = "${config.tokenKey}.$suffix"

    private const val SCHEMA_VERSION_KEY = "openpush.schemaVersion"
    private const val SCHEMA_VERSION = 2

    private fun customEventHeadKey(config: OpenPushConfiguration) = sessionKey(config, "customEvents.head")
    private fun customEventCountKey(config: OpenPushConfiguration) = sessionKey(config, "customEvents.count")
    private fun customEventSlotKey(config: OpenPushConfiguration, slot: Int) =
        sessionKey(config, "customEvents.slot.$slot")

    private fun restoreCustomEvents(config: OpenPushConfiguration) {
        val preferences = prefs() ?: return
        synchronized(customEventLock) {
            val countKey = customEventCountKey(config)
            if (preferences.contains(countKey)) {
                val count = preferences.getInt(countKey, -1)
                val head = preferences.getInt(customEventHeadKey(config), -1)
                val restored = runCatching {
                    require(count in 0..CUSTOM_EVENT_QUEUE_CAP)
                    require(head in 0 until CUSTOM_EVENT_QUEUE_CAP)
                    List(count) { offset ->
                        val slot = (head + offset) % CUSTOM_EVENT_QUEUE_CAP
                        JSONObject(requireNotNull(preferences.getString(customEventSlotKey(config, slot), null)))
                    }
                }.getOrNull()
                if (restored == null) {
                    onLog("stored custom events ignored — invalid queue")
                    customEvents.clear()
                    customEventHead = 0
                    rewriteCustomEventsLocked(preferences, config)
                } else {
                    customEvents += restored
                    customEventHead = head
                    // A successfully migrated queue never consults the old whole-array value again.
                    preferences.edit().remove(sessionKey(config, "customEvents")).apply()
                }
                return
            }

            // Builds before schema 2 stored the entire queue as one JSON array. Read it once, keep
            // FIFO order (and the newest 500 if a malformed older build exceeded the cap), then fan
            // it out into fixed slots so every later enqueue writes one event instead of the queue.
            val raw = preferences.getString(sessionKey(config, "customEvents"), null)
            if (raw.isNullOrEmpty()) {
                customEventHead = 0
                return
            }
            val restored = runCatching {
                val array = JSONArray(raw)
                val first = (array.length() - CUSTOM_EVENT_QUEUE_CAP).coerceAtLeast(0)
                List(array.length() - first) { offset -> array.getJSONObject(first + offset) }
            }.getOrElse {
                onLog("stored custom events ignored — invalid JSON")
                emptyList()
            }
            customEvents += restored
            customEventHead = 0
            rewriteCustomEventsLocked(preferences, config)
        }
    }

    /** Adds one durable queue record. The number and size of preference values written here do not
     *  grow with the queue: the previous events stay in their slots. */
    private fun enqueueCustomEventLocked(event: JSONObject): Boolean {
        val config = configuration ?: return false
        var overflowed = false
        if (customEvents.size == CUSTOM_EVENT_QUEUE_CAP) {
            customEvents.removeFirst()
            customEventHead = (customEventHead + 1) % CUSTOM_EVENT_QUEUE_CAP
            if (!customEventCapWarned) {
                customEventCapWarned = true
                overflowed = true
            }
        }
        val slot = (customEventHead + customEvents.size) % CUSTOM_EVENT_QUEUE_CAP
        customEvents.addLast(event)
        prefs()?.edit()
            ?.putString(customEventSlotKey(config, slot), event.toString())
            ?.putInt(customEventHeadKey(config), customEventHead)
            ?.putInt(customEventCountKey(config), customEvents.size)
            ?.remove(sessionKey(config, "customEvents"))
            ?.apply()
        return overflowed
    }

    /** One-time migration/repair and attach-time staged merge. Enqueue never takes this path. */
    private fun rewriteCustomEventsLocked(
        preferences: android.content.SharedPreferences,
        config: OpenPushConfiguration,
    ) {
        val editor = preferences.edit()
        for (slot in 0 until CUSTOM_EVENT_QUEUE_CAP) editor.remove(customEventSlotKey(config, slot))
        customEventHead = 0
        if (customEvents.isEmpty()) {
            editor.remove(customEventHeadKey(config)).remove(customEventCountKey(config))
        } else {
            customEvents.forEachIndexed { slot, event ->
                editor.putString(customEventSlotKey(config, slot), event.toString())
            }
            editor.putInt(customEventHeadKey(config), 0)
                .putInt(customEventCountKey(config), customEvents.size)
        }
        editor.remove(sessionKey(config, "customEvents")).apply()
    }

    private fun mergeStagedCustomEvents(config: OpenPushConfiguration) {
        var overflowed = false
        synchronized(customEventLock) {
            if (stagedCustomEvents.isNotEmpty()) {
                customEvents += stagedCustomEvents
                stagedCustomEvents.clear()
                while (customEvents.size > CUSTOM_EVENT_QUEUE_CAP) {
                    customEvents.removeFirst()
                    if (!customEventCapWarned) {
                        customEventCapWarned = true
                        overflowed = true
                    }
                }
                prefs()?.let { rewriteCustomEventsLocked(it, config) }
            }
            customEventReady = true
        }
        if (overflowed) onLog("custom events over $CUSTOM_EVENT_QUEUE_CAP queued — dropping the oldest")
    }

    private fun removeCustomEventsLocked(count: Int) {
        val config = configuration ?: return
        val removed = minOf(count, customEvents.size)
        val oldHead = customEventHead
        repeat(removed) { customEvents.removeFirst() }
        customEventHead = if (customEvents.isEmpty()) {
            0
        } else {
            (oldHead + removed) % CUSTOM_EVENT_QUEUE_CAP
        }
        val editor = prefs()?.edit() ?: return
        repeat(removed) { offset ->
            editor.remove(customEventSlotKey(config, (oldHead + offset) % CUSTOM_EVENT_QUEUE_CAP))
        }
        if (customEvents.isEmpty()) {
            editor.remove(customEventHeadKey(config)).remove(customEventCountKey(config))
        } else {
            editor.putInt(customEventHeadKey(config), customEventHead)
                .putInt(customEventCountKey(config), customEvents.size)
        }
        editor.remove(sessionKey(config, "customEvents")).apply()
    }

    private fun scheduleCustomEventFlush(delayMillis: Long) = synchronized(customEventLock) {
        if (customEvents.isEmpty() || customEventTimer != null) return
        customEventTimer = scheduler.schedule(delayMillis) { flushCustomEvents() }
    }

    private fun flushCustomEvents() {
        synchronized(customEventLock) {
            customEventTimer?.cancel()
            customEventTimer = null
        }
        enqueue { doFlushCustomEvents() }
    }

    private suspend fun doFlushCustomEvents(scheduleNext: Boolean = true, operation: DrainOperation? = null): Boolean {
        if (operation?.isCancelled == true) return false
        val claimed = synchronized(customEventLock) {
            Triple(
                configuration,
                customEventGeneration,
                customEvents.take(50).map { JSONObject(it.toString()) },
            )
        }
        val config = claimed.first ?: return claimed.third.isEmpty()
        val generation = claimed.second
        val batch = claimed.third
        if (batch.isEmpty()) return true
        val external = synchronized(sessionLock) { pendingExternalId }
        val sub = subscriptionId
        if (external == null && sub == null) {
            onLog("custom events held — register or log in before they can be attributed")
            return false
        }
        batch.forEach { event ->
            if (!event.has("external_id") && !event.has("subscription_id")) {
                if (external != null) {
                    event.put("external_id", external)
                } else {
                    event.put("subscription_id", sub)
                }
            }
        }
        val result = post(
            config,
            "/v1/apps/${config.appID}/events",
            JSONObject().put("events", JSONArray(batch)).toString(),
            credential(),
            operation,
        )
        if (operation?.isCancelled == true) return false
        if (synchronized(customEventLock) { generation != customEventGeneration }) return false
        if (result.status in 200..299) {
            synchronized(customEventLock) {
                removeCustomEventsLocked(batch.size)
            }
            customEventAttempt = 0
            onLog("✓ custom events sent (${batch.size})")
            if (scheduleNext) scheduleCustomEventFlush(0)
            return true
        } else if (result.status in 400..499 && result.status != 429) {
            // The server has judged this batch and will judge it the same way forever. Retaining it
            // wedges the queue: the batch is claimed from the head, so every later event waits behind
            // a request that can only fail. 429 is the one 4xx that means "later", not "never".
            onLog(
                "✗ custom events refused (${result.status}) — batch of ${batch.size} dropped; " +
                    "the server does not accept it and a retry would not change that",
            )
            synchronized(customEventLock) {
                removeCustomEventsLocked(batch.size)
            }
            // The queue moved on, so the backoff position the dropped batch reached is not the next
            // batch's to inherit: a fresh batch's first transient failure waits the first step, not
            // whatever step this one had climbed to.
            customEventAttempt = 0
            if (scheduleNext) scheduleCustomEventFlush(0)
            return true
        } else {
            // 429 carries Retry-After; the schedule is a floor, never a reason to come back sooner
            // than the server asked.
            val delay = backoffDelay(
                CUSTOM_EVENT_RETRY_DELAYS_MS[
                    minOf(customEventAttempt, CUSTOM_EVENT_RETRY_DELAYS_MS.lastIndex),
                ],
                retryAfterMs(result),
            )
            customEventAttempt++
            onLog("✗ custom events failed (${result.status}); retrying in ${delay}ms")
            synchronized(customEventLock) {
                if (customEventRetry == null) {
                    customEventRetry = scheduler.schedule(delay) {
                        synchronized(customEventLock) { customEventRetry = null }
                        scheduleCustomEventFlush(0)
                    }
                }
            }
            return false
        }
    }

    /** Coalesces adjacent state mutations while keeping explicit durability boundaries synchronous. */
    private fun persistSession() {
        if (configuration == null) return
        synchronized(sessionPersistenceLock) {
            sessionPersistPending = true
            if (sessionPersistTimer == null) {
                sessionPersistTimer = scheduler.schedule(SESSION_PERSIST_WINDOW_MS, ::flushPersistSession)
            }
        }
    }

    private fun flushPersistSession() = synchronized(sessionPersistenceLock) {
        if (!sessionPersistPending) return
        sessionPersistTimer?.cancel()
        sessionPersistTimer = null
        sessionPersistPending = false
        persistSessionNow()
    }

    private fun discardPendingSessionPersistenceLocked() {
        sessionPersistTimer?.cancel()
        sessionPersistTimer = null
        sessionPersistPending = false
    }

    private fun persistSessionNow() {
        val config = configuration ?: return
        val preferences = prefs() ?: return
        val tagSnapshot = synchronized(tags) { LinkedHashMap(tags) }
        val aliasSnapshot = synchronized(aliases) { LinkedHashMap(aliases) }
        val externalId: String?
        val detached: Boolean
        val hashRequired: Boolean
        val durationMs: Long
        val durationKey: String?
        synchronized(sessionLock) {
            externalId = pendingExternalId
            detached = identityDetached
            hashRequired = authHashRequired
        }
        synchronized(sessionDurationLock) {
            durationMs = pendingSessionDurationMs
            durationKey = pendingSessionDurationKey
        }

        preferences.edit()
            .putInt(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
            .putString(sessionKey(config, "tags"), JSONObject(tagSnapshot as Map<*, *>).toString())
            .putString(sessionKey(config, "aliases"), JSONObject(aliasSnapshot as Map<*, *>).toString())
            .apply {
                val subscription = subscriptionId
                if (subscription == null) {
                    remove(sessionKey(config, "subscriptionId"))
                } else {
                    putString(sessionKey(config, "subscriptionId"), subscription)
                }
                if (externalId == null) {
                    remove(sessionKey(config, "externalId"))
                } else {
                    putString(sessionKey(config, "externalId"), externalId)
                }
                putBoolean(sessionKey(config, "detached"), detached)
                // The hash itself never lands on disk; only the fact that one is owed does.
                putBoolean(sessionKey(config, "authHashRequired"), hashRequired)
                putInt(sessionKey(config, "notificationTypes"), notificationTypes)
                putBoolean(sessionKey(config, "optedOut"), optedOut)
                putLong(sessionKey(config, "appSessionAt"), lastAppSessionAt)
                putLong(sessionKey(config, "sessionDurationMs"), durationMs)
                if (durationKey == null) {
                    remove(sessionKey(config, "sessionDurationKey"))
                } else {
                    putString(sessionKey(config, "sessionDurationKey"), durationKey)
                }
                remove(sessionKey(config, "status"))
                // Clean up any value written by a pre-alignment build.
                remove(sessionKey(config, "authHash"))
            }
            .apply()
    }

    private fun restoreSession(config: OpenPushConfiguration) {
        val preferences = prefs() ?: return
        val storedSchemaVersion = preferences.getInt(SCHEMA_VERSION_KEY, 0)

        val stagedTags = synchronized(tags) { LinkedHashMap(tags) }
        val restoredTags = decodeStringMap(preferences.getString(sessionKey(config, "tags"), null))
        synchronized(tags) {
            tags.clear()
            tags.putAll(restoredTags)
            tags.putAll(stagedTags)
        }

        val stagedAliases = synchronized(aliases) { LinkedHashMap(aliases) }
        val restoredAliases = decodeStringMap(preferences.getString(sessionKey(config, "aliases"), null))
        synchronized(aliases) {
            aliases.clear()
            aliases.putAll(restoredAliases)
            aliases.putAll(stagedAliases)
        }

        synchronized(sessionLock) {
            val hasStagedIdentity = pendingExternalId != null || pendingAuthHash != null || identityDetached
            if (!hasStagedIdentity) {
                pendingExternalId = preferences.getString(sessionKey(config, "externalId"), null)
                pendingAuthHash = null
                identityDetached = preferences.getBoolean(sessionKey(config, "detached"), false)
                // A session stored without the flag reads false: a host on hashes gets one more
                // unverified assertion on its next launch, and from then on the flag is written on
                // every login.
                authHashRequired = preferences.getBoolean(sessionKey(config, "authHashRequired"), false)
                // A restored identity has not been asserted by this process, and one that owes a hash
                // cannot be until the host supplies it again — either way the next login must be sent.
                identityDelivered = false
            }
        }
        val notificationTypesKey = sessionKey(config, "notificationTypes")
        val legacyStatusKey = sessionKey(config, "status")
        if (preferences.contains(notificationTypesKey)) {
            notificationTypes = preferences.getInt(notificationTypesKey, notificationTypes)
        } else if (storedSchemaVersion < SCHEMA_VERSION && preferences.contains(legacyStatusKey)) {
            // Preserve a decision stored in the single legacy `status` value, splitting it
            // into the retained device value and explicit opt-out axes.
            when (val legacyStatus = preferences.getInt(legacyStatusKey, STATUS_NO_PERMISSION)) {
                STATUS_OPTED_OUT -> optedOut = true
                else -> notificationTypes = legacyStatus
            }
        }
        optedOut = preferences.getBoolean(sessionKey(config, "optedOut"), optedOut)
        lastAppSessionAt = preferences.getLong(sessionKey(config, "appSessionAt"), 0L)
        synchronized(sessionDurationLock) {
            pendingSessionDurationMs = preferences
                .getLong(sessionKey(config, "sessionDurationMs"), 0L)
                .coerceAtLeast(0L)
            pendingSessionDurationKey = preferences
                .getString(sessionKey(config, "sessionDurationKey"), null)
                ?.takeIf(String::isNotBlank)
            if (pendingSessionDurationMs > 0L && pendingSessionDurationKey == null) {
                pendingSessionDurationKey = UUID.randomUUID().toString()
            }
        }
        // The server-side subscription outlives the process, so its id is restored with the session.
        subscriptionId = preferences.getString(sessionKey(config, "subscriptionId"), null) ?: subscriptionId
        // Never retain legacy proof material, even if an older SDK wrote it.
        preferences.edit().remove(sessionKey(config, "authHash")).apply()
    }

    private fun clearPersistedSession(config: OpenPushConfiguration) {
        val preferences = prefs() ?: return
        val editor = preferences.edit()
        editor
            .remove(sessionKey(config, "tags"))
            .remove(sessionKey(config, "aliases"))
            .remove(sessionKey(config, "subscriptionId"))
            .remove(sessionKey(config, "externalId"))
            .remove(sessionKey(config, "authHash"))
            .remove(sessionKey(config, "authHashRequired"))
            .remove(sessionKey(config, "detached"))
            .remove(sessionKey(config, "status"))
            .remove(sessionKey(config, "notificationTypes"))
            .remove(sessionKey(config, "optedOut"))
            .remove(sessionKey(config, "appSessionAt"))
            .remove(sessionKey(config, "sessionDurationMs"))
            .remove(sessionKey(config, "sessionDurationKey"))
            .remove(sessionKey(config, "customEvents"))
            .remove(customEventHeadKey(config))
            .remove(customEventCountKey(config))
            .remove(sessionKey(config, "receipts"))
            .remove(sessionKey(config, "receiptsSent"))
        for (slot in 0 until CUSTOM_EVENT_QUEUE_CAP) editor.remove(customEventSlotKey(config, slot))
        editor.apply()
    }

    private fun decodeStringMap(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, json.optString(key, ""))
                }
            }
        }.getOrElse {
            onLog("stored session data ignored — invalid JSON")
            emptyMap()
        }
    }

    internal suspend fun defaultTransport(req: Request): Response = suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpURLConnection?>()
        val worker = scope.launch(Dispatchers.IO) {
            // Tagged before the socket exists, so every byte this request moves is attributed.
            // Cleared in the outer `finally`: the thread goes back to a pool and must not carry
            // the tag with it.
            TrafficStats.setThreadStatsTag(SOCKET_TAG)
            try {
                val opened = httpConnectionFactory(URL(req.url))
                connection.set(opened)
                if (!continuation.isActive) return@launch
                val response = try {
                    opened.requestMethod = req.method
                    opened.connectTimeout = 10_000
                    opened.readTimeout = 15_000
                    req.headers.forEach(opened::setRequestProperty)
                    req.body?.let {
                        opened.doOutput = true
                        opened.outputStream.use { output -> output.write(it.toByteArray()) }
                    }
                    val status = opened.responseCode
                    val body = (if (status in 200..299) opened.inputStream else opened.errorStream)
                        ?.bufferedReader()?.readText().orEmpty()
                    Response(status, body, responseHeaders(opened))
                } finally {
                    connection.compareAndSet(opened, null)
                    opened.disconnect()
                }
                if (continuation.isActive) runCatching { continuation.resume(response) }
            } catch (error: Throwable) {
                if (error is CancellationException && !continuation.isActive) return@launch
                if (continuation.isActive) runCatching { continuation.resumeWithException(error) }
            } finally {
                connection.getAndSet(null)?.disconnect()
                TrafficStats.clearThreadStatsTag()
            }
        }
        continuation.invokeOnCancellation {
            // HttpURLConnection has no coroutine API. Closing it is the cooperative boundary:
            // connect, response-header, and body reads unblock instead of outliving JobService.
            connection.getAndSet(null)?.disconnect()
            worker.cancel()
        }
    }

    private fun openHttpConnection(url: URL): HttpURLConnection = url.openConnection() as HttpURLConnection

    /** `headerFields` with lower-cased names. The status line arrives under a null key and is not a
     *  header; a repeated header is joined the way it travels on the wire. */
    private fun responseHeaders(connection: HttpURLConnection): Map<String, String> =
        connection.headerFields.orEmpty().mapNotNull { (name, values) ->
            name?.let { it.lowercase(Locale.ROOT) to values.orEmpty().filterNotNull().joinToString(", ") }
        }.toMap()
}

/**
 * Marks declarations that are **not public API**: the seams OpenPush's own modules use to talk to
 * each other. They can change or disappear in any release without notice. Some of them reach the
 * SDK key and the device credential, so using one is a compile error until the call site opts in
 * with `@OptIn(OpenPushInternalApi::class)`.
 */
@RequiresOptIn(
    message = "Internal to the OpenPush modules, not public API; it can change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
annotation class OpenPushInternalApi

/** Optional notification-shade operations installed by a renderer module. */
@OpenPushInternalApi
interface ShadeManager {
    fun clearAll(context: Context)
    fun remove(context: Context, notificationId: Int)
    fun removeGroup(context: Context, group: String)
}

@OpenPushInternalApi
object OpenPushBridge {
    private val foreground = mutableSetOf<() -> Unit>()
    private val background = mutableSetOf<() -> Unit>()
    private val subscriptionReady = mutableSetOf<() -> Unit>()
    private val attached = mutableSetOf<() -> Unit>()
    private val permissionResults = mutableSetOf<(Boolean) -> Unit>()

    @Volatile var shadeManager: ShadeManager? = null

    /** Replaces the received-message record [OpenPush.Notifications.beginReceived] consults; null for the default. */
    @Volatile var receivedMessageLog: ReceivedMessageLog? = null
    val currentActivity: Activity? get() = OpenPush.currentActivity?.get()
    val context: Context? get() = OpenPush.bridgeContext()
    val appId: String? get() = OpenPush.bridgeAppId()
    val subscriptionId: String? get() = OpenPush.subscriptionId
    val token: String? get() = OpenPush.currentToken
    val tokenKey: String? get() = OpenPush.bridgeTokenKey()
    val isDebuggable: Boolean get() = OpenPush.bridgeIsDebuggable()
    fun diagnostics(): Map<String, Any?> = OpenPush.diagnostics()
    fun url(path: String): String? = OpenPush.bridgeUrl(path)

    /** The SDK key and device credential headers. Kotlin-only, so Java code cannot bypass the opt-in. */
    @JvmSynthetic
    fun requestHeaders(): Map<String, String> = OpenPush.bridgeHeaders()

    /** An authenticated request to the OpenPush server. Kotlin-only, like [requestHeaders]. */
    @JvmSynthetic
    suspend fun request(method: String, path: String, body: String? = null) = OpenPush.bridgeRequest(method, path, body)

    fun addLifecycleListener(onForeground: () -> Unit, onBackground: () -> Unit) {
        synchronized(foreground) {
            foreground += onForeground
            background += onBackground
        }
    }
    fun addSubscriptionListener(listener: () -> Unit) {
        synchronized(subscriptionReady) { subscriptionReady += listener }
    }

    /** Fires at the end of every attach, and immediately when the SDK is already attached. */
    fun addAttachListener(listener: () -> Unit) {
        synchronized(attached) { attached += listener }
        if (OpenPush.isInitialized) listener()
    }
    fun addPermissionResultListener(listener: (Boolean) -> Unit) {
        synchronized(permissionResults) { permissionResults += listener }
    }
    fun receiptBody(
        types: List<String>,
        messageId: String,
        token: String,
        appId: String,
        extra: Map<String, Any?> = emptyMap(),
    ): String = OpenPushPayload.receiptBody(types, messageId, token, appId, extra)
    internal fun notifyForegrounded() = synchronized(foreground) { foreground.toList() }.forEach { it() }
    internal fun notifyBackgrounded() = synchronized(foreground) { background.toList() }.forEach { it() }
    internal fun notifySubscriptionReady() =
        synchronized(subscriptionReady) { subscriptionReady.toList() }.forEach { it() }
    internal fun notifyAttached() = synchronized(attached) { attached.toList() }.forEach { it() }
    internal fun notifyPermissionResult(granted: Boolean) =
        synchronized(permissionResults) { permissionResults.toList() }.forEach { it(granted) }
}
