package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * A migrating app's previous push SDK token rides the registration body as `prior_install` until a
 * registration carrying it succeeds, and is then never sent again for this install.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(OpenPushInternalApi::class)
class OpenPushPriorInstallTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val requests = Collections.synchronizedList(mutableListOf<OpenPush.Request>())
    private val reads = AtomicInteger()

    @Volatile private var status = 200

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.clearPersistedStateForTests()
        OpenPush.scheduler = TestScheduler()
        OpenPush.onLog = {}
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
    }

    @After fun restore() {
        OpenPush.scheduler = OpenPush.RealScheduler
        OpenPush.resetForTests()
    }

    /** A fresh process: in-memory state gone, preferences kept, the source counted. */
    private fun launch(found: PriorInstall?) {
        OpenPush.resetForTests()
        OpenPush.scheduler = TestScheduler()
        OpenPush.onLog = {}
        OpenPush.priorInstallSource = {
            PriorInstallSource {
                reads.incrementAndGet()
                found
            }
        }
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk-public", credential = { "opdev" }) {
            requests += it
            val code = if (it.url.endsWith("/subscriptions")) status else 200
            OpenPush.Response(code, if (code in 200..299) """{"id":"sub_1","status":1}""" else "")
        }
    }

    private fun register(): JSONObject {
        OpenPush.registerBlocking("provider-token")
        val request = synchronized(requests) { requests.last { it.url.endsWith("/subscriptions") } }
        return JSONObject(request.body!!)
    }

    @Test fun theBodyCarriesBothFields() {
        launch(PriorInstall("fcm-old", "9f1c-sub"))
        val prior = register().getJSONObject("prior_install")
        assertEquals("fcm-old", prior.getString("token"))
        assertEquals("9f1c-sub", prior.getString("subscription_id"))
        assertEquals(2, prior.length())
    }

    @Test fun aMissingFieldIsOmitted() {
        launch(PriorInstall(null, "9f1c-sub"))
        val prior = register().getJSONObject("prior_install")
        assertFalse(prior.has("token"))
        assertEquals("9f1c-sub", prior.getString("subscription_id"))
    }

    @Test fun nothingFoundOmitsTheObject() {
        launch(null)
        assertFalse(register().has("prior_install"))
    }

    @Test fun itRidesEveryRegistrationUntilOneSucceedsThenNeverAgain() {
        launch(PriorInstall("fcm-old", "9f1c-sub"))
        status = 400
        assertTrue(register().has("prior_install"))
        assertTrue(register().has("prior_install"))
        status = 200
        assertTrue(register().has("prior_install"))
        assertFalse(register().has("prior_install"))

        // A later launch neither reads the previous SDK's file again nor sends it.
        reads.set(0)
        launch(PriorInstall("fcm-old", "9f1c-sub"))
        assertFalse(register().has("prior_install"))
        assertEquals(0, reads.get())
    }

    @Test fun aFailedRegistrationLeavesItOwedAcrossLaunches() {
        launch(PriorInstall("fcm-old", "9f1c-sub"))
        status = 400
        assertTrue(register().has("prior_install"))

        status = 200
        launch(PriorInstall("fcm-old", "9f1c-sub"))
        assertTrue(register().has("prior_install"))
        assertFalse(register().has("prior_install"))
    }

    @Test fun itIsReadOncePerProcess() {
        launch(PriorInstall("fcm-old", "9f1c-sub"))
        status = 400
        register()
        register()
        assertEquals(1, reads.get())
    }

    @Test fun nothingFoundIsRecordedAndNotReadAgain() {
        launch(null)
        assertFalse(register().has("prior_install"))
        assertEquals(1, reads.get())

        // Even if the previous SDK's data appeared later, the empty read stands for this install.
        launch(PriorInstall("fcm-old", "9f1c-sub"))
        assertFalse(register().has("prior_install"))
        assertEquals(1, reads.get())
    }

    @Test fun aThrowingSourceReadsAsNothing() {
        OpenPush.resetForTests()
        OpenPush.scheduler = TestScheduler()
        OpenPush.priorInstallSource = { PriorInstallSource { error("boom") } }
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk-public", credential = { "opdev" }) {
            requests += it
            OpenPush.Response(200, """{"id":"sub_1","status":1}""")
        }
        assertFalse(register().has("prior_install"))
    }
}
