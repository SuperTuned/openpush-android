package ai.openpush.android.onesignal

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushBridge
import ai.openpush.android.OpenPushInternalApi
import ai.openpush.android.ShadeManager
import ai.openpush.android.inappmessages.InAppLifecycleStage
import ai.openpush.android.inappmessages.InAppMessageClickEvent
import ai.openpush.android.inappmessages.InAppMessageLifecycleEvent
import ai.openpush.android.inappmessages.OpenPushInAppMessages
import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OneSignalAliasTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var gate = CountDownLatch(0)

    @Before fun reset() {
        OpenPush::class.java.declaredMethods
            .first { it.name.startsWith("resetForTests") }
            .invoke(OpenPush)
        OpenPush.onLog = {}
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        bodies.clear()
    }

    private fun initialize() {
        OneSignal.initialize(context, appId = "demo", sdkKey = "sdk") {
            bodies += it.body!!
            gate.countDown()
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
    }

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }
    private fun CountDownLatch.awaitRequests() = assertTrue(await(5, TimeUnit.SECONDS))
    private fun lastBody() = JSONObject(bodies.last())

    private fun awaitCondition(what: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun initializeThroughTheAliasConfiguresOpenPush() {
        initialize()
        assertTrue(OpenPush.isInitialized)
        assertEquals(OpenPush.sdkVersion, OneSignal.sdkVersion)
    }

    @Test fun namedExplicitSdkKeyReachesOpenPush() {
        var observedKey: String? = null
        val registered = expect(1)
        OneSignal.initialize(context, appId = "demo", sdkKey = "explicit-sdk-key") { request ->
            observedKey = request.headers["X-OP-SDK-Key"]
            registered.countDown()
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }

        OneSignal.register("provider-token")
        registered.awaitRequests()
        assertEquals("explicit-sdk-key", observedKey)
    }

    @Test fun loginThroughTheAliasPutsTheExternalIdOnTheWire() = runTest {
        initialize()
        val registered = expect(1)
        OneSignal.register("provider-token")
        registered.awaitRequests()
        assertTrue(OpenPush.awaitRegistration())

        bodies.clear()
        val loggedIn = expect(1)
        OneSignal.login("player-9")
        loggedIn.awaitRequests()
        assertTrue(OpenPush.awaitRegistration())
        assertEquals("player-9", lastBody().getString("external_id"))
    }

    @Test fun logoutThroughTheAliasSendsAnExplicitEmptyExternalId() = runTest {
        initialize()
        val registered = expect(1)
        OneSignal.register("provider-token")
        registered.awaitRequests()
        assertTrue(OpenPush.awaitRegistration())

        val loggedIn = expect(1)
        OneSignal.login("player-9")
        loggedIn.awaitRequests()
        assertTrue(OpenPush.awaitRegistration())

        bodies.clear()
        val loggedOut = expect(1)
        OneSignal.logout()
        loggedOut.awaitRequests()
        assertEquals("", lastBody().getString("external_id"))
    }

    @Test fun suspendIdentityCallsForwardToOpenPush() = runTest {
        initialize()
        val registered = expect(1)
        OneSignal.register("provider-token")
        registered.awaitRequests()

        assertEquals(ai.openpush.android.IdentityResult.Success, OneSignal.loginSuspend("player-9"))
        assertEquals(ai.openpush.android.IdentityResult.Success, OneSignal.logoutSuspend())
    }

    @Test fun tagsAndAliasesShareOneSessionMapWithOpenPush() {
        initialize()
        OneSignal.User.addTag("tier", "vip")
        OneSignal.User.addAlias("steam_id", "7656")
        assertEquals("vip", OpenPush.User.getTags()["tier"])
        assertEquals("vip", OneSignal.User.getTags()["tier"])
    }

    @Test fun outcomeAliasPostsAJourneyCustomEvent() {
        initialize()
        val registered = expect(1)
        OneSignal.register("provider-token")
        registered.awaitRequests()

        val sent = expect(1)
        OneSignal.Session.addOutcomeWithValue("tutorial_complete", 1f)
        OpenPush.onAppBackgrounded()
        sent.awaitRequests()

        val events = lastBody().getJSONArray("events")
        assertEquals("tutorial_complete", events.getJSONObject(0).getString("name"))
        assertEquals(1.0, events.getJSONObject(0).getJSONObject("properties").getDouble("value"), 0.0)
    }

    @Test fun trackEventForwards() {
        initialize()
        val registered = expect(1)
        OneSignal.register("provider-token")
        registered.awaitRequests()

        val sent = expect(1)
        OneSignal.User.trackEvent("signed_in", mapOf("plan" to "pro"))
        OpenPush.onAppBackgrounded()
        sent.awaitRequests()

        val event = lastBody().getJSONArray("events").getJSONObject(0)
        assertEquals("signed_in", event.getString("name"))
        assertEquals("pro", event.getJSONObject("properties").getString("plan"))
    }

    @Test fun addUniqueOutcomeUsesADeviceScopedIdempotencyKey() {
        initialize()
        val registered = expect(1)
        OneSignal.register("provider-token")
        registered.awaitRequests()

        val sent = expect(1)
        OneSignal.Session.addUniqueOutcome("signup")
        OneSignal.Session.addUniqueOutcome("signup")
        OpenPush.onAppBackgrounded()
        sent.awaitRequests()

        // Two events on the wire, one key: the device sends both and the server keeps the first.
        val events = lastBody().getJSONArray("events")
        assertEquals(2, events.length())
        val first = events.getJSONObject(0)
        val second = events.getJSONObject(1)
        assertEquals("signup", first.getString("name"))
        assertEquals(0, first.getJSONObject("properties").length())
        val key = first.getString("idempotency_key")
        assertEquals(key, second.getString("idempotency_key"))
        assertTrue(key.startsWith("unique:signup:"))
        assertFalse("the subscription id became the analytics scope", key.contains("sub_1"))
        assertFalse("the raw push token reached the analytics store", key.contains("provider-token"))
    }

    @Test fun aUniqueOutcomeKeyDoesNotChangeWhenRegistrationStateChanges() {
        val beforeRegistration = uniqueOutcomeKey("signup")
        OneSignal.initialize(context, appId = "demo", sdkKey = "sdk") {
            bodies += it.body!!
            gate.countDown()
            OpenPush.Response(200, "{}")
        }
        val registered = expect(1)
        OneSignal.register("provider-token")
        registered.awaitRequests()

        val key = uniqueOutcomeKey("signup")
        assertEquals(null, OpenPush.User.pushSubscription.id)
        assertEquals(beforeRegistration, key)
        assertFalse(key.contains("provider-token"))
        assertEquals(key, uniqueOutcomeKey("signup"))
    }

    @Test fun absentCompatibilityStringsAreEmptyRatherThanNull() {
        initialize()
        assertEquals("", OneSignal.User.externalId)
        assertEquals("", OneSignal.User.pushSubscription.id)
        assertEquals("", OneSignal.User.pushSubscription.token)
        assertEquals("", OneSignal.User.pushSubscription.snapshot().id)
        assertEquals("", OneSignal.User.pushSubscription.snapshot().token)
    }

    @Test fun pushSubscriptionIsALiveViewAndOptOutRidesTheWire() = runTest {
        initialize()
        val registered = expect(1)
        OneSignal.register("provider-token")
        registered.awaitRequests()
        assertTrue(OpenPush.awaitRegistration())

        val optedOut = expect(1)
        OneSignal.User.pushSubscription.optOut()
        optedOut.awaitRequests()
        assertEquals(-2, lastBody().getInt("status"))
        assertFalse(OneSignal.User.pushSubscription.optedIn)
        assertFalse(OpenPush.User.pushSubscription.optedIn)
    }

    @Test fun subscriptionObserverIsRegisteredWithOpenPush() = runTest {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        initialize()
        // The observer list is change-only. Grant permission before registration so the
        // registered snapshot is opted in and optOut produces a deterministic change.
        val registered = expect(1)
        OneSignal.register("provider-token")
        registered.awaitRequests()
        assertTrue(OpenPush.awaitRegistration())
        val seen = java.util.concurrent.atomic.AtomicInteger()
        val handle = OneSignal.User.pushSubscription.addObserver { seen.incrementAndGet() }
        OneSignal.User.pushSubscription.optOut()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(seen.get() > 0)
        OneSignal.User.pushSubscription.removeObserver(handle)
    }

    @Test fun facadeObserversRunOnTheCoreMainThreadPostWithNoSecondHop() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        initialize()
        val registered = expect(1)
        OneSignal.register("provider-token")
        registered.awaitRequests()
        // Once the subscription id is in, a late registration answer reports nothing new.
        awaitCondition("the registration answer") { OpenPush.User.pushSubscription.id != null }
        shadowOf(Looper.getMainLooper()).idle()

        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val core = OpenPush.PushSubscription.addObserver { events += "core:${it.optedIn}" }
        val facade = OneSignal.User.pushSubscription.addObserver {
            events += "facade:${it.optedIn}:${Looper.myLooper() == Looper.getMainLooper()}"
        }
        OneSignal.User.pushSubscription.optOut()
        assertTrue("an observer ran inside optOut", events.none { it.contains("false") })
        // A marker queued right after optOut. The core SDK's one post carries both observers, so
        // the facade's observer runs before the marker; a second hop would queue it behind.
        android.os.Handler(Looper.getMainLooper()).post { events += "marker" }
        shadowOf(Looper.getMainLooper()).idle()

        // Only the opt-out and the marker are about ordering here.
        assertEquals(
            listOf("core:false", "facade:false:true", "marker"),
            events.filter { it.contains("false") || it == "marker" },
        )

        OpenPush.PushSubscription.removeObserver(core)
        OneSignal.User.pushSubscription.removeObserver(facade)
    }

    @Test fun changeObserverForwards() = runTest {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        initialize()
        OneSignal.register("provider-token")
        assertTrue(OpenPush.awaitRegistration())

        val changes = mutableListOf<PushSubscriptionChange>()
        val handle = OneSignal.User.pushSubscription.addChangeObserver(changes::add)
        OneSignal.User.pushSubscription.optOut()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, changes.size)
        assertTrue(changes.single().previous!!.optedIn)
        assertFalse(changes.single().current.optedIn)
        OneSignal.User.pushSubscription.removeChangeObserver(handle)
        OneSignal.User.pushSubscription.optIn()
        assertEquals(1, changes.size)
    }

    @Test fun inAppListenersForward() {
        // A real display, because a handle that resolves is not proof that the listener is on the
        // engine's own list. The in-app engine needs a visible Activity and a subscription id, so
        // this test initializes over one and answers the message list itself.
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume().get()
        val phases = mutableListOf<InAppMessageLifecycleEvent>()
        val clicks = mutableListOf<InAppMessageClickEvent>()

        val lifecycleHandle = OneSignal.InAppMessages.addLifecycleListener(phases::add)
        val clickHandle = OneSignal.InAppMessages.addClickListener(clicks::add)
        assertNotEquals(lifecycleHandle, clickHandle)

        OneSignal.initialize(activity, appId = "demo", sdkKey = "sdk") { request ->
            when {
                request.url.endsWith("/iams") -> OpenPush.Response(200, IAM_FIXTURE)
                else -> OpenPush.Response(200, """{"id":"sub_1"}""")
            }
        }
        OneSignal.register("provider-token")
        awaitCondition("the in-app engine to present a message") { phases.isNotEmpty() }

        assertEquals("iam_1", phases.first().messageId)
        assertEquals(InAppLifecycleStage.WILL_DISPLAY, phases.first().stage)

        // Removing through the facade removes from the same list: pausing takes the message down
        // and unpausing presents it again, and nothing further arrives.
        OneSignal.InAppMessages.removeLifecycleListener(lifecycleHandle)
        OneSignal.InAppMessages.paused = true
        assertTrue(OpenPushInAppMessages.paused)
        val seen = phases.size
        OneSignal.InAppMessages.paused = false
        assertEquals(seen, phases.size)

        // The click listener shares that registry — its handle is the engine's own — but only a
        // document can produce a tap, so this asserts the forward and the handle, not a callback.
        assertTrue(clicks.isEmpty())
        OpenPushInAppMessages.removeClickListener(clickHandle)
        OneSignal.InAppMessages.removeClickListener(clickHandle)
        OneSignal.InAppMessages.paused = true
    }

    @Test fun theSuspendRequestPermissionAnswersFromTheCachedGrant() = runTest {
        // Granted BEFORE initialize: the permission cache is populated at attach, so a
        // grant afterwards would leave the cached value false. Same grant call as
        // OpenPushPermissionTests.kt.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        initialize()
        // The suspend form, with and without its argument.
        assertTrue(OneSignal.Notifications.requestPermission())
        assertTrue(OneSignal.Notifications.requestPermission(false))
        assertTrue(OneSignal.Notifications.requestPermission(fallbackToSettings = true))
    }

    @Test fun canRequestPermissionForwardsToOpenPush() {
        initialize()

        assertEquals(
            OpenPush.Notifications.canRequestPermission,
            OneSignal.Notifications.canRequestPermission,
        )
    }

    @Test fun foregroundCallbacksReachMainWithoutLosingPreventDefault() {
        initialize()
        val registered = expect(1)
        OneSignal.register("provider-token")
        registered.awaitRequests()
        Robolectric.buildActivity(Activity::class.java).setup()

        val callbackWasOnMain = java.util.concurrent.atomic.AtomicBoolean()
        val prevented = java.util.concurrent.atomic.AtomicBoolean()
        val handle = OneSignal.Notifications.addForegroundLifecycleListener { event ->
            callbackWasOnMain.set(Looper.myLooper() == Looper.getMainLooper())
            event.preventDefault()
        }
        val worker = Thread {
            prevented.set(
                OneSignal.Notifications.processForegroundMessage(
                    mapOf("op_message_id" to "m1", "title" to "hello"),
                ),
            )
        }
        worker.start()
        val deadline = System.currentTimeMillis() + 2_000L
        while (worker.isAlive && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        worker.join(100L)
        OneSignal.Notifications.removeForegroundLifecycleListener(handle)

        assertFalse("foreground callback deadlocked", worker.isAlive)
        assertTrue(callbackWasOnMain.get())
        assertTrue(prevented.get())
    }

    @Test fun receivedPairForwardsTheDuplicateCheck() {
        initialize()
        val push = mapOf("op_message_id" to "m-dup", "title" to "hello")
        assertTrue(OneSignal.Notifications.beginReceived(push))
        assertFalse("a copy still in flight is refused", OneSignal.Notifications.beginReceived(push))
        OneSignal.Notifications.finishReceived(push)
        assertFalse("a handled copy is refused", OneSignal.Notifications.beginReceived(push))
        val other = mapOf("op_message_id" to "m-free")
        assertTrue(OneSignal.Notifications.beginReceived(other))
        OneSignal.Notifications.finishReceived(other, handled = false)
        assertTrue("an unhandled copy stays free", OneSignal.Notifications.beginReceived(other))
        OneSignal.Notifications.finishReceived(other)
    }

    @Test fun displayedForwardPostsAConfirmedReceipt() {
        initialize()
        val registered = expect(1)
        OneSignal.register("provider-token")
        registered.awaitRequests()
        bodies.clear()
        val messageId = "displayed-${java.util.UUID.randomUUID()}"
        val confirmed = expect(1)

        OneSignal.Notifications.processDisplayed(mapOf("op_message_id" to messageId))

        confirmed.awaitRequests()
        val event = lastBody().getJSONArray("events").getJSONObject(0)
        assertEquals("confirmed", event.getString("type"))
        assertEquals(messageId, event.getString("message_id"))
    }

    @OptIn(OpenPushInternalApi::class)
    @Test
    fun shadeManagementForwardsToOpenPush() {
        initialize()
        val calls = mutableListOf<String>()
        OpenPushBridge.shadeManager = object : ShadeManager {
            override fun clearAll(context: Context) {
                calls += "clear"
            }

            override fun remove(context: Context, notificationId: Int) {
                calls += "remove:$notificationId"
            }

            override fun removeGroup(context: Context, group: String) {
                calls += "group:$group"
            }
        }

        OneSignal.Notifications.clearAllNotifications()
        OneSignal.Notifications.removeNotification(17)
        OneSignal.Notifications.removeGroupedNotifications("orders")

        assertEquals(listOf("clear", "remove:17", "group:orders"), calls)
        OpenPushBridge.shadeManager = null
    }

    private companion object {
        const val IAM_FIXTURE =
            "{\"in_app_messages\":[{\"id\":\"iam_1\"," +
                "\"html_path\":\"/v1/apps/demo/iams/iam_1/html\",\"layout\":\"center\",\"triggers\":[]}]}"
    }
}
