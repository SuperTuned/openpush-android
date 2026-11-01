package ai.openpush.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowJobService
import org.robolectric.util.ReflectionHelpers
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(OpenPushInternalApi::class)
class OpenPushBackgroundDrainTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = TestScheduler()
    private val requests = Collections.synchronizedList(mutableListOf<OpenPush.Request>())

    @Volatile private var failIngest = false

    @Volatile private var failAll = false
    private var now = 2_000_000L

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.scheduler = clock
        OpenPush.nowMs = { now }
        OpenPush.jitter = { 0L }
        OpenPush.onLog = {}
        requests.clear()
        failIngest = false
        failAll = false
        context.getSystemService(JobScheduler::class.java).cancelAll()
    }

    @After fun restore() {
        OpenPush.scheduler = OpenPush.RealScheduler
        OpenPush.resetForTests()
        context.getSystemService(JobScheduler::class.java).cancelAll()
    }

    private fun attach() {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", serverURL = "https://example.test") {
            requests += it
            val failed = failAll || (failIngest && it.url.endsWith("/v1/ingest"))
            if (failed) {
                OpenPush.Response(503, "")
            } else {
                OpenPush.Response(200, if (it.url.endsWith("/subscriptions")) """{"id":"sub_1"}""" else "{}")
            }
        }
    }

    private fun registered() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.onAppForegrounded()
        requests.clear()
    }

    private fun pendingJob() = shadowOf(context.getSystemService(JobScheduler::class.java))
        .getPendingJob(OpenPushSyncJobService.JOB_ID)

    private fun startedService(): Pair<OpenPushSyncJobService, ShadowJobService> {
        val service = Robolectric.buildService(OpenPushSyncJobService::class.java).create().get()
        val params = ReflectionHelpers.newInstance(JobParameters::class.java)
        assertTrue(service.onStartJob(params))
        return service to shadowOf(service)
    }

    private fun awaitFinished(shadow: ShadowJobService) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!shadow.isJobFinished && System.nanoTime() < deadline) Thread.yield()
        assertTrue("background job did not finish", shadow.isJobFinished)
    }

    @Test fun aBackgroundedAppWithQueuedWorkSchedulesTheJob() {
        registered()
        failIngest = true
        OpenPush.receiptBlocking("received", "m1")

        OpenPush.onAppBackgrounded()

        val job = pendingJob()
        assertNotNull(job)
        assertEquals(OpenPushSyncJobService.JOB_ID, job.id)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertEquals(1_000L, job.minLatencyMillis)
        assertEquals(60_000L, job.maxExecutionDelayMillis)
        assertFalse(job.isPersisted)
    }

    @Test fun noJobIsScheduledWhenNothingIsPending() {
        attach()

        OpenPush.onAppBackgrounded()

        assertNull(pendingJob())
    }

    @Test fun theJobDrainsReceiptsCustomEventsRegistrationAndDurationThenFinishes() {
        registered()
        failIngest = true
        OpenPush.receiptBlocking("received", "m1")
        failIngest = false
        requests.clear()
        OpenPush.trackEvent("checkout_started")
        OpenPush.User.addTag("plan", "pro")
        now += 10_000L
        OpenPush.onAppBackgrounded()
        assertNull(OpenPushBridge.diagnostics()["lastDrainAt"])

        val (service, shadow) = startedService()
        awaitFinished(shadow)

        val paths = requests.map { it.url.substringAfter("example.test") }
        assertTrue(paths.any { it.endsWith("/subscriptions") })
        assertTrue(paths.any { it.endsWith("/v1/ingest") })
        assertTrue(paths.any { it.endsWith("/events") })
        assertTrue(
            requests.any {
                it.url.endsWith("/sessions") &&
                    org.json.JSONObject(it.body!!).optLong("duration_seconds") == 10L
            },
        )
        assertFalse(shadow.isRescheduleNeeded)
        assertFalse(OpenPush.hasPendingWork())
        assertEquals(now, OpenPushBridge.diagnostics()["lastDrainAt"])
        service.onDestroy()
    }

    @Test fun theJobReschedulesWhenARequestFailed() {
        registered()
        failIngest = true
        OpenPush.receiptBlocking("received", "m1")
        requests.clear()
        failAll = true

        val (service, shadow) = startedService()
        awaitFinished(shadow)

        assertTrue(requests.any { it.url.endsWith("/v1/ingest") })
        assertTrue(shadow.isRescheduleNeeded)
        assertTrue(OpenPush.hasPendingWork())
        service.onDestroy()
    }

    @Test fun stoppingTheJobCancelsItsSerializedDrainAndReleasesReceiptClaims() {
        registered()
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val ingestRequests = AtomicInteger()

        // Refresh the transport while the queue is empty, then populate it directly so no ordinary
        // receipt call can put a separate drain closure ahead of the JobService's operation.
        OpenPush.initialize(
            context,
            appId = "demo",
            sdkKey = "sdk",
            serverURL = "https://example.test",
        ) { request ->
            requests += request
            if (request.url.endsWith("/v1/ingest") && ingestRequests.incrementAndGet() == 1) {
                firstRequestStarted.countDown()
                // Deliberately ignores coroutine cancellation. The drain wrapper must detach and
                // release its queue claim while this host transport remains blocked.
                releaseFirstRequest.await()
            }
            OpenPush.Response(200, if (request.url.endsWith("/subscriptions")) """{"id":"sub_1"}""" else "{}")
        }
        requests.clear()
        val queue = ReflectionHelpers.getField<ReceiptQueue>(OpenPush, "receiptQueue")
        repeat(ReceiptQueue.BATCH_LIMIT + 1) { index ->
            assertTrue(queue.enqueue("received", "cancel-$index", "provider-token", "demo"))
        }

        val service = Robolectric.buildService(OpenPushSyncJobService::class.java).create().get()
        val params = ReflectionHelpers.newInstance(JobParameters::class.java)
        assertTrue(service.onStartJob(params))
        assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS))

        assertTrue("the stopped job still has durable work to reschedule", service.onStopJob(params))
        try {
            assertNull(OpenPushBridge.diagnostics()["lastDrainAt"])
            assertFalse(shadowOf(service).isJobFinished)

            // Keep the first transport blocked. Cancellation must detach the serialized closure and
            // release its claim so the next drain can send both batches without waiting for it.
            assertTrue(releaseFirstRequest.count > 0L)
            assertTrue(runBlocking { withTimeout(2_000L) { OpenPush.drainAll() } })
            assertTrue(releaseFirstRequest.count > 0L)
            assertEquals(3, requests.count { it.url.endsWith("/v1/ingest") })
            assertEquals(0, queue.size)
        } finally {
            releaseFirstRequest.countDown()
            service.onDestroy()
        }
    }
}
