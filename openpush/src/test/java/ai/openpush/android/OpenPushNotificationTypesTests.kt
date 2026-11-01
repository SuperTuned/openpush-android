package ai.openpush.android

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushNotificationTypesTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var gate = CountDownLatch(0)

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.onLog = {}
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) {
            bodies += it.body!!
            gate.countDown()
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
        OpenPush.clearToken()
        bodies.clear()
    }

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }
    private fun CountDownLatch.awaitRequests() = assertTrue(await(2, TimeUnit.SECONDS))

    @Test fun everyRegistrationCarriesZeroWithoutPermission() {
        val done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        assertEquals(0, JSONObject(bodies.single()).getInt("status"))
    }

    @Test fun aPermittedDeviceReportsOne() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.onAppForegrounded()
        val done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        assertEquals(1, JSONObject(bodies.single()).getInt("status"))
    }

    @Test fun theDeviceNeverClaimsAServerOwnedCode() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.onAppForegrounded()
        var done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        done = expect(1)
        OpenPush.PushSubscription.optOut()
        done.awaitRequests()
        val sent = bodies.map { JSONObject(it).getInt("status") }
        assertEquals(listOf(1, -2), sent)
        assertTrue(sent.none { it in listOf(-10, -22, -31, -50) })
    }
}
