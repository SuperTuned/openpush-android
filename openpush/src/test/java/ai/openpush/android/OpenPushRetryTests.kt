package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushRetryTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = TestScheduler()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private val requests = Collections.synchronizedList(mutableListOf<OpenPush.Request>())

    @Volatile private var gate = CountDownLatch(0)

    @Volatile private var responseGate = CountDownLatch(0)

    @Volatile private var status = 200

    @Volatile private var eventStatus = 200

    @Volatile private var responseHeaders: Map<String, String> = emptyMap()

    @Before fun reset() {
        OpenPush.scheduler = clock
        OpenPush.resetForTests()
        OpenPush.onLog = {}
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        // Jitter is random in production and pinned here, or a test could not name the millisecond a
        // retry was filed for. The random default is asserted by theDefaultJitterStaysUnderOneSecond.
        OpenPush.jitter = { 0 }
        responseGate = CountDownLatch(0)
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") {
            requests += it
            bodies += it.body!!
            // Read the response code before signalling that the request started: a test that
            // changes `status` as soon as it sees the request must not change this response.
            val code = if (it.url.endsWith("/events")) eventStatus else status
            gate.countDown()
            responseGate.await(2, TimeUnit.SECONDS)
            OpenPush.Response(code, if (code in 200..299) """{"id":"sub_1"}""" else "", responseHeaders)
        }
    }

    @After fun restoreClock() {
        OpenPush.scheduler = OpenPush.RealScheduler
        OpenPush.resetForTests()
    }
    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }

    // Lets the main looper run while waiting: registration-failed callbacks are posted there.
    private fun CountDownLatch.awaitRequests() = assertTrue(awaitOnMain(2, TimeUnit.SECONDS))
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (true) {
            idleMain()
            if (condition() || System.nanoTime() >= deadline) break
            Thread.yield()
        }
        assertTrue(condition())
    }

    @Test fun aTransportFailureRetriesTwiceAndOnlyThenReportsFailure() {
        status = -1
        var failures = 0
        OpenPush.onRegistrationFailed = { failures++ }
        var done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        awaitCondition { !clock.isIdle() }
        assertEquals(0, failures)
        done = expect(1)
        clock.advance(2_000)
        done.awaitRequests()
        awaitCondition { !clock.isIdle() }
        assertEquals(0, failures)
        done = expect(1)
        clock.advance(8_000)
        done.awaitRequests()
        assertEquals(3, bodies.size)
        awaitCondition { failures == 1 }
        assertEquals(1, failures)
        clock.advance(60_000)
        assertTrue(clock.isIdle())
    }

    @Test fun aClientErrorNeverRetries() {
        status = 401
        var failures = 0
        val failed = CountDownLatch(1)
        OpenPush.onRegistrationFailed = {
            failures++
            failed.countDown()
        }
        val done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        failed.awaitRequests()
        clock.advance(60_000)
        assertEquals(1, bodies.size)
        assertEquals(1, failures)
        assertTrue(clock.isIdle())
    }

    @Test fun aNewerRegistrationSupersedesThePendingRetry() {
        status = 500
        var done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        status = 200
        done = expect(1)
        OpenPush.login("user-7")
        done.awaitRequests()
        clock.advance(60_000)
        assertEquals(2, bodies.size)
        assertEquals("user-7", JSONObject(bodies.last()).getString("external_id"))
    }

    @Test fun aSupersededRetryStillCompletesItsCaller() {
        OpenPush.registerBlocking("provider-token")
        bodies.clear()

        status = 500
        responseGate = CountDownLatch(1)
        val callbackResult = AtomicReference<IdentityResult>()
        val callbackDone = CountDownLatch(1)
        val requestStarted = expect(1)
        OpenPush.login("user-7") {
            callbackResult.set(it)
            callbackDone.countDown()
        }
        requestStarted.awaitRequests()

        // Supersede the login after its failed response is fixed, but before it can schedule a
        // retry. This exercises the generation check beside retryTimer rather than the later retry.
        status = 200
        OpenPush.PushSubscription.optOut()
        responseGate.countDown()

        callbackDone.awaitRequests()
        assertEquals(
            IdentityResult.Failed("superseded by a newer registration"),
            callbackResult.get(),
        )
        OpenPush.settleForTests()
        assertEquals(2, bodies.size)
        assertEquals(-2, JSONObject(bodies.last()).getInt("status"))
    }

    /**
     * A 429 is the server saying "later", with a number attached. The SDK's own schedule is a floor,
     * never a licence to come back before the number: two seconds here would be the SDK arguing with
     * a server that is already shedding load.
     */
    @Test fun aFourTwentyNineIsRetriedAfterTheServersRetryAfter() {
        status = 429
        responseHeaders = mapOf("Retry-After" to "5")
        val done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        awaitCondition { !clock.isIdle() }

        val delay = clock.pendingDelays().single()
        assertTrue("a 429 was filed for ${delay}ms; the server asked for 5 000", delay >= 5_000L)
        assertEquals(5_000L, delay)

        // And it really does wait that long, rather than merely being written down for it.
        clock.advance(4_999)
        assertEquals(1, bodies.size)
        val retried = expect(1)
        clock.advance(1)
        retried.awaitRequests()
        assertEquals(2, bodies.size)
    }

    /** Without the spread, every device a server dropped comes back in the same millisecond. */
    @Test fun retriesCarryJitterWithinOneSecond() {
        OpenPush.jitter = { 750 }
        status = 503
        val done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        awaitCondition { !clock.isIdle() }
        assertEquals(2_750L, clock.pendingDelays().single())
    }

    @Test fun theDefaultJitterStaysUnderOneSecond() {
        val drawn = (1..500).map { OpenPush.defaultJitter(2_000L) }
        assertTrue(drawn.all { it in 0L..1_000L })
        assertTrue("500 draws all landed on the same value", drawn.distinct().size > 1)
    }

    /** The events endpoint is the one that actually sends `Retry-After: 5`, so it is the one the
     *  rule exists for. */
    @Test fun retryAfterIsHonoredForCustomEventsToo() {
        OpenPush.registerBlocking("provider-token")
        awaitCondition { clock.isIdle() }
        eventStatus = 429
        responseHeaders = mapOf("Retry-After" to "5")

        OpenPush.trackEvent("cart_viewed")
        val done = expect(1)
        clock.advance(OpenPush.COALESCE_WINDOW_MS)
        done.awaitRequests()
        awaitCondition { !clock.isIdle() }

        // 5 s beats the two-second first step of the custom-event schedule.
        assertEquals(5_000L, clock.pendingDelays().single())
    }

    /**
     * A dropped batch takes its backoff position with it. Otherwise the next batch's first transient
     * failure inherits a step it never climbed, and a queue that is moving again waits as if it were
     * still in the outage that the dropped batch happened to be in.
     */
    @Test fun aFourHundredResetsTheAttemptCounter() {
        OpenPush.registerBlocking("provider-token")
        awaitCondition { clock.isIdle() }

        eventStatus = 503
        OpenPush.trackEvent("first")
        var done = expect(1)
        clock.advance(OpenPush.COALESCE_WINDOW_MS)
        done.awaitRequests()
        awaitCondition { !clock.isIdle() }
        assertEquals(2_000L, clock.pendingDelays().single())
        assertEquals(1, OpenPush.customEventAttempt)

        // The retry lands on a 400: the batch is judged and dropped, and the counter goes with it.
        eventStatus = 400
        done = expect(1)
        clock.advance(2_000) // fires the retry timer, which posts a zero-delay flush
        clock.advance(0)
        done.awaitRequests()
        // The gate opens inside the transport, before the response is handled — wait for the drop
        // itself, or the next trackEvent races the removal it performs.
        awaitCondition { OpenPush.customEventAttempt == 0 }
        awaitCondition { clock.isIdle() }

        // A fresh batch, a fresh outage: the first step again, not the second.
        eventStatus = 503
        OpenPush.trackEvent("second")
        done = expect(1)
        clock.advance(OpenPush.COALESCE_WINDOW_MS)
        done.awaitRequests()
        awaitCondition { !clock.isIdle() }
        assertEquals(2_000L, clock.pendingDelays().single())
    }

    @Test fun aPendingRetryDoesNotBlockTheQueue() {
        status = 500
        var done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        status = 200
        // There has to be an identity to detach from: a logout while anonymous sends nothing.
        OpenPush.loginBlocking("user-7")
        done = expect(1)
        OpenPush.logout()
        done.awaitRequests()
        assertEquals("", JSONObject(bodies.last()).getString("external_id"))
    }
}
