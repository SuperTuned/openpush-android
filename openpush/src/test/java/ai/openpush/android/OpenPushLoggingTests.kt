package ai.openpush.android

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushLoggingTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var originalFlags = 0

    @Before fun reset() {
        originalFlags = context.applicationInfo.flags
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        useDefaultSink()
        ShadowLog.clear()
    }

    @After fun restoreFlags() {
        context.applicationInfo.flags = originalFlags
    }

    @Test fun theDefaultSinkIsSilentWhenTheHostIsNotDebuggable() {
        context.applicationInfo.flags = originalFlags and ApplicationInfo.FLAG_DEBUGGABLE.inv()

        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk")

        assertTrue(ShadowLog.getLogsForTag("OpenPush").isEmpty())
    }

    @Test fun theDefaultSinkPrintsWhenDebuggable() {
        context.applicationInfo.flags = originalFlags or ApplicationInfo.FLAG_DEBUGGABLE

        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk")

        assertTrue(ShadowLog.getLogsForTag("OpenPush").any { it.msg.contains("attach · server") })
    }

    @Test fun aHostSinkAlwaysReceivesEveryLine() {
        context.applicationInfo.flags = originalFlags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        val lines = mutableListOf<String>()
        OpenPush.onLog = { lines += it }

        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk")

        assertEquals(2, lines.size)
        assertTrue(lines.any { it.contains("notification_types") })
        assertTrue(lines.any { it.contains("attach · server") })
    }

    private fun useDefaultSink() {
        val sink = OpenPush::class.java.declaredMethods.single { it.name.startsWith("defaultLogSink") }
            .also { it.isAccessible = true }
        OpenPush.onLog = { message -> sink.invoke(OpenPush, message) }
    }
}
