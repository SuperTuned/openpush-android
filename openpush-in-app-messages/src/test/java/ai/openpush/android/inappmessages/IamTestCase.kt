package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushBridge
import ai.openpush.android.OpenPushConfiguration
import ai.openpush.android.OpenPushInternalApi
import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Base for the in-app engine tests: a real [OpenPush] attach over a capturing transport, a
 * [FakePresenter] in place of the popup, and a hand-driven [TestIamScheduler].
 *
 * The core module's `resetForTests`/`clearPersistedStateForTests` are `internal` to that module and
 * therefore out of reach here, so every test attaches under its own `tokenKey`. Core namespaces its
 * persisted session by `tokenKey`, and the in-app state store is keyed by it too, so a fresh key is
 * a fresh workspace.
 *
 * That leaves the core itself shared, and its registration finishes on an IO thread by telling the
 * engine the subscription is ready, which makes the engine fetch. Such a fetch can arrive late, even
 * from the test before, and a fetch replaces the definitions and clears abandoned messages. So the
 * harness throttles every unforced fetch ([UNFORCED_FETCH_INTERVAL_MS]) and the fixture fetches
 * force their way through: whatever subscription-ready fetch turns up after a fixture has loaded is
 * dropped by the throttle, whichever test it came from.
 */
@OptIn(OpenPushInternalApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal abstract class IamTestCase {
    data class Recorded(val method: String, val url: String, val body: String?)

    protected val context: Context = ApplicationProvider.getApplicationContext()
    private val requests = mutableListOf<Recorded>()

    /** Set from the core's registration thread once a registration has finished. */
    @Volatile private var registered = false
    protected val presenter = FakePresenter()
    protected val scheduler = TestIamScheduler()
    protected lateinit var activity: Activity
    protected lateinit var tokenKey: String

    /** Body the capturing transport answers the in-app list GET with. */
    protected var iamsFixture: String = """{"in_app_messages":[]}"""

    /** Status the capturing transport answers the in-app list GET with. */
    protected var iamsStatus: Int = 200

    /** Status the capturing transport answers POST /v1/ingest with. */
    protected var ingestStatus: Int = 200

    protected val serverUrl = "https://example.invalid"

    @Before
    fun startSdk() {
        OpenPush.onLog = {}
        OpenPush.onRegistered = { registered = true }
        OpenPush.onRegistrationFailed = null
        OpenPushInAppMessages.resetForTests()
        OpenPushInAppMessages.scope = CoroutineScope(Dispatchers.Unconfined)
        // `onMain` is deliberately left as it ships. Robolectric runs this thread as the main
        // looper, so a call the test makes itself still runs inline, while one the SDK makes from a
        // network or callback thread is posted and waits for [idle]. Replacing it with a direct
        // call would let those threads into the engine and hide the very races confinement exists
        // to stop. `resetForTests` puts the production seam back for the test that swaps it.
        OpenPushInAppMessages.scheduler = scheduler
        OpenPushInAppMessages.presenterFactory = { presenter }
        OpenPushInAppMessages.fetchIntervalMs = UNFORCED_FETCH_INTERVAL_MS
        activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().get()
        tokenKey = "t-${UUID.randomUUID()}"
        attach(tokenKey)
        registerToken("provider-token")
    }

    protected fun attach(key: String) {
        OpenPush.attach(
            activity,
            OpenPushConfiguration("demo", "sdk", serverUrl, tokenKey = key),
            { "opdev" },
            ::transport,
        )
    }

    /**
     * Registers and waits for the registration to finish, then runs the subscription-ready fetch it
     * posted, so setup does not end with this test's own registration still in flight.
     */
    protected fun registerToken(token: String) {
        registered = false
        OpenPush.register(token)
        await("the registration to finish") { registered && OpenPushBridge.subscriptionId != null }
        idle()
    }

    /**
     * Answers the in-app list GET with [fixture] and drives one fetch to completion. The fetch is
     * forced past the throttle; any unforced fetch after it is throttled, so nothing else can
     * replace the definitions it loaded.
     */
    protected fun fetchFixture(fixture: String) {
        iamsFixture = fixture
        OpenPushInAppMessages.engine.fetch(force = true)
        await("an in-app message on screen") { presenter.shows.isNotEmpty() }
    }

    protected fun oneMessage(id: String, htmlPath: String = "/v1/apps/demo/iams/$id/html"): String =
        """{"in_app_messages":[{"id":"$id","html_path":"$htmlPath","layout":"center","triggers":[]}]}"""

    /** A copy of what the transport has recorded, read under the lock its threads append with. */
    protected fun recordedRequests(): List<Recorded> = synchronized(requests) { requests.toList() }

    protected fun ingestRequests() = recordedRequests().filter { it.url.endsWith("/v1/ingest") }

    /** Runs whatever the engine posted to the main thread. */
    protected fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    protected fun await(what: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private suspend fun transport(request: OpenPush.Request): OpenPush.Response {
        synchronized(requests) { requests += Recorded(request.method, request.url, request.body) }
        return when {
            request.url.endsWith("/iams") -> OpenPush.Response(iamsStatus, iamsFixture)
            request.url.endsWith("/v1/ingest") -> OpenPush.Response(ingestStatus, "{}")
            request.url.contains("/subscriptions") -> OpenPush.Response(
                200,
                """{"id":"sub_1","app":"demo","status":1,"status_name":"subscribed"}""",
            )
            else -> OpenPush.Response(200, "{}")
        }
    }

    protected companion object {
        /** Long enough that no unforced fetch gets through twice in one test. */
        const val UNFORCED_FETCH_INTERVAL_MS = 24 * 60 * 60_000L
    }
}

/** Records what the engine asked to be shown and lets a test push [PresenterEvent]s back. */
internal class FakePresenter : IamPresenter {
    data class Shown(val message: IamMessage, val url: String, val headers: Map<String, String>)

    val shows = mutableListOf<Shown>()

    /** Documents handed over by [preview], in order. */
    val previews = mutableListOf<String>()
    var hides = 0
        private set
    private var events: ((PresenterEvent) -> Unit)? = null

    override fun show(
        message: IamMessage,
        url: String,
        headers: Map<String, String>,
        events: (PresenterEvent) -> Unit,
    ) {
        shows += Shown(message, url, headers)
        this.events = events
    }

    override fun preview(activity: Activity, message: IamMessage, html: String, events: (PresenterEvent) -> Unit) {
        previews += html
        this.events = events
    }

    override fun hide() {
        hides++
    }

    fun emit(event: PresenterEvent) {
        val sink = events ?: throw AssertionError("no in-app message has been shown")
        sink(event)
    }
}

/** Hand-driven clock for the engine's display-duration timer. */
internal class TestIamScheduler : IamScheduler {
    private class Entry(val dueAt: Long, val action: () -> Unit) {
        @Volatile var cancelled = false
    }

    private val entries = mutableListOf<Entry>()
    private var now = 0L

    /** Timers scheduled and not yet cancelled or fired. */
    val pending: Int get() = synchronized(entries) { entries.count { !it.cancelled } }

    override fun schedule(delayMillis: Long, action: () -> Unit): IamCancellable {
        val entry = Entry(now + delayMillis, action)
        synchronized(entries) { entries += entry }
        return IamCancellable { entry.cancelled = true }
    }

    fun advance(millis: Long) {
        now += millis
        val due = synchronized(entries) {
            val ready = entries.filter { it.dueAt <= now && !it.cancelled }
            entries.removeAll { it.dueAt <= now }
            ready
        }
        due.forEach { it.action() }
    }
}
