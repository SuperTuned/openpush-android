package ai.openpush.android.onesignal

import ai.openpush.android.OpenPush
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

/** The facade's `initialize` binds a third positional string as the SDK key, like the core SDK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FacadeInitializeOverloadTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sent = mutableListOf<OpenPush.Request>()

    @Before fun reset() {
        OpenPush::class.java.declaredMethods.first { it.name.startsWith("resetForTests") }.invoke(OpenPush)
        sent.clear()
        OpenPush.onLog = {}
        val info = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).applicationInfo!!
        info.metaData = android.os.Bundle().apply { putString(OpenPush.META_SDK_KEY, "pk_from_manifest") }
    }

    private fun recordedKey(): String? {
        OpenPush.register("provider-token")
        val deadline = System.currentTimeMillis() + 5_000
        while (synchronized(sent) { sent.isEmpty() } && System.currentTimeMillis() < deadline) Thread.sleep(5)
        return synchronized(sent) { sent.first().headers["X-OP-SDK-Key"] }
    }

    private fun recording(): suspend (OpenPush.Request) -> OpenPush.Response = { request ->
        synchronized(sent) { sent += request }
        OpenPush.Response(200, """{"id":"sub_1"}""")
    }

    @Test fun aThirdPositionalStringIsTheSdkKey() {
        OneSignal.initialize(context, "demo", "pk_positional", transport = recording())

        assertEquals("pk_positional", recordedKey())
    }

    @Test fun theNamedManifestKeyFormStillReadsTheManifest() {
        OneSignal.initialize(context, "demo", serverURL = "https://push.example.com", transport = recording())

        assertEquals("pk_from_manifest", recordedKey())
        assertTrue(synchronized(sent) { sent.first().url.startsWith("https://push.example.com/") })
    }
}
