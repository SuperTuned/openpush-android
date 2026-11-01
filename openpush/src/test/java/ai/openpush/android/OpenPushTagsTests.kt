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
class OpenPushTagsTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = mutableListOf<String>()

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        OpenPush.onLog = {}
        bodies.clear()
        OpenPush.clearToken()
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) {
            bodies += it.body!!
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
        OpenPush.clearToken()
    }

    @Test fun addTagsIsOneRequestAndRidesEveryLaterRegistration() {
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyTagsBlocking(mapOf("plan" to "pro", "seats" to "3"))
        assertEquals(2, bodies.size)
        val sent = JSONObject(bodies.last()).getJSONObject("tags")
        assertEquals("pro", sent.getString("plan"))
        assertEquals("3", sent.getString("seats"))
        OpenPush.registerBlocking("rotated-token")
        assertEquals("pro", JSONObject(bodies.last()).getJSONObject("tags").getString("plan"))
    }

    @Test fun removedTagIsSentAsAnEmptyValueUntilAccepted() {
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyTagsBlocking(mapOf("plan" to "pro"))
        OpenPush.applyTagsBlocking(mapOf("plan" to ""))
        assertEquals("", JSONObject(bodies.last()).getJSONObject("tags").getString("plan"))
        OpenPush.registerBlocking("rotated-token")
        assertFalse(JSONObject(bodies.last()).has("tags"))
    }

    @Test fun tagsBeforeATokenAreHeldAndSentWithTheFirstRegistration() {
        OpenPush.applyTagsBlocking(mapOf("plan" to "pro"))
        assertTrue(bodies.isEmpty())
        OpenPush.registerBlocking("provider-token")
        assertEquals("pro", JSONObject(bodies.single()).getJSONObject("tags").getString("plan"))
    }

    @Test fun aRegistrationWithoutTagsCarriesNoTagsObject() {
        OpenPush.registerBlocking("provider-token")
        assertFalse(JSONObject(bodies.single()).has("tags"))
    }
}
