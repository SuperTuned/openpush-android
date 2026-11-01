package ai.openpush.android

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushObserverTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var gate = CountDownLatch(0)

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.onLog = {}
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") {
            bodies += it.body!!
            gate.countDown()
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
        OpenPush.clearToken()
        bodies.clear()
    }

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }
    private fun CountDownLatch.awaitRequests() {
        assertTrue(await(2, TimeUnit.SECONDS))
        OpenPush.settleForTests()
    }

    @Test fun permissionObserverFiresOnAForwardedGrant() {
        val observed = mutableListOf<Boolean>()
        OpenPush.Notifications.addPermissionObserver { observed += it }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        OpenPush.Notifications.requestPermission(activity) {}
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )
        idleMain()
        assertEquals(listOf(true), observed)
    }

    @Test fun subscriptionObserversReportOptOutAndOptInAndSurviveAnotherObserverThrowing() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.onAppForegrounded()
        var done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        val observed = mutableListOf<Boolean>()
        OpenPush.PushSubscription.addObserver { error("expected test exception") }
        OpenPush.PushSubscription.addObserver { observed += it.optedIn }
        done = expect(1)
        OpenPush.PushSubscription.optOut()
        done.awaitRequests()
        done = expect(1)
        OpenPush.PushSubscription.optIn()
        done.awaitRequests()
        idleMain()
        assertEquals(listOf(false, true), observed)
    }

    @Test fun removedPermissionObserverStopsFiring() {
        val observed = mutableListOf<Boolean>()
        val handle = OpenPush.Notifications.addPermissionObserver { observed += it }
        OpenPush.Notifications.removePermissionObserver(handle)
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.onAppForegrounded()
        assertTrue(observed.isEmpty())
    }

    @Test fun permissionObserverNeverDoubleFiresUnderConcurrency() {
        val observed = Collections.synchronizedList(mutableListOf<Boolean>())
        OpenPush.Notifications.addPermissionObserver { observed += it }
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val start = CyclicBarrier(3)
        val workers = List(2) {
            Thread {
                start.await(2, TimeUnit.SECONDS)
                OpenPush.onAppForegrounded()
            }.also(Thread::start)
        }

        start.await(2, TimeUnit.SECONDS)
        workers.forEach { worker ->
            worker.join(2_000)
            assertTrue("permission refresh worker did not finish", !worker.isAlive)
        }

        idleMain()
        assertEquals(listOf(true), observed)
    }
}
