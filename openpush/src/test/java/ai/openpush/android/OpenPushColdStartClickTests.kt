package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tap on a notification starts the process, forwards the click and only then lets the host reach
 * its own `addClickListener`. These cover the hold that keeps that click from being lost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushColdStartClickTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = mutableListOf<String>()
    private val logs = mutableListOf<String>()

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        OpenPush.onLog = {}
        bodies.clear()
        logs.clear()
        OpenPush.clearToken()
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) {
            bodies += it.body!!
            OpenPush.Response(202, "{}")
        }
        OpenPush.clearToken()
        OpenPush.registerBlocking("provider-token")
        bodies.clear()
        OpenPush.onLog = { logs += it }
    }

    private fun click(messageId: String, extra: Map<String, String> = emptyMap()) =
        OpenPush.processClickBlocking(mapOf("op_message_id" to messageId) + extra)

    @Test fun aClickBeforeAnyListenerIsDeliveredToTheFirstListenerAdded() {
        click("m1")

        var seen: ClickEvent? = null
        val first = OpenPush.Notifications.addClickListener { seen = it }
        var secondSaw: ClickEvent? = null
        val second = OpenPush.Notifications.addClickListener { secondSaw = it }
        try {
            // Delivered inside addClickListener, on this thread — nothing was idled in between.
            assertEquals("m1", seen?.messageId)
            // The hold is drained by the first listener; a later one does not replay it.
            assertNull("the held click was replayed", secondSaw)
        } finally {
            OpenPush.Notifications.removeClickListener(first)
            OpenPush.Notifications.removeClickListener(second)
        }
    }

    @Test fun heldClicksAreCappedAtTen() {
        repeat(12) { index -> click("m${index + 1}") }

        val seen = mutableListOf<String?>()
        val handle = OpenPush.Notifications.addClickListener { seen += it.messageId }
        try {
            // The two oldest were dropped; the ten most recent survive, in order.
            assertEquals((3..12).map { "m$it" }, seen)
            assertEquals(
                2,
                logs.count { it.startsWith("held click dropped —") },
            )
        } finally {
            OpenPush.Notifications.removeClickListener(handle)
        }
    }

    @Test fun theReceiptIsPostedImmediatelyEvenWhenHeld() {
        click("m1")

        val event = JSONObject(bodies.single()).getJSONArray("events").getJSONObject(0)
        assertEquals("clicked", event.getString("type"))
        assertEquals("m1", event.getString("message_id"))

        var seen: ClickEvent? = null
        val handle = OpenPush.Notifications.addClickListener { seen = it }
        try {
            assertNotNull("the click was reported but not held", seen)
        } finally {
            OpenPush.Notifications.removeClickListener(handle)
        }
    }

    @Test fun anActionIdOnTheDataReachesTheEvent() {
        var seen: ClickEvent? = null
        val handle = OpenPush.Notifications.addClickListener { seen = it }
        try {
            click("m1", mapOf(OpenPush.ACTION_ID_KEY to "reply", "order_id" to "A-1"))

            assertEquals("reply", seen!!.actionId)
            assertEquals("m1", seen!!.notification.messageId)
            // The action id is an OpenPush control key, not host data.
            assertEquals(mapOf("order_id" to "A-1"), seen!!.notification.additionalData)
        } finally {
            OpenPush.Notifications.removeClickListener(handle)
        }

        // A body tap carries no action id.
        var body: ClickEvent? = null
        val second = OpenPush.Notifications.addClickListener { body = it }
        try {
            click("m2")
            assertNull(body!!.actionId)
        } finally {
            OpenPush.Notifications.removeClickListener(second)
        }
    }

    @Test fun aForeignClickIsNeitherHeldNorReported() {
        OpenPush.processClickBlocking(mapOf("title" to "hi"))

        assertTrue("a receipt was posted for another sender's notification", bodies.isEmpty())
        assertTrue(logs.any { it == "click on a notification OpenPush did not send — ignored" })

        var seen: ClickEvent? = null
        val handle = OpenPush.Notifications.addClickListener { seen = it }
        try {
            assertNull("a foreign click was held", seen)
        } finally {
            OpenPush.Notifications.removeClickListener(handle)
        }
    }

    @Test fun anAttachToAnotherWorkspaceDropsTheHeldClicks() {
        click("m1")

        OpenPush.initialize(context, appId = "other", sdkKey = "sdk", credential = { "opdev" }) {
            OpenPush.Response(202, "{}")
        }

        var seen: ClickEvent? = null
        val handle = OpenPush.Notifications.addClickListener { seen = it }
        try {
            assertNull("a click held for the previous workspace was delivered", seen)
        } finally {
            OpenPush.Notifications.removeClickListener(handle)
        }
    }
}
