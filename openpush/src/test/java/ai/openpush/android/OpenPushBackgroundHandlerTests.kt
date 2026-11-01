package ai.openpush.android

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A handler the manifest can name: public, no-argument constructor, records what it was given. */
class RecordingBackgroundHandler : OpenPushBackgroundMessageHandler {
    override fun onBackgroundMessage(context: Context, event: BackgroundMessageEvent) {
        received += event
        contexts += context
    }

    companion object {
        val received = mutableListOf<BackgroundMessageEvent>()
        val contexts = mutableListOf<Context>()
    }
}

class ThrowingBackgroundHandler : OpenPushBackgroundMessageHandler {
    override fun onBackgroundMessage(context: Context, event: BackgroundMessageEvent): Unit =
        throw IllegalStateException("boom")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushBackgroundHandlerTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private val backgroundPush = mapOf("op_message_id" to "bg1", "op_background" to "1", "level" to "7")

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        OpenPush.onLog = { logs += it }
        logs.clear()
        bodies.clear()
        RecordingBackgroundHandler.received.clear()
        RecordingBackgroundHandler.contexts.clear()
        clearMeta()
    }

    private fun applicationInfo() = shadowOf(context.packageManager)
        .getInternalMutablePackageInfo(context.packageName).applicationInfo!!

    private fun declareHandler(name: String) {
        val info = applicationInfo()
        if (info.metaData == null) info.metaData = android.os.Bundle()
        info.metaData.putString(OpenPush.META_BACKGROUND_MESSAGE_HANDLER, name)
    }

    private fun clearMeta() {
        applicationInfo().metaData = android.os.Bundle()
    }

    private fun initialize() {
        OpenPush.clearToken()
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) {
            bodies += it.body!!
            OpenPush.Response(202, "{}")
        }
        OpenPush.clearToken()
        OpenPush.registerBlocking("provider-token")
        Robolectric.buildActivity(Activity::class.java).setup()
        bodies.clear()
    }

    private fun firstEvent() = JSONObject(bodies.single()).getJSONArray("events").getJSONObject(0)

    @Test fun theManifestHandlerReceivesABackgroundPushWithNoRuntimeListener() {
        declareHandler(RecordingBackgroundHandler::class.java.name)
        initialize()

        assertTrue(OpenPush.processForegroundMessageBlocking(backgroundPush))

        val event = RecordingBackgroundHandler.received.single()
        assertEquals("bg1", event.messageId)
        assertEquals(mapOf("level" to "7"), event.notification.additionalData)
        assertEquals(context.applicationContext, RecordingBackgroundHandler.contexts.single())
        assertFalse("handled, so not dropped", logs.any { it.contains("dropped") })
        assertEquals("received", firstEvent().getString("type"))
    }

    @Test fun theManifestHandlerRunsInAProcessWhereInitializeNeverRan() {
        declareHandler(RecordingBackgroundHandler::class.java.name)
        // What a cold process has: the androidx.startup initializer ran, initialize did not.
        OpenPushLifecycleInitializer().create(context)
        assertFalse(OpenPush.isInitialized)

        OpenPush.processForegroundMessageBlocking(backgroundPush)

        assertEquals("bg1", RecordingBackgroundHandler.received.single().messageId)
    }

    @Test fun theManifestHandlerAndARuntimeListenerBothReceiveThePush() {
        declareHandler(RecordingBackgroundHandler::class.java.name)
        initialize()
        var listened: BackgroundMessageEvent? = null
        val handle = OpenPush.Notifications.addBackgroundMessageListener { listened = it }
        try {
            assertTrue(OpenPush.processForegroundMessageBlocking(backgroundPush))
            assertEquals("bg1", listened!!.messageId)
            assertEquals("bg1", RecordingBackgroundHandler.received.single().messageId)
        } finally {
            OpenPush.Notifications.removeBackgroundMessageListener(handle)
        }
    }

    @Test fun aHandlerThatThrowsIsLoggedAndThePushIsStillReceipted() {
        declareHandler(ThrowingBackgroundHandler::class.java.name)
        initialize()

        assertTrue(OpenPush.processForegroundMessageBlocking(backgroundPush))

        assertTrue(logs.any { it.contains("background message handler threw") && it.contains("boom") })
        assertEquals("received", firstEvent().getString("type"))
    }

    @Test fun aNameThatIsNotAHandlerIsLoggedAndThePushIsDropped() {
        declareHandler("com.example.NoSuchHandler")
        initialize()

        assertTrue(OpenPush.processForegroundMessageBlocking(backgroundPush))

        assertTrue(logs.any { it.contains("com.example.NoSuchHandler") })
        assertTrue(logs.any { it.contains("background push bg1 dropped") })
    }

    @Test fun withoutTheMetaDataNothingChanges() {
        initialize()

        assertTrue(OpenPush.processForegroundMessageBlocking(backgroundPush))

        assertTrue(RecordingBackgroundHandler.received.isEmpty())
        assertTrue(logs.any { it.contains("background push bg1 dropped") })
    }

    @Test fun aPushWithTextNeverReachesTheHandler() {
        declareHandler(RecordingBackgroundHandler::class.java.name)
        initialize()

        OpenPush.processForegroundMessageBlocking(backgroundPush + ("title" to "hi"))

        assertTrue(RecordingBackgroundHandler.received.isEmpty())
    }
}
