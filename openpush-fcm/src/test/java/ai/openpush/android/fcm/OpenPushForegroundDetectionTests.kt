package ai.openpush.android.fcm

import ai.openpush.android.ForegroundEvent
import ai.openpush.android.OpenPush
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `FirebaseMessagingService.onMessageReceived` fires with the app foregrounded, backgrounded or
 * killed, so "a message arrived" is not the same fact as "the user is looking at the app". These
 * tests pin the difference: foreground listeners and `preventDefault()` belong to the foregrounded
 * case only, while the `received` receipt is owed on every delivery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushForegroundDetectionTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private val urls = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var gate = CountDownLatch(0)

    @Before fun reset() {
        OpenPush::class.java.declaredMethods
            .first { it.name.startsWith("resetForTests") }
            .invoke(OpenPush)
        OpenPush.onLog = {}
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        bodies.clear()
        urls.clear()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    private fun transport(): suspend (OpenPush.Request) -> OpenPush.Response = {
        urls += it.url
        bodies += it.body!!
        gate.countDown()
        OpenPush.Response(200, """{"id":"sub_1"}""")
    }

    private fun initialize(host: Context = context) =
        OpenPush.initialize(host, appId = "demo", sdkKey = "sdk", transport = transport())

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }
    private fun CountDownLatch.awaitRequests() = assertTrue(await(2, TimeUnit.SECONDS))
    private fun settleRegistrationQueue() {
        OpenPush::class.java.declaredMethods
            .first { it.name.startsWith("settleForTests") && it.parameterCount == 1 }
            .invoke(OpenPush, 5_000L)
    }
    private fun service() = Robolectric.buildService(OpenPushMessagingService::class.java).create().get()
    private fun push() = mapOf("op_message_id" to "m1", "title" to "hi", "body" to "there")
    private fun notifications() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    /** Registers a provider token so receipts have something to ride, then forgets the traffic. */
    private fun registered() {
        val done = expect(1)
        service().onNewToken("provider-token")
        done.awaitRequests()
        bodies.clear()
        urls.clear()
    }

    // Backgrounded: the host's listeners are not in play

    @Test fun aBackgroundPushNeverReachesAForegroundListener() {
        initialize()
        val seen = mutableListOf<ForegroundEvent>()
        val handle = OpenPush.Notifications.addForegroundLifecycleListener { seen += it }
        try {
            service().handleData(push())
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }
        assertTrue("a background push reached a foreground listener", seen.isEmpty())
    }

    @Test fun aBackgroundPushIsStillRenderedEvenWhenAListenerWouldPreventDefault() {
        initialize()
        val handle = OpenPush.Notifications.addForegroundLifecycleListener { it.preventDefault() }
        try {
            service().handleData(push())
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }
        assertEquals("preventDefault() suppressed a notification the user never saw", 1, notifications().size)
    }

    @Test fun aBackgroundPushStillPostsTheReceivedReceipt() {
        initialize()
        registered()

        val received = expect(1)
        service().handleData(push())
        received.awaitRequests()
        settleRegistrationQueue()

        val ingestBodies = urls.indices
            .filter { urls[it].endsWith("/v1/ingest") }
            .map { bodies[it] }
        val matching = buildList {
            ingestBodies.forEach { body ->
                val events = JSONObject(body).getJSONArray("events")
                for (index in 0 until events.length()) {
                    val event = events.getJSONObject(index)
                    if (event.getString("type") == "received") add(event)
                }
            }
        }
        assertEquals(1, matching.size)
        assertEquals("m1", matching.single().getString("message_id"))
    }

    // Foregrounded: the documented compatibility behavior

    @Test fun aForegroundPushReachesTheListenerAndPreventDefaultSuppressesDisplay() {
        initialize()
        registered()
        Robolectric.buildActivity(Activity::class.java).setup()
        // setup() is itself a refresh point (onActivityStarted → onAppForegrounded) and can enqueue a
        // registration, so clear again: the assertion below reads bodies.single().
        bodies.clear()
        urls.clear()

        val seen = mutableListOf<ForegroundEvent>()
        val received = expect(1)
        val handle = OpenPush.Notifications.addForegroundLifecycleListener {
            seen += it
            it.preventDefault()
        }
        try {
            service().handleData(push())
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }
        received.awaitRequests()

        assertEquals("m1", seen.single().messageId)
        assertTrue("preventDefault() did not suppress the built-in display", notifications().isEmpty())
        assertEquals("received", JSONObject(bodies.single()).getJSONArray("events").getJSONObject(0).getString("type"))
    }

    @Test fun aPushAfterTheAppLeavesTheForegroundIsABackgroundPushAgain() {
        initialize()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val runs = AtomicInteger()
        val handle = OpenPush.Notifications.addForegroundLifecycleListener { runs.incrementAndGet() }
        try {
            service().handleData(push())
            controller.pause().stop()
            service().handleData(mapOf("op_message_id" to "m2", "title" to "hi", "body" to "again"))
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }
        assertEquals("the listener ran for a push that arrived after the app was stopped", 1, runs.get())
    }

    // Attaching from an Activity: the adoption, in both directions

    /**
     * The intended case for the adoption in `attach`. A host that calls `initialize` from a fragment,
     * a lazy-init path or a button handler attaches *after* its Activity's `onStart`, so the watcher
     * registers too late to have counted it: `startedActivityCount` is 0 even though the user is
     * looking at the app. Without adopting the Activity the process would look backgrounded until the
     * next foreground transition, and every push in that session would skip the host's listeners.
     */
    @Test fun attachingFromAnAlreadyResumedActivityCountsAsForegrounded() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        initialize(controller.get())

        val seen = mutableListOf<ForegroundEvent>()
        val handle = OpenPush.Notifications.addForegroundLifecycleListener { seen += it }
        try {
            service().handleData(push())
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }
        assertEquals("m1", seen.single().messageId)
    }

    /**
     * The inverse, and the dangerous direction: the adopted Activity must not keep the process looking
     * foregrounded once it has stopped. An Activity attached from during `onStart`/`onRestart` is never
     * resumed, so `onActivityPaused` never fires for it, and the started-Activity counter was already
     * past its `onActivityStarted` — so `onActivityStopped` clearing the adopted reference is the only
     * thing standing between a backgrounded process and a `preventDefault()` that suppresses a
     * notification the user never saw.
     */
    @Test fun anAdoptedActivityThatStopsWithoutEverResumingStopsCountingAsForeground() {
        val controller = Robolectric.buildActivity(Activity::class.java).create().start()
        initialize(controller.get())

        val runs = AtomicInteger()
        val handle = OpenPush.Notifications.addForegroundLifecycleListener {
            runs.incrementAndGet()
            it.preventDefault()
        }
        try {
            controller.stop()
            service().handleData(push())
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }
        assertEquals("a stopped Activity still counted as foreground", 0, runs.get())
        assertEquals("preventDefault() suppressed a notification the user never saw", 1, notifications().size)
    }

    // The documented trust-the-host fallback

    /**
     * With no Application there is no lifecycle to observe, so the SDK cannot tell foreground from
     * background. `attach` already logs that the host must call `onAppForegrounded()` itself; the
     * matching contract is that the SDK keeps dispatching rather than silently going quiet.
     */
    @Test fun aHostWithoutAnApplicationContextKeepsOwningTheDecision() {
        val hostOwned = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
        }
        initialize(hostOwned)

        val seen = mutableListOf<ForegroundEvent>()
        val handle = OpenPush.Notifications.addForegroundLifecycleListener { seen += it }
        try {
            service().handleData(push())
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }
        assertEquals("m1", seen.single().messageId)
    }
}
