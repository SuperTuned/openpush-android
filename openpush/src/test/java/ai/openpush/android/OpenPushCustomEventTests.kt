package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushCustomEventTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = TestScheduler()
    private val requests = Collections.synchronizedList(mutableListOf<OpenPush.Request>())
    private val logs = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var gate = CountDownLatch(0)

    @Volatile private var eventStatus = 200

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.scheduler = clock
        OpenPush.onLog = { logs += it }
        // Retries carry random jitter in production; pinned to nothing here so a backoff step lands
        // on the millisecond these tests advance to. The spread itself is covered in OpenPushRetryTests.
        OpenPush.jitter = { 0 }
        OpenPush.initialize(context, appId = "journey-app", sdkKey = "sdk-public") {
            requests += it
            gate.countDown()
            if (it.url.endsWith("/events")) {
                OpenPush.Response(
                    eventStatus,
                    if (eventStatus in 200..299) """{"accepted":2,"dropped":0}""" else "",
                )
            } else {
                OpenPush.Response(200, """{"id":"sub-journey"}""")
            }
        }
        // Synchronous on purpose: a registration that succeeds asks the custom event queue to flush,
        // and through `register` that call lands some time after the transport has already returned —
        // late enough to add a second flush to a test that has begun tracking events.
        OpenPush.registerBlocking("provider-token")
        assertTrue(clock.isIdle())
        requests.clear()
        logs.clear()
    }

    @After fun restore() {
        OpenPush.scheduler = OpenPush.RealScheduler
        OpenPush.resetForTests()
    }

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }
    private fun CountDownLatch.awaitRequests() = assertTrue(await(2, TimeUnit.SECONDS))

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.yield()
        assertTrue(condition())
    }

    /** The queue as it sits on disk — the whole of it, not the 50 a batch claims. */
    private fun queued(): JSONArray {
        val prefs = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
        repeat(10) {
            val count = prefs.getInt("openpush.deviceToken.customEvents.count", -1)
            if (count < 0) {
                val legacy = prefs.getString("openpush.deviceToken.customEvents", null)
                return if (legacy == null) JSONArray() else JSONArray(legacy)
            }
            val head = prefs.getInt("openpush.deviceToken.customEvents.head", 0)
            val records = (0 until count).map { offset ->
                val slot = (head + offset) % OpenPush.CUSTOM_EVENT_QUEUE_CAP
                prefs.getString("openpush.deviceToken.customEvents.slot.$slot", null)
            }
            if (records.all { it != null }) {
                return JSONArray().also { queue -> records.forEach { queue.put(JSONObject(it!!)) } }
            }
            // A flush updates the slot values and metadata in one editor, but these reads are
            // separate calls and can straddle that update. Retry the snapshot rather than turning
            // that observation window into a product failure.
            Thread.yield()
        }
        throw AssertionError("custom-event queue metadata did not match its slot records")
    }

    private fun eventsOf(request: OpenPush.Request): JSONArray = JSONObject(request.body!!).getJSONArray("events")

    private fun keysOf(request: OpenPush.Request): List<String> {
        val events = eventsOf(request)
        return (0 until events.length()).map { events.getJSONObject(it).getString("idempotency_key") }
    }

    /** Fires the retry the last failure scheduled: the timer itself, then the zero-delay flush it
     *  posts, then the request that flush makes. */
    private fun awaitRetryAt(delay: Long) {
        val before = requests.size
        clock.advance(delay - 1)
        assertEquals("a retry fired before ${delay}ms had passed", before, requests.size)
        expect(1)
        clock.advance(1)
        clock.advance(0)
        gate.awaitRequests()
    }

    @Test fun nearbyEventsShareOneAttributedRequest() {
        OpenPush.trackEvent("onboarding_started", mapOf("step" to 1))
        OpenPush.trackEvent("onboarding_finished", mapOf("step" to 2))

        clock.advance(OpenPush.COALESCE_WINDOW_MS - 1)
        assertTrue(requests.isEmpty())
        gate = CountDownLatch(1)
        clock.advance(1)
        assertTrue(gate.await(2, TimeUnit.SECONDS))

        val request = requests.single()
        assertTrue(request.url.endsWith("/v1/apps/journey-app/events"))
        val events = JSONObject(request.body!!).getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("onboarding_started", events.getJSONObject(0).getString("name"))
        assertEquals("sub-journey", events.getJSONObject(0).getString("subscription_id"))
        assertEquals("onboarding_finished", events.getJSONObject(1).getString("name"))
    }

    @Test fun anEventTrackedBeforeInitializeRidesTheFirstFlush() {
        OpenPush.resetForTests()
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        requests.clear()

        OpenPush.trackEvent("before_initialize", mapOf("step" to 1))
        OpenPush.initialize(context, appId = "journey-app", sdkKey = "sdk-public") {
            requests += it
            gate.countDown()
            if (it.url.endsWith("/events")) {
                OpenPush.Response(200, "{}")
            } else {
                OpenPush.Response(200, """{"id":"sub-journey"}""")
            }
        }
        OpenPush.registerBlocking("provider-token")
        requests.clear()

        expect(1)
        clock.advance(0)
        gate.awaitRequests()

        val event = eventsOf(requests.single()).getJSONObject(0)
        assertEquals("before_initialize", event.getString("name"))
        assertEquals(1, event.getJSONObject("properties").getInt("step"))
        assertEquals("sub-journey", event.getString("subscription_id"))
    }

    @Test fun preInitializeStagingKeepsTheNewestFiftyAndWarnsOnce() {
        OpenPush.resetForTests()
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        logs.clear()

        repeat(OpenPush.STAGED_CUSTOM_EVENT_CAP + 2) { OpenPush.trackEvent("staged_$it") }
        OpenPush.initialize(context, appId = "journey-app", sdkKey = "sdk-public") {
            OpenPush.Response(200, "{}")
        }

        val stored = queued()
        assertEquals(OpenPush.STAGED_CUSTOM_EVENT_CAP, stored.length())
        assertEquals("staged_2", stored.getJSONObject(0).getString("name"))
        assertEquals(
            "staged_${OpenPush.STAGED_CUSTOM_EVENT_CAP + 1}",
            stored.getJSONObject(stored.length() - 1).getString("name"),
        )
        assertEquals(1, logs.count { it.contains("staged before initialize") })
    }

    @Test fun enqueueStoresOneIndependentRecordInsteadOfRewritingItsPredecessors() {
        val prefs = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
        OpenPush.trackEvent("first")
        val firstRecord = prefs.getString("openpush.deviceToken.customEvents.slot.0", null)

        OpenPush.trackEvent("second")

        assertEquals(2, prefs.getInt("openpush.deviceToken.customEvents.count", -1))
        assertEquals(firstRecord, prefs.getString("openpush.deviceToken.customEvents.slot.0", null))
        assertEquals("first", JSONObject(firstRecord!!).getString("name"))
        assertEquals(
            "second",
            JSONObject(
                prefs.getString("openpush.deviceToken.customEvents.slot.1", null)!!,
            ).getString("name"),
        )
        assertFalse(prefs.contains("openpush.deviceToken.customEvents"))
    }

    @Test fun aLegacyArrayMigratesOnceWithoutChangingOrderOrEventIdentity() {
        OpenPush.resetForTests()
        val prefs = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(
            "openpush.deviceToken.customEvents",
            JSONArray()
                .put(
                    JSONObject().put("name", "first").put("properties", JSONObject())
                        .put("timestamp", 1.0).put("idempotency_key", "stable-first"),
                )
                .put(
                    JSONObject().put("name", "second").put("properties", JSONObject())
                        .put("timestamp", 2.0).put("idempotency_key", "stable-second"),
                )
                .toString(),
        ).commit()

        OpenPush.initialize(context, appId = "journey-app", sdkKey = "sdk-public") {
            OpenPush.Response(200, "{}")
        }

        val migrated = queued()
        assertEquals(
            listOf("first", "second"),
            (0 until migrated.length()).map { migrated.getJSONObject(it).getString("name") },
        )
        assertEquals(
            listOf("stable-first", "stable-second"),
            (0 until migrated.length()).map { migrated.getJSONObject(it).getString("idempotency_key") },
        )
        assertFalse(prefs.contains("openpush.deviceToken.customEvents"))
        assertEquals(2, prefs.getInt("openpush.deviceToken.customEvents.count", -1))
    }

    @Test fun slotRecordsSurviveAColdStartWithFifoOrderAndRetryIdentity() {
        OpenPush.trackEvent("first")
        OpenPush.trackEvent("second")
        val stored = queued()
        val storedKeys = (0 until stored.length()).map {
            stored.getJSONObject(it).getString("idempotency_key")
        }

        OpenPush.resetForTests()
        requests.clear()
        OpenPush.initialize(context, appId = "journey-app", sdkKey = "sdk-public") {
            requests += it
            if (it.url.endsWith("/events")) {
                gate.countDown()
                OpenPush.Response(200, "{}")
            } else {
                OpenPush.Response(200, """{"id":"sub-journey"}""")
            }
        }
        OpenPush.registerBlocking("provider-token")
        requests.clear()

        expect(1)
        clock.advance(0)
        gate.awaitRequests()

        val sent = eventsOf(requests.single { it.url.endsWith("/events") })
        assertEquals(
            listOf("first", "second"),
            (0 until sent.length()).map { sent.getJSONObject(it).getString("name") },
        )
        assertEquals(
            storedKeys,
            (0 until sent.length()).map { sent.getJSONObject(it).getString("idempotency_key") },
        )
    }

    /**
     * A caller that knows the event's identity supplies the key and the SDK sends exactly that —
     * which is what makes a once-per-device outcome collapse server-side into one occurrence.
     */
    @Test fun aSuppliedIdempotencyKeyIsSentVerbatim() {
        OpenPush.trackEvent("signup", emptyMap(), "unique:signup:sub-journey")
        OpenPush.trackEvent("signup", emptyMap(), "unique:signup:sub-journey")
        OpenPush.trackEvent("signup")

        gate = CountDownLatch(1)
        clock.advance(OpenPush.COALESCE_WINDOW_MS)
        assertTrue(gate.await(2, TimeUnit.SECONDS))

        val keys = keysOf(requests.single())
        assertEquals("unique:signup:sub-journey", keys[0])
        assertEquals("unique:signup:sub-journey", keys[1])
        // The generated key is untouched by the overload: no key supplied, a fresh UUID stamped.
        assertNotEquals(keys[0], keys[2])
        UUID.fromString(keys[2])
    }

    @Test fun invalidEventDoesNotScheduleARequest() {
        OpenPush.trackEvent("bad/event")
        OpenPush.trackEvent("not_json", mapOf("value" to Double.NaN))
        clock.advance(OpenPush.COALESCE_WINDOW_MS)
        assertTrue(requests.isEmpty())
    }

    @Test fun workspaceSwitchClearsQueuedEvents() {
        OpenPush.trackEvent("belongs_to_first_workspace")

        OpenPush.attach(
            context,
            OpenPushConfiguration("other-app", "other-key", tokenKey = "other-token"),
            credential = { null },
            transport = { OpenPush.Response(200, "{}") },
        )
        OpenPush.User.addTag("workspace", "other")
        clock.advance(OpenPush.COALESCE_WINDOW_MS)

        assertTrue(requests.none { it.url.endsWith("/events") })
        assertTrue(
            context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
                .getString("openpush.deviceToken.customEvents", null) == null,
        )
    }

    @Test fun everyEventCarriesAUniqueIdempotencyKey() {
        OpenPush.trackEvent("first")
        OpenPush.trackEvent("second")
        OpenPush.flushCustomEventsBlocking()

        val keys = keysOf(requests.single())
        assertEquals(2, keys.size)
        keys.forEach { assertEquals(it, UUID.fromString(it).toString()) }
        assertNotEquals(keys[0], keys[1])
    }

    @Test fun aRetriedBatchResendsTheSameKeys() {
        eventStatus = 503
        OpenPush.trackEvent("survives_the_outage")
        expect(1)
        clock.advance(OpenPush.COALESCE_WINDOW_MS)
        gate.awaitRequests()
        awaitCondition { !clock.isIdle() }

        eventStatus = 200
        awaitRetryAt(2_000)

        assertEquals(2, requests.size)
        assertEquals(keysOf(requests[0]), keysOf(requests[1]))
        assertEquals(1, keysOf(requests[1]).size)
        awaitCondition { queued().length() == 0 }
    }

    @Test fun aFourHundredDropsTheBatchAndLogsTheStatus() {
        eventStatus = 400
        OpenPush.trackEvent("malformed_for_this_server")
        OpenPush.trackEvent("its_neighbour")
        expect(1)
        clock.advance(OpenPush.COALESCE_WINDOW_MS)
        gate.awaitRequests()

        awaitCondition { queued().length() == 0 }
        assertTrue(logs.any { it.contains("refused (400)") && it.contains("batch of 2") })
        // Nothing is left to retry, so nothing is left ticking: the queue does not wedge.
        awaitCondition { clock.isIdle() }
        clock.advance(60_000)
        assertEquals(1, requests.size)
    }

    @Test fun aFourTwentyNineIsRetried() {
        eventStatus = 429
        OpenPush.trackEvent("too_fast")
        expect(1)
        clock.advance(OpenPush.COALESCE_WINDOW_MS)
        gate.awaitRequests()
        awaitCondition { !clock.isIdle() }
        assertEquals(1, queued().length())

        eventStatus = 200
        awaitRetryAt(2_000)
        assertEquals(2, requests.size)
        awaitCondition { queued().length() == 0 }
    }

    @Test fun retriesBackOffAndCap() {
        eventStatus = 503
        OpenPush.trackEvent("stubborn")
        expect(1)
        clock.advance(OpenPush.COALESCE_WINDOW_MS)
        gate.awaitRequests()

        listOf(2_000L, 8_000L, 30_000L, 30_000L).forEach { delay ->
            awaitCondition { !clock.isIdle() }
            awaitRetryAt(delay)
        }

        assertEquals(5, requests.size)
        assertEquals(1, queued().length())
        requests.forEach { assertEquals("stubborn", eventsOf(it).getJSONObject(0).getString("name")) }
    }

    @Test fun theQueueIsCappedAtFiveHundredDroppingTheOldest() {
        repeat(OpenPush.CUSTOM_EVENT_QUEUE_CAP + 2) { OpenPush.trackEvent("event_$it") }

        val stored = queued()
        assertEquals(OpenPush.CUSTOM_EVENT_QUEUE_CAP, stored.length())
        assertEquals("event_2", stored.getJSONObject(0).getString("name"))
        assertEquals(
            "event_${OpenPush.CUSTOM_EVENT_QUEUE_CAP + 1}",
            stored.getJSONObject(stored.length() - 1).getString("name"),
        )
        assertEquals(1, logs.count { it.contains("dropping the oldest") })
    }

    @Test fun anUnsupportedPropertyValueIsDroppedWithTheMessage() {
        OpenPush.trackEvent("opaque_value", mapOf("x" to Any()))
        OpenPush.trackEvent("opaque_inside_a_list", mapOf("x" to listOf(Any())))
        OpenPush.trackEvent("non_string_key", mapOf("x" to mapOf(1 to "one")))

        assertEquals(0, queued().length())
        assertEquals(3, logs.count { it.contains("not JSON-safe") })
        assertTrue(logs.any { it.contains("'opaque_value'") })
        clock.advance(OpenPush.COALESCE_WINDOW_MS)
        assertTrue(requests.isEmpty())
    }

    @Test fun nestedJsonSafePropertiesStillRideTheWire() {
        OpenPush.trackEvent(
            "cart",
            mapOf(
                "items" to listOf(mapOf("sku" to "a-1", "qty" to 2), mapOf("sku" to "b-2", "qty" to 1)),
                "codes" to arrayOf("SPRING", "VIP"),
                "sizes" to intArrayOf(1, 2),
                "total" to 19.5,
                "gift" to true,
                "note" to null,
            ),
        )
        OpenPush.flushCustomEventsBlocking()

        val props = eventsOf(requests.single()).getJSONObject(0).getJSONObject("properties")
        assertEquals("a-1", props.getJSONArray("items").getJSONObject(0).getString("sku"))
        assertEquals("VIP", props.getJSONArray("codes").getString(1))
        assertEquals(2, props.getJSONArray("sizes").getInt(1))
        assertEquals(19.5, props.getDouble("total"), 0.0)
        assertTrue(props.getBoolean("gift"))
        assertTrue(props.isNull("note"))
    }

    @Test fun charSequencePropertiesKeepTheCompatibilityStringCoercion() {
        OpenPush.trackEvent(
            "typed_text",
            mapOf("builder" to StringBuilder("hello"), "nested" to listOf(StringBuffer("world"))),
        )
        OpenPush.flushCustomEventsBlocking()

        val props = eventsOf(requests.single()).getJSONObject(0).getJSONObject("properties")
        assertEquals("hello", props.getString("builder"))
        assertEquals("world", props.getJSONArray("nested").getString(0))
    }

    @Test fun timestampsUseTheTestClock() {
        OpenPush.nowMs = { 1_700_000_000_000L }
        OpenPush.trackEvent("at_a_known_instant")
        OpenPush.flushCustomEventsBlocking()

        assertEquals(1.7e9, eventsOf(requests.single()).getJSONObject(0).getDouble("timestamp"), 0.0)
    }

    @Test fun aDroppedBatchLeavesTheRestOfTheQueueFlushing() {
        eventStatus = 400
        repeat(60) { OpenPush.trackEvent("event_$it") }
        expect(1)
        clock.advance(OpenPush.COALESCE_WINDOW_MS)
        gate.awaitRequests()

        // The drop posts the next flush immediately rather than leaving the tail stranded.
        awaitCondition { !clock.isIdle() }
        expect(1)
        clock.advance(0)
        gate.awaitRequests()

        awaitCondition { queued().length() == 0 }
        assertEquals(50, eventsOf(requests[0]).length())
        assertEquals(10, eventsOf(requests[1]).length())
        assertFalse(logs.any { it.contains("retrying") })
    }
}
