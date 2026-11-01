package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

// / Three compatibility members preserve common call-site shapes: initWithContext, the instance
// / observer pair on pushSubscription, and the suspend requestPermission.
// / Every other v5 member already matches by name, so these three are the whole
// / delta. Pinning them stops a later cleanup from tidying away a signature the
// / migration path depends on.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushMigrationOverloadTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before fun reset() {
        OpenPush::class.java.declaredMethods
            .first { it.name.startsWith("resetForTests") }
            .invoke(OpenPush)
        bodies.clear()
        OpenPush.onLog = {}
        OpenPush.onRegistrationFailed = null
        val info = shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName).applicationInfo!!
        info.metaData = android.os.Bundle()
        info.metaData.putString(OpenPush.META_SDK_KEY, "pk_from_manifest")
    }

    private fun attachRecording() {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") {
            bodies += it.body!!
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
    }

    @Test fun initWithContextConfiguresFromTheManifestKey() {
        OpenPush.initWithContext(context, "demo")
        assertTrue(OpenPush.isInitialized)
    }

    @Test fun theInstanceObserverSharesTheStaticObserverList() {
        attachRecording()
        OpenPush.registerBlocking("provider-token")
        val seen = AtomicInteger()
        // Registered through the instance, removed through the static pair: one list.
        val handle = OpenPush.User.pushSubscription.addObserver { seen.incrementAndGet() }
        OpenPush.PushSubscription.removeObserver(handle)
        OpenPush.User.pushSubscription.optOut()
        assertEquals(0, seen.get())
    }

    @Test fun theInstanceObserverReceivesChanges() {
        // Permission first: PushSubscriptionState only carries `optedIn`, so without it an opt-out
        // moves nothing the observer can see and the change is correctly deduped away.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        attachRecording()
        OpenPush.registerBlocking("provider-token")
        val seen = AtomicInteger()
        val handle = OpenPush.User.pushSubscription.addObserver { seen.incrementAndGet() }
        OpenPush.User.pushSubscription.optOut()
        idleMain()
        assertTrue(seen.get() > 0)
        OpenPush.User.pushSubscription.removeObserver(handle)
    }

    @Test fun theSuspendRequestPermissionAnswersFromTheCachedGrant() = runTest {
        // Granted BEFORE attach: the permission cache is populated at attach, so a
        // grant afterwards would leave the cached value false.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        attachRecording()
        // `val granted = requestPermission()` in a coroutine is the suspend form, as in 1.2.1.
        val granted: Boolean = OpenPush.Notifications.requestPermission()
        assertTrue(granted)
        assertTrue(OpenPush.Notifications.requestPermission(false))
        assertTrue(OpenPush.Notifications.requestPermission(fallbackToSettings = true))
    }
}
