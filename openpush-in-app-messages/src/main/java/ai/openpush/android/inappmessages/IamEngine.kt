package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushBridge
import ai.openpush.android.OpenPushInternalApi
import ai.openpush.android.OpenPushMainThread
import android.annotation.TargetApi
import android.app.Activity
import android.os.Build
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** The message id every developer preview reports itself under. */
internal const val IAM_PREVIEW_ID = "preview"

/**
 * Everything the in-app feature decides: which messages exist, which of them may be shown now, what
 * is on screen, and what the host is told about it. [OpenPushInAppMessages] is the published name
 * for this object and holds no state of its own.
 *
 * Two collections, and the difference between them is the point:
 * - [definitions] is what the server last said. Only a fetch replaces it, and nothing else ever
 *   removes an entry. A message whose trigger has not fired yet, or whose redisplay delay has not
 *   elapsed, stays here waiting for its moment.
 * - [queue] is what may be shown right now. [evaluate] throws it away and re-derives it from
 *   [definitions] whenever anything eligibility depends on has changed.
 *
 * [triggerGeneration] is what keeps an already-displayed message from looping. It moves on a
 * trigger mutation and on a new session; a message that has been displayed records the generation
 * it was displayed under, and cannot be displayed again until the counter has moved past it.
 *
 * **All engine state is confined to the main thread.** The collections have a lock, but the scalars
 * around them — what is on screen, whether a display has rendered, which page it is on, whether the
 * queue is held — do not, and a lock on each would not help: they are read and written together and
 * only make sense as one consistent picture. Confinement gives that for free. Every way in from
 * another thread therefore hops first: the lifecycle, attach, subscription and permission-result
 * callbacks arrive on whatever thread the host called the SDK from; [paused] and the trigger calls
 * are public API a host may use from anywhere; a fetch answers on a network thread; and every
 * [IamScheduler] callback re-enters through [onMain]. The seam is [onMain], which runs inline when
 * it is already on the main thread, so the hop costs nothing on the path that matters. The one
 * field a host can read off the main thread is [paused]'s backing value, which is `@Volatile`.
 * The way out is the host's listeners: [emit] and [notifyClick] post them through
 * [OpenPushMainThread], so they run after the engine step that raised them, in the order raised.
 */
@OptIn(OpenPushInternalApi::class)
internal class IamEngine(
    store: InAppStateStore? = null,
    presenterFactory: () -> IamPresenter = { PopupPresenter() },
    scheduler: IamScheduler = MainThreadScheduler,
    now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val triggerValues = linkedMapOf<String, Any>()
    private val lifecycle = linkedMapOf<UUID, (InAppMessageLifecycleEvent) -> Unit>()
    private val clicks = linkedMapOf<UUID, (InAppMessageClickEvent) -> Unit>()

    /** Every message the last fetch returned, eligible or not. */
    var definitions: List<IamMessage> = emptyList()
        private set

    /** The messages [evaluate] found eligible, in definition order. */
    val queue = ArrayDeque<IamMessage>()

    /** The message on screen, if any. */
    var current: IamMessage? = null
        private set

    /** Moves on every trigger mutation and on every new session. */
    var triggerGeneration: Long = 0L
        private set

    /**
     * Messages whose document failed to load. They are skipped for the rest of the process: a
     * message that cannot render can never report having rendered, so nothing would ever close it
     * and [finish]'s own re-evaluation would put it straight back on screen. A fetch clears the set
     * — the next document may well load.
     */
    private val abandoned = mutableSetOf<String>()

    /** The message a pause took down, owed the head of the queue until display resumes. */
    private var heldByPause: IamMessage? = null

    /**
     * Whether the queue is waiting on a permission prompt an action asked for. Android's prompt is
     * a system dialog over the Activity: putting the next message up behind or on top of it would
     * either hide the prompt or answer it by accident, so the queue waits for the user.
     */
    private var heldForPrompt = false

    /** The backstop that ends [heldForPrompt] when no result ever arrives. */
    private var promptTimer: IamCancellable? = null

    /** Messages that have already asked for a permission; a document gets one ask, not one per tap. */
    private val hasPrompted = mutableSetOf<String>()

    /** Whether the message on screen has reported that its document rendered. */
    private var rendered = false

    /**
     * The page the document on screen last reported moving to, or `null` for a single-page
     * document. Carried on every click event, and cleared with each display.
     */
    var currentPageId: String? = null
        private set

    private var presenter: IamPresenter? = null
    private var durationTimer: IamCancellable? = null

    /**
     * The surface a developer preview is on, if any. Deliberately not [presenter]: a preview is not
     * a display, so it must not be able to be hidden, timed out or finished by the display paths.
     */
    private var previewPresenter: IamPresenter? = null

    /** The Activity a preview was started over, so a link in it has somewhere to open from. */
    private var previewActivity: Activity? = null
    private var previewRendered = false
    private var previewClicked = false

    /** The synthetic message a preview runs as. It is never fetched, queued, counted or recorded. */
    private val previewMessage = IamMessage(
        id = IAM_PREVIEW_ID,
        htmlPath = "",
        layout = "center",
        triggers = emptyList(),
        displayDurationMs = null,
        redisplayLimit = 1,
        redisplayDelayMs = 0L,
        endTimeMs = null,
        hasDynamicTriggers = false,
    )

    /** The single outstanding wake-up for a time-based trigger. See [scheduleDynamicReevaluation]. */
    private var dynamicTimer: IamCancellable? = null

    private var backgroundAt: Long? = null

    /**
     * Whether the app is behind everything else. A fetch from there spends a request on a list
     * nothing can display, so it is refused; the return to the foreground fetches instead.
     */
    private var backgrounded = false

    /** The one outstanding retry of a fetch the server refused. See [fetch]. */
    private var fetchRetryTimer: IamCancellable? = null

    private var sessionAt = now()
    private var lastFetch = 0L

    @Volatile
    var store: InAppStateStore? = store
        private set

    // Seams. Production values are the only ones that ship; the tests replace them wholesale.
    var scope: CoroutineScope = defaultScope()
    var scheduler: IamScheduler = scheduler
    var presenterFactory: () -> IamPresenter = presenterFactory
    var nowMs: () -> Long = now
    var fetchIntervalMs = 30_000L
    var onMain: (() -> Unit) -> Unit = ::postToMain

    /** The shipping [onMain]: inline when already there, posted otherwise, on the SDK's main-thread path. */
    private fun postToMain(action: () -> Unit) = OpenPushMainThread.run(action)

    /** [paused]'s value. Volatile because the getter is public API a host may read from anywhere. */
    @Volatile
    private var pausedValue = false

    /**
     * Suspends and resumes display. Pausing is not a dismissal: the message on screen comes down and
     * goes back to the head of the queue with nothing recorded — no redisplay record, no generation
     * stamp, no `willDismiss`/`didDismiss`, no receipt — so resuming puts the same message back up
     * rather than consuming one of its displays on a moment the user never saw.
     *
     * Settable from any thread; the change takes effect on the main thread, so a host that sets it
     * from a worker sees the display come down a post later rather than from underneath the looper.
     */
    var paused: Boolean
        get() = pausedValue
        set(value) = onMain {
            pausedValue = value
            if (value) hold() else release()
        }

    init {
        // Every one of these arrives on whatever thread the host called the SDK from, so each
        // callback marshals before it touches anything. See the class KDoc's confinement note.
        OpenPushBridge.addAttachListener(::onAttached)
        OpenPushBridge.addLifecycleListener(::onForegrounded, ::onBackgrounded)
        OpenPushBridge.addSubscriptionListener { fetch() }
        OpenPushBridge.addPermissionResultListener { onMain { releasePrompt() } }
    }

    // The five trigger calls are public API a host may make from any thread, and each one ends in a
    // re-evaluation that reads and writes the whole engine — so the mutation travels with it.

    fun addTrigger(key: String, value: Any) = onMain {
        synchronized(lock) { triggerValues[key] = value }
        onTriggersChanged()
    }

    fun addTriggers(values: Map<String, Any>) = onMain {
        synchronized(lock) { triggerValues.putAll(values) }
        onTriggersChanged()
    }

    fun removeTrigger(key: String) = onMain {
        synchronized(lock) { triggerValues.remove(key) }
        onTriggersChanged()
    }

    fun removeTriggers(keys: Collection<String>) = onMain {
        synchronized(lock) { keys.forEach(triggerValues::remove) }
        onTriggersChanged()
    }

    fun clearTriggers() = onMain {
        synchronized(lock) { triggerValues.clear() }
        onTriggersChanged()
    }

    fun addLifecycleListener(listener: (InAppMessageLifecycleEvent) -> Unit): UUID =
        UUID.randomUUID().also { synchronized(lock) { lifecycle[it] = listener } }

    fun removeLifecycleListener(id: UUID) {
        synchronized(lock) { lifecycle.remove(id) }
    }

    fun addClickListener(listener: (InAppMessageClickEvent) -> Unit): UUID =
        UUID.randomUUID().also { synchronized(lock) { clicks[it] = listener } }

    fun removeClickListener(id: UUID) {
        synchronized(lock) { clicks.remove(id) }
    }

    /**
     * Loads this workspace's state. The `tokenKey` that names it is only known once a configuration
     * is attached, so the store cannot be built at process start.
     */
    private fun onAttached() = onMain {
        val context = OpenPushBridge.context ?: return@onMain
        val key = OpenPushBridge.tokenKey ?: return@onMain
        store = InAppStateStore(context.getSharedPreferences(InAppStateStore.PREFS, 0), "state.$key", nowMs)
            .also {
                it.load()
                it.purge()
            }
    }

    private fun onBackgrounded() = onMain {
        backgroundAt = nowMs()
        backgrounded = true
    }

    /**
     * A return after a real absence is a new session: the session-time triggers restart, and every
     * message that has already been displayed is re-armed by the generation bump.
     */
    private fun onForegrounded() = onMain {
        backgrounded = false
        val now = nowMs()
        val at = backgroundAt
        if (at != null && now - at >= NEW_SESSION_MS) {
            backgroundAt = null
            sessionAt = now
            synchronized(lock) { triggerGeneration++ }
            // The fetch that opens a session is the one fetch that must not be throttled: the last
            // one may have been seconds before the user left, and this list is the whole session's.
            fetch(force = true)
        }
        evaluate()
        presentNext()
        scheduleDynamicReevaluation()
    }

    private fun onTriggersChanged() {
        synchronized(lock) { triggerGeneration++ }
        evaluate()
        presentNext()
        scheduleDynamicReevaluation()
    }

    /**
     * Asks the server for this subscription's messages.
     *
     * Three things are refused rather than sent. A fetch inside [fetchIntervalMs] of the last one is
     * throttled, unless [force] — the session-start fetch says so, because a session's list is worth
     * a request however recently the last one went. A fetch while the app is [backgrounded] is
     * dropped with a log: nothing could be displayed with the answer. And an answer the server
     * refuses buys exactly one more attempt, [FETCH_RETRY_MS] later; a second refusal waits for the
     * next session rather than turning into a loop against a server that is already struggling.
     */
    fun fetch(force: Boolean = false) = onMain { fetch(force, retry = false) }

    private fun fetch(force: Boolean, retry: Boolean) {
        val app = OpenPushBridge.appId ?: return
        val subscription = OpenPushBridge.subscriptionId ?: return
        if (backgrounded) {
            OpenPush.onLog("in-app fetch skipped — the app is in the background")
            return
        }
        val now = nowMs()
        if (!force && now - lastFetch < fetchIntervalMs) return
        lastFetch = now
        scope.launch {
            val response = OpenPushBridge.request("GET", "/v1/apps/$app/subscriptions/$subscription/iams")
            if (response.status !in 200..299) {
                OpenPush.onLog("✗ in-app fetch failed (${response.status})")
                if (!retry) onMain { scheduleFetchRetry() }
                return@launch
            }
            // A 2xx that is not a message list (an empty body, a proxy's HTML page) is a refused
            // fetch, not a crash: it gets the same one retry as a non-2xx answer.
            val parsed = runCatching { parseList(response.body) }.getOrElse {
                OpenPush.onLog("✗ in-app fetch failed (${response.status}, not a message list)")
                if (!retry) onMain { scheduleFetchRetry() }
                return@launch
            }
            onMain {
                cancelFetchRetry()
                replaceDefinitions(parsed)
                presentNext()
            }
        }
    }

    private fun scheduleFetchRetry() {
        cancelFetchRetry()
        fetchRetryTimer = scheduler.schedule(FETCH_RETRY_MS) {
            onMain {
                fetchRetryTimer = null
                // Forced, because the failed attempt has already moved `lastFetch`.
                fetch(force = true, retry = true)
            }
        }
    }

    private fun cancelFetchRetry() {
        fetchRetryTimer?.cancel()
        fetchRetryTimer = null
    }

    /**
     * Takes the fetch's answer as the whole truth: a message the server no longer returns is gone,
     * and a message it returns twice is kept once. Re-derives the queue, but presents nothing —
     * the caller decides when the surface may come up.
     */
    fun replaceDefinitions(rows: List<IamMessage>) {
        synchronized(lock) {
            definitions = rows.distinctBy(IamMessage::id)
            abandoned.clear()
        }
        evaluate()
        scheduleDynamicReevaluation()
    }

    /**
     * Rebuilds [queue] from [definitions]. Nothing is removed from [definitions] here: a message
     * that fails today is simply absent from the queue and is reconsidered on the next evaluation.
     */
    fun evaluate() = synchronized(lock) {
        queue.clear()
        val now = nowMs()
        definitions.asSequence()
            .filter { it.id != current?.id }
            .filter { it.id !in abandoned }
            .filter { it.endTimeMs == null || now < it.endTimeMs }
            .filter { eligible(it, now) }
            .forEach(queue::add)
        // A message a pause took down keeps the head: resuming must put back what pausing removed,
        // not whatever happens to come first in definition order.
        heldByPause?.let { held ->
            if (queue.removeAll { it.id == held.id }) queue.addFirst(held)
        }
        Unit
    }

    /**
     * Whether [m] may be displayed now.
     *
     * The two ways a message can be blocked are deliberately distinct:
     * - **`dismissed`** is written when the *user* closes a message. A message with the default
     *   `redisplayLimit` of 1 is a one-shot, so a user dismissal blocks it forever — a second
     *   display would be nagging, not delivery.
     * - **the redisplay record** governs a message that is allowed to come back
     *   (`redisplayLimit > 1`). Three things must hold: the trigger generation must have moved
     *   since this message's last display, the redisplay delay must have elapsed, and the display
     *   count must still be under the limit. The generation is what stops a message that is
     *   allowed to repeat from repeating immediately after its own dismissal.
     */
    private fun eligible(m: IamMessage, now: Long): Boolean {
        val state = store ?: return false
        if (!InAppTriggerController.matches(
                m.triggers,
                triggerValues,
                sessionSeconds(now),
                sinceLastSeconds(now),
            )
        ) {
            return false
        }
        if (m.redisplayLimit <= 1 && m.id in state.dismissed) return false
        val prior = state.redisplay[m.id] ?: return true
        val generationChanged = (state.displayedGeneration[m.id] ?: -1L) != triggerGeneration
        return generationChanged && now - prior.at >= m.redisplayDelayMs && prior.quantity < m.redisplayLimit
    }

    private fun sessionSeconds(now: Long): Double = (now - sessionAt) / 1000.0

    private fun sinceLastSeconds(now: Long): Double =
        store?.lastDismissed?.let { (now - it) / 1000.0 } ?: Double.MAX_VALUE

    /**
     * The earliest instant after [now] at which a `session_time` or `min_time_since` condition that
     * is unsatisfied today could be satisfied by nothing happening at all, or `null` when no
     * definition is waiting on the clock.
     *
     * Only definitions [IamMessage.hasDynamicTriggers] marks are scanned — every other message
     * needs a host call, and waiting for one is not the scheduler's job. Definitions that are
     * already eligible, [abandoned], or past their `end_time` are skipped: the first has nothing to
     * wait for and the other two will never be shown.
     *
     * Only `greater` and `greater_or_equal` are schedulable. Both kinds measure elapsed time, which
     * only grows, so `less` and `less_or_equal` can go from true to false but never the other way,
     * and `equal` would hold for a single instant no timer can be trusted to land on. `not_equal`
     * is true everywhere except that instant, so it is already true and has no deadline either.
     *
     * A `min_time_since` condition is skipped when nothing has ever been dismissed: "never" is
     * already infinitely long ago, so the condition is satisfied now rather than at some later
     * instant.
     */
    fun nextDynamicDeadlineMs(now: Long): Long? = synchronized(lock) {
        definitions.asSequence()
            .filter(IamMessage::hasDynamicTriggers)
            .filter { it.id !in abandoned }
            .filter { it.endTimeMs == null || now < it.endTimeMs }
            .filter { !eligible(it, now) }
            .flatMap { message -> message.triggers.asSequence().flatten() }
            .mapNotNull(::deadlineOf)
            .filter { it > now }
            .minOrNull()
    }

    /** When [condition] starts being true, for the conditions that time alone can turn true. */
    private fun deadlineOf(condition: IamCondition): Long? {
        if (condition.op != "greater" && condition.op != "greater_or_equal") return null
        val seconds = condition.value?.toString()?.toDoubleOrNull() ?: return null
        val elapsed = (seconds * 1000).toLong()
        return when (condition.kind) {
            "session_time" -> sessionAt + elapsed
            "min_time_since" -> store?.lastDismissed?.plus(elapsed)
            else -> null
        }
    }

    /**
     * Puts exactly one wake-up on the clock, at [nextDynamicDeadlineMs], and re-evaluates there.
     * Any outstanding wake-up is cancelled first, so a message whose deadline has moved — or gone —
     * never leaves a stale timer behind, and the engine holds at most one at a time. Called after
     * everything that can change a deadline: a fetch, a trigger mutation, a display ending, display
     * resuming, and a new session.
     *
     * The wake-up re-arms itself, because satisfying the earliest deadline usually leaves a later
     * one behind it. There is no runaway: a deadline that has passed is no longer in the future and
     * is not scheduled again, whether or not the message it belonged to was actually shown.
     *
     * **Time passing is not a trigger change.** [triggerGeneration] does not move here. A message
     * that has already been displayed in this generation stays blocked when its `min_time_since`
     * delay elapses, exactly as it would for any other reason; it waits for a real trigger mutation
     * or a new session like every other message.
     */
    fun scheduleDynamicReevaluation() {
        synchronized(lock) {
            dynamicTimer?.cancel()
            val now = nowMs()
            val deadline = nextDynamicDeadlineMs(now)
            dynamicTimer = deadline?.let { at ->
                // +1: `greater` needs the comparison to be strict, and a timer that fired exactly
                // on the deadline would find the condition still unmet and schedule itself again.
                scheduler.schedule(at - now + 1) {
                    onMain {
                        evaluate()
                        presentNext()
                        scheduleDynamicReevaluation()
                    }
                }
            }
        }
    }

    /** Takes the first queued message and puts it on screen. */
    fun presentNext() {
        if (paused) return
        if (heldForPrompt) return
        if (OpenPushBridge.currentActivity == null) return
        if (store == null) return
        val subscription = OpenPushBridge.subscriptionId ?: return
        // Claimed under one lock, not two: between taking a message off the queue and naming it
        // [current] there must be no moment where it is in neither, or an evaluation running
        // alongside would find it eligible and a second call would put the same message up twice.
        val next = synchronized(lock) {
            if (current != null) return
            val candidate = queue.removeFirstOrNull() ?: return
            current = candidate
            candidate
        }
        val url = OpenPushBridge.url("${next.htmlPath}?subscription_id=$subscription")
        if (url == null) {
            synchronized(lock) {
                current = null
                queue.addFirst(next)
            }
            return
        }
        rendered = false
        currentPageId = null
        emit(next, "willDisplay")
        val surface = presenterFactory()
        presenter = surface
        // The display-duration timer waits for the document to say it has rendered; a surface that
        // never comes up must not be counted down against.
        surface.show(next, url, OpenPushBridge.requestHeaders()) { event ->
            onPresenterEvent(event, next)
        }
    }

    /** Puts [message] back at the head of the queue and records nothing. */
    private fun hold() {
        val message = current ?: return
        cancelDisplayTimer()
        presenter?.hide()
        presenter = null
        currentPageId = null
        synchronized(lock) {
            current = null
            heldByPause = message
            queue.addFirst(message)
        }
    }

    private fun release() {
        synchronized(lock) { heldByPause = null }
        presentNext()
        scheduleDynamicReevaluation()
    }

    /**
     * Everything a presenter reports, marshalled onto the main thread before any state or view is
     * touched — the bridge is called from a WebView thread, and a dismiss listener from wherever the
     * framework tore the popup down. An event that arrives after the message it belongs to has gone
     * has nothing left to act on, so it is dropped — [from] names the message the reporting surface
     * was shown for, so a presenter that outlives its message cannot speak for the next one.
     */
    fun onPresenterEvent(event: PresenterEvent, from: IamMessage? = null) = onMain {
        val message = current ?: return@onMain
        if (from != null && from.id != message.id) return@onMain
        when (event) {
            is PresenterEvent.Rendered -> onRendered(message)
            is PresenterEvent.PageChanged -> onPageChanged(message, event.pageId)
            is PresenterEvent.Action -> handle(message, event.action)
            is PresenterEvent.Failed -> onFailed(message, event.reason)
            is PresenterEvent.Dismissed -> finish(message, dismissed = true)
        }
    }

    /**
     * The document moved to another page. The page is remembered for as long as this display lasts,
     * so a click reported afterwards can say which page it was made on, and it is worth one
     * impression of its own.
     */
    private fun onPageChanged(message: IamMessage, pageId: String) {
        currentPageId = pageId.takeIf(String::isNotEmpty) ?: currentPageId
        pageImpression(message, pageId)
    }

    /**
     * The document is on screen. This is where a display is counted — the `didDisplay` callback, the
     * impression receipt, the display-duration countdown and the generation stamp that stops the
     * message coming straight back all hang off this one event — so a message the user never saw
     * leaves none of them behind.
     *
     * A repeated `rendering_complete` (a multi-page document reports one per page) re-runs only the
     * impression, which the store deduplicates and retries on its own terms.
     */
    private fun onRendered(message: IamMessage) {
        if (!rendered) {
            rendered = true
            emit(message, "didDisplay")
            val generation = synchronized(lock) { triggerGeneration }
            store?.withLock { displayedGeneration[message.id] = generation }
            startDisplayTimer(message)
        }
        impression(message)
    }

    /**
     * The document will never render, so the message is put down for the rest of the process:
     * no impression, no redisplay record, no dismiss events, and [evaluate] skips it from here.
     * Nothing is recorded, so a later fetch — which clears [abandoned] — can try it again.
     */
    private fun onFailed(message: IamMessage, reason: String) {
        OpenPush.onLog("✗ in-app message ${message.id} was not shown ($reason)")
        cancelDisplayTimer()
        presenter?.hide()
        presenter = null
        rendered = false
        currentPageId = null
        synchronized(lock) {
            current = null
            abandoned += message.id
        }
        evaluate()
        OpenPushMainThread.post(::presentNext)
    }

    private fun startDisplayTimer(message: IamMessage) {
        val duration = message.displayDurationMs ?: return
        cancelDisplayTimer()
        durationTimer = scheduler.schedule(duration) { onMain { finish(message, dismissed = false) } }
    }

    private fun cancelDisplayTimer() {
        durationTimer?.cancel()
        durationTimer = null
    }

    /**
     * One action the document reported. The bridge only parsed it — everything it asks for happens
     * here, on the main thread, where the SDK's own surfaces are.
     */
    private fun handle(message: IamMessage, action: IamAction) {
        recordClick(message, action)
        applyHostEffects(message, action)
        // The message closing itself is not the user dismissing it: the display is recorded, but a
        // one-shot is not burned by a document that shut its own door behind an action.
        if (action.closes) finish(message, dismissed = false)
    }

    /**
     * What an action asks the host to do. The message document cannot do any of it itself — the
     * bridge that receives it parses and nothing more — so a document can only ever ask for the
     * three things the server's action schema names: a permission prompt, tags, and outcomes.
     */
    private fun applyHostEffects(message: IamMessage, action: IamAction) {
        action.prompts.forEach { prompt ->
            when (prompt) {
                "push" -> promptForPush(message)
                "location" -> OpenPush.onLog(
                    "location prompt ignored — OpenPush does not own location permission",
                )
            }
        }
        if (action.tags.isNotEmpty()) OpenPush.User.addTags(action.tags)
        action.outcomes.forEach { OpenPush.trackEvent(it) }
        action.url?.let { url ->
            OpenPushBridge.currentActivity?.let { IamUrlOpener.open(it, url, action.urlTarget) }
        }
    }

    /**
     * Asks for the notification permission and holds the queue while the system dialog is up.
     *
     * The hold ends at whichever comes first: the request's own callback, the core module's
     * permission-result listener, or [PROMPT_HOLD_MS]. The callback is the one that matters and is
     * why it is passed at all — an already-granted permission and a permanently denied one show no
     * dialog and answer on the spot, and neither reaches `onRequestPermissionsResult`. Without it the
     * queue would stop for a minute with nothing on screen. The timer stays as the backstop for a host
     * that forwards no result at all, and for a request made with no resumed Activity, which the core
     * module defers until one resumes.
     */
    private fun promptForPush(message: IamMessage) {
        if (!hasPrompted.add(message.id)) return
        heldForPrompt = true
        promptTimer?.cancel()
        promptTimer = scheduler.schedule(PROMPT_HOLD_MS) { onMain { releasePrompt() } }
        OpenPush.Notifications.requestPermission(fallbackToSettings = true) { onMain { releasePrompt() } }
    }

    private fun releasePrompt() {
        if (!heldForPrompt) return
        heldForPrompt = false
        promptTimer?.cancel()
        promptTimer = null
        presentNext()
    }

    /**
     * Takes the message down and records the display. [onRendered] has normally stamped the
     * generation already; a message taken down before its document ever reported rendering is
     * stamped here instead, because the redisplay record written below would otherwise make it
     * eligible again the instant this method re-evaluates.
     */
    private fun finish(message: IamMessage, dismissed: Boolean) {
        val state = store ?: return
        emit(message, "willDismiss")
        cancelDisplayTimer()
        presenter?.hide()
        presenter = null
        val now = nowMs()
        val hadRendered = rendered
        rendered = false
        currentPageId = null
        val generation = synchronized(lock) {
            current = null
            triggerGeneration
        }
        state.withLock {
            if (dismissed) this.dismissed += message.id
            val prior = redisplay[message.id] ?: InAppStateStore.Redisplay(now, 0)
            prior.at = now
            prior.quantity++
            redisplay[message.id] = prior
            if (!hadRendered) displayedGeneration[message.id] = generation
            lastDismissed = now
        }
        state.save()
        emit(message, "didDismiss")
        evaluate()
        // A dismissal moves `lastDismissed`, which is what every `min_time_since` deadline is
        // measured from, so every one of them has just changed.
        scheduleDynamicReevaluation()
        // Posted, never inline: this runs from a presenter callback, and the next message must not
        // go up while the surface coming down is still on the stack.
        OpenPushMainThread.post(::presentNext)
    }

    /**
     * Shows a document bundled in the app's own assets, through the shipping presenter, as if the
     * server had sent it.
     *
     * This exists so a developer can see and click a real in-app message — the real WebView, the
     * real bridge, the real layout, the real link handling — with no workspace, no subscription and
     * no network. It is deliberately **outside** the display machinery: a preview has its own
     * presenter and its own event path, so it writes no receipt, no impression, no redisplay
     * record, no dismissal and no generation stamp, and it neither consumes nor blocks the queue.
     * Nothing a preview does can change what a real message does next.
     *
     * The host is still told what happened: lifecycle and click listeners fire for the synthetic
     * message id `"preview"`, which is what makes the fixture worth clicking.
     */
    fun previewLocalDocument(activity: Activity, assetPath: String) {
        val html = runCatching {
            activity.assets.open(assetPath).bufferedReader().use(BufferedReader::readText)
        }.getOrElse {
            OpenPush.onLog("✗ in-app preview could not read the asset '$assetPath'")
            return
        }
        previewDocument(activity, html)
    }

    /** [previewLocalDocument] with the document already in hand. */
    fun previewDocument(activity: Activity, html: String) = onMain {
        endPreview(quiet = true)
        previewActivity = activity
        val surface = presenterFactory()
        previewPresenter = surface
        emit(previewMessage, "willDisplay")
        surface.preview(activity, previewMessage, html) { event -> onMain { onPreviewEvent(event) } }
    }

    /** The preview's own presenter switch. Every arm that would record something is absent. */
    private fun onPreviewEvent(event: PresenterEvent) {
        if (previewPresenter == null) return
        when (event) {
            is PresenterEvent.Rendered -> if (!previewRendered) {
                previewRendered = true
                emit(previewMessage, "didDisplay")
            }
            // Worth an impression receipt for a real message, and nothing at all for a preview.
            is PresenterEvent.PageChanged -> Unit
            is PresenterEvent.Action -> onPreviewAction(event.action)
            is PresenterEvent.Failed -> {
                OpenPush.onLog("✗ in-app preview was not shown (${event.reason})")
                endPreview(quiet = true)
            }
            is PresenterEvent.Dismissed -> endPreview()
        }
    }

    /**
     * What a tap on a preview does. The click reaches the host's own listeners and a URL opens for
     * real, because seeing those two work is what a preview is for. The three effects that write to
     * the live user — a permission prompt, tags and outcomes — are refused with a log instead: a
     * bundled fixture must not be able to ask the OS for a permission or put a tag on a real
     * profile.
     */
    private fun onPreviewAction(action: IamAction) {
        notifyClick(
            InAppMessageClickEvent(
                messageId = previewMessage.id,
                actionId = action.actionId,
                url = action.url,
                urlTarget = action.urlTarget,
                closingMessage = action.closes,
                firstClick = !previewClicked,
                pageId = null,
            ),
        )
        previewClicked = true
        if (action.prompts.isNotEmpty() || action.tags.isNotEmpty() || action.outcomes.isNotEmpty()) {
            OpenPush.onLog("in-app preview ignored a prompt, tag or outcome — a preview writes nothing")
        }
        action.url?.let { url ->
            (OpenPushBridge.currentActivity ?: previewActivity)?.let { activity ->
                IamUrlOpener.open(activity, url, action.urlTarget)
            }
        }
        if (action.closes) endPreview()
    }

    /** Takes a preview down. [quiet] for a surface that never came up, which nothing mourns. */
    private fun endPreview(quiet: Boolean = false) {
        val surface = previewPresenter ?: return
        previewPresenter = null
        previewActivity = null
        previewRendered = false
        previewClicked = false
        if (!quiet) emit(previewMessage, "willDismiss")
        surface.hide()
        if (!quiet) emit(previewMessage, "didDismiss")
    }

    /**
     * Hands one click event to every registered listener. Like every public callback it is posted to
     * the main thread, even from there, so host code never runs inside the engine's own step; the
     * one queue keeps clicks and lifecycle events in the order the engine raised them.
     */
    fun notifyClick(event: InAppMessageClickEvent) = post(clicks, event)

    private fun emit(message: IamMessage, phase: String) =
        post(lifecycle, InAppMessageLifecycleEvent(message.id, phase))

    /** Posts [event] to the listeners in [target] now, skipping any removed before the post runs. */
    private fun <E> post(target: Map<UUID, (E) -> Unit>, event: E) {
        val listeners = synchronized(lock) { target.entries.map { it.key to it.value } }
        if (listeners.isEmpty()) return
        OpenPushMainThread.post {
            listeners.forEach { (id, listener) ->
                if (synchronized(lock) { target[id] === listener }) listener(event)
            }
        }
    }

    /** The message rows of a fetch answer; throws when [body] is not a JSON object. */
    private fun parseList(body: String): List<IamMessage> {
        val rows = JSONObject(body).optJSONArray("in_app_messages") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.let(::parse) }
    }

    fun parse(row: JSONObject): IamMessage? {
        val id = row.optString("id")
        if (id.isEmpty()) return null
        val groups = mutableListOf<List<IamCondition>>()
        val outer = row.optJSONArray("triggers") ?: JSONArray()
        for (index in 0 until outer.length()) {
            val inner = outer.optJSONArray(index) ?: continue
            val group = mutableListOf<IamCondition>()
            for (position in 0 until inner.length()) {
                // A condition that is not an object drops the whole message: skipping it would widen
                // the group's AND and show the message to people it was never meant for.
                val condition = inner.optJSONObject(position) ?: return null
                group += IamCondition(
                    condition.optString("id"),
                    condition.optString("kind"),
                    condition.optString("property").takeIf(String::isNotEmpty),
                    condition.optString("operator"),
                    condition.opt("value"),
                )
            }
            groups += group
        }
        val redisplay = row.optJSONObject("redisplay")
        return IamMessage(
            id = id,
            htmlPath = row.optString("html_path"),
            layout = row.optString("layout", "center"),
            triggers = groups,
            displayDurationMs = row.optLong("display_duration").takeIf { it > 0 }?.times(1000),
            redisplayLimit = redisplay?.optInt("limit", 1) ?: 1,
            redisplayDelayMs = (redisplay?.optDouble("delay", 0.0)?.times(1000))?.toLong() ?: 0,
            endTimeMs = endTimeMs(row),
            hasDynamicTriggers = groups.any { group -> group.any { it.kind in DYNAMIC_TRIGGER_KINDS } },
        )
    }

    /**
     * `end_time` is the row's `end_at`, which the server writes as epoch seconds. A hand-built
     * payload may carry an ISO-8601 instant instead, so both are read; anything else means the
     * message has no end time rather than an end time of zero.
     */
    private fun endTimeMs(row: JSONObject): Long? {
        if (row.isNull("end_time")) return null
        val value = row.opt("end_time") ?: return null
        if (value is Number) return (value.toDouble() * 1000).toLong()
        val text = value.toString().trim()
        if (text.isEmpty()) return null
        text.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) isoMillis(text) else legacyIsoMillis(text)
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun isoMillis(text: String): Long? = runCatching { Instant.parse(text).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        .recoverCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }
        .getOrNull()

    /**
     * java.time is API 26+. Below it, the same shapes are read with SimpleDateFormat: an offset or
     * `Z` when present, otherwise UTC, with the fraction cut or padded to milliseconds.
     */
    private fun legacyIsoMillis(text: String): Long? {
        val normalized = text.replace(Regex("""\.(\d+)""")) { "." + it.groupValues[1].take(3).padEnd(3, '0') }
        return LEGACY_ISO_PATTERNS.firstNotNullOfOrNull { pattern ->
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val position = ParsePosition(0)
            format.parse(normalized, position)?.takeIf { position.index == normalized.length }?.time
        }
    }

    /** Returns the engine to a just-loaded state. Not part of the published surface. */
    fun resetForTests() {
        cancelDisplayTimer()
        dynamicTimer?.cancel()
        dynamicTimer = null
        promptTimer?.cancel()
        promptTimer = null
        cancelFetchRetry()
        heldForPrompt = false
        hasPrompted.clear()
        presenter = null
        previewPresenter = null
        previewActivity = null
        previewRendered = false
        previewClicked = false
        store = null
        rendered = false
        currentPageId = null
        backgrounded = false
        synchronized(lock) {
            current = null
            heldByPause = null
            abandoned.clear()
            definitions = emptyList()
            queue.clear()
            triggerValues.clear()
            lifecycle.clear()
            clicks.clear()
            triggerGeneration = 0L
        }
        backgroundAt = null
        lastFetch = 0L
        nowMs = System::currentTimeMillis
        onMain = ::postToMain
        sessionAt = nowMs()
        fetchIntervalMs = 30_000L
        paused = false
    }

    internal companion object {
        /**
         * The shipping [scope]. What escapes one of its jobs is logged instead of reaching the host's
         * default handler, which on Android ends the process.
         */
        fun defaultScope(): CoroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
                OpenPush.onLog("✗ in-app messages: unexpected ${error.javaClass.simpleName} — ${error.message}")
            },
        )

        /** A return after this long away starts a new session. */
        private const val NEW_SESSION_MS = 30_000L

        /** How long the queue waits on a permission prompt before giving up on hearing the answer. */
        private const val PROMPT_HOLD_MS = 60_000L

        /** How long a refused fetch waits before its one retry. */
        private const val FETCH_RETRY_MS = 30_000L

        /** Trigger kinds whose value the host never sets, so only the clock can satisfy them. */
        private val DYNAMIC_TRIGGER_KINDS = setOf("session_time", "min_time_since")
        private val LEGACY_ISO_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
        )
    }
}
