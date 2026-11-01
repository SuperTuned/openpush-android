package ai.openpush.android

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushAccessorTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private val clock = TestScheduler()

    @Volatile private var gate = CountDownLatch(0)

    @Before fun reset() {
        OpenPush.scheduler = clock
        OpenPush.resetForTests()
        OpenPush.onLog = {}
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        attach()
    }

    @After fun restoreClock() {
        OpenPush.scheduler = OpenPush.RealScheduler
    }

    private fun attach() {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") {
            bodies += it.body!!
            gate.countDown()
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
    }

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }
    private fun CountDownLatch.awaitRequests() = assertTrue(await(2, TimeUnit.SECONDS))
    private fun registered() {
        val done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        bodies.clear()
    }

    @Test fun getTagsReturnsACopyAndNotALiveView() {
        registered()
        var done = expect(1)
        OpenPush.User.addTag("plan", "pro")
        clock.advance(300)
        done.awaitRequests()
        val first = OpenPush.User.getTags()
        done = expect(1)
        OpenPush.User.addTag("seats", "3")
        clock.advance(300)
        done.awaitRequests()
        assertEquals(mapOf("plan" to "pro"), first)
        assertEquals(mapOf("plan" to "pro", "seats" to "3"), OpenPush.User.getTags())
    }

    @Test fun removeTagsSendsEveryRemovalInOneRequest() {
        registered()
        var done = expect(1)
        OpenPush.User.addTags(mapOf("plan" to "pro", "seats" to "3"))
        clock.advance(300)
        done.awaitRequests()
        bodies.clear()
        done = expect(1)
        OpenPush.User.removeTags(listOf("plan", "seats"))
        clock.advance(300)
        done.awaitRequests()
        val tags = JSONObject(bodies.single()).getJSONObject("tags")
        assertEquals("", tags.getString("plan"))
        assertEquals("", tags.getString("seats"))
    }

    @Test fun removeAliasesSendsEveryRemovalInOneRequest() {
        registered()
        var done = expect(1)
        OpenPush.User.addAliases(mapOf("crm" to "c-1", "loyalty" to "l-1"))
        clock.advance(300)
        done.awaitRequests()
        bodies.clear()
        done = expect(1)
        OpenPush.User.removeAliases(listOf("crm", "loyalty"))
        clock.advance(300)
        done.awaitRequests()
        val aliases = JSONObject(bodies.single()).getJSONObject("aliases")
        assertEquals("", aliases.getString("crm"))
        assertEquals("", aliases.getString("loyalty"))
    }

    @Test fun externalIdFollowsLoginAndLogout() {
        registered()
        assertNull(OpenPush.User.externalId)
        var done = expect(1)
        OpenPush.login("user-7")
        done.awaitRequests()
        assertEquals("user-7", OpenPush.User.externalId)
        done = expect(1)
        OpenPush.logout()
        done.awaitRequests()
        assertNull(OpenPush.User.externalId)
    }

    @Test fun initializationAndVersionAreExposed() {
        OpenPush.resetForTests()
        assertFalse(OpenPush.isInitialized)
        attach()
        assertTrue(OpenPush.isInitialized)
        // The version comes from gradle.properties; assert it is exposed, not that it is one release.
        assertEquals(BuildConfig.OPENPUSH_VERSION, OpenPush.sdkVersion)
        assertTrue(OpenPush.sdkVersion.isNotEmpty())
    }

    @Test fun userSubscriptionCanOptOutAndSnapshot() {
        registered()
        val done = expect(1)
        OpenPush.User.pushSubscription.optOut()
        done.awaitRequests()
        assertEquals(-2, JSONObject(bodies.last()).getInt("status"))
        assertFalse(OpenPush.User.pushSubscription.optedIn)
        assertFalse(OpenPush.PushSubscription.optedIn)
        val snapshot = OpenPush.User.pushSubscription.snapshot()
        assertEquals("sub_1", snapshot.id)
        assertEquals("provider-token", snapshot.token)
        assertFalse(snapshot.optedIn)
    }

    @Test fun userSubscriptionCanOptIn() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.onAppForegrounded()
        registered()
        val done = expect(1)
        OpenPush.User.pushSubscription.optIn()
        done.awaitRequests()
        assertEquals(1, JSONObject(bodies.last()).getInt("status"))
    }

    @Test fun requestPermissionWithoutAnActivityUsesTheResumedOne() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        OpenPush.Notifications.requestPermission { }
        PermissionPrompt.started(controller.get())
        controller.pause().stop()
    }

    @Test fun requestPermissionWithNoResumedActivityWaitsForTheNextResume() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controller.pause().stop()
        var granted: Boolean? = null
        OpenPush.Notifications.requestPermission { granted = it }
        assertNull(granted)
        assertNull(shadowOf(controller.get()).lastRequestedPermission)

        controller.start().resume()
        PermissionPrompt.answer(controller.get(), granted = false)
        idleMain()
        assertEquals(false, granted)
    }
}
