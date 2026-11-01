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
class OpenPushReinitializeTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = mutableListOf<String>()

    // The server answers with the status it now holds, which for a registration is the one just sent.
    // A fixed `1` would look like a server-side re-subscribe and retire the local opt-out (spec A6).
    private val transport: suspend (OpenPush.Request) -> OpenPush.Response = {
        bodies += it.body!!
        val status = JSONObject(it.body!!).optInt("status", 1)
        OpenPush.Response(200, """{"id":"sub_1","status":$status}""")
    }

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.clearPersistedStateForTests()
        OpenPush.onLog = {}
        bodies.clear()
    }

    private fun init(appId: String = "demo", sdkKey: String = "sdk") =
        OpenPush.initialize(context, appId = appId, sdkKey = sdkKey, credential = { "opdev" }, transport = transport)

    @Test fun aRepeatedInitializeWithTheSameConfigurationKeepsTagsAliasesIdentityAndOptOut() {
        init()
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyTagsBlocking(mapOf("plan" to "pro"))
        OpenPush.applyAliasesBlocking(mapOf("crm_id" to "c-42"))
        OpenPush.loginBlocking("user-7", "hash-9")
        OpenPush.optOutBlocking()
        // Drop the token so the attach itself makes no request of its own — an app-session ping
        // needs one — and the registration below is the only body to read.
        OpenPush.clearToken()
        bodies.clear()

        init() // same appId, sdkKey, server, tokenKey
        OpenPush.registerBlocking("provider-token")

        val body = JSONObject(bodies.single())
        assertEquals("pro", body.getJSONObject("tags").getString("plan"))
        assertEquals("c-42", body.getJSONObject("aliases").getString("crm_id"))
        assertEquals("user-7", body.getString("external_id"))
        assertEquals("hash-9", body.getString("external_id_auth_hash"))
        assertEquals(-2, body.getInt("status"))
        assertEquals("sub_1", OpenPush.User.pushSubscription.id)
    }

    @Test fun aRepeatedInitializeDoesNotClearQueuedCustomEvents() {
        init()
        OpenPush.registerBlocking("provider-token")
        OpenPush.trackEvent("level_up")
        init()
        // The event must still be in the persisted queue: the next flush sends it.
        OpenPush.flushCustomEventsBlocking()
        assertTrue(bodies.any { it.contains("\"level_up\"") })
    }

    @Test fun aDifferentAppIdIsStillAWorkspaceSwitch() {
        init()
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyTagsBlocking(mapOf("plan" to "pro"))
        OpenPush.clearToken()
        bodies.clear()
        init(appId = "other")
        OpenPush.registerBlocking("provider-token")
        assertFalse(JSONObject(bodies.single()).has("tags"))
    }

    @Test fun configurationsCompareByValue() {
        assertEquals(OpenPushConfiguration("a", "k"), OpenPushConfiguration("a", "k", "https://app.openpush.ai/"))
        assertFalse(OpenPushConfiguration("a", "k") == OpenPushConfiguration("a", "k2"))
        assertEquals(OpenPushConfiguration("a", "k").hashCode(), OpenPushConfiguration("a", "k").hashCode())
    }
}
