package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * `User.externalId` names an identity the server actually took, a login or a logout that would change
 * nothing on the wire sends nothing, and an empty external id is refused rather than registered
 * (spec U6, U8, U9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushIdentityIdempotenceTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private val clock = TestScheduler()
    private var response = """{"id":"sub_1"}"""
    private var status = 200

    private fun attach() = OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) {
        bodies += it.body!!
        OpenPush.Response(status, response)
    }

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.clearPersistedStateForTests()
        OpenPush.scheduler = clock
        OpenPush.onLog = {}
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        OpenPush.onIdentityRejected = null
        bodies.clear()
        status = 200
        response = """{"id":"sub_1"}"""
        attach()
    }

    @After fun restoreClock() {
        OpenPush.scheduler = OpenPush.RealScheduler
    }

    @Test fun loginWithTheSameIdAfterAcceptanceSendsNothing() {
        OpenPush.registerBlocking("provider-token")
        assertEquals(IdentityResult.Success, OpenPush.loginBlocking("user-a"))
        bodies.clear()
        assertEquals(IdentityResult.Success, OpenPush.loginBlocking("user-a"))
        assertTrue(bodies.isEmpty())
        assertEquals("user-a", OpenPush.User.externalId)
    }

    @Test fun loginWithTheSameIdAfterAFailureIsRetried() {
        status = 503
        response = ""
        OpenPush.registerBlocking("provider-token")
        assertTrue(OpenPush.loginBlocking("user-a") is IdentityResult.Failed)
        status = 200
        response = """{"id":"sub_1"}"""
        bodies.clear()
        assertEquals(IdentityResult.Success, OpenPush.loginBlocking("user-a"))
        assertEquals("user-a", JSONObject(bodies.single()).getString("external_id"))
    }

    @Test fun aRotatedHashIsAlwaysSent() {
        OpenPush.registerBlocking("provider-token")
        assertEquals(IdentityResult.Success, OpenPush.loginBlocking("user-a", "hash-1"))
        bodies.clear()
        assertEquals(IdentityResult.Success, OpenPush.loginBlocking("user-a", "hash-2"))
        val body = JSONObject(bodies.single())
        assertEquals("user-a", body.getString("external_id"))
        assertEquals("hash-2", body.getString("external_id_auth_hash"))
    }

    @Test fun logoutWhileAnonymousSendsNothingButClearsStagedWrites() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        OpenPush.onLog = { logs += it }
        OpenPush.registerBlocking("provider-token")
        OpenPush.User.addTag("plan", "pro")
        bodies.clear()
        assertEquals(IdentityResult.Success, OpenPush.logoutBlocking())
        assertTrue(bodies.isEmpty())
        assertTrue(OpenPush.User.getTags().isEmpty())
        // The open coalescing window was absorbed, so the cleared tag cannot ride a later flush.
        assertEquals(listOf(OpenPush.SESSION_PERSIST_WINDOW_MS), clock.pendingDelays())
        clock.advance(2 * OpenPush.COALESCE_WINDOW_MS)
        assertTrue(bodies.isEmpty())
        assertTrue(logs.any { it.contains("already anonymous") })
    }

    @Test fun logoutAfterALoginStillDetachesOnTheWire() {
        OpenPush.registerBlocking("provider-token")
        OpenPush.loginBlocking("user-a")
        bodies.clear()
        assertEquals(IdentityResult.Success, OpenPush.logoutBlocking())
        assertEquals("", JSONObject(bodies.single()).getString("external_id"))
        assertNull(OpenPush.User.externalId)
    }

    @Test fun anEmptyExternalIdIsRefused() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        OpenPush.onLog = { logs += it }
        OpenPush.registerBlocking("provider-token")
        OpenPush.loginBlocking("user-a")
        bodies.clear()
        assertEquals(IdentityResult.Failed("empty external id"), OpenPush.loginBlocking("   "))
        assertTrue(bodies.isEmpty())
        assertTrue(logs.any { it.contains("empty") })
        // A refusal changes nothing: the identity in place is still the one the host logged in with.
        assertEquals("user-a", OpenPush.User.externalId)
    }

    @Test fun aRejectedIdentityBecomesAnonymousAndReachesTheHook() {
        response = """{"id":"sub_1","status":1,"identity_rejected":"bad hash"}"""
        var note: String? = null
        OpenPush.onIdentityRejected = { note = it }
        OpenPush.registerBlocking("provider-token")
        assertEquals(
            IdentityResult.IdentityRejected("bad hash"),
            OpenPush.loginBlocking("user-a", "wrong"),
        )
        assertNull(OpenPush.User.externalId)
        idleMain()
        assertEquals("bad hash", note)
    }

    @Test fun aRejectedIdentityCanBeLoggedInAgainWithTheRightHash() {
        response = """{"id":"sub_1","identity_rejected":"bad hash"}"""
        OpenPush.registerBlocking("provider-token")
        OpenPush.loginBlocking("user-a", "wrong")
        response = """{"id":"sub_1"}"""
        bodies.clear()
        assertEquals(IdentityResult.Success, OpenPush.loginBlocking("user-a", "right"))
        val body = JSONObject(bodies.single())
        assertEquals("user-a", body.getString("external_id"))
        assertEquals("right", body.getString("external_id_auth_hash"))
    }
}
