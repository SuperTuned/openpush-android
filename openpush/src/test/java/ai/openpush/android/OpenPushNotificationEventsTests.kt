package ai.openpush.android

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushNotificationEventsTests {
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
            OpenPush.Response(202, "{}")
        }
        OpenPush.clearToken()
        OpenPush.registerBlocking("provider-token")
        // Foreground listeners only run while an Activity is visible, so these tests need one up.
        Robolectric.buildActivity(Activity::class.java).setup()
        bodies.clear()
    }

    private fun firstEvent() = JSONObject(bodies.single()).getJSONArray("events").getJSONObject(0)

    @Test fun aForegroundListenerCanPreventTheDefaultDisplay() {
        val handle = OpenPush.Notifications.addForegroundLifecycleListener { it.preventDefault() }
        val prevented = OpenPush.processForegroundMessageBlocking(mapOf("op_message_id" to "m1", "title" to "hi"))
        assertTrue(prevented)
        assertEquals("received", firstEvent().getString("type"))
        assertEquals("m1", firstEvent().getString("message_id"))

        bodies.clear()
        OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        assertFalse(OpenPush.processForegroundMessageBlocking(mapOf("op_message_id" to "m2")))
    }

    @Test fun aForegroundListenerThatDoesNothingLeavesDisplayToTheHost() {
        val handle = OpenPush.Notifications.addForegroundLifecycleListener { }
        try {
            assertFalse(OpenPush.processForegroundMessageBlocking(mapOf("op_message_id" to "m1")))
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }
    }

    @Test fun processClickPostsAClickedReceiptAndReachesListeners() {
        var seen: ClickEvent? = null
        val handle = OpenPush.Notifications.addClickListener { seen = it }
        try {
            OpenPush.processClickBlocking(mapOf("op_message_id" to "m1", "deep_link" to "app://x"))
            assertEquals("clicked", firstEvent().getString("type"))
            assertEquals("m1", seen!!.messageId)
            assertEquals("app://x", seen!!.data["deep_link"])
        } finally {
            OpenPush.Notifications.removeClickListener(handle)
        }
    }

    @Test fun aClickOnAnotherSendersNotificationReachesNoListenerAndPostsNoReceipt() {
        var seen: ClickEvent? = null
        val logs = mutableListOf<String>()
        OpenPush.onLog = { logs += it }
        val handle = OpenPush.Notifications.addClickListener { seen = it }
        try {
            // Another SDK sharing this provider integration: no op_message_id, so not ours.
            OpenPush.processClickBlocking(mapOf("title" to "hi"))
            assertTrue(bodies.isEmpty())
            assertNull(seen)
            assertTrue(logs.any { it == "click on a notification OpenPush did not send — ignored" })
        } finally {
            OpenPush.Notifications.removeClickListener(handle)
            OpenPush.onLog = {}
        }
    }

    @Test fun aForeignForegroundMessageIsIgnoredWithoutAReceipt() {
        var seen: ForegroundEvent? = null
        val handle = OpenPush.Notifications.addForegroundLifecycleListener {
            seen = it
            it.preventDefault()
        }
        try {
            assertFalse(OpenPush.processForegroundMessageBlocking(mapOf("title" to "hi")))
            assertTrue(bodies.isEmpty())
            assertNull(seen)
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }
    }

    @Test fun aFailingListenerDoesNotStopTheOthers() {
        var second = false
        val bad = OpenPush.Notifications.addClickListener { error("listener blew up") }
        val good = OpenPush.Notifications.addClickListener { second = true }
        try {
            OpenPush.processClickBlocking(mapOf("op_message_id" to "m1"))
            assertTrue(second)
        } finally {
            OpenPush.Notifications.removeClickListener(bad)
            OpenPush.Notifications.removeClickListener(good)
        }
    }

    // ---- background (data-only) pushes ---------------------------------------------------------

    private val backgroundPush = mapOf("op_message_id" to "bg1", "op_background" to "1", "level" to "7")

    @Test fun aBackgroundPushReachesItsListenerNotTheForegroundOnesAndIsNotDisplayed() {
        var background: BackgroundMessageEvent? = null
        var foreground: ForegroundEvent? = null
        val bg = OpenPush.Notifications.addBackgroundMessageListener { background = it }
        val fg = OpenPush.Notifications.addForegroundLifecycleListener { foreground = it }
        try {
            assertTrue(
                "true means the host must not display it",
                OpenPush.processForegroundMessageBlocking(backgroundPush),
            )
            assertEquals("bg1", background!!.messageId)
            assertEquals(backgroundPush, background!!.data)
            assertEquals(mapOf("level" to "7"), background!!.notification.additionalData)
            assertNull(foreground)
            assertEquals("received", firstEvent().getString("type"))
            assertEquals("bg1", firstEvent().getString("message_id"))
        } finally {
            OpenPush.Notifications.removeBackgroundMessageListener(bg)
            OpenPush.Notifications.removeForegroundLifecycleListener(fg)
        }
    }

    @Test fun aBackgroundPushWithNoListenerIsLoggedAndDroppedButStillReceipted() {
        val logs = mutableListOf<String>()
        OpenPush.onLog = { logs += it }
        try {
            assertTrue(OpenPush.processForegroundMessageBlocking(backgroundPush))
            assertTrue(logs.any { it.contains("background push bg1 dropped") })
            assertEquals("received", firstEvent().getString("type"))
        } finally {
            OpenPush.onLog = {}
        }
    }

    @Test fun aRemovedBackgroundListenerIsNotCalled() {
        var calls = 0
        val handle = OpenPush.Notifications.addBackgroundMessageListener { calls++ }
        OpenPush.Notifications.removeBackgroundMessageListener(handle)

        assertTrue(OpenPush.processForegroundMessageBlocking(backgroundPush))
        assertEquals(0, calls)
    }

    @Test fun theFlagOnAPushWithTextIsIgnoredSoItShowsAsBefore() {
        var background: BackgroundMessageEvent? = null
        var foreground: ForegroundEvent? = null
        val bg = OpenPush.Notifications.addBackgroundMessageListener { background = it }
        val fg = OpenPush.Notifications.addForegroundLifecycleListener { foreground = it }
        try {
            assertFalse(OpenPush.processForegroundMessageBlocking(backgroundPush + ("title" to "hi")))
            assertNull(background)
            assertEquals("hi", foreground!!.notification.title)
        } finally {
            OpenPush.Notifications.removeBackgroundMessageListener(bg)
            OpenPush.Notifications.removeForegroundLifecycleListener(fg)
        }
    }
}
