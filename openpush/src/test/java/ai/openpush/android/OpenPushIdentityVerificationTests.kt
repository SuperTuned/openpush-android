package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** A verified identity is never asserted without its hash after a relaunch (spec A5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushIdentityVerificationTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private fun attach() = OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) {
        bodies += it.body!!
        OpenPush.Response(200, """{"id":"sub_1","status":1}""")
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
        OpenPush.onIdentityVerificationRequired = null
        bodies.clear()
        attach()
    }

    @Test fun afterARelaunchAHashedIdentityIsOmittedNotAsserted() {
        OpenPush.registerBlocking("provider-token")
        OpenPush.loginBlocking("user-7", "hash-9")
        coldStart()
        bodies.clear()
        OpenPush.registerBlocking("provider-token")
        val body = JSONObject(bodies.single())
        assertFalse(body.has("external_id"))
        assertFalse(body.has("external_id_auth_hash"))
        assertEquals("user-7", OpenPush.User.externalId)
    }

    @Test fun theVerificationHookFiresOnceWithTheExternalIdAndIsDeliveredOnInstall() {
        OpenPush.registerBlocking("provider-token")
        OpenPush.loginBlocking("user-7", "hash-9")
        coldStart()
        val asked = mutableListOf<String>()
        OpenPush.onIdentityVerificationRequired = { asked += it } // installed after attach
        OpenPush.registerBlocking("provider-token")
        OpenPush.registerBlocking("provider-token")
        idleMain()
        assertEquals(listOf("user-7"), asked)
    }

    @Test fun concurrentHookInstallationConsumesAPendingRequestOnce() {
        OpenPush.registerBlocking("provider-token")
        OpenPush.loginBlocking("user-7", "hash-9")
        coldStart()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val deliveries = AtomicInteger()
        val threads = List(2) {
            Thread {
                ready.countDown()
                start.await()
                OpenPush.onIdentityVerificationRequired = { deliveries.incrementAndGet() }
            }.also(Thread::start)
        }
        ready.await()
        start.countDown()
        threads.forEach(Thread::join)

        idleMain()
        assertEquals(1, deliveries.get())
    }

    @Test fun reSupplyingTheHashAssertsTheIdentityAgain() {
        OpenPush.registerBlocking("provider-token")
        OpenPush.loginBlocking("user-7", "hash-9")
        coldStart()
        OpenPush.loginBlocking("user-7", "hash-9")
        val body = JSONObject(bodies.last())
        assertEquals("user-7", body.getString("external_id"))
        assertEquals("hash-9", body.getString("external_id_auth_hash"))
    }

    @Test fun anUnhashedIdentityStillRidesAfterARelaunch() {
        OpenPush.registerBlocking("provider-token")
        OpenPush.loginBlocking("user-7")
        coldStart()
        bodies.clear()
        OpenPush.registerBlocking("provider-token")
        assertEquals("user-7", JSONObject(bodies.single()).getString("external_id"))
    }
}
