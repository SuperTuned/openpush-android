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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushPhoneSubscriptionTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = TestScheduler()
    private val requests = Collections.synchronizedList(mutableListOf<OpenPush.Request>())

    /** Answers for phone writes, consumed in order; once empty, every write is accepted. */
    private val answers = Collections.synchronizedList(mutableListOf<OpenPush.Response>())

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.scheduler = clock
        OpenPush.jitter = { 0 }
        OpenPush.onLog = {}
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        requests.clear()
        answers.clear()
    }

    @After fun restore() {
        OpenPush.scheduler = OpenPush.RealScheduler
        OpenPush.resetForTests()
    }

    private fun initialize(appId: String = "demo") {
        OpenPush.initialize(context, appId = appId, sdkKey = "sdk-public", credential = { "opdev" }) { request ->
            val body = request.body?.let(::JSONObject)
            if (body?.optString("platform") in setOf("sms", "whatsapp")) {
                requests += request
                synchronized(answers) { answers.removeFirstOrNull() }
                    ?: OpenPush.Response(200, """{"id":"sub_${body!!.getString("platform")}","status":0}""")
            } else {
                OpenPush.Response(200, """{"id":"sub_push"}""")
            }
        }
        OpenPush.settleForTests()
    }

    @Test fun aLoggedInUserIsNamedWithItsAuthHash() {
        initialize()
        OpenPush.login("player-9", "hash-9")
        OpenPush.User.addSms("+14155550123")
        OpenPush.User.addWhatsApp("+14155550124")
        OpenPush.settleForTests()

        for (body in phoneBodies()) {
            assertEquals("player-9", body.getString("external_id"))
            assertEquals("hash-9", body.getString("external_id_auth_hash"))
        }
        assertEquals(2, phoneBodies().size)
    }

    @Test fun anIdentityRejectionIsLoggedAsStoredWithoutAUser() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        initialize()
        OpenPush.onLog = { logs += it }
        answers += OpenPush.Response(200, """{"id":"sub_sms","status":0,"identity_rejected":"auth hash mismatch"}""")
        OpenPush.login("player-9", "bad-hash")
        OpenPush.User.addSms("+14155550123")
        OpenPush.settleForTests()

        assertTrue(logs.any { it.contains("stored without a user") && it.contains("auth hash mismatch") })
    }

    private fun phoneBodies() = requests.map { JSONObject(it.body!!) }

    @Test fun addSmsPostsTheNumberAsAnSmsTokenWithNoStatus() {
        initialize()
        assertTrue(OpenPush.User.addSms("+14155550123"))
        OpenPush.settleForTests()

        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://app.openpush.ai/v1/apps/demo/subscriptions", request.url)
        assertEquals("sdk-public", request.headers["X-OP-SDK-Key"])
        val body = JSONObject(request.body!!)
        assertEquals("+14155550123", body.getString("token"))
        assertEquals("sms", body.getString("platform"))
        assertFalse("a device never sends a positive status", body.has("status"))
        assertFalse("no external_id while anonymous", body.has("external_id"))
    }

    @Test fun addWhatsAppPostsTheWhatsAppPlatform() {
        initialize()
        assertTrue(OpenPush.User.addWhatsApp(" 00447700900123 "))
        OpenPush.settleForTests()

        val body = phoneBodies().single()
        assertEquals("+447700900123", body.getString("token"))
        assertEquals("whatsapp", body.getString("platform"))
        assertFalse(body.has("status"))
    }

    @Test fun separatorsAreRemovedBeforeTheNumberIsSent() {
        initialize()
        assertTrue(OpenPush.User.addSms("+1 (415) 555-0123"))
        assertFalse("a leading zero after + is refused", OpenPush.User.addSms("+0123456789"))
        OpenPush.settleForTests()

        assertEquals("+14155550123", phoneBodies().single().getString("token"))
    }

    @Test fun aLoggedInUserIsNamedOnTheWrite() {
        initialize()
        OpenPush.login("player-9")
        OpenPush.User.addSms("+14155550123")
        OpenPush.settleForTests()

        assertEquals("player-9", phoneBodies().single().getString("external_id"))
    }

    @Test fun removeSmsSendsStatusMinusTwo() {
        initialize()
        assertTrue(OpenPush.User.removeSms("+14155550123"))
        OpenPush.settleForTests()

        val body = phoneBodies().single()
        assertEquals("sms", body.getString("platform"))
        assertEquals("+14155550123", body.getString("token"))
        assertEquals(-2, body.getInt("status"))
    }

    @Test fun removeWhatsAppSendsStatusMinusTwo() {
        initialize()
        OpenPush.User.removeWhatsApp("+14155550123")
        OpenPush.settleForTests()

        val body = phoneBodies().single()
        assertEquals("whatsapp", body.getString("platform"))
        assertEquals(-2, body.getInt("status"))
    }

    @Test fun aNumberThatIsNotE164IsRefusedLocallyAndNeverSent() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        initialize()
        OpenPush.onLog = { logs += it }

        assertFalse(OpenPush.User.addSms("415 555 0123"))
        assertFalse(OpenPush.User.removeWhatsApp("+123"))
        OpenPush.settleForTests()

        assertTrue(requests.isEmpty())
        assertTrue(PhoneSubscriptions.pendingWrites().isEmpty())
        assertTrue(logs.any { it.contains(PhoneNumberFormat.INVALID_NUMBER_MESSAGE) })
    }

    @Test fun theReturnedSubscriptionIdIsKeptPerPlatformAndNumber() {
        initialize()
        answers += OpenPush.Response(200, """{"id":"sub_sms_1","status":0,"status_name":"Not verified"}""")
        OpenPush.User.addSms("+14155550123")
        OpenPush.settleForTests()

        assertEquals("sub_sms_1", PhoneSubscriptions.subscriptionId(PhonePlatform.SMS, "+14155550123"))
        assertEquals(null, PhoneSubscriptions.subscriptionId(PhonePlatform.WHATSAPP, "+14155550123"))
    }

    @Test fun aWriteBeforeInitializeIsSentOnceAttached() {
        assertTrue(OpenPush.User.addSms("+14155550123"))
        assertTrue(requests.isEmpty())

        initialize()
        OpenPush.settleForTests()

        assertEquals("+14155550123", phoneBodies().single().getString("token"))
        assertTrue(PhoneSubscriptions.pendingWrites().isEmpty())
    }

    @Test fun onlyTheNewestWriteForANumberIsSent() {
        OpenPush.User.addSms("+14155550123")
        OpenPush.User.removeSms("+14155550123")
        OpenPush.User.addWhatsApp("+14155550123")

        initialize()
        OpenPush.settleForTests()

        val bodies = phoneBodies()
        assertEquals(2, bodies.size)
        assertEquals(-2, bodies[0].getInt("status"))
        assertEquals("sms", bodies[0].getString("platform"))
        assertEquals("whatsapp", bodies[1].getString("platform"))
    }

    @Test fun aServerErrorIsRetriedOnTheBackoff() {
        initialize()
        answers += OpenPush.Response(503, "")
        OpenPush.User.addSms("+14155550123")
        OpenPush.settleForTests()

        assertEquals(1, requests.size)
        assertEquals(listOf(2_000L), clock.pendingDelays())
        assertEquals(1, PhoneSubscriptions.pendingWrites().size)

        clock.advance(2_000L)
        OpenPush.settleForTests()

        assertEquals(2, requests.size)
        assertTrue(PhoneSubscriptions.pendingWrites().isEmpty())
    }

    @Test fun aTransportFailureAnd429AreRetriedAndHonourRetryAfter() {
        initialize()
        answers += OpenPush.Response(-1, "offline")
        answers += OpenPush.Response(429, "", mapOf("retry-after" to "20"))
        OpenPush.User.addSms("+14155550123")
        OpenPush.settleForTests()
        assertEquals(listOf(2_000L), clock.pendingDelays())

        clock.advance(2_000L)
        OpenPush.settleForTests()
        assertEquals("Retry-After wins over the 8 s step", listOf(20_000L), clock.pendingDelays())

        clock.advance(20_000L)
        OpenPush.settleForTests()
        assertEquals(3, requests.size)
        assertTrue(PhoneSubscriptions.pendingWrites().isEmpty())
    }

    @Test fun aFinal4xxIsDroppedAndDoesNotWedgeTheNextWrite() {
        initialize()
        answers += OpenPush.Response(400, """{"detail":"token is not a phone number"}""")
        OpenPush.User.addSms("+14155550123")
        OpenPush.User.addWhatsApp("+14155550123")
        OpenPush.settleForTests()

        assertEquals(2, requests.size)
        assertTrue(clock.isIdle())
        assertTrue(PhoneSubscriptions.pendingWrites().isEmpty())
    }

    @Test fun anUnsentWriteSurvivesProcessDeath() {
        initialize()
        answers += OpenPush.Response(503, "")
        OpenPush.User.addSms("+14155550123")
        OpenPush.settleForTests()
        assertEquals(1, requests.size)

        // A new process: memory is gone, preferences are not.
        OpenPush.resetForTests()
        OpenPush.scheduler = clock
        OpenPush.jitter = { 0 }
        OpenPush.onLog = {}
        initialize()
        OpenPush.settleForTests()

        assertEquals(2, requests.size)
        assertEquals("+14155550123", phoneBodies().last().getString("token"))
        assertTrue(PhoneSubscriptions.pendingWrites().isEmpty())
    }

    @Test fun writesOwedToOneAppAreNotSentToAnother() {
        initialize("app-a")
        answers += OpenPush.Response(503, "")
        OpenPush.User.addSms("+14155550123")
        OpenPush.settleForTests()
        assertEquals(1, requests.size)

        initialize("app-b")
        OpenPush.settleForTests()
        clock.advance(60_000L)
        OpenPush.settleForTests()
        assertEquals("nothing goes to app-b", 1, requests.size)

        initialize("app-a")
        OpenPush.settleForTests()
        assertEquals(2, requests.size)
        assertEquals("https://app.openpush.ai/v1/apps/app-a/subscriptions", requests.last().url)
    }
}
