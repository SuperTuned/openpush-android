package ai.openpush.android.fcm

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushNotification
import ai.openpush.android.liveupdates.OpenPushLiveUpdatePayload
import ai.openpush.android.liveupdates.OpenPushLiveUpdateResult
import ai.openpush.android.liveupdates.OpenPushLiveUpdates
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** API 24 has no notification channels: both renderers must post without touching them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class OpenPushNotificationRendererApi24Tests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before fun reset() {
        OpenPush::class.java.declaredMethods
            .first { it.name.startsWith("resetForTests") }
            .invoke(OpenPush)
        context.getSharedPreferences("openpush.posted", Context.MODE_PRIVATE).edit().clear().commit()
        manager.cancelAll()
    }

    @Test fun rendersAPushOnApi24() {
        val data = mapOf("op_message_id" to "m24", "title" to "hi", "body" to "there", "op_android_group" to "g")
        val renderer = OpenPushNotificationRenderer(context)

        val id = renderer.render(OpenPushNotification.fromData(data))
        renderer.render(OpenPushNotification.fromData(data + ("op_message_id" to "m24b")))

        assertNotNull(id)
        val posted = shadowOf(manager).getNotification(id!!)
        assertNotNull(posted)
        assertEquals("hi", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        // Two children in one group: the summary is posted too, still without a channel.
        assertEquals(3, shadowOf(manager).allNotifications.size)
    }

    @Test fun rendersALiveUpdateOnApi24() {
        val payload = "{\"key\":\"progress\",\"instance_id\":\"trip-24\",\"event\":\"start\",\"sequence\":1," +
            "\"event_attributes\":{\"title\":\"Delivery\",\"body\":\"On the way\"}," +
            "\"event_updates\":{\"current_progress\":10,\"max_progress\":100}}"

        val result = OpenPushLiveUpdates.handle(
            context,
            mapOf(OpenPushLiveUpdatePayload.DATA_KEY to payload),
            "trip-24",
        )

        assertEquals(OpenPushLiveUpdateResult.DISPLAYED, result)
        val posted = shadowOf(manager).allNotifications.single()
        assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Delivery", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }
}
