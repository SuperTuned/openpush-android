package ai.openpush.android

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * The response `status` is adopted on the opt-out axis only, the subscription observer fires with the
 * previous and current state, and the subscription id survives a relaunch (spec A6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushStatusAdoptionTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private var response = """{"id":"sub_1"}"""

    private fun grantNotifications() = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun attach() = OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) {
        bodies += it.body!!
        OpenPush.Response(200, response)
    }

    private fun coldStart() {
        OpenPush.resetForTests()
        attach()
        settle()
    }

    /** Attaching with a restored token re-publishes notification_types and pings the app session, both
     *  on background queues. Wait for them so the assertions only see what the test itself sent. */
    private fun settle() {
        var seen = -1
        while (seen != bodies.size) {
            seen = bodies.size
            Thread.sleep(150)
        }
    }

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.clearPersistedStateForTests()
        OpenPush.onLog = {}
        bodies.clear()
        response = """{"id":"sub_1"}"""
        grantNotifications()
        attach()
    }

    @Test fun aServerOptOutIsAdopted() {
        response = """{"id":"sub_1","status":-2,"status_name":"unsubscribed"}"""
        OpenPush.registerBlocking("provider-token")
        assertFalse(OpenPush.PushSubscription.optedIn)
        assertEquals(-2, OpenPush.wireStatus())
    }

    @Test fun aPositiveServerStatusClearsALocalOptOut() {
        OpenPush.registerBlocking("provider-token")
        OpenPush.optOutBlocking()
        response = """{"id":"sub_1","status":1}"""
        OpenPush.registerBlocking("provider-token")
        assertEquals(1, OpenPush.wireStatus())
    }

    @Test fun otherNegativeStatusesAreReportedNotAdopted() {
        response = """{"id":"sub_1","status":-10}"""
        OpenPush.registerBlocking("provider-token")
        assertEquals(1, OpenPush.wireStatus()) // permission granted in this harness
    }

    @Test fun theObserverFiresWhenTheSubscriptionIdArrivesWithPreviousAndCurrent() {
        val changes = Collections.synchronizedList(mutableListOf<PushSubscriptionChange>())
        OpenPush.PushSubscription.addChangeObserver { changes += it }
        OpenPush.registerBlocking("provider-token")
        idleMain()
        assertEquals(null, changes.last().previous?.id)
        assertEquals("sub_1", changes.last().current.id)
    }

    @Test fun theSubscriptionIdSurvivesAColdStart() {
        OpenPush.registerBlocking("provider-token")
        coldStart()
        assertEquals("sub_1", OpenPush.User.pushSubscription.id)
    }

    @Test fun notCollectedFieldsAreLogged() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        OpenPush.onLog = { logs += it }
        response = """{"id":"sub_1","status":1,"not_collected":["country"]}"""
        OpenPush.registerBlocking("provider-token")
        assertTrue(logs.any { it.contains("not_collected") && it.contains("country") })
    }
}
