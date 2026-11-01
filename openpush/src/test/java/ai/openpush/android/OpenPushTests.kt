package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetCallbacks() {
        // The receipt queue and its dedupe set are persisted, so a class that posts receipts starts
        // from an empty store and a singleton that has never attached.
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        OpenPush.onLog = {}
        OpenPush.clearToken()
    }

    @Test fun aConfigurationNeedsOnlyAnAppIdAndAnSdkKey() {
        val config = OpenPushConfiguration("demo", "sdk")
        assertEquals("demo", config.appID)
        assertEquals("sdk", config.sdkKey)
        assertEquals(OpenPush.DEFAULT_SERVER_URL, config.serverURL)
        assertEquals(
            "https://app.openpush.ai/v1/apps/demo/subscriptions",
            config.url("/v1/apps/${config.appID}/subscriptions"),
        )
    }

    @Test fun languageKeepsTheRegionOnlyForChinese() {
        assertEquals("zh-TW", OpenPush.languageTag(Locale("zh", "TW")))
        assertEquals("pt", OpenPush.languageTag(Locale("pt", "BR")))
        assertEquals("he", OpenPush.languageTag(Locale("iw")))
    }

    @Test fun configurationTrimsTrailingSlash() {
        assertEquals(
            "https://example.test",
            OpenPushConfiguration(
                appID = "app",
                sdkKey = "sdk",
                serverURL = "https://example.test///",
            ).serverURL,
        )
    }

    @Test fun registrationCarriesPublicAndDeviceCredentials() {
        var request: OpenPush.Request? = null
        OpenPush.attach(
            context,
            OpenPushConfiguration(
                appID = "demo",
                sdkKey = "sdk",
                serverURL = "https://example.test",
            ),
            { "opdev" },
        ) {
            request = it
            OpenPush.Response(200, "{}")
        }
        OpenPush.registerBlocking("provider-token", "external")
        assertEquals("sdk", request!!.headers["X-OP-SDK-Key"])
        assertEquals("opdev", request!!.headers["X-OP-Device-Key"])
        val body = JSONObject(request!!.body!!)
        assertEquals("external", body.getString("external_id"))
        assertEquals(OpenPush.sdkVersion, body.getString("sdk_version"))
        assertEquals(android.os.Build.VERSION.RELEASE, body.getString("os_version"))
        assertTrue(body.has("rooted"))
    }

    @Test fun failedReceiptCanRetryAndSuccessfulReceiptDeduplicates() {
        var attempts = 0
        OpenPush.attach(
            context,
            OpenPushConfiguration(
                appID = "demo",
                sdkKey = "sdk",
                serverURL = "https://example.test",
            ),
            { "opdev" },
        ) {
            attempts++
            OpenPush.Response(if (attempts == 1) 503 else 202, "{}")
        }
        OpenPush.registerBlocking("provider-token")
        OpenPush.settleForTests()
        attempts = 0

        // The server refused it, so the queue keeps it — the receipt is owed, not lost.
        OpenPush.receiptBlocking("received", "m1")
        assertEquals(1, attempts)
        assertTrue("a refused receipt was dropped", OpenPush.hasPendingReceipts())

        // The next drain sends it, once.
        OpenPush.drainReceiptsBlocking()
        assertEquals(2, attempts)
        assertTrue("an accepted receipt is still owed", !OpenPush.hasPendingReceipts())

        // And the persisted dedupe set refuses to enqueue it a second time.
        OpenPush.receiptBlocking("received", "m1")
        assertEquals(2, attempts)
    }

    @Test fun lateRegistrationResponseDoesNotInvokeCallback() {
        var credential = "old"
        var registered = false
        OpenPush.onRegistered = { registered = true }
        OpenPush.attach(
            context,
            OpenPushConfiguration(
                appID = "demo",
                sdkKey = "sdk",
                serverURL = "https://example.test",
            ),
            { credential },
        ) {
            credential = "new"
            OpenPush.Response(200, "{}")
        }
        OpenPush.registerBlocking("provider-token")
        assertTrue(!registered)
    }
}
