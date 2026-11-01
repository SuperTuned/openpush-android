package ai.openpush.android

import android.Manifest
import android.app.Activity
import android.app.Application
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushForegroundSyncTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private val appSessions = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var gate = CountDownLatch(0)

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        appSessions.clear()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.onLog = {}
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") {
            if (it.url.endsWith("/sessions")) {
                appSessions += it.body!!
            } else {
                bodies += it.body!!
                gate.countDown()
            }
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
        OpenPush.clearToken()
        bodies.clear()
    }

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }
    private fun CountDownLatch.awaitRequests() = assertTrue(await(2, TimeUnit.SECONDS))
    private fun registerZero() {
        val done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        assertEquals(0, JSONObject(bodies.last()).getInt("status"))
    }

    @Test fun enablingNotificationsInSettingsMakesTheDeviceTargetable() {
        registerZero()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val done = expect(1)
        OpenPush.onAppForegrounded()
        done.awaitRequests()
        assertEquals(1, JSONObject(bodies.last()).getInt("status"))
    }

    @Test fun aPermissionGrantedByAnotherPromptIsSeenWhenTheActivityResumes() {
        // Another plugin's runtime-permission dialog pauses and resumes the host Activity but never
        // stops it, so no foreground transition happens; the resume has to notice the grant.
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        OpenPush.settleForTests()
        registerZero()
        val observed = mutableListOf<Boolean>()
        OpenPush.Notifications.addPermissionObserver { observed += it }
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val done = expect(1)
        activity.pause().resume()
        done.awaitRequests()
        assertEquals(1, JSONObject(bodies.last()).getInt("status"))
        idleMain()
        assertEquals(listOf(true), observed)
    }

    @Test fun aResumeWithUnchangedPermissionSendsNothing() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        OpenPush.settleForTests()
        registerZero()
        bodies.clear()
        val unexpected = expect(1)
        activity.pause().resume()
        assertFalse(unexpected.await(300, TimeUnit.MILLISECONDS))
        assertTrue(bodies.isEmpty())
    }

    @Test fun unchangedForegroundsSendOneThrottledAppSessionOnly() {
        registerZero()
        bodies.clear()
        val unexpected = expect(1)
        OpenPush.onAppForegrounded()
        OpenPush.onAppForegrounded()
        assertFalse(unexpected.await(300, TimeUnit.MILLISECONDS))
        assertTrue(bodies.isEmpty())
        assertEquals(1, appSessions.size)
    }

    @Test fun revokingPermissionIsReportedOnNextForeground() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.onAppForegrounded()
        val first = expect(1)
        OpenPush.register("provider-token")
        first.awaitRequests()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val second = expect(1)
        OpenPush.onAppForegrounded()
        second.awaitRequests()
        assertEquals(0, JSONObject(bodies.last()).getInt("status"))
    }

    @Test fun receivingAForegroundPushDoesNotRewriteASubscribedDeviceAsStatusZero() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.onAppForegrounded()
        OpenPush.registerBlocking("provider-token")
        assertEquals(1, JSONObject(bodies.last()).getInt("status"))

        // Make foreground listeners eligible, then model a stale or transient OS permission read.
        // Delivery must post its receipt without treating the push as another lifecycle transition.
        Robolectric.buildActivity(Activity::class.java).setup()
        OpenPush.settleForTests()
        bodies.clear()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        OpenPush.processForegroundMessageBlocking(mapOf("op_message_id" to "m1"))
        OpenPush.settleForTests()

        assertEquals(1, bodies.size)
        val receipt = JSONObject(bodies.single()).getJSONArray("events").getJSONObject(0)
        assertEquals("received", receipt.getString("type"))
        assertTrue(OpenPush.User.pushSubscription.optedIn)
    }

    @Test fun lifecycleWatcherDrivesTheFirstStartAfterBackground() {
        registerZero()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controller.pause().stop()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val done = expect(1)
        controller.start().resume()
        done.awaitRequests()
        assertEquals(1, JSONObject(bodies.last()).getInt("status"))
    }

    @Test fun foregroundWithoutTokenIsHeldForTheNextRegistration() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.onAppForegrounded()
        assertTrue(bodies.isEmpty())
        val done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        assertEquals(1, JSONObject(bodies.last()).getInt("status"))
    }
}
