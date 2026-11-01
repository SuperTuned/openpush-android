package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What each `initialize` shape binds to. A third positional string used to bind as the server URL
 * in Kotlin and as the SDK key in Java; it is the SDK key in both now, and the manifest-key form
 * takes its extras by name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushInitializeOverloadTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logs = mutableListOf<String>()

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.onRegistrationFailed = null
        logs.clear()
        OpenPush.onLog = { logs += it }
        val info = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).applicationInfo!!
        info.metaData = android.os.Bundle().apply { putString(OpenPush.META_SDK_KEY, "pk_from_manifest") }
    }

    private fun sdkKey() = OpenPush.bridgeHeaders()["X-OP-SDK-Key"]

    private fun server() = OpenPush.bridgeUrl("")

    @Test fun aThirdPositionalStringIsTheSdkKey() {
        OpenPush.initialize(context, "demo", "pk_positional")

        assertEquals("pk_positional", sdkKey())
        assertEquals(OpenPush.DEFAULT_SERVER_URL, server())
    }

    @Test fun aThirdPositionalStringWithATrailingTransportIsTheSdkKey() {
        OpenPush.initialize(context, "demo", "pk_positional") { OpenPush.Response(200, "{}") }

        assertEquals("pk_positional", sdkKey())
    }

    @Test fun aThirdPositionalStringWithANamedCredentialIsTheSdkKey() {
        OpenPush.initialize(context, "demo", "pk_positional", credential = { "opdev" })

        assertEquals("pk_positional", sdkKey())
        assertEquals("opdev", OpenPush.bridgeHeaders()["X-OP-Device-Key"])
    }

    @Test fun theNamedManifestKeyFormStillReadsTheManifest() {
        OpenPush.initialize(context, "demo", serverURL = "https://push.example.com")

        assertEquals("pk_from_manifest", sdkKey())
        assertEquals("https://push.example.com", server())
    }

    @Test fun theTwoArgumentFormsStillReadTheManifest() {
        OpenPush.initialize(context, "demo")
        assertEquals("pk_from_manifest", sdkKey())

        OpenPush.resetForTests()
        OpenPush.initialize(context, appId = "demo") { OpenPush.Response(200, "{}") }
        assertEquals("pk_from_manifest", sdkKey())
    }

    /** The shape the earlier docs gave for a self-hosted server keeps working, with a log line. */
    @Test fun aPositionalServerUrlIsStillTheServerUrlWithTheManifestKey() {
        OpenPush.initialize(context, "demo", "https://push.example.com")

        assertEquals("pk_from_manifest", sdkKey())
        assertEquals("https://push.example.com", server())
        assertTrue(logs.toString(), logs.any { it.contains("reads a server URL as the SDK key") })
    }

    @Test fun anExplicitServerUrlNeverTurnsAKeyIntoAUrl() {
        OpenPush.initialize(context, "demo", "pk_explicit", "https://push.example.com")

        assertEquals("pk_explicit", sdkKey())
        assertEquals("https://push.example.com", server())
    }
}
