package ai.openpush.android

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
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
class OpenPushSubscriptionTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        OpenPush.onLog = {}
        OpenPush.clearToken()
    }

    private fun attach(body: String = """{"id":"sub_9"}""", status: Int = 200) {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) {
            OpenPush.Response(status, body)
        }
        OpenPush.clearToken()
    }

    @Test fun aRegisteredDeviceWithPermissionIsOptedIn() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        attach()
        OpenPush.registerBlocking("provider-token")
        val state = OpenPush.User.pushSubscription
        assertEquals("sub_9", state.id)
        assertEquals("provider-token", state.token)
        assertTrue(state.optedIn)
    }

    @Test fun withoutThePermissionTheSubscriptionIsNotOptedIn() {
        attach()
        OpenPush.registerBlocking("provider-token")
        val state = OpenPush.User.pushSubscription
        assertEquals("provider-token", state.token)
        assertFalse(state.optedIn)
    }

    @Test fun withoutATokenThereIsNoSubscriptionYet() {
        attach()
        val state = OpenPush.User.pushSubscription
        assertNull(state.id)
        assertNull(state.token)
        assertFalse(state.optedIn)
    }

    @Test fun aFailedRegistrationLeavesTheIdUnset() {
        attach(status = 500, body = "boom")
        OpenPush.registerBlocking("provider-token")
        assertNull(OpenPush.User.pushSubscription.id)
    }

    /**
     * The locked cross-part contract is *cached* OS permission AND token AND status ≠ -2, and both other
     * SDKs read their cache. Reading `pushSubscription` must therefore do no OS I/O and must not mutate
     * the cache as a side effect of a getter — a host that polls it in a list adapter would otherwise hit
     * `NotificationManager` and `checkSelfPermission` on every bind.
     */
    @Test fun thePushSubscriptionGetterReadsTheCacheAndNeverTheOs() {
        attach()
        OpenPush.registerBlocking("provider-token")
        assertFalse(OpenPush.User.pushSubscription.optedIn)

        // processForegroundMessage only dispatches while an Activity is visible, so one has to be up.
        // It is started *before* the grant below on purpose: setup() itself is a refresh point
        // (onActivityStarted → onAppForegrounded), and starting it after the grant would refresh the
        // cache and defeat the very thing this test pins.
        Robolectric.buildActivity(Activity::class.java).setup()

        // The OS grants the permission underneath the SDK. No sanctioned refresh point has run, so the
        // getter must keep reporting the cached answer.
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(OpenPush.User.pushSubscription.optedIn)
        assertFalse(OpenPush.PushSubscription.optedIn)

        // Receiving a push is not an app lifecycle transition. It must not refresh permission or
        // rewrite a subscribed device as status 0 just because a notification was delivered.
        OpenPush.processForegroundMessageBlocking(mapOf("op_message_id" to "m1"))
        assertFalse(OpenPush.User.pushSubscription.optedIn)

        // The real foreground lifecycle remains the sanctioned refresh point.
        OpenPush.onAppForegrounded()
        assertTrue(OpenPush.User.pushSubscription.optedIn)
    }

    @Test fun aRevokedPermissionAlsoWaitsForARefreshPoint() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        attach()
        OpenPush.registerBlocking("provider-token")
        assertTrue(OpenPush.User.pushSubscription.optedIn)

        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(OpenPush.User.pushSubscription.optedIn)

        // The host forwarding the permission result is a sanctioned refresh point; it does not touch the
        // token, so this isolates the permission half of the rule.
        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE,
            intArrayOf(PackageManager.PERMISSION_DENIED),
        )
        assertFalse(OpenPush.User.pushSubscription.optedIn)
    }
}
