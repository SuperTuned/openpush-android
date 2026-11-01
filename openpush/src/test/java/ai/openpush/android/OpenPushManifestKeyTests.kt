package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// / The SDK key moves out of initialize's argument list and into manifest
// / meta-data, so the call can take only the app id. This extends the existing
// / ai.openpush.* convention the notification renderer already uses.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushManifestKeyTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logs = mutableListOf<String>()
    private val failures = mutableListOf<String>()

    @Before fun reset() {
        OpenPush::class.java.declaredMethods
            .first { it.name.startsWith("resetForTests") }
            .invoke(OpenPush)
        logs.clear()
        failures.clear()
        OpenPush.onLog = { logs += it }
        OpenPush.onRegistrationFailed = { failures += it }
        clearMeta()
    }

    private fun applicationInfo() = shadowOf(context.packageManager)
        .getInternalMutablePackageInfo(context.packageName).applicationInfo!!

    private fun putMeta(name: String, value: String) {
        val info = applicationInfo()
        if (info.metaData == null) info.metaData = android.os.Bundle()
        info.metaData.putString(name, value)
    }

    private fun clearMeta() {
        applicationInfo().metaData = android.os.Bundle()
    }

    @Test fun aManifestKeyConfiguresTheSdk() {
        putMeta(OpenPush.META_SDK_KEY, "pk_from_manifest")
        OpenPush.initialize(context, appId = "demo")
        assertTrue(OpenPush.isInitialized)
    }

    @Test fun initializeFromTheManifestReadsBothKeys() {
        putMeta(OpenPush.META_APP_ID, "manifest-app")
        putMeta(OpenPush.META_SDK_KEY, "pk_from_manifest")

        OpenPush.initialize(context)

        assertTrue(OpenPush.isInitialized)
        assertEquals("manifest-app", OpenPush.bridgeAppId())
        assertEquals("pk_from_manifest", OpenPush.bridgeHeaders()["X-OP-SDK-Key"])
    }

    @Test fun refusesWhenTheAppIdIsMissing() {
        putMeta(OpenPush.META_SDK_KEY, "pk_from_manifest")

        OpenPush.initialize(context)

        assertFalse(OpenPush.isInitialized)
        assertTrue(
            logs.any {
                it.endsWith(
                    "<meta-data android:name=\"${OpenPush.META_APP_ID}\" " +
                        "android:value=\"your-app-id\" />\n" +
                        "<meta-data android:name=\"${OpenPush.META_SDK_KEY}\" " +
                        "android:value=\"pk_live_…\" />",
                )
            },
        )
        idleMain()
        assertTrue(failures.isNotEmpty())
    }

    @Test fun anExplicitKeyBeatsTheManifestKey() {
        putMeta(OpenPush.META_SDK_KEY, "pk_from_manifest")
        OpenPush.initialize(context, appId = "demo", sdkKey = "explicit")
        assertTrue(OpenPush.isInitialized)
    }

    @Test fun withNoKeyAnywhereInitializeRefusesLoudlyAndStaysUnconfigured() {
        OpenPush.initialize(context, appId = "demo")
        assertFalse(OpenPush.isInitialized)
        assertTrue(logs.any { it.contains(OpenPush.META_SDK_KEY) })
        idleMain()
        assertTrue(failures.isNotEmpty())
    }

    @Test fun aBlankManifestKeyCountsAsAbsent() {
        putMeta(OpenPush.META_SDK_KEY, "   ")
        OpenPush.initialize(context, appId = "demo")
        assertFalse(OpenPush.isInitialized)
    }

    @Test fun theMetaDataNamesFollowTheExistingConvention() {
        assertEquals("ai.openpush.sdk_key", OpenPush.META_SDK_KEY)
        assertEquals("ai.openpush.app_id", OpenPush.META_APP_ID)
    }
}
