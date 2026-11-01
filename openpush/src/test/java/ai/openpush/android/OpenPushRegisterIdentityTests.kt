package ai.openpush.android

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
 * `register(token, externalId)` is kept for the apps and wrappers that call it, and deprecated. It
 * used to put its id into that one registration body next to the auth hash of whoever was logged in,
 * while the session went on asserting the old identity. An identity now gets in only through login:
 * the id is logged in first, and every body after it agrees with the session.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushRegisterIdentityTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val logs = Collections.synchronizedList(mutableListOf<String>())

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        OpenPush.onIdentityRejected = null
        OpenPush.onLog = { logs += it }
        OpenPush.attach(
            context,
            OpenPushConfiguration(appID = "demo", sdkKey = "sdk", serverURL = "https://example.test"),
            { "opdev" },
        ) { request ->
            if (request.url.endsWith("/subscriptions")) bodies += JSONObject(request.body!!)
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
        OpenPush.registerBlocking("provider-token")
        bodies.clear()
        logs.clear()
    }

    private fun loggedIn(id: String, hash: String) {
        OpenPush.loginBlocking(id, hash)
        OpenPush.settleForTests()
        bodies.clear()
        logs.clear()
    }

    private fun registered(externalId: String?): JSONObject {
        OpenPush.register("provider-token", externalId)
        OpenPush.settleForTests()
        return bodies.last()
    }

    @Test fun anotherIdIsLoggedInWithoutTheCurrentLoginsHash() {
        loggedIn("user-1", "hash-for-user-1")

        val body = registered("user-2")

        assertEquals("user-2", body.getString("external_id"))
        assertFalse("another identity's hash rode along", body.has("external_id_auth_hash"))
        assertEquals("the session disagrees with what was sent", "user-2", OpenPush.User.externalId)
        assertTrue(logs.any { it.contains("register(token, externalId) logs in a new external id") })
        assertTrue("the log line carries the external id", logs.none { "register(token" in it && "user-2" in it })

        // The next registration asserts the same identity rather than flipping back.
        OpenPush.register("provider-token")
        OpenPush.settleForTests()
        assertEquals("user-2", bodies.last().getString("external_id"))
        assertFalse(bodies.last().has("external_id_auth_hash"))
    }

    @Test fun theCurrentIdKeepsItsLoginAndHash() {
        loggedIn("user-1", "hash-for-user-1")

        val body = registered("user-1")

        assertEquals("user-1", body.getString("external_id"))
        assertEquals("hash-for-user-1", body.getString("external_id_auth_hash"))
        assertTrue(logs.none { it.contains("register(token, externalId)") })
    }

    @Test fun aNullOrEmptyIdRegistersLikeTheOneArgumentForm() {
        loggedIn("user-1", "hash-for-user-1")

        for (externalId in listOf(null, "", "  ")) {
            val body = registered(externalId)
            assertEquals("user-1", body.getString("external_id"))
            assertEquals("hash-for-user-1", body.getString("external_id_auth_hash"))
        }
        assertEquals("user-1", OpenPush.User.externalId)
    }

    /** The wire stays what it was for an anonymous device: the id is in the body, with no hash. */
    @Test fun anAnonymousDeviceSendsTheIdAsALogin() {
        val body = registered("player-7")

        assertEquals("provider-token", body.getString("token"))
        assertEquals("player-7", body.getString("external_id"))
        assertFalse(body.has("external_id_auth_hash"))
        assertEquals("player-7", OpenPush.User.externalId)
    }

    /** A logged-out session is not "the same identity": the id is logged in again. */
    @Test fun afterLogoutTheIdIsLoggedInAgain() {
        loggedIn("user-1", "hash-for-user-1")
        OpenPush.logoutBlocking()
        OpenPush.settleForTests()

        val body = registered("user-1")

        assertEquals("user-1", body.getString("external_id"))
        assertFalse(body.has("external_id_auth_hash"))
        assertEquals("user-1", OpenPush.User.externalId)
    }
}
