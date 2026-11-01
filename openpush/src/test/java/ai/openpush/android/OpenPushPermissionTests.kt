package ai.openpush.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushPermissionTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() {
        OpenPush.resetForTests()
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.onLog = {}
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") { OpenPush.Response(200, "{}") }
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test fun canRequestPermissionIsFalseWhenGranted() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.onAppForegrounded()

        assertFalse(OpenPush.Notifications.canRequestPermission)
    }

    @Test fun canRequestPermissionIsTrueBeforeTheFirstPrompt() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(OpenPush.Notifications.canRequestPermission)
    }

    @Test fun canRequestPermissionIsFalseAfterAPermanentDenial() {
        val activity = activity()
        shadowOf(activity).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
            .edit().putBoolean("openpush.permissionRequested", true).commit()

        assertFalse(OpenPush.Notifications.canRequestPermission)
    }

    @Test
    @Config(sdk = [31])
    fun canRequestPermissionIsFalseBelowTiramisu() {
        assertFalse(OpenPush.Notifications.canRequestPermission)
    }

    @Test fun requestPermissionAsksTheSystemAndWaitsForTheAnswer() {
        val activity = activity()
        var granted: Boolean? = null
        OpenPush.Notifications.requestPermission(activity) { granted = it }
        val prompt = PermissionPrompt.started(activity).get()
        assertNull(granted)
        PermissionPrompt.answer(prompt, granted = true)
        idleMain()
        assertEquals(true, granted)
        assertTrue(OpenPush.Notifications.permission)
    }

    @Test fun anAlreadyGrantedPermissionSkipsThePrompt() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val activity = activity()
        var granted: Boolean? = null
        OpenPush.Notifications.requestPermission(activity) { granted = it }
        idleMain()
        assertEquals(true, granted)
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test fun aRepeatedDenialWaitsForTheReturnFromSystemSettingsAndCompletesOnce() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        OpenPush.Notifications.requestPermission(activity) {}
        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE,
            intArrayOf(PackageManager.PERMISSION_DENIED),
        )
        assertFalse(OpenPush.Notifications.permission)
        val results = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(activity, fallbackToSettings = true, results::add)
        assertTrue(results.isEmpty())
        assertEquals(
            Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            shadowOf(activity).nextStartedActivity.action,
        )

        controller.pause().stop()
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        controller.start().resume()

        idleMain()
        assertEquals(listOf(true), results)
        assertTrue(OpenPush.Notifications.permission)

        controller.pause().stop().start().resume()
        assertEquals(listOf(true), results)
    }

    @Test fun overlappingPermissionRequestsShareTheOneSystemResult() {
        val activity = activity()
        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()

        OpenPush.Notifications.requestPermission(activity, callback = first::add)
        OpenPush.Notifications.requestPermission(activity, callback = second::add)

        assertTrue(first.isEmpty())
        assertTrue(second.isEmpty())
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )

        idleMain()
        assertEquals(listOf(true), first)
        assertEquals(listOf(true), second)
    }

    @Test
    @Config(sdk = [26])
    fun beforeApi33ThePermissionIsTheNotificationToggle() {
        val activity = activity()
        var granted: Boolean? = null
        OpenPush.Notifications.requestPermission(activity) { granted = it }
        idleMain()
        assertEquals(true, granted) // Robolectric enables notifications by default
        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    @Test fun aForeignRequestCodeIsIgnored() {
        val activity = activity()
        var granted: Boolean? = null
        OpenPush.Notifications.requestPermission(activity) { granted = it }
        OpenPush.Notifications.onPermissionResult(1234, intArrayOf(PackageManager.PERMISSION_GRANTED))
        assertNull(granted)
    }
}
