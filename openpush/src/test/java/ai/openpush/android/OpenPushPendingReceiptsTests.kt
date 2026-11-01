package ai.openpush.android

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * A tap on a notification can start the process and report the click before the host has called
 * `initialize` — wrapper SDKs initialize late. These cover the staging that keeps that receipt, and
 * the delivery receipt of a push to a killed app, until the SDK is attached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushPendingReceiptsTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private val logs = Collections.synchronizedList(mutableListOf<String>())

    @Before fun reset() {
        prefs.edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        bodies.clear()
        logs.clear()
        OpenPush.onLog = { logs += it }
    }

    private fun attach() {
        OpenPush.attach(
            context,
            OpenPushConfiguration(appID = "demo", sdkKey = "sdk", serverURL = "https://example.test"),
            { "opdev" },
        ) { request ->
            if (request.url.endsWith("/v1/ingest")) {
                bodies += request.body!!
                OpenPush.Response(202, "{}")
            } else {
                OpenPush.Response(200, """{"id":"sub_1"}""")
            }
        }
    }

    /** A previous process registered a token; this one starts cold with only the preferences. */
    private fun coldStartAfterAnEarlierRegistration() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.settleForTests()
        OpenPush.resetForTests()
        bodies.clear()
        logs.clear()
        // The startup initializer hands OpenPush the Application before any host code runs.
        OpenPush.observeLifecycle(context as Application)
    }

    private fun sentEvents(): List<Pair<String, String>> = bodies.flatMap { body ->
        val array = JSONObject(body).getJSONArray("events")
        (0 until array.length()).map {
            val event = array.getJSONObject(it)
            event.getString("type") to event.getString("message_id")
        }
    }

    @Test fun aClickBeforeInitializeIsStagedAndSentAfterInitialize() {
        coldStartAfterAnEarlierRegistration()

        OpenPush.Notifications.processClick(mapOf("op_message_id" to "m1"))
        OpenPush.settleForTests()

        assertTrue("nothing can be sent before initialize", bodies.isEmpty())
        assertTrue(logs.contains("staged receipt clicked for m1 until initialize"))
        assertEquals(1, OpenPush.stagedReceiptCount())

        attach()
        OpenPush.settleForTests()

        assertEquals(listOf("clicked" to "m1"), sentEvents())
        assertEquals(0, OpenPush.stagedReceiptCount())
        assertFalse(OpenPush.hasPendingReceipts())
    }

    @Test fun aDeliveryToAKilledAppIsStagedAndSentAfterInitialize() {
        coldStartAfterAnEarlierRegistration()

        OpenPush.processForegroundMessageBlocking(mapOf("op_message_id" to "m1"))
        assertTrue(bodies.isEmpty())

        attach()
        OpenPush.settleForTests()

        assertEquals(listOf("received" to "m1"), sentEvents())
    }

    @Test fun stagingSurvivesAProcessDeathBeforeInitialize() {
        coldStartAfterAnEarlierRegistration()
        OpenPush.processClickBlocking(mapOf("op_message_id" to "m1"))

        // The process dies between the tap and the host's initialize.
        OpenPush.resetForTests()
        assertEquals(0, bodies.size)
        OpenPush.observeLifecycle(context as Application)
        attach()
        OpenPush.settleForTests()

        assertEquals(listOf("clicked" to "m1"), sentEvents())
        assertEquals(0, OpenPush.stagedReceiptCount())
    }

    @Test fun aReceiptStagedTwiceIsSentOnce() {
        coldStartAfterAnEarlierRegistration()
        OpenPush.processClickBlocking(mapOf("op_message_id" to "m1"))
        OpenPush.processClickBlocking(mapOf("op_message_id" to "m1"))
        assertEquals(1, OpenPush.stagedReceiptCount())

        attach()
        OpenPush.settleForTests()

        assertEquals(listOf("clicked" to "m1"), sentEvents())
    }

    @Test fun aStagedReceiptWaitsForATokenWhenThereIsNoneYet() {
        OpenPush.observeLifecycle(context as Application)
        OpenPush.processClickBlocking(mapOf("op_message_id" to "m1"))

        attach()
        OpenPush.settleForTests()
        assertTrue(bodies.isEmpty())
        assertEquals(1, OpenPush.stagedReceiptCount())

        OpenPush.registerBlocking("provider-token")
        OpenPush.settleForTests()

        assertEquals(listOf("clicked" to "m1"), sentEvents())
        assertEquals(0, OpenPush.stagedReceiptCount())
    }

    @Test fun receiptsRecordedAfterAttachAreSentDirectly() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.settleForTests()
        bodies.clear()

        OpenPush.processClickBlocking(mapOf("op_message_id" to "m1"))

        assertEquals(listOf("clicked" to "m1"), sentEvents())
        assertEquals(0, OpenPush.stagedReceiptCount())
        assertFalse(logs.any { it.startsWith("staged receipt") })
    }

    @Test fun theStagingAreaKeepsTheNewestFifty() {
        val staging = PendingReceipts({ prefs }) { 0L }
        repeat(52) { index -> staging.stage("clicked", "m$index") }

        assertEquals(PendingReceipts.CAP, staging.size)
        val drained = mutableListOf<String>()
        staging.drain { drained += it.messageId }
        assertEquals((2 until 52).map { "m$it" }, drained)
    }

    @Test fun theStagingAreaCollapsesDuplicatesAndKeepsTimestamps() {
        var clock = 100L
        val staging = PendingReceipts({ prefs }) { clock }
        assertTrue(staging.stage("clicked", "m1"))
        clock = 200L
        assertFalse(staging.stage("clicked", "m1"))
        assertTrue(staging.stage("received", "m1"))

        // A second instance over the same preferences is a restarted process.
        val restarted = PendingReceipts({ prefs }) { 999L }
        val drained = mutableListOf<PendingReceipts.Entry>()
        restarted.drain { drained += it }
        assertEquals(
            listOf(
                PendingReceipts.Entry("clicked", "m1", 100L),
                PendingReceipts.Entry("received", "m1", 200L),
            ),
            drained,
        )
        assertEquals(0, PendingReceipts({ prefs }) { 0L }.size)
    }
}
