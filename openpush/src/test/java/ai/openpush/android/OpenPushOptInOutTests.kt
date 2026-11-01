package ai.openpush.android

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushOptInOutTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private val logs = mutableListOf<String>()
    private val clock = TestScheduler()

    /** The body the stub server answers with. A var, so a test can change the server's mind mid-run. */
    private var response = """{"id":"sub_1"}"""
    private var responseStatus = 200

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.scheduler = clock
        OpenPush.jitter = { 0L }
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        bodies.clear()
        logs.clear()
        response = """{"id":"sub_1"}"""
        responseStatus = 200
        OpenPush.onLog = { logs += it }
        OpenPush.clearToken()
    }

    @After fun restoreScheduler() {
        OpenPush.scheduler = OpenPush.RealScheduler
        OpenPush.resetForTests()
    }

    private fun grantNotifications() = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun attach(body: String = """{"id":"sub_1"}""", appId: String = "demo") {
        response = body
        OpenPush.initialize(context, appId = appId, sdkKey = "sdk", credential = { "opdev" }) {
            bodies += it.body!!
            OpenPush.Response(responseStatus, response)
        }
        OpenPush.clearToken()
    }

    @Test fun optOutSendsStatusMinusTwoImmediately() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.optOutBlocking()
        assertEquals(2, bodies.size)
        assertEquals(-2, JSONObject(bodies.last()).getInt("status"))
    }

    @Test fun optInSendsStatusOne() {
        grantNotifications()
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.optOutBlocking()
        OpenPush.optInBlocking()
        assertEquals(1, JSONObject(bodies.last()).getInt("status"))
    }

    @Test fun aRegistrationBeforeAnyOptCallCarriesTheDeviceStatus() {
        attach()
        OpenPush.registerBlocking("provider-token")
        assertEquals(0, JSONObject(bodies.single()).getInt("status"))
    }

    @Test fun theStatusRidesEveryLaterRegistration() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.optOutBlocking()
        OpenPush.registerBlocking("rotated-token")
        val body = JSONObject(bodies.last())
        assertEquals("rotated-token", body.getString("token"))
        assertEquals(-2, body.getInt("status"))
    }

    @Test fun optOutBeforeATokenIsHeldAndRidesTheFirstRegistration() {
        attach()
        OpenPush.optOutBlocking()
        assertTrue(bodies.isEmpty())
        assertTrue(logs.any { it.contains("status stored") })
        OpenPush.registerBlocking("provider-token")
        assertEquals(-2, JSONObject(bodies.single()).getInt("status"))
    }

    @Test fun optOutWithoutATokenDoesNotCancelAPendingRetry() {
        attach()
        responseStatus = 500
        OpenPush.register("provider-token")
        OpenPush.settleForTests()
        assertEquals(listOf(2_000L), clock.pendingDelays())
        assertTrue(OpenPush.hasPendingWork())

        OpenPush.clearToken()
        OpenPush.PushSubscription.optOut()
        assertEquals(listOf(2_000L, OpenPush.SESSION_PERSIST_WINDOW_MS), clock.pendingDelays())
        assertTrue(OpenPush.hasPendingWork())

        responseStatus = 200
        clock.advance(2_000L)
        OpenPush.settleForTests()
        assertEquals(2, bodies.size)
        assertEquals(-2, JSONObject(bodies.last()).getInt("status"))
        assertFalse(OpenPush.hasPendingWork())
    }

    @Test fun optedOutIsNotOptedInEvenWithPermissionAndAToken() {
        grantNotifications()
        attach()
        OpenPush.registerBlocking("provider-token")
        assertTrue(OpenPush.PushSubscription.optedIn)
        OpenPush.optOutBlocking()
        assertFalse(OpenPush.PushSubscription.optedIn)
        assertFalse(OpenPush.User.pushSubscription.optedIn)
        OpenPush.optInBlocking()
        assertTrue(OpenPush.PushSubscription.optedIn)
    }

    @Test fun optInWithoutPermissionSendsTheHonestDeviceStatus() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.optInBlocking()
        assertEquals(0, JSONObject(bodies.last()).getInt("status"))
        assertFalse(OpenPush.PushSubscription.optedIn)
    }

    @Test fun aPositiveResponseStatusClearsAnEarlierLocalOptOutOnlyWhenTheServerSaysSo() {
        grantNotifications()
        attach(body = """{"id":"sub_1","status":1}""")
        OpenPush.registerBlocking("provider-token")
        // The opt-out registration is answered with the status the server now holds, so the local
        // opt-out stands: only a *positive* status retires it (spec A6).
        response = """{"id":"sub_1","status":-2}"""
        OpenPush.optOutBlocking()
        assertFalse(OpenPush.PushSubscription.optedIn)
        response = """{"id":"sub_1","status":1}"""
        OpenPush.registerBlocking("provider-token")
        assertTrue(OpenPush.PushSubscription.optedIn)
    }

    @Test fun aWorkspaceSwitchClearsTheStatus() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.optOutBlocking()
        attach(appId = "other")
        OpenPush.registerBlocking("provider-token")
        // The app-session ping is posted asynchronously and can land after the registration.
        val registration = synchronized(bodies) { bodies.last { JSONObject(it).has("platform") } }
        assertEquals(0, JSONObject(registration).getInt("status"))
    }
}
