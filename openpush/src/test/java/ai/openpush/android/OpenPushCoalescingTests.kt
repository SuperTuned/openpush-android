package ai.openpush.android

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushCoalescingTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = TestScheduler()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var gate = CountDownLatch(0)

    @Before fun reset() {
        OpenPush.scheduler = clock
        OpenPush.onLog = {}
        OpenPush.resetForTests()
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") {
            if (!it.url.endsWith("/sessions")) {
                bodies += it.body!!
                gate.countDown()
            }
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
    }

    @After fun restoreClock() {
        OpenPush.scheduler = OpenPush.RealScheduler
    }

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }
    private fun CountDownLatch.awaitRequests() = assertTrue(await(2, TimeUnit.SECONDS))
    private fun registered() {
        val done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        bodies.clear()
    }

    @Test fun writesInsideTheWindowBecomeOneRegistration() {
        registered()
        OpenPush.User.addTag("plan", "pro")
        OpenPush.User.addTag("seats", "3")
        OpenPush.User.addAlias("crm_id", "c-42")
        clock.advance(299)
        assertTrue(bodies.isEmpty())
        val done = expect(1)
        clock.advance(1)
        done.awaitRequests()
        assertEquals(1, bodies.size)
        val body = JSONObject(bodies.single())
        assertEquals("pro", body.getJSONObject("tags").getString("plan"))
        assertEquals("3", body.getJSONObject("tags").getString("seats"))
        assertEquals("c-42", body.getJSONObject("aliases").getString("crm_id"))
    }

    @Test fun theWindowIsFixedAndDoesNotRestartOnALaterWrite() {
        registered()
        OpenPush.User.addTag("plan", "pro")
        clock.advance(200)
        OpenPush.User.addTag("seats", "3")
        val done = expect(1)
        clock.advance(100)
        done.awaitRequests()
        assertEquals("3", JSONObject(bodies.single()).getJSONObject("tags").getString("seats"))
    }

    @Test fun aWriteAfterTheWindowClosedOpensANewOne() {
        registered()
        var done = expect(1)
        OpenPush.User.addTag("plan", "pro")
        clock.advance(300)
        done.awaitRequests()
        done = expect(1)
        OpenPush.User.addTag("plan", "free")
        clock.advance(300)
        done.awaitRequests()
        assertEquals(2, bodies.size)
    }

    @Test fun loginAbsorbsThePendingWriteIntoItsOwnRegistration() {
        registered()
        OpenPush.User.addTag("plan", "pro")
        val done = expect(1)
        OpenPush.login("user-7")
        done.awaitRequests()
        assertEquals(1, bodies.size)
        assertEquals("pro", JSONObject(bodies.single()).getJSONObject("tags").getString("plan"))
        clock.advance(1_000)
        assertTrue(clock.isIdle())
    }

    @Test fun anExplicitRegisterAbsorbsThePendingWrite() {
        registered()
        OpenPush.User.addTag("plan", "pro")
        val done = expect(1)
        OpenPush.register("rotated-token")
        done.awaitRequests()
        assertEquals(1, bodies.size)
        assertEquals("rotated-token", JSONObject(bodies.single()).getString("token"))
        clock.advance(1_000)
        assertTrue(clock.isIdle())
    }

    @Test fun backgroundingFlushesThePendingWriteWithoutWaitingOutTheWindow() {
        registered()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        OpenPush.User.addTag("plan", "pro")
        val done = expect(1)
        controller.pause().stop()
        done.awaitRequests()
        assertEquals("pro", JSONObject(bodies.single()).getJSONObject("tags").getString("plan"))
    }

    @Test fun aWriteWithoutATokenOpensNoWindowAndRidesTheFirstRegistration() {
        OpenPush.User.addTag("plan", "pro")
        assertEquals(listOf(OpenPush.SESSION_PERSIST_WINDOW_MS), clock.pendingDelays())
        val done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        assertEquals("pro", JSONObject(bodies.single()).getJSONObject("tags").getString("plan"))
    }
}
