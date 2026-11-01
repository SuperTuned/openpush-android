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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushIdentityTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = mutableListOf<String>()
    private val logs = mutableListOf<String>()

    @Before fun reset() {
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        bodies.clear()
        logs.clear()
        OpenPush.onLog = { logs += it }
        OpenPush.clearToken()
    }

    private fun attach(status: Int = 200, body: String = """{"id":"sub_1"}""") {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) {
            bodies += it.body!!
            OpenPush.Response(status, body)
        }
        OpenPush.clearToken()
    }

    @Test fun loginWithATokenReSendsIdentityAndAuthHash() {
        attach()
        OpenPush.registerBlocking("provider-token")
        val result = OpenPush.loginBlocking("user-7", "hash-9")
        assertEquals(IdentityResult.Success, result)
        val body = JSONObject(bodies.last())
        assertEquals("user-7", body.getString("external_id"))
        assertEquals("hash-9", body.getString("external_id_auth_hash"))
        assertEquals(2, bodies.size)
    }

    @Test fun loginWithoutATokenDefersAndRidesTheNextRegistration() {
        attach()
        val result = OpenPush.loginBlocking("user-7")
        assertEquals(IdentityResult.Success, result)
        assertTrue(bodies.isEmpty())
        assertTrue(logs.any { it.contains("deferred") })
        OpenPush.registerBlocking("provider-token")
        assertEquals("user-7", JSONObject(bodies.single()).getString("external_id"))
    }

    @Test fun identityRidesEveryLaterTokenRefresh() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.loginBlocking("user-7", "hash-9")
        OpenPush.registerBlocking("rotated-token")
        val body = JSONObject(bodies.last())
        assertEquals("rotated-token", body.getString("token"))
        assertEquals("user-7", body.getString("external_id"))
        assertEquals("hash-9", body.getString("external_id_auth_hash"))
    }

    @Test fun rejectedIdentityIsReportedWithItsNote() {
        attach(body = """{"id":"sub_1","identity_rejected":"unknown external id"}""")
        OpenPush.registerBlocking("provider-token")
        assertEquals(
            IdentityResult.IdentityRejected("unknown external id"),
            OpenPush.loginBlocking("user-7", "hash-9"),
        )
    }

    @Test fun nonSuccessResponseFailsTheLogin() {
        attach(status = 401, body = "nope")
        OpenPush.registerBlocking("provider-token")
        assertTrue(OpenPush.loginBlocking("user-7") is IdentityResult.Failed)
    }

    @Test fun logoutSendsAnExplicitEmptyExternalId() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.loginBlocking("user-7", "hash-9")
        assertEquals(IdentityResult.Success, OpenPush.logoutBlocking())
        val body = JSONObject(bodies.last())
        assertTrue(body.has("external_id"))
        assertEquals("", body.getString("external_id"))
        assertFalse(body.has("external_id_auth_hash"))
        OpenPush.registerBlocking("rotated-token")
        assertEquals("", JSONObject(bodies.last()).getString("external_id"))
    }

    @Test fun aChangedCredentialFailsTheLoginRatherThanClaimingSuccess() {
        var credential = "old"
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { credential }) {
            bodies += it.body!!
            credential = "new"
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
        OpenPush.clearToken()
        OpenPush.registerBlocking("provider-token")
        credential = "old"
        assertTrue(OpenPush.loginBlocking("user-7") is IdentityResult.Failed)
    }
}
