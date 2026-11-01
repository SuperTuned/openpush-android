package ai.openpush.android

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/** Drives the OpenPushPermissionActivity a request started on top of a host Activity. */
internal object PermissionPrompt {
    /** The prompt [host] started, created and showing the system request. */
    fun started(host: Activity): ActivityController<OpenPushPermissionActivity> {
        val intent: Intent? = shadowOf(host).nextStartedActivity
        assertEquals(OpenPushPermissionActivity::class.java.name, intent?.component?.className)
        val controller = Robolectric.buildActivity(OpenPushPermissionActivity::class.java, intent).setup()
        val request = shadowOf(controller.get()).lastRequestedPermission
        assertTrue(request.requestedPermissions.contains(Manifest.permission.POST_NOTIFICATIONS))
        return controller
    }

    /** Answers the system request the way the OS does: the grant is recorded before the result arrives. */
    fun answer(prompt: OpenPushPermissionActivity, granted: Boolean) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        if (granted) {
            shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        val request = shadowOf(prompt).lastRequestedPermission
        prompt.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED),
        )
    }

    fun answer(host: Activity, granted: Boolean): OpenPushPermissionActivity =
        started(host).get().also { answer(it, granted) }
}
