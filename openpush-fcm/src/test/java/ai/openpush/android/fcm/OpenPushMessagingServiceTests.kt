package ai.openpush.android.fcm

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushBridge
import ai.openpush.android.OpenPushInternalApi
import ai.openpush.android.ReceivedMessageLog
import ai.openpush.android.liveupdates.OpenPushLiveUpdatePayload
import ai.openpush.android.liveupdates.OpenPushLiveUpdates
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

@OptIn(OpenPushInternalApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushMessagingServiceTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private val logs = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var gate = CountDownLatch(0)

    @Before fun reset() {
        OpenPush::class.java.declaredMethods
            .first { it.name.startsWith("resetForTests") }
            .invoke(OpenPush)
        logs.clear()
        OpenPush.onLog = { logs += it }
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        bodies.clear()
        OpenPushBridge.receivedMessageLog = null
        context.getSharedPreferences("ai.openpush.android.live_updates", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    private fun initialize() {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") {
            bodies += it.body!!
            gate.countDown()
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
    }

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }
    private fun CountDownLatch.awaitRequests() = assertTrue(await(2, TimeUnit.SECONDS))
    private fun service() = Robolectric.buildService(OpenPushMessagingService::class.java).create().get()

    /** Foreground listeners and `preventDefault()` only apply while an Activity is visible. */
    private fun foregrounded() = Robolectric.buildActivity(Activity::class.java).setup()

    @Test fun onNewTokenRegistersWithOpenPush() {
        initialize()
        val done = expect(1)
        service().onNewToken("provider-token")
        done.awaitRequests()
        assertEquals("provider-token", JSONObject(bodies.single()).getString("token"))
    }

    @Test fun onNewTokenBeforeInitializeIsIgnored() {
        assertFalse(OpenPush.isInitialized)
        service().onNewToken("provider-token")
        assertTrue(bodies.isEmpty())
    }

    @Test fun aForegroundListenerThatPreventsDefaultStopsTheBuiltInDisplay() {
        initialize()
        foregrounded()
        val handle = OpenPush.Notifications.addForegroundLifecycleListener { it.preventDefault() }
        try {
            service().handleData(mapOf("op_message_id" to "m1", "title" to "hi", "body" to "there"))
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun aRemoteMessageCollapseKeyReachesTheForegroundEvent() {
        initialize()
        foregrounded()
        var collapseKey: String? = null
        var rawData: Map<String, String>? = null
        val handle = OpenPush.Notifications.addForegroundLifecycleListener {
            collapseKey = it.notification.collapseKey
            rawData = it.notification.rawData
            it.preventDefault()
        }
        try {
            service().onMessageReceived(
                RemoteMessage.Builder("to")
                    .setCollapseKey("orders")
                    .addData("op_message_id", "m-collapse")
                    .addData("title", "hi")
                    .build(),
            )
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }

        assertEquals("orders", collapseKey)
        assertEquals(mapOf("op_message_id" to "m-collapse", "title" to "hi"), rawData)
        assertFalse(rawData!!.containsKey(OpenPushClickActivity.EXTRA_COLLAPSE_KEY))
    }

    @Test fun aPayloadWithATitleAndBodyIsRendered() {
        initialize()
        service().handleData(mapOf("op_message_id" to "m1", "title" to "hi", "body" to "there"))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test fun aDataOnlyPayloadWithNoTitleOrBodyIsNotRendered() {
        initialize()
        service().handleData(mapOf("op_message_id" to "m1", "deep_link" to "app://x"))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun liveUpdateUsesCollapseKeyAndNeverFallsThroughToOrdinaryRenderer() {
        initialize()
        val service = service()
        val data = liveData("start", 1, 10) + mapOf("title" to "ordinary", "body" to "must not show")

        service.handleData(data, "delivery-42")

        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals(OpenPushLiveUpdates.CHANNEL_ID, shadowOf(manager).allNotifications.single().channelId)
    }

    @Test fun invalidLiveUpdateIsDroppedInsteadOfBecomingAnOrdinaryNotification() {
        initialize()
        val service = service()

        service.handleData(
            mapOf(
                OpenPushLiveUpdatePayload.DATA_KEY to "not-json",
                "title" to "must not show",
                "body" to "must not show",
            ),
            "delivery-42",
        )

        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test fun liveUpdateStartUpdateAndEndReuseOneNativeNotification() {
        initialize()
        val service = service()
        val manager = context.getSystemService(NotificationManager::class.java)

        service.handleData(liveData("start", 1, 10), "delivery-42")
        val firstId = manager.activeNotifications.single().id
        service.handleData(liveData("update", 2, 75), "delivery-42")
        assertEquals(listOf(firstId), manager.activeNotifications.map { it.id })

        service.handleData(liveData("end", 3, 100), "delivery-42")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun foregroundPreventionAlsoPreventsLiveUpdateRendering() {
        initialize()
        foregrounded()
        val handle = OpenPush.Notifications.addForegroundLifecycleListener { it.preventDefault() }
        try {
            service().handleData(liveData("start", 1, 10), "delivery-42")
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test fun liveUpdatePostsOneReceivedReceipt() {
        initialize()
        val registered = expect(1)
        service().onNewToken("provider-token")
        registered.awaitRequests()
        bodies.clear()

        val received = expect(1)
        service().handleData(liveData("start", 1, 10), "delivery-42")
        received.awaitRequests()

        val events = JSONObject(bodies.single()).getJSONArray("events")
        assertEquals(1, events.length())
        assertEquals("received", events.getJSONObject(0).getString("type"))
        assertEquals("live-1", events.getJSONObject(0).getString("message_id"))
    }

    // ---- background (data-only) pushes ---------------------------------------------------------

    @Test fun aBackgroundPushIsNotDrawnAndReachesItsListenerWithAReceipt() {
        initialize()
        val registered = expect(1)
        service().onNewToken("provider-token")
        registered.awaitRequests()
        bodies.clear()

        val seen = Collections.synchronizedList(mutableListOf<Map<String, String>>())
        val handle = OpenPush.Notifications.addBackgroundMessageListener { seen += it.data }
        val received = expect(1)
        try {
            service().handleData(mapOf("op_message_id" to "bg-1", "op_background" to "1", "sync" to "inbox"), null)
            received.awaitRequests()
        } finally {
            OpenPush.Notifications.removeBackgroundMessageListener(handle)
        }

        assertEquals("inbox", seen.single()["sync"])
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        val events = JSONObject(bodies.single()).getJSONArray("events")
        assertEquals(1, events.length())
        assertEquals("received", events.getJSONObject(0).getString("type"))
        assertEquals("bg-1", events.getJSONObject(0).getString("message_id"))
    }

    @Test fun aBackgroundPushWithoutAListenerIsDroppedWithALog() {
        initialize()
        service().handleData(mapOf("op_message_id" to "bg-2", "op_background" to "1"), null)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertTrue(logs.any { it.contains("background push bg-2 dropped") })
        assertFalse("not the renderer's empty-text drop", logs.any { it.contains("title and body") })
    }

    @Test fun aPushWithoutTheFlagRendersAsBefore() {
        initialize()
        service().handleData(mapOf("op_message_id" to "m-plain", "title" to "hi", "body" to "there"), null)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test fun theStaticHandleRoutesABackgroundPushToo() {
        initialize()
        var seen: String? = null
        val handle = OpenPush.Notifications.addBackgroundMessageListener { seen = it.messageId }
        try {
            OpenPushMessagingService.handle(
                context,
                RemoteMessage.Builder("to")
                    .setData(mapOf("op_message_id" to "bg-3", "op_background" to "1"))
                    .build(),
            )
        } finally {
            OpenPush.Notifications.removeBackgroundMessageListener(handle)
        }

        assertEquals("bg-3", seen)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    // ---- pushes this SDK did not send ----------------------------------------------------------

    /** A host sharing its provider integration keeps its own traffic: we hand it back untouched. */
    class RecordingService : OpenPushMessagingService() {
        val foreign = mutableListOf<RemoteMessage>()
        public override fun onForeignMessage(message: RemoteMessage) {
            foreign += message
        }
    }

    @Test fun aForeignDataMessageGoesToOnForeignMessage() {
        initialize()
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val message = RemoteMessage.Builder("to")
            .addData("title", "someone else's push")
            .addData("body", "not ours")
            .build()

        service.onMessageReceived(message)

        assertEquals(1, service.foreign.size)
        assertEquals("someone else's push", service.foreign.single().data["title"])
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        assertTrue("no receipt is owed for another sender's push", bodies.isEmpty())
    }

    @Test fun anOpenPushMessageNeverReachesOnForeignMessage() {
        initialize()
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val message = RemoteMessage.Builder("to")
            .addData("op_message_id", "m1")
            .addData("title", "hi")
            .addData("body", "there")
            .build()

        service.onMessageReceived(message)

        assertTrue(service.foreign.isEmpty())
        assertEquals(
            1,
            shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size,
        )
    }

    @Test fun theDefaultOnForeignMessageOnlyLogs() {
        initialize()
        val message = RemoteMessage.Builder("to").addData("title", "theirs").build()

        service().onMessageReceived(message)

        assertTrue(logs.any { it.contains("onForeignMessage") })
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test fun aForeignMapWithoutARemoteMessageIsOnlyLogged() {
        initialize()

        service().handleData(mapOf("title" to "theirs", "body" to "not ours"))

        assertTrue(logs.any { it.contains("ignored") })
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    // ---- the static entry point for hosts with their own service -------------------------------

    @Test fun theStaticHandleRendersTheSameNotification() {
        initialize()
        val message = RemoteMessage.Builder("to")
            .addData("op_message_id", "m1")
            .addData("title", "hi")
            .addData("body", "there")
            .build()

        OpenPushMessagingService.handle(context, message)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals(
            OpenPushNotificationRenderer.DEFAULT_CHANNEL_ID,
            shadowOf(manager).allNotifications.single().channelId,
        )
    }

    @Test fun theStaticHandleAlsoRoutesLiveUpdatesAndIgnoresForeignPushes() {
        initialize()
        val manager = context.getSystemService(NotificationManager::class.java)

        val live = RemoteMessage.Builder("to").setData(liveData("start", 1, 10))
            .setCollapseKey("delivery-42").build()
        OpenPushMessagingService.handle(context, live)
        assertEquals(OpenPushLiveUpdates.CHANNEL_ID, shadowOf(manager).allNotifications.single().channelId)

        manager.cancelAll()
        val foreign = RemoteMessage.Builder("to").addData("title", "theirs").addData("body", "x").build()
        OpenPushMessagingService.handle(context, foreign)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun aRenderedNotificationPostsAConfirmedReceipt() {
        initialize()
        val registered = expect(1)
        service().onNewToken("provider-token")
        registered.awaitRequests()
        bodies.clear()

        val receipts = expect(2)
        service().handleData(mapOf("op_message_id" to "m1", "title" to "hi", "body" to "there"))
        receipts.awaitRequests()

        val types = bodies.map { JSONObject(it).getJSONArray("events").getJSONObject(0).getString("type") }
        assertTrue(types.containsAll(listOf("received", "confirmed")))
    }

    // ---- a second copy of a push already handled -----------------------------------------------

    /** Records every lookup and write in memory, or throws from both when [broken]. */
    private class RecordingLog(private val broken: Boolean = false) : ReceivedMessageLog {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        private val recorded = Collections.synchronizedSet(mutableSetOf<String>())
        override fun contains(messageId: String): Boolean {
            calls += "contains $messageId"
            if (broken) throw IllegalStateException("disk unavailable")
            return messageId in recorded
        }
        override fun record(messageId: String) {
            calls += "record $messageId"
            if (broken) throw IllegalStateException("disk unavailable")
            recorded += messageId
        }
    }

    /** Fails to render the first time, as a process that dies mid-delivery would. */
    class FailingOnceService : OpenPushMessagingService() {
        var failed = false
        override fun renderer(): OpenPushNotificationRenderer {
            if (!failed) {
                failed = true
                throw IllegalStateException("killed mid-delivery")
            }
            return super.renderer()
        }
    }

    private fun push(id: String) = mapOf("op_message_id" to id, "title" to "hi", "body" to "there")

    private fun registered() {
        initialize()
        val registered = expect(1)
        service().onNewToken("provider-token")
        registered.awaitRequests()
        bodies.clear()
    }

    private fun receiptMessageIds(): List<String> = synchronized(bodies) {
        bodies.flatMap { body ->
            val events = JSONObject(body).optJSONArray("events") ?: return@flatMap emptyList()
            (0 until events.length()).map { events.getJSONObject(it).optString("message_id") }
        }
    }

    @Test fun aRepeatedPushIsNotRenderedReportedOrDispatched() {
        registered()
        foregrounded()
        val seen = mutableListOf<String?>()
        val handle = OpenPush.Notifications.addForegroundLifecycleListener { seen += it.notification.messageId }
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            val first = expect(2)
            service().handleData(push("m1"))
            first.awaitRequests()
            manager.cancelAll()

            // The second copy, then a fresh push whose receipts mark the end of the queue.
            val next = expect(2)
            service().handleData(push("m1"))
            service().handleData(push("m2"))
            next.awaitRequests()
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }

        assertEquals(listOf("m1", "m2"), seen)
        val active = manager.activeNotifications.map { it.id }
        assertEquals(listOf(OpenPushNotificationRenderer.notificationIdFor("m2")), active)
        assertEquals(2, receiptMessageIds().count { it == "m1" })
        assertTrue(logs.contains("duplicate push m1 ignored"))
    }

    @Test fun aRepeatWhileTheFirstIsStillShowingDoesNotRealert() {
        initialize()
        val manager = context.getSystemService(NotificationManager::class.java)
        service().handleData(push("m1"))
        val posted = shadowOf(manager).allNotifications.single()

        service().handleData(push("m1"))

        assertTrue("the shown notification was not re-posted", posted === shadowOf(manager).allNotifications.single())
    }

    @Test fun differentIdsAreBothHandled() {
        initialize()
        service().handleData(push("m1"))
        service().handleData(push("m2"))
        assertEquals(2, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
        assertFalse(logs.any { it.startsWith("duplicate push") })
    }

    @Test fun aPushAListenerPreventedStillCountsAsHandled() {
        initialize()
        foregrounded()
        val handle = OpenPush.Notifications.addForegroundLifecycleListener { it.preventDefault() }
        try {
            service().handleData(push("m1"))
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }

        service().handleData(push("m1"))

        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        assertTrue(logs.contains("duplicate push m1 ignored"))
    }

    @Test fun aRepeatDeliveredToAFreshProcessIsStillIgnored() {
        initialize()
        // What the first copy left behind in a process that has since been killed.
        context.getSharedPreferences("openpush.received", Context.MODE_PRIVATE).edit()
            .putString("messages", JSONObject().put("m1", System.currentTimeMillis()).toString()).commit()

        service().handleData(push("m1"))

        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test fun theStaticHandleAlsoIgnoresARepeat() {
        initialize()
        val manager = context.getSystemService(NotificationManager::class.java)
        val message = RemoteMessage.Builder("to").setData(push("m1")).build()

        OpenPushMessagingService.handle(context, message)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        manager.cancelAll()
        OpenPushMessagingService.handle(context, message)

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertTrue(logs.contains("duplicate push m1 ignored"))
    }

    @Test fun theServiceAndTheStaticHandleShareOneRecord() {
        initialize()
        service().handleData(push("m1"))
        context.getSystemService(NotificationManager::class.java).cancelAll()

        OpenPushMessagingService.handle(context, RemoteMessage.Builder("to").setData(push("m1")).build())

        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test fun aBrokenRecordStillHandlesThePush() {
        initialize()
        OpenPushBridge.receivedMessageLog = RecordingLog(broken = true)

        service().handleData(push("m1"))

        assertEquals(1, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
        assertTrue(logs.any { it.startsWith("duplicate check failed for push m1") })
        assertTrue(logs.any { it.startsWith("push m1 could not be recorded") })
    }

    @Test fun theIdIsRecordedOnlyAfterHandlingFinishes() {
        initialize()
        val log = RecordingLog().also { OpenPushBridge.receivedMessageLog = it }
        var calledBeforeRender: List<String>? = null
        foregrounded()
        val handle = OpenPush.Notifications.addForegroundLifecycleListener { calledBeforeRender = log.calls.toList() }
        try {
            service().handleData(push("m1"))
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }

        assertEquals(listOf("contains m1"), calledBeforeRender)
        assertEquals(listOf("contains m1", "record m1"), log.calls)
    }

    @Test fun aCopyArrivingWhileTheFirstIsStillBeingHandledIsDropped() {
        initialize()
        foregrounded()
        val inListener = CountDownLatch(1)
        val release = CountDownLatch(1)
        val listenerCalls = AtomicInteger()
        val handle = OpenPush.Notifications.addForegroundLifecycleListener {
            listenerCalls.incrementAndGet()
            inListener.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        try {
            // Services are built on the test thread; the provider delivers on its own pool.
            val firstService = service()
            val first = Thread { firstService.handleData(push("m1")) }.apply { start() }
            assertTrue(inListener.await(5, TimeUnit.SECONDS))
            service().handleData(push("m1"))
            release.countDown()
            first.join(5_000)
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }

        assertEquals(1, listenerCalls.get())
        assertTrue(logs.any { it.contains("still being handled") })
    }

    @Test fun aDeliveryThatFailsMidwayLeavesTheIdFreeForALaterCopy() {
        initialize()
        val failing = Robolectric.buildService(FailingOnceService::class.java).create().get()
        runCatching { failing.handleData(push("m1")) }

        service().handleData(push("m1"))

        assertEquals(1, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
    }

    @Test fun foreignMessagesAreNeverClaimed() {
        initialize()
        val log = RecordingLog().also { OpenPushBridge.receivedMessageLog = it }
        val service = Robolectric.buildService(RecordingService::class.java).create().get()
        val message = RemoteMessage.Builder("to").addData("title", "theirs").addData("body", "x").build()

        service.onMessageReceived(message)
        service.onMessageReceived(message)
        OpenPushMessagingService.handle(context, message)

        assertEquals(2, service.foreign.size)
        assertTrue(log.calls.isEmpty())
    }

    @Test fun liveUpdatesAreNotClaimedSoAReusedIdStillApplies() {
        initialize()
        val log = RecordingLog().also { OpenPushBridge.receivedMessageLog = it }
        val manager = context.getSystemService(NotificationManager::class.java)
        // A journey can resend one message id for a Live Update on a later run.
        val start = liveData("start", 1, 10) + ("op_message_id" to "journey-node")
        val update = liveData("update", 2, 75) + ("op_message_id" to "journey-node")

        service().handleData(start, "delivery-42")
        service().handleData(update, "delivery-42")
        OpenPushMessagingService.handle(
            context,
            RemoteMessage.Builder("to").setData(liveData("end", 3, 100) + ("op_message_id" to "journey-node"))
                .setCollapseKey("delivery-42").build(),
        )

        assertTrue("the end frame was applied", shadowOf(manager).allNotifications.isEmpty())
        assertTrue(log.calls.isEmpty())
        assertFalse(logs.any { it.startsWith("duplicate push") })
    }

    private fun liveData(event: String, sequence: Int, progress: Int): Map<String, String> {
        val updates = if (event == "end") {
            ""
        } else {
            ",\"event_updates\":{\"current_progress\":$progress,\"max_progress\":100}"
        }
        return mapOf(
            OpenPushLiveUpdatePayload.DATA_KEY to
                "{\"key\":\"progress\",\"event\":\"$event\",\"sequence\":$sequence," +
                "\"event_attributes\":{\"title\":\"Delivery\"}$updates}",
            "op_message_id" to "live-$sequence",
        )
    }
}
