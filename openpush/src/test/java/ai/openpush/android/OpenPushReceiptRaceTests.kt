package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushReceiptRaceTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
    private val ingestBodies = Collections.synchronizedList(mutableListOf<String>())

    @Before fun reset() {
        preferences.edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.onLog = {}
        ingestBodies.clear()
    }

    private fun config(app: String, tokenKey: String = "openpush.deviceToken") =
        OpenPushConfiguration(app, "sdk-$app", "https://$app.example.test", tokenKey = tokenKey)

    @Test fun publicReceiptsAndClicksAreDurableBeforeABlockedChannelCanDrain() {
        val registrationStarted = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val blockRegistration = AtomicBoolean(false)
        val firstConfig = config("workspace-a", "workspace-a.token")
        OpenPush.attach(context, firstConfig, { "device-a" }) { request ->
            if (request.url.endsWith("/subscriptions") && blockRegistration.get()) {
                registrationStarted.countDown()
                releaseRegistration.await(5, TimeUnit.SECONDS)
            }
            OpenPush.Response(200, if (request.url.endsWith("/subscriptions")) """{"id":"sub-a"}""" else "{}")
        }
        OpenPush.registerBlocking("token-a")

        blockRegistration.set(true)
        OpenPush.register("token-a")
        assertTrue(registrationStarted.await(5, TimeUnit.SECONDS))

        OpenPush.receipt("received", "message-direct")
        OpenPush.Notifications.processClick(mapOf("op_message_id" to "message-a"))

        val stored = preferences.getString("workspace-a.token.receipts", null)
        assertNotNull("the click returned before its receipt reached durable storage", stored)
        val storedReceipts = JSONArray(stored)
        assertEquals(2, storedReceipts.length())
        assertEquals("message-direct", storedReceipts.getJSONObject(0).getString("message_id"))
        assertEquals("message-a", storedReceipts.getJSONObject(1).getString("message_id"))
        assertEquals("token-a", storedReceipts.getJSONObject(1).getString("token"))
        assertEquals("workspace-a", storedReceipts.getJSONObject(1).getString("app"))

        // Simulate process death while the registration-channel closure is still unable to run.
        OpenPush.resetForTests()
        OpenPush.attach(context, firstConfig, { "device-a" }) { request ->
            if (request.url.endsWith("/v1/ingest")) ingestBodies += request.body!!
            OpenPush.Response(200, if (request.url.endsWith("/subscriptions")) """{"id":"sub-a"}""" else "{}")
        }
        releaseRegistration.countDown()
        OpenPush.settleForTests()
        OpenPush.settleForTests()

        assertEquals(1, ingestBodies.size)
        val events = org.json.JSONObject(ingestBodies.single()).getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("received", events.getJSONObject(0).getString("type"))
        assertEquals("message-direct", events.getJSONObject(0).getString("message_id"))
        assertEquals("clicked", events.getJSONObject(1).getString("type"))
        assertEquals("message-a", events.getJSONObject(1).getString("message_id"))
        assertEquals("token-a", events.getJSONObject(1).getString("token"))
        assertEquals("workspace-a", events.getJSONObject(1).getString("app"))
    }

    @Test fun aSuccessfulOldDrainCannotResurrectReceiptsAfterAWorkspaceSwitch() = assertOldDrainCannotResurrect(202)

    @Test fun aRetryableOldDrainCannotResurrectReceiptsAfterAWorkspaceSwitch() = assertOldDrainCannotResurrect(503)

    private fun assertOldDrainCannotResurrect(status: Int) {
        val ingestStarted = CountDownLatch(1)
        val releaseIngest = CountDownLatch(1)
        val firstConfig = config("workspace-a")
        val secondConfig = config("workspace-b")
        OpenPush.attach(context, firstConfig, { "device-a" }) { request ->
            if (request.url.endsWith("/v1/ingest")) {
                ingestStarted.countDown()
                releaseIngest.await(5, TimeUnit.SECONDS)
                OpenPush.Response(status, "{}")
            } else {
                OpenPush.Response(200, """{"id":"sub-a"}""")
            }
        }
        OpenPush.registerBlocking("token-a")
        OpenPush.receipt("received", "message-a")
        assertTrue(ingestStarted.await(5, TimeUnit.SECONDS))

        OpenPush.attach(context, secondConfig, { "device-b" }) {
            OpenPush.Response(200, """{"id":"sub-b"}""")
        }
        releaseIngest.countDown()
        OpenPush.settleForTests()
        OpenPush.settleForTests()

        assertFalse(OpenPush.hasPendingReceipts())
        assertFalse(preferences.contains("openpush.deviceToken.receipts"))
        assertFalse(preferences.contains("openpush.deviceToken.receiptsSent"))

        // A later process in workspace B must not restore anything written by A's late response.
        OpenPush.resetForTests()
        var laterIngest = false
        OpenPush.attach(context, secondConfig, { "device-b" }) { request ->
            if (request.url.endsWith("/v1/ingest")) laterIngest = true
            OpenPush.Response(200, """{"id":"sub-b"}""")
        }
        OpenPush.onAppForegrounded()
        OpenPush.settleForTests()

        assertFalse(laterIngest)
        assertFalse(OpenPush.hasPendingReceipts())
    }
}
