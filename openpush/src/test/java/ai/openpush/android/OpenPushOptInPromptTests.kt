package ai.openpush.android

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class OpenPushOptInPromptTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private val logs = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var gate = CountDownLatch(0)

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.onLog = { logs += it }
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") {
            bodies += it.body!!
            gate.countDown()
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
        OpenPush.clearToken()
        OpenPush.registerBlocking("provider-token")
        bodies.clear()
    }

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }
    private fun CountDownLatch.awaitRequests() = assertTrue(await(2, TimeUnit.SECONDS))

    @Test fun optInPromptsWithTheResumedActivity() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        OpenPush.PushSubscription.optIn()
        PermissionPrompt.started(controller.get())
    }

    @Test fun explicitActivityPromptThenReportsTheGrant() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val done = expect(1)
        OpenPush.PushSubscription.optIn(activity)
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )
        done.awaitRequests()
        assertEquals(1, JSONObject(bodies.last()).getInt("status"))
        assertTrue(OpenPush.PushSubscription.optedIn)
    }

    @Test fun refusedPromptReportsZero() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val done = expect(1)
        OpenPush.PushSubscription.optIn(activity)
        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE,
            intArrayOf(PackageManager.PERMISSION_DENIED),
        )
        done.awaitRequests()
        assertEquals(0, JSONObject(bodies.last()).getInt("status"))
    }

    @Test fun noActivityReportsTheHonestStatusAndWhy() {
        val done = expect(1)
        OpenPush.PushSubscription.optIn()
        done.awaitRequests()
        assertEquals(0, JSONObject(bodies.last()).getInt("status"))
        assertTrue(logs.any { it.contains("no foreground Activity") })
    }

    @Test fun alreadyPermittedSkipsThePrompt() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val done = expect(1)
        OpenPush.PushSubscription.optIn(activity)
        done.awaitRequests()
        assertNull(shadowOf(activity).lastRequestedPermission)
        assertEquals(1, JSONObject(bodies.last()).getInt("status"))
    }
}
