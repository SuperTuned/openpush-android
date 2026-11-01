package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * A receipt is the only evidence that a message was delivered or opened, and it is owed at the worst
 * possible moment for a network. These cover the durable queue that holds it until the server says
 * it has it: across a failed request, across a process death, and without ever sending it twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushReceiptQueueTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private val urls = Collections.synchronizedList(mutableListOf<String>())
    private val logs = Collections.synchronizedList(mutableListOf<String>())

    /** What the next `/v1/ingest` answers. Everything else always succeeds. */
    @Volatile private var ingestStatus = 202

    /** Answers used, one per `/v1/ingest`, before falling back to [ingestStatus]. */
    private val ingestAnswers = java.util.concurrent.ConcurrentLinkedQueue<Int>()

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        OpenPush.onIdentityRejected = null
        bodies.clear()
        urls.clear()
        logs.clear()
        ingestStatus = 202
        ingestAnswers.clear()
        OpenPush.onLog = { logs += it }
    }

    private fun attach() {
        OpenPush.attach(
            context,
            OpenPushConfiguration(appID = "demo", sdkKey = "sdk", serverURL = "https://example.test"),
            { "opdev" },
        ) { request ->
            if (request.url.endsWith("/v1/ingest")) {
                urls += request.url
                bodies += request.body!!
                OpenPush.Response(ingestAnswers.poll() ?: ingestStatus, "{}")
            } else {
                OpenPush.Response(200, """{"id":"sub_1"}""")
            }
        }
    }

    /** Attaches and registers a provider token, then forgets the traffic that took. */
    private fun registered() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.settleForTests()
        bodies.clear()
        urls.clear()
        logs.clear()
    }

    private fun events(body: String) = JSONObject(body).getJSONArray("events")

    @Test fun aReceiptSurvivesAColdStartAndIsSentOnTheNextForeground() {
        registered()
        ingestStatus = 503
        OpenPush.receiptBlocking("received", "m1")
        assertEquals("the failed receipt was not attempted", 1, urls.size)
        assertTrue("a receipt the server refused was forgotten", OpenPush.hasPendingReceipts())

        // The process dies here. Only SharedPreferences crosses the gap.
        OpenPush.resetForTests()
        bodies.clear()
        urls.clear()
        ingestStatus = 202
        attach()
        OpenPush.onAppForegrounded()
        OpenPush.settleForTests()

        assertEquals("the restored receipt was not sent exactly once", 1, urls.size)
        assertTrue(urls.single().endsWith("/v1/ingest"))
        val event = events(bodies.single()).getJSONObject(0)
        assertEquals("received", event.getString("type"))
        assertEquals("m1", event.getString("message_id"))
        assertEquals("provider-token", event.getString("token"))
        assertEquals("demo", event.getString("app"))
        assertFalse(OpenPush.hasPendingReceipts())
    }

    @Test fun receiptsAreBatchedFiftyPerRequest() {
        registered()
        ingestStatus = 503
        repeat(120) { index -> OpenPush.receiptBlocking("received", "m$index") }
        assertTrue(OpenPush.hasPendingReceipts())

        bodies.clear()
        urls.clear()
        ingestStatus = 202
        OpenPush.drainReceiptsBlocking()

        assertEquals("120 owed receipts should be three requests", 3, urls.size)
        assertEquals(listOf(50, 50, 20), bodies.map { events(it).length() })
        // In the order they were queued, and each one only once.
        val ids = bodies.flatMap { body ->
            val array = events(body)
            (0 until array.length()).map { array.getJSONObject(it).getString("message_id") }
        }
        assertEquals((0 until 120).map { "m$it" }, ids)
        assertFalse(OpenPush.hasPendingReceipts())
    }

    @Test fun theDedupeSetIsBoundedToFiveHundred() {
        val prefs = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
        val queue = ReceiptQueue(prefs, "k.receipts", "k.receiptsSent") { 0L }
        repeat(520) { index ->
            assertTrue(queue.enqueue("received", "m$index", "tok", "demo"))
            queue.markSent(queue.nextBatch(1))
        }

        assertEquals(ReceiptQueue.SENT_CAP, queue.sentKeys.size)
        assertEquals("received|m20", queue.sentKeys.first())
        assertEquals("received|m519", queue.sentKeys.last())
        // The oldest keys were dropped, so their receipts can be owed again; the newest cannot.
        assertTrue(queue.enqueue("received", "m0", "tok", "demo"))
        assertFalse(queue.enqueue("received", "m519", "tok", "demo"))

        // The bound is on disk, not only in memory.
        val reloaded = ReceiptQueue(prefs, "k.receipts", "k.receiptsSent") { 0L }
        assertEquals(ReceiptQueue.SENT_CAP, reloaded.sentKeys.size)
        assertEquals("received|m519", reloaded.sentKeys.last())
    }

    @Test fun thePendingQueueIsBoundedToFiveHundredAndLogsOnce() {
        val prefs = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
        val queue = ReceiptQueue(prefs, "k.receipts", "k.receiptsSent") { 0L }

        repeat(ReceiptQueue.PENDING_CAP + 2) { index ->
            assertTrue(queue.enqueue("received", "m$index", "tok", "demo"))
        }

        assertEquals(ReceiptQueue.PENDING_CAP, queue.size)
        assertEquals(1, logs.count { it.contains("receipts over 500 queued") })
        val retained = queue.nextBatch(ReceiptQueue.PENDING_CAP)
        assertEquals("m2", retained.first().messageId)
        assertEquals("m501", retained.last().messageId)

        // The cap is durable, not an in-memory view applied only while enqueueing.
        val reloaded = ReceiptQueue(prefs, "k.receipts", "k.receiptsSent") { 0L }
        assertEquals(ReceiptQueue.PENDING_CAP, reloaded.size)
        assertEquals("m2", reloaded.nextBatch(1).single().messageId)
    }

    @Test fun aDuplicateReceiptIsNotEnqueued() {
        registered()
        OpenPush.receiptBlocking("received", "m1")
        assertEquals(1, urls.size)

        OpenPush.receiptBlocking("received", "m1")
        assertEquals("the same receipt went twice", 1, urls.size)
        assertTrue(logs.any { it.contains("already queued or acknowledged") })

        // A different event type for the same message is a different receipt and still goes.
        OpenPush.receiptBlocking("clicked", "m1")
        assertEquals(2, urls.size)
        assertEquals("clicked", events(bodies.last()).getJSONObject(0).getString("type"))
    }

    @Test fun theLogTellsTheTruthOnFailure() {
        registered()
        ingestStatus = 503
        OpenPush.receiptBlocking("received", "m1")

        assertTrue(
            "the log never said the receipt is still owed: $logs",
            logs.any { it.contains("receipts queued for retry (503)") },
        )
        assertTrue(
            "a receipt that is still queued was logged as a failure: $logs",
            logs.none { it.contains("receipt") && it.contains("failed") },
        )
        assertTrue(OpenPush.hasPendingReceipts())
    }

    @Test fun drainRunsAfterAnAcceptedRegistration() {
        registered()
        ingestStatus = 503
        OpenPush.receiptBlocking("received", "m1")
        bodies.clear()
        urls.clear()
        ingestStatus = 202

        // No foreground transition in between: the accepted registration is the only thing here
        // that can drain the queue.
        OpenPush.register("provider-token")
        // Two settles: the accept path enqueues the drain while it is itself running, so the drain
        // lands behind the latch the first settle put on the queue.
        OpenPush.settleForTests()
        OpenPush.settleForTests()

        assertEquals("an accepted registration did not drain the receipt queue", 1, urls.size)
        assertEquals("m1", events(bodies.single()).getJSONObject(0).getString("message_id"))
        assertFalse(OpenPush.hasPendingReceipts())
    }

    @Test fun aWorkspaceSwitchDropsTheQueueAndARepeatedAttachKeepsIt() {
        registered()
        ingestStatus = 503
        OpenPush.receiptBlocking("received", "m1")
        assertTrue(OpenPush.hasPendingReceipts())

        // Same configuration: nothing about the subscription changed, so the receipt is still owed.
        attach()
        assertTrue("a repeated attach dropped a receipt that is still owed", OpenPush.hasPendingReceipts())

        // A different workspace has never heard of this message.
        OpenPush.attach(
            context,
            OpenPushConfiguration(appID = "other", sdkKey = "sdk2", serverURL = "https://example.test"),
            { "opdev" },
        ) { OpenPush.Response(200, """{"id":"sub_2"}""") }
        assertFalse("a workspace switch kept the previous workspace's receipts", OpenPush.hasPendingReceipts())
    }

    private fun sentIds() = bodies.flatMap { body ->
        val array = events(body)
        (0 until array.length()).map { array.getJSONObject(it).getString("message_id") }
    }

    /** Sixty receipts owed: two batches, the first of fifty. */
    private fun sixtyOwed() {
        registered()
        ingestStatus = 503
        repeat(60) { index -> OpenPush.receiptBlocking("received", "m$index") }
        bodies.clear()
        urls.clear()
        logs.clear()
    }

    @Test fun aBatchTheServerRefusesIsDroppedAndTheNextBatchIsSent() {
        for (refusal in listOf(400, 404, 422)) {
            reset()
            sixtyOwed()
            ingestAnswers += refusal
            ingestStatus = 202

            OpenPush.drainReceiptsBlocking()

            assertEquals(
                "the refused batch and the next one, each once ($refusal)",
                listOf(50, 10),
                bodies.map {
                    events(it).length()
                },
            )
            assertEquals((50 until 60).map { "m$it" }, sentIds().drop(50))
            assertFalse("a refused batch stayed owed ($refusal)", OpenPush.hasPendingReceipts())
            assertTrue(logs.any { it.contains("receipts dropped ($refusal)") })

            // Dropped, not acknowledged: nothing about it went into the dedupe history, and a drain
            // after it sends nothing more.
            bodies.clear()
            urls.clear()
            OpenPush.drainReceiptsBlocking()
            assertEquals(0, urls.size)
        }
    }

    @Test fun aServerFailureKeepsTheBatchAndStopsTheDrain() {
        for (failure in listOf(500, 503, 429)) {
            reset()
            sixtyOwed()
            ingestStatus = failure

            OpenPush.drainReceiptsBlocking()

            assertEquals("the drain stops at the failing batch ($failure)", 1, urls.size)
            assertTrue(OpenPush.hasPendingReceipts())
            assertTrue(logs.none { it.contains("receipts dropped") })

            ingestStatus = 202
            bodies.clear()
            urls.clear()
            OpenPush.drainReceiptsBlocking()
            assertEquals((0 until 60).map { "m$it" }, sentIds())
        }
    }

    @Test fun aReceiptIsGivenUpAfterTenServerFailuresAnHourApart() {
        var clock = 1_700_000_000_000L
        val prefs = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
        val queue = ReceiptQueue(prefs, "k.receipts", "k.receiptsSent") { clock }
        queue.enqueue("received", "m1", "tok", "demo")

        repeat(ReceiptQueue.MAX_SERVER_FAILURES - 1) {
            assertEquals(0, queue.markFailed(queue.nextBatch()))
            clock += ReceiptQueue.SERVER_FAILURE_SPACING_MS
        }
        assertEquals(1, queue.size)
        // The count is durable, so a relaunch does not reset it.
        val reloaded = ReceiptQueue(prefs, "k.receipts", "k.receiptsSent") { clock }
        assertEquals(1, reloaded.markFailed(reloaded.nextBatch()))
        assertEquals(0, reloaded.size)
        assertEquals(0, ReceiptQueue(prefs, "k.receipts", "k.receiptsSent") { clock }.size)
    }

    @Test fun anOutageCountsAsOneServerFailureAnHour() {
        var clock = 1_700_000_000_000L
        val queue =
            ReceiptQueue(
                context.getSharedPreferences("openpush", Context.MODE_PRIVATE),
                "k.receipts",
                "k.receiptsSent",
            ) {
                clock
            }
        queue.enqueue("received", "m1", "tok", "demo")

        // Every new receipt and every foreground drains; an hour of a failing server is one failure.
        repeat(200) {
            assertEquals(0, queue.markFailed(queue.nextBatch()))
            clock += 10_000
        }

        assertEquals("an outage used up a receipt's failures", 1, queue.size)
    }

    @Test fun networkFailuresNeverUseUpTheServerFailureCount() {
        val queue =
            ReceiptQueue(
                context.getSharedPreferences("openpush", Context.MODE_PRIVATE),
                "k.receipts",
                "k.receiptsSent",
            ) {
                0L
            }
        queue.enqueue("received", "m1", "tok", "demo")

        repeat(50) { assertEquals(0, queue.markFailed(queue.nextBatch(), serverAnswered = false)) }

        assertEquals("an offline device lost a receipt to its own lack of network", 1, queue.size)
    }

    @Test fun aReceiptOwedForOverSevenDaysIsGivenUpOnlyWhenADrainFails() {
        var clock = 1_700_000_000_000L
        val prefs = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
        val queue = ReceiptQueue(prefs, "k.receipts", "k.receiptsSent") { clock }
        queue.enqueue("received", "old", "tok", "demo")
        clock += ReceiptQueue.MAX_AGE_MS - 1
        queue.enqueue("received", "young", "tok", "demo")

        // Old but not yet past the bound: a failure keeps both.
        assertEquals(0, queue.markFailed(queue.nextBatch(), serverAnswered = false))
        assertEquals(2, queue.size)

        clock += 2
        // Past the bound, an old receipt still goes out with its batch; it is only given up when
        // that drain fails.
        val batch = queue.nextBatch()
        assertEquals(listOf("old", "young"), batch.map { it.messageId })
        assertEquals(1, queue.markFailed(batch, serverAnswered = false))
        assertEquals(listOf("young"), queue.nextBatch().map { it.messageId })
    }

    /**
     * A device upgrading with receipts an earlier build stored: no `server_failures` key, and an
     * `attempts` count that included network failures. They load, drain, and their old attempt
     * count never evicts them.
     */
    @Test fun receiptsStoredByAnEarlierBuildStillDrain() {
        val stored = JSONArray()
        repeat(3) { index ->
            stored.put(
                JSONObject()
                    .put("type", "received")
                    .put("message_id", "legacy$index")
                    .put("token", "provider-token")
                    .put("app", "demo")
                    .put("queued_at", System.currentTimeMillis() - 60_000L)
                    .put("attempts", 25),
            )
        }
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit()
            .putString("openpush.deviceToken.receipts", stored.toString())
            .putString("openpush.deviceToken", "provider-token")
            .commit()

        attach()
        ingestStatus = 503
        OpenPush.drainReceiptsBlocking()
        assertTrue("one server failure evicted receipts with old attempt counts", OpenPush.hasPendingReceipts())

        ingestStatus = 202
        bodies.clear()
        urls.clear()
        OpenPush.drainReceiptsBlocking()

        assertEquals(listOf("legacy0", "legacy1", "legacy2"), sentIds())
        assertFalse(OpenPush.hasPendingReceipts())
    }
}
