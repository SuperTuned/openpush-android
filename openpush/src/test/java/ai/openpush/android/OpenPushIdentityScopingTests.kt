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

/** Tags and aliases are scoped to the identity that set them (spec A4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushIdentityScopingTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = mutableListOf<String>()

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.clearPersistedStateForTests()
        OpenPush.onLog = {}
        bodies.clear()
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) {
            bodies += it.body!!
            OpenPush.Response(200, """{"id":"sub_1","status":1}""")
        }
        OpenPush.registerBlocking("provider-token")
        bodies.clear()
    }

    @Test fun anonymousTagsCarryForwardIntoTheFirstLogin() {
        OpenPush.applyTagsBlocking(mapOf("plan" to "pro"))
        OpenPush.loginBlocking("user-a")
        val body = JSONObject(bodies.last())
        assertEquals("user-a", body.getString("external_id"))
        assertEquals("pro", body.getJSONObject("tags").getString("plan"))
    }

    @Test fun loggingInAsAnotherUserClearsTheFirstUsersTagsAndAliases() {
        OpenPush.loginBlocking("user-a")
        OpenPush.applyTagsBlocking(mapOf("plan" to "pro"))
        OpenPush.applyAliasesBlocking(mapOf("crm_id" to "c-1"))
        OpenPush.loginBlocking("user-b")
        val body = JSONObject(bodies.last())
        assertEquals("user-b", body.getString("external_id"))
        assertFalse(body.has("tags"))
        assertFalse(body.has("aliases"))
        assertTrue(OpenPush.User.getTags().isEmpty())
    }

    @Test fun logoutClearsLocalTagsAndAliasesAndSendsOnlyTheDetach() {
        OpenPush.loginBlocking("user-a")
        OpenPush.applyTagsBlocking(mapOf("plan" to "pro"))
        OpenPush.logoutBlocking()
        val body = JSONObject(bodies.last())
        assertEquals("", body.getString("external_id"))
        assertFalse(body.has("tags"))
        assertFalse(body.has("aliases"))
    }

    @Test fun aRemovedTagRidesOnceAndIsPrunedAfterTheRegistrationIsAccepted() {
        OpenPush.applyTagsBlocking(mapOf("plan" to "pro"))
        OpenPush.applyTagsBlocking(mapOf("plan" to ""))
        assertEquals("", JSONObject(bodies.last()).getJSONObject("tags").getString("plan"))
        OpenPush.registerBlocking("rotated-token")
        assertFalse(JSONObject(bodies.last()).has("tags"))
        assertFalse(OpenPush.User.getTags().containsKey("plan"))
    }
}
