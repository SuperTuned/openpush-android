package ai.openpush.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushInitializeTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logs = mutableListOf<String>()

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        logs.clear()
        OpenPush.onLog = { logs += it }
        OpenPush.clearToken()
    }

    @Test fun initializeUsesTheDefaultServerAndAppScopedPath() {
        var request: OpenPush.Request? = null
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") {
            request = it
            OpenPush.Response(200, "{}")
        }
        OpenPush.registerBlocking("provider-token")
        assertEquals("https://app.openpush.ai", OpenPush.DEFAULT_SERVER_URL)
        assertEquals("https://app.openpush.ai/v1/apps/demo/subscriptions", request!!.url)
        assertEquals("sdk", request!!.headers["X-OP-SDK-Key"])
        assertNull(request!!.headers["X-OP-Device-Key"])
    }

    @Test fun initializePassesThroughAnExplicitServerAndCredential() {
        var request: OpenPush.Request? = null
        OpenPush.initialize(
            context,
            appId = "demo",
            sdkKey = "sdk",
            serverURL = "https://example.test///",
            credential = { "opdev" },
        ) {
            request = it
            OpenPush.Response(200, "{}")
        }
        OpenPush.registerBlocking("provider-token")
        assertEquals("https://example.test/v1/apps/demo/subscriptions", request!!.url)
        assertEquals("opdev", request!!.headers["X-OP-Device-Key"])
    }

    @Test fun aBlankAppIdIsRefusedWithALog() {
        OpenPush.initialize(context, appId = "  ", sdkKey = "sdk")

        assertFalse(OpenPush.isInitialized)
        assertTrue(logs.any { it.contains("app id") })
    }

    @Test fun anAppIdWithWhitespaceIsRefused() {
        OpenPush.initialize(context, appId = "demo app", sdkKey = "sdk")

        assertFalse(OpenPush.isInitialized)
        assertTrue(logs.any { it.contains("app id") })
    }

    @Test fun aThrowingPreferencesStoreDoesNotCrashAttach() {
        val lockedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                throw IllegalStateException("locked storage")
        }

        OpenPush.initialize(lockedContext, appId = "demo", sdkKey = "sdk")

        assertTrue(OpenPush.isInitialized)
        assertEquals(1, logs.count { it == "OpenPush preferences unavailable — locked storage" })
    }

    @Test fun registrationAckReadsIdAndRejectionShapes() {
        assertEquals("sub_1", OpenPushPayload.registrationAck("""{"id":"sub_1"}""").subscriptionId)
        assertNull(OpenPushPayload.registrationAck("""{"id":"sub_1"}""").identityRejection)
        assertEquals(
            "auth hash mismatch",
            OpenPushPayload.registrationAck("""{"identity_rejected":"auth hash mismatch"}""").identityRejection,
        )
        assertEquals(
            "unknown external id",
            OpenPushPayload
                .registrationAck("""{"identity_rejected":{"note":"unknown external id"}}""")
                .identityRejection,
        )
        assertEquals(
            "identity verification rejected",
            OpenPushPayload.registrationAck("""{"identity_rejected":true}""").identityRejection,
        )
        assertNull(OpenPushPayload.registrationAck("""{"identity_rejected":false}""").identityRejection)
        assertNull(OpenPushPayload.registrationAck("not json at all").subscriptionId)
        assertNull(OpenPushPayload.registrationAck("").identityRejection)
    }

    /**
     * Tags, aliases, an identity or a status set *before* `initialize` are logged as stored and riding the
     * next registration. `attach` cleared the session maps unconditionally, so on the very first attach
     * that promise was a lie — the values were logged as retained and then silently discarded.
     */
    @Test fun sessionStateSetBeforeTheFirstInitializeSurvivesIt() {
        OpenPush.resetForTests()
        val bodies = mutableListOf<String>()
        OpenPush.applyTagsBlocking(mapOf("plan" to "pro"))
        OpenPush.applyAliasesBlocking(mapOf("crm_id" to "c-42"))
        OpenPush.loginBlocking("user-7", "hash-9")
        OpenPush.optOutBlocking()
        assertTrue(logs.any { it.contains("tags stored") })

        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) {
            bodies += it.body!!
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
        OpenPush.registerBlocking("provider-token")
        val body = JSONObject(bodies.single())
        assertEquals("pro", body.getJSONObject("tags").getString("plan"))
        assertEquals("c-42", body.getJSONObject("aliases").getString("crm_id"))
        assertEquals("user-7", body.getString("external_id"))
        assertEquals("hash-9", body.getString("external_id_auth_hash"))
        assertEquals(-2, body.getInt("status"))
    }

    /** A *later* attach is a workspace switch, and no session state may cross it. */
    @Test fun aLaterInitializeIsAWorkspaceSwitchAndClearsTheSession() {
        OpenPush.resetForTests()
        val bodies = mutableListOf<String>()
        val transport: suspend (OpenPush.Request) -> OpenPush.Response = {
            bodies += it.body!!
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }, transport = transport)
        OpenPush.clearToken()
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyTagsBlocking(mapOf("plan" to "pro"))
        OpenPush.applyAliasesBlocking(mapOf("crm_id" to "c-42"))
        OpenPush.loginBlocking("user-7", "hash-9")
        OpenPush.optOutBlocking()

        OpenPush.clearToken()
        OpenPush.initialize(context, appId = "other", sdkKey = "sdk2", credential = { "opdev" }, transport = transport)
        OpenPush.clearToken()
        bodies.clear()
        OpenPush.registerBlocking("provider-token")
        val body = JSONObject(bodies.single())
        assertFalse(body.has("tags"))
        assertFalse(body.has("aliases"))
        assertFalse(body.has("external_id"))
        assertFalse(body.has("external_id_auth_hash"))
        assertEquals(0, body.getInt("status"))
    }

    @Test fun rejectedIdentityStillRegistersTheDeviceAndLogsLoudly() {
        var registered = false
        OpenPush.onRegistered = { registered = true }
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") {
            OpenPush.Response(200, """{"id":"sub_1","identity_rejected":"auth hash mismatch"}""")
        }
        OpenPush.registerBlocking("provider-token", "external")
        idleMain()
        assertTrue(registered)
        assertTrue(logs.any { it.contains("identity rejected") && it.contains("auth hash mismatch") })
    }
}
