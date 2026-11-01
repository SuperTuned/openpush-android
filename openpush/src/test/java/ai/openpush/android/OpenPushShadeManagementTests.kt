package ai.openpush.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
class OpenPushShadeManagementTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val logs = mutableListOf<String>()

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPushBridge.shadeManager = null
        OpenPush.onLog = { logs += it }
        logs.clear()
        manager.cancelAll()
        manager.createNotificationChannel(NotificationChannel("test", "Test", NotificationManager.IMPORTANCE_LOW))
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") { OpenPush.Response(200, "{}") }
    }

    @Test fun clearAllFallsBackToThePlatformWithoutTheFcmModule() {
        manager.notify(7, Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info).build())

        OpenPush.Notifications.clearAll()

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun targetedOperationsExplainWhyTheyCannotRunWithoutTheFcmModule() {
        OpenPush.Notifications.remove(7)
        OpenPush.Notifications.removeGroup("orders")

        assertTrue(logs.any { it.contains("remove() requires the FCM renderer module") })
        assertTrue(logs.any { it.contains("removeGroup() requires the FCM renderer module") })
    }
}
