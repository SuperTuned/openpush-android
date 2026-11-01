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

/**
 * Consecutive session-state calls made through the **public** API must land in the order the app made
 * them. `Dispatchers.IO` is multi-threaded, so two `launch`es give no ordering guarantee: a sign-out
 * handler that calls `login("user-1")` then `logout()` used to be able to leave the device attached to
 * the account the user had just signed out of.
 *
 * Each ordering case runs [ATTEMPTS] times — a scheduling race that reverses one time in ten is still a
 * bug, and a single attempt would only find it one time in ten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushSessionOrderingTests {
    private companion object {
        const val ATTEMPTS = 25
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var gate = CountDownLatch(0)
    private val clock = TestScheduler()

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.scheduler = clock
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        OpenPush.onLog = {}
        OpenPush.clearToken()
    }

    @After fun restoreClock() {
        OpenPush.scheduler = OpenPush.RealScheduler
    }

    private fun attach() {
        OpenPush.clearToken()
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) {
            bodies += it.body!!
            gate.countDown()
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
        OpenPush.clearToken()
        bodies.clear()
    }

    /** Arms the transport to count the next [requests] requests. */
    private fun expect(requests: Int) = CountDownLatch(requests).also { gate = it }

    private fun CountDownLatch.awaitRequests() =
        assertTrue("timed out waiting for the queued request(s)", await(2, TimeUnit.SECONDS))

    @Test fun aLogoutRightAfterALoginAlwaysDetachesOnTheWire() {
        repeat(ATTEMPTS) { attempt ->
            attach()
            OpenPush.registerBlocking("provider-token")
            val done = expect(2)
            OpenPush.login("user-1")
            OpenPush.logout()
            done.awaitRequests()
            assertEquals("attempt $attempt", 3, bodies.size)
            assertEquals("attempt $attempt", "", JSONObject(bodies.last()).getString("external_id"))
        }
    }

    @Test fun aLoginRightAfterALogoutAlwaysAttachesOnTheWire() {
        repeat(ATTEMPTS) { attempt ->
            attach()
            OpenPush.registerBlocking("provider-token")
            // There has to be an identity to detach from: a logout while anonymous sends nothing.
            OpenPush.loginBlocking("user-0")
            val done = expect(2)
            OpenPush.logout()
            OpenPush.login("user-1")
            done.awaitRequests()
            assertEquals("attempt $attempt", "user-1", JSONObject(bodies.last()).getString("external_id"))
        }
    }

    @Test fun twoTagWritesForTheSameKeyAreLastWriterWins() {
        repeat(ATTEMPTS) { attempt ->
            attach()
            OpenPush.registerBlocking("provider-token")
            val done = expect(1)
            OpenPush.User.addTag("plan", "pro")
            OpenPush.User.addTag("plan", "free")
            clock.advance(300)
            done.awaitRequests()
            assertEquals("attempt $attempt", 2, bodies.size)
            assertEquals("attempt $attempt", "free", tagsOf(bodies.last()).getString("plan"))
            // The session map is last-writer-wins too, so a later token refresh keeps "free".
            OpenPush.registerBlocking("rotated-token")
            assertEquals("attempt $attempt", "free", tagsOf(bodies.last()).getString("plan"))
        }
    }

    @Test fun twoAliasWritesForTheSameLabelAreLastWriterWins() {
        repeat(ATTEMPTS) { attempt ->
            attach()
            OpenPush.registerBlocking("provider-token")
            val done = expect(1)
            OpenPush.User.addAlias("crm_id", "c-1")
            OpenPush.User.addAlias("crm_id", "c-2")
            clock.advance(300)
            done.awaitRequests()
            assertEquals(
                "attempt $attempt",
                "c-2",
                JSONObject(bodies.last()).getJSONObject("aliases").getString("crm_id"),
            )
        }
    }

    @Test fun optOutRightAfterOptInAlwaysSendsMinusTwoLast() {
        repeat(ATTEMPTS) { attempt ->
            attach()
            OpenPush.registerBlocking("provider-token")
            val done = expect(2)
            OpenPush.PushSubscription.optIn()
            OpenPush.PushSubscription.optOut()
            done.awaitRequests()
            assertEquals("attempt $attempt", -2, JSONObject(bodies.last()).getInt("status"))
        }
    }

    /**
     * `login`/`logout` took their callback with a safe call — `callback?.invoke(doLogin(…))` — so the
     * whole registration was short-circuited away when the host passed no callback.
     */
    @Test fun loginWithoutACallbackStillReachesTheServer() {
        attach()
        OpenPush.registerBlocking("provider-token")
        val done = expect(1)
        OpenPush.login("user-7", "hash-9")
        done.awaitRequests()
        assertEquals("user-7", JSONObject(bodies.last()).getString("external_id"))
    }

    @Test fun logoutWithoutACallbackStillReachesTheServer() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.loginBlocking("user-7")
        val done = expect(1)
        OpenPush.logout()
        done.awaitRequests()
        assertEquals("", JSONObject(bodies.last()).getString("external_id"))
    }

    private fun tagsOf(body: String) = JSONObject(body).getJSONObject("tags")
}
