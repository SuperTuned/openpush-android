package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushAppSessionTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val requests = Collections.synchronizedList(mutableListOf<OpenPush.Request>())

    @Volatile private var gate = CountDownLatch(0)

    @Volatile private var durationStarted = CountDownLatch(0)

    @Volatile private var durationRelease = CountDownLatch(0)
    private val durationStatuses = ArrayDeque<Int>()
    private var clock = 2_000_000L

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.nowMs = { clock }
        OpenPush.onLog = {}
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") {
            requests += it
            gate.countDown()
            val body = it.body?.let(::JSONObject)
            if (it.url.endsWith("/sessions") && body?.has("duration_seconds") == true) {
                durationStarted.countDown()
                durationRelease.await(2, TimeUnit.SECONDS)
                val status = synchronized(durationStatuses) {
                    if (durationStatuses.isEmpty()) 200 else durationStatuses.removeFirst()
                }
                OpenPush.Response(status, "{}")
            } else {
                OpenPush.Response(200, """{"id":"sub_1"}""")
            }
        }
        // This class exercises a visible session through the public manual lifecycle hook.
        OpenPush.onAppForegrounded()
    }

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }
    private fun CountDownLatch.awaitRequests() = assertTrue(await(2, TimeUnit.SECONDS))
    private fun requestSnapshot() = synchronized(requests) { requests.toList() }
    private fun durationRequests() = requestSnapshot().filter {
        it.url.endsWith("/sessions") && JSONObject(it.body!!).has("duration_seconds")
    }
    private fun durationBody(index: Int = 0) = JSONObject(durationRequests()[index].body!!)
    private fun respondWith(vararg statuses: Int) = synchronized(durationStatuses) {
        durationStatuses.clear()
        statuses.forEach(durationStatuses::addLast)
    }
    private fun awaitSessionCount(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (requestSnapshot().count { it.url.endsWith("/sessions") } >= expected) return
            Thread.sleep(10)
        }
        assertEquals(expected, requestSnapshot().count { it.url.endsWith("/sessions") })
    }

    @Test fun foregroundPostsOneAppSessionPingAndThrottlesTheNext() {
        val done = expect(1)
        OpenPush.register("session-token")
        done.awaitRequests()

        OpenPush.onAppForegrounded()
        awaitSessionCount(1)
        val pings = requestSnapshot().filter { it.url.endsWith("/sessions") }
        assertEquals(1, pings.size)
        assertEquals("session-token", JSONObject(pings.single().body!!).getString("token"))

        OpenPush.onAppForegrounded()
        Thread.sleep(100)
        assertEquals(1, requestSnapshot().count { it.url.endsWith("/sessions") })
    }

    @Test fun aLaterForegroundPostsAgain() {
        val done = expect(1)
        OpenPush.register("session-token")
        done.awaitRequests()
        OpenPush.onAppForegrounded()
        awaitSessionCount(1)

        clock += OpenPush.APP_SESSION_MIN_INTERVAL_MS
        OpenPush.onAppForegrounded()
        awaitSessionCount(2)
        assertEquals(2, requestSnapshot().count { it.url.endsWith("/sessions") })
    }

    @Test fun foregroundTimeIsAccumulatedAndPostedOnBackgroundWithAnIdempotencyKey() {
        OpenPush.registerBlocking("session-token")
        requests.clear()

        clock += 40_000L
        OpenPush.onAppBackgrounded()
        OpenPush.settleForTests()

        assertEquals(1, durationRequests().size)
        assertEquals("session-token", durationBody().getString("token"))
        assertEquals(40L, durationBody().getLong("duration_seconds"))
        assertTrue(durationBody().getString("idempotency_key").isNotBlank())
        assertEquals(0L, OpenPush.pendingSessionDurationSeconds())
    }

    @Test fun aSuccessfulPostSubtractsOnlyWhatWasSent() {
        OpenPush.registerBlocking("session-token")
        requests.clear()
        durationStarted = CountDownLatch(1)
        durationRelease = CountDownLatch(1)

        clock += 40_000L
        OpenPush.onAppBackgrounded()
        durationStarted.awaitRequests()
        val sentKey = durationBody().getString("idempotency_key")

        OpenPush.onAppForegrounded()
        clock += 15_000L
        OpenPush.onAppBackgrounded()
        assertEquals(55L, OpenPush.pendingSessionDurationSeconds())

        durationRelease.countDown()
        OpenPush.settleForTests()
        assertEquals(15L, OpenPush.pendingSessionDurationSeconds())
        val storedKey = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
            .getString("openpush.deviceToken.sessionDurationKey", null)
        assertTrue(storedKey?.isNotBlank() == true)
        assertNotEquals(sentKey, storedKey)
        assertEquals(1, durationRequests().size)
    }

    @Test fun aFullDayInFlightDoesNotDiscardNewlyAccruedTime() {
        OpenPush.registerBlocking("session-token")
        requests.clear()
        durationStarted = CountDownLatch(1)
        durationRelease = CountDownLatch(1)

        clock += OpenPush.MAX_SESSION_DURATION_MS
        OpenPush.onAppBackgrounded()
        durationStarted.awaitRequests()
        assertEquals(86_400L, durationBody().getLong("duration_seconds"))

        OpenPush.onAppForegrounded()
        clock += 15_000L
        OpenPush.onAppBackgrounded()
        assertEquals(86_415L, OpenPush.pendingSessionDurationSeconds())

        durationRelease.countDown()
        OpenPush.settleForTests()

        assertEquals(15L, OpenPush.pendingSessionDurationSeconds())
        assertTrue(OpenPush.hasPendingWork())
    }

    @Test fun theAccumulatedTotalIsClampedAtOneDay() {
        OpenPush.registerBlocking("session-token")
        requests.clear()

        clock += OpenPush.MAX_SESSION_DURATION_MS + 90_000L
        OpenPush.onAppBackgrounded()
        OpenPush.settleForTests()

        assertEquals(86_400L, durationBody().getLong("duration_seconds"))
    }

    @Test fun aFourHundredDropsTheReport() {
        OpenPush.registerBlocking("session-token")
        requests.clear()
        respondWith(400)

        clock += 12_000L
        OpenPush.onAppBackgrounded()
        OpenPush.settleForTests()

        assertEquals(12L, durationBody().getLong("duration_seconds"))
        assertEquals(0L, OpenPush.pendingSessionDurationSeconds())
        assertFalse(OpenPush.hasPendingWork())
    }

    @Test fun aPendingDurationSurvivesAColdStart() {
        OpenPush.registerBlocking("session-token")
        requests.clear()
        respondWith(503)
        clock += 27_000L
        OpenPush.onAppBackgrounded()
        OpenPush.settleForTests()
        val firstKey = durationBody().getString("idempotency_key")
        assertEquals(27L, OpenPush.pendingSessionDurationSeconds())

        OpenPush.resetForTests()
        OpenPush.nowMs = { clock }
        OpenPush.onLog = {}
        requests.clear()
        respondWith(200)
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") {
            requests += it
            OpenPush.Response(200, "{}")
        }
        OpenPush.settleForTests()
        assertEquals(27L, OpenPush.pendingSessionDurationSeconds())

        OpenPush.onAppBackgrounded()
        OpenPush.settleForTests()
        val restored = durationRequests().single()
        assertEquals(27L, JSONObject(restored.body!!).getLong("duration_seconds"))
        assertEquals(firstKey, JSONObject(restored.body!!).getString("idempotency_key"))
    }

    @Test fun theDurationRideIsIdempotentAcrossRetries() {
        OpenPush.registerBlocking("session-token")
        requests.clear()
        respondWith(503, 200)

        clock += 19_000L
        OpenPush.onAppBackgrounded()
        OpenPush.settleForTests()
        assertEquals(19L, OpenPush.pendingSessionDurationSeconds())

        OpenPush.onAppBackgrounded()
        OpenPush.settleForTests()

        assertEquals(2, durationRequests().size)
        assertEquals(
            durationBody(0).getString("idempotency_key"),
            durationBody(1).getString("idempotency_key"),
        )
        assertEquals(0L, OpenPush.pendingSessionDurationSeconds())
    }
}
