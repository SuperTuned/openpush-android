package ai.openpush.android

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(OpenPushInternalApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushBridgeTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() {
        OpenPush.clearPersistedStateForTests()
        OpenPush.resetForTests()
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        OpenPush.onLog = {}
    }

    private fun initialize() {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) {
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
    }

    @Test fun receiptBodyMergesExtraFieldsIntoEveryEvent() {
        val body = OpenPushBridge.receiptBody(
            types = listOf("iam_impression", "iam_click"),
            messageId = "m1",
            token = "provider-token",
            appId = "demo",
            extra = mapOf("click_id" to "c1", "first_click" to true),
        )
        val events = JSONObject(body).getJSONArray("events")
        assertEquals(2, events.length())
        listOf("iam_impression", "iam_click").forEachIndexed { index, type ->
            val event = events.getJSONObject(index)
            assertEquals(type, event.getString("type"))
            assertEquals("demo", event.getString("app"))
            assertEquals("m1", event.getString("message_id"))
            assertEquals("provider-token", event.getString("token"))
            assertEquals("c1", event.getString("click_id"))
            assertTrue(event.getBoolean("first_click"))
        }
    }

    @Test fun receiptBodyWithoutExtraCarriesOnlyTheWireFields() {
        val body = OpenPushBridge.receiptBody(
            types = listOf("received"),
            messageId = "m1",
            token = "provider-token",
            appId = "demo",
        )
        val event = JSONObject(body).getJSONArray("events").getJSONObject(0)
        assertEquals(4, event.length())
    }

    @Test fun attachListenersFireAtTheEndOfAttachAndImmediatelyWhenAlreadyAttached() {
        var early = 0
        OpenPushBridge.addAttachListener { early++ }
        assertEquals(0, early)

        initialize()
        assertEquals(1, early)

        var late = 0
        OpenPushBridge.addAttachListener { late++ }
        assertEquals(1, late)

        initialize()
        assertEquals(2, early)
        assertEquals(2, late)
    }

    @Test fun permissionResultListenersFireFromOnPermissionResult() {
        initialize()
        val results = mutableListOf<Boolean>()
        OpenPushBridge.addPermissionResultListener { results += it }

        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE,
            intArrayOf(PackageManager.PERMISSION_DENIED),
        )
        assertEquals(listOf(false), results)

        // Listeners receive the effective permission, so the OS grant has to be real for it to be true.
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )
        assertEquals(listOf(false, true), results)

        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE + 1,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )
        assertEquals(listOf(false, true), results)
    }

    @Test fun tokenAndTokenKeyReflectTheCurrentState() {
        assertNull(OpenPushBridge.token)
        assertNull(OpenPushBridge.tokenKey)

        initialize()
        assertEquals("openpush.deviceToken", OpenPushBridge.tokenKey)
        assertNull(OpenPushBridge.token)

        OpenPush.registerBlocking("provider-token")
        assertEquals("provider-token", OpenPushBridge.token)
    }

    @Test fun isDebuggableReadsTheApplicationFlag() {
        val info = context.applicationInfo
        val original = info.flags
        try {
            info.flags = original and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            assertFalse(OpenPushBridge.isDebuggable)

            initialize()
            assertFalse(OpenPushBridge.isDebuggable)

            info.flags = original or ApplicationInfo.FLAG_DEBUGGABLE
            assertTrue(OpenPushBridge.isDebuggable)
        } finally {
            info.flags = original
        }
    }

    @Test fun diagnosticsAreAReadOnlySnapshotOfQueuedPipelineWork() {
        val empty = OpenPushBridge.diagnostics()
        assertEquals(
            setOf("receiptsQueued", "customEventsQueued", "pendingSessionSeconds", "lastDrainAt"),
            empty.keys,
        )
        assertEquals(0, empty["receiptsQueued"])
        assertEquals(0, empty["customEventsQueued"])
        assertEquals(0L, empty["pendingSessionSeconds"])
        assertNull(empty["lastDrainAt"])

        OpenPush.trackEvent("before_initialize")

        val snapshot = OpenPushBridge.diagnostics()
        assertEquals(1, snapshot["customEventsQueued"])
        assertNull(snapshot["lastDrainAt"])
    }
}
