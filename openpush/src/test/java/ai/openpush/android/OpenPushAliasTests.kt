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
class OpenPushAliasTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = mutableListOf<String>()
    private val logs = mutableListOf<String>()

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        bodies.clear()
        logs.clear()
        OpenPush.onLog = { logs += it }
        OpenPush.clearToken()
    }

    private fun attach(body: String = """{"id":"sub_1"}""", appId: String = "demo") {
        OpenPush.initialize(context, appId = appId, sdkKey = "sdk", credential = { "opdev" }) {
            bodies += it.body!!
            OpenPush.Response(200, body)
        }
        OpenPush.clearToken()
    }

    @Test fun addAliasesIsOneRequestAndRidesEveryLaterRegistration() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyAliasesBlocking(mapOf("crm_id" to "c-42", "loyalty_id" to "l-7"))
        assertEquals(2, bodies.size)
        val sent = JSONObject(bodies.last()).getJSONObject("aliases")
        assertEquals("c-42", sent.getString("crm_id"))
        assertEquals("l-7", sent.getString("loyalty_id"))
        OpenPush.registerBlocking("rotated-token")
        assertEquals("c-42", JSONObject(bodies.last()).getJSONObject("aliases").getString("crm_id"))
    }

    @Test fun removedAliasIsSentAsAnEmptyValueUntilAccepted() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyAliasesBlocking(mapOf("crm_id" to "c-42"))
        OpenPush.removeAliasesBlocking(listOf("crm_id"))
        assertEquals("", JSONObject(bodies.last()).getJSONObject("aliases").getString("crm_id"))
        OpenPush.registerBlocking("rotated-token")
        assertFalse(JSONObject(bodies.last()).has("aliases"))
    }

    @Test fun aliasesBeforeATokenAreHeldAndSentWithTheFirstRegistration() {
        attach()
        OpenPush.applyAliasesBlocking(mapOf("crm_id" to "c-42"))
        assertTrue(bodies.isEmpty())
        assertTrue(logs.any { it.contains("aliases stored") })
        OpenPush.registerBlocking("provider-token")
        assertEquals("c-42", JSONObject(bodies.single()).getJSONObject("aliases").getString("crm_id"))
    }

    @Test fun aRegistrationWithoutAliasesCarriesNoAliasesObject() {
        attach()
        OpenPush.registerBlocking("provider-token")
        assertFalse(JSONObject(bodies.single()).has("aliases"))
    }

    @Test fun anEmptyLabelIsDropped() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyAliasesBlocking(mapOf("" to "c-42"))
        assertFalse(JSONObject(bodies.last()).has("aliases"))
    }

    @Test fun aliasesAndTagsRideTheSameBodyIndependently() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyTagsBlocking(mapOf("plan" to "pro"))
        OpenPush.applyAliasesBlocking(mapOf("crm_id" to "c-42"))
        val body = JSONObject(bodies.last())
        assertEquals("pro", body.getJSONObject("tags").getString("plan"))
        assertEquals("c-42", body.getJSONObject("aliases").getString("crm_id"))
    }

    @Test fun aRejectedAliasWriteIsLoggedLoudlyAndTheDeviceStaysRegistered() {
        var registered = false
        OpenPush.onRegistered = { registered = true }
        attach(body = """{"id":"sub_1","identity_rejected":"auth hash mismatch"}""")
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyAliasesBlocking(mapOf("crm_id" to "c-42"))
        idleMain()
        assertTrue(registered)
        assertTrue(logs.any { it.contains("identity rejected") && it.contains("auth hash mismatch") })
    }

    /**
     * The server refuses reserved and over-cap labels with a 2xx `aliases_refused` note rather than a
     * 400, so the SDK keeps its session map uncapped and surfaces the refusal as loudly as it surfaces
     * `identity_rejected`.
     */
    @Test fun refusedAliasesAreLoggedLoudlyAndTheDeviceStaysRegistered() {
        var registered = false
        OpenPush.onRegistered = { registered = true }
        attach(body = """{"id":"sub_1","aliases_refused":["external_id","eleventh"]}""")
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyAliasesBlocking(mapOf("crm_id" to "c-42"))
        idleMain()
        assertTrue(registered)
        assertTrue(logs.any { it.contains("aliases refused") && it.contains("external_id") })
        assertEquals(
            listOf("external_id", "eleventh"),
            OpenPushPayload.registrationAck("""{"aliases_refused":["external_id","eleventh"]}""").refusedAliases,
        )
        assertTrue(OpenPushPayload.registrationAck("""{"id":"sub_1"}""").refusedAliases.isEmpty())
    }

    @Test fun aWorkspaceSwitchClearsTheAliases() {
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyAliasesBlocking(mapOf("crm_id" to "c-42"))
        attach(appId = "other")
        OpenPush.registerBlocking("provider-token")
        assertFalse(JSONObject(bodies.last()).has("aliases"))
    }
}
