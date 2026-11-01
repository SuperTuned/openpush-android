package ai.openpush.android

import ai.openpush.android.fcm.OpenPushClickActivity
import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.startup.AppInitializer
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections

@OptIn(OpenPushInternalApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushLifecycleTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() {
        OpenPush.onLog = {}
        OpenPush.resetForTests()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun attach() {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk")
    }

    private fun grantNotifications() = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @Test fun aPermissionGrantedOutsideTheAppIsPickedUpOnTheNextForeground() {
        attach()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        assertFalse(OpenPush.Notifications.permission)
        controller.pause().stop()
        grantNotifications()
        assertFalse("the cache must not be a live OS read", OpenPush.Notifications.permission)
        controller.start().resume()
        assertTrue(OpenPush.Notifications.permission)
    }

    @Test fun aSecondActivityStartIsNotAForegroundTransition() {
        attach()
        var foregrounds = 0
        OpenPushBridge.addLifecycleListener(onForeground = { foregrounds++ }, onBackground = {})
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        assertEquals(1, foregrounds)
        grantNotifications()
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        assertEquals("a second start is not a foreground", 1, foregrounds)
        assertTrue("its resume still notices the permission", OpenPush.Notifications.permission)
        second.pause().stop()
        first.pause().stop()
        first.start().resume()
        assertEquals(2, foregrounds)
    }

    @Test fun theTrackedActivityIsReleasedWhenItGoesAway() {
        attach()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        assertSame(controller.get(), OpenPush.currentActivity?.get())
        controller.pause()
        assertNull(OpenPush.currentActivity?.get())
        controller.stop()
    }

    @Test fun theClickTrampolineNeverBecomesTheCurrentActivity() {
        attach()
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        assertSame(host.get(), OpenPush.currentActivity?.get())

        val trampoline = Robolectric.buildActivity(OpenPushClickActivity::class.java).setup()

        assertSame(host.get(), OpenPush.currentActivity?.get())
        trampoline.pause().stop().destroy()
        host.pause().stop().destroy()
    }

    @Test fun theClickTrampolineDoesNotCreateAForegroundSession() {
        attach()
        assertFalse(OpenPush.isAppForegrounded)

        val trampoline = Robolectric.buildActivity(OpenPushClickActivity::class.java).setup()

        assertFalse(OpenPush.isAppForegrounded)
        trampoline.pause().stop().destroy()
        assertFalse(OpenPush.isAppForegrounded)
    }

    @Test fun theWatcherRegisteredBeforeAttachCountsAnActivityStartedBeforeAttach() {
        AppInitializer.getInstance(context)
            .initializeComponent(OpenPushLifecycleInitializer::class.java)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val bodies = Collections.synchronizedList(mutableListOf<String>())

        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") { request ->
            if (!request.url.endsWith("/sessions")) request.body?.let(bodies::add)
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
        assertTrue(OpenPush.isAppForegrounded)
        OpenPush.registerBlocking("provider-token")
        bodies.clear()

        OpenPush.User.addTag("plan", "pro")
        controller.pause().stop()
        OpenPush.settleForTests()

        assertFalse(OpenPush.isAppForegrounded)
        assertTrue(
            bodies.any { JSONObject(it).optJSONObject("tags")?.optString("plan") == "pro" },
        )
    }

    @Test fun backgroundOnlyInitializationDoesNotStartForegroundDuration() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        var clock = 1_000_000L
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        OpenPush.nowMs = { clock }
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") { request ->
            request.body?.let(bodies::add)
            OpenPush.Response(200, if (request.url.endsWith("/subscriptions")) """{"id":"sub_1"}""" else "{}")
        }
        OpenPush.registerBlocking("provider-token")

        // The process remains background-only for two hours before its first Activity exists.
        clock += 2L * 60L * 60L * 1_000L
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        bodies.clear()
        clock += 9_000L
        controller.pause().stop()
        OpenPush.settleForTests()

        val durationBodies = bodies.map(::JSONObject).filter { it.has("duration_seconds") }
        assertEquals(1, durationBodies.size)
        assertEquals(9L, durationBodies.single().getLong("duration_seconds"))
    }
}
