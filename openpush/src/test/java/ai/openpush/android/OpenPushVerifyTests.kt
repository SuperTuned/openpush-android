package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushVerifyTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val requests = Collections.synchronizedList(mutableListOf<OpenPush.Request>())

    /** What `/verify` answers next; a null answer makes the transport throw, as a dead network does. */
    @Volatile private var answer: OpenPush.Response? = pending()

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.onLog = {}
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        requests.clear()
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk-public", credential = { "opdev" }) { request ->
            if (!request.url.contains("/verify")) return@initialize OpenPush.Response(200, """{"id":"sub_1"}""")
            requests += request
            answer ?: throw IOException("Unable to resolve host")
        }
    }

    @After fun restore() = OpenPush.resetForTests()

    private fun body() = JSONObject(requests.last().body!!)

    private fun expectError(code: OpenPushVerifyError.Code, call: suspend () -> Unit): OpenPushVerifyError {
        try {
            runBlocking { call() }
        } catch (error: OpenPushVerifyError) {
            assertEquals(code, error.code)
            return error
        }
        fail("expected OpenPushVerifyError $code")
        throw AssertionError()
    }

    private fun started(): OpenPushVerification = runBlocking { OpenPush.Verify.start("+14155550123") }

    @Test fun startPostsTheNumberChannelAndLocale() {
        runBlocking { OpenPush.Verify.start(" 0014155550123", OpenPushVerifyChannel.WHATSAPP, "pt-BR") }

        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://app.openpush.ai/v1/apps/demo/verify", request.url)
        assertEquals("sdk-public", request.headers["X-OP-SDK-Key"])
        assertEquals("+14155550123", body().getString("to"))
        assertEquals("whatsapp", body().getString("channel"))
        assertEquals("pt-BR", body().getString("locale"))
    }

    @Test fun startNormalisesTheNumberLikeTheServer() {
        runBlocking { OpenPush.Verify.start("+1-415-555-0123") }
        assertEquals("+14155550123", body().getString("to"))
        expectError(OpenPushVerifyError.Code.INVALID_NUMBER) { OpenPush.Verify.start("+1415+5550123") }
        assertEquals(1, requests.size)
    }

    @Test fun startDefaultsToAutoAndOmitsAnAbsentLocale() {
        started()

        assertEquals("auto", body().getString("channel"))
        assertFalse(body().has("locale"))
    }

    @Test fun theResponseDecodesIntoAVerification() {
        val verification = started()

        assertEquals("vf_123", verification.id)
        assertEquals("+1415•••0123", verification.maskedNumber)
        assertEquals(OpenPushVerifyChannel.SMS, verification.channel)
        assertEquals(OpenPushVerificationStatus.PENDING, verification.status)
        assertEquals(0, verification.attempts)
        assertEquals(1_792_751_130_000L, verification.expiresAt!!.time)
        assertFalse("the full number stays out of toString", verification.toString().contains("+14155550123"))
    }

    @Test fun checkPostsTheCodeAndTheFullNumberToTheVerification() {
        val verification = started()
        answer = OpenPush.Response(200, verificationJson(status = "approved", attempts = 1))

        val checked = runBlocking { OpenPush.Verify.check(verification, " 482913 ") }

        assertEquals("https://app.openpush.ai/v1/apps/demo/verify/vf_123/check", requests.last().url)
        assertEquals("+14155550123", body().getString("to"))
        assertEquals("482913", body().getString("code"))
        assertEquals(OpenPushVerificationStatus.APPROVED, checked.status)
        assertEquals(1, checked.attempts)
        assertEquals(OpenPushVerificationStatus.PENDING, verification.status)
    }

    @Test fun aWrongCodeIsAPendingVerificationNotAnError() {
        val verification = started()
        answer = OpenPush.Response(200, verificationJson(status = "pending", attempts = 2))

        val checked = runBlocking { OpenPush.Verify.check(verification, "000000") }

        assertEquals(OpenPushVerificationStatus.PENDING, checked.status)
        assertEquals(2, checked.attempts)
    }

    @Test fun expiredAndMaxAttemptsDecode() {
        val verification = started()
        answer = OpenPush.Response(200, verificationJson(status = "expired"))
        assertEquals(
            OpenPushVerificationStatus.EXPIRED,
            runBlocking {
                OpenPush.Verify.check(verification, "1")
            }.status,
        )
        answer = OpenPush.Response(200, verificationJson(status = "max_attempts"))
        assertEquals(
            OpenPushVerificationStatus.MAX_ATTEMPTS,
            runBlocking { OpenPush.Verify.check(verification, "1") }.status,
        )
    }

    @Test fun resendPostsTheRequestedChannel() {
        val verification = started()
        answer = OpenPush.Response(200, verificationJson(channel = "call"))

        val resent = runBlocking { OpenPush.Verify.resend(verification, OpenPushVerifyChannel.CALL) }

        assertEquals("https://app.openpush.ai/v1/apps/demo/verify/vf_123/resend", requests.last().url)
        assertEquals("+14155550123", body().getString("to"))
        assertEquals("call", body().getString("channel"))
        assertEquals(OpenPushVerifyChannel.CALL, resent.channel)
    }

    @Test fun resendDefaultsToTheVerificationsChannel() {
        val verification = started()
        runBlocking { OpenPush.Verify.resend(verification) }

        assertEquals("sms", body().getString("channel"))
    }

    @Test fun rateLimitedMapsFrom429() {
        answer = error(429, "rate_limited", "Too many codes for this number")
        val error = expectError(OpenPushVerifyError.Code.RATE_LIMITED) { OpenPush.Verify.start("+14155550123") }
        assertEquals("Too many codes for this number", error.message)
        assertEquals(429, error.status)
    }

    @Test fun tooSoonMapsFrom429() {
        val verification = started()
        answer = error(429, "too_soon", "Wait 30 seconds before resending")
        val error = expectError(OpenPushVerifyError.Code.TOO_SOON) { OpenPush.Verify.resend(verification) }
        assertEquals("Wait 30 seconds before resending", error.message)
    }

    @Test fun notPendingMapsFrom409() {
        val verification = started()
        answer = error(409, "not_pending", "This verification is approved")
        expectError(OpenPushVerifyError.Code.NOT_PENDING) { OpenPush.Verify.check(verification, "482913") }
    }

    @Test fun providerAndRefusedCodesMap() {
        answer = error(502, "provider", "The SMS provider is unavailable")
        expectError(OpenPushVerifyError.Code.PROVIDER) { OpenPush.Verify.start("+14155550123") }
        answer = error(400, "refused", "WhatsApp is not enabled for this app")
        expectError(OpenPushVerifyError.Code.REFUSED) { OpenPush.Verify.start("+14155550123") }
    }

    @Test fun optedOutCreditAndNoRateCodesMap() {
        answer = error(409, "opted_out", "This number has opted out of texts")
        val optedOut = expectError(OpenPushVerifyError.Code.OPTED_OUT) { OpenPush.Verify.start("+14155550123") }
        assertEquals(409, optedOut.status)
        answer = error(402, "credit", "No text credit left")
        val credit = expectError(OpenPushVerifyError.Code.NO_CREDIT) { OpenPush.Verify.start("+14155550123") }
        assertEquals(402, credit.status)
        answer = error(409, "no_rate", "Texts to this country are not available")
        expectError(OpenPushVerifyError.Code.NO_RATE) { OpenPush.Verify.start("+14155550123") }
    }

    @Test fun anUnknownCodeIsRefusedWhateverTheStatus() {
        answer = error(409, "something_new", "A refusal this SDK does not know")
        expectError(OpenPushVerifyError.Code.REFUSED) { OpenPush.Verify.start("+14155550123") }
        answer = error(429, "something_new", "A refusal this SDK does not know")
        expectError(OpenPushVerifyError.Code.REFUSED) { OpenPush.Verify.start("+14155550123") }
    }

    @Test fun aPlainStringDetailIsRefusedWithItsMessage() {
        answer = OpenPush.Response(400, """{"detail":"locale is not supported"}""")
        val error = expectError(OpenPushVerifyError.Code.REFUSED) { OpenPush.Verify.start("+14155550123") }
        assertEquals("locale is not supported", error.message)
    }

    @Test fun withoutACodeTheStatusDecides() {
        answer = OpenPush.Response(429, "")
        expectError(OpenPushVerifyError.Code.RATE_LIMITED) { OpenPush.Verify.start("+14155550123") }
        answer = OpenPush.Response(409, "")
        expectError(OpenPushVerifyError.Code.REFUSED) { OpenPush.Verify.start("+14155550123") }
        answer = OpenPush.Response(503, "<html>")
        expectError(OpenPushVerifyError.Code.NETWORK) { OpenPush.Verify.start("+14155550123") }
        answer = OpenPush.Response(404, """{"detail":"Not Found"}""")
        expectError(OpenPushVerifyError.Code.REFUSED) { OpenPush.Verify.start("+14155550123") }
    }

    @Test fun aTransportFailureIsNetwork() {
        answer = null
        val error = expectError(OpenPushVerifyError.Code.NETWORK) { OpenPush.Verify.start("+14155550123") }
        assertNull(error.status)
        assertEquals("Unable to resolve host", error.message)
    }

    @Test fun anUnreadable2xxIsProvider() {
        answer = OpenPush.Response(200, "{}")
        expectError(OpenPushVerifyError.Code.PROVIDER) { OpenPush.Verify.start("+14155550123") }
    }

    @Test fun anInvalidNumberFailsBeforeAnythingIsSent() {
        expectError(OpenPushVerifyError.Code.INVALID_NUMBER) { OpenPush.Verify.start("415-555-0123") }
        assertTrue(requests.isEmpty())
    }

    @Test fun theCallbackFormReportsSuccessAndFailure() {
        val results = Collections.synchronizedList(mutableListOf<OpenPushVerifyResult>())
        val done = CountDownLatch(2)
        OpenPush.Verify.start("+14155550123") {
            results += it
            done.countDown()
        }
        OpenPush.Verify.start("not a number") {
            results += it
            done.countDown()
        }
        assertTrue(done.awaitOnMain(5, TimeUnit.SECONDS))

        val success = results.filterIsInstance<OpenPushVerifyResult.Success>().single()
        assertEquals("vf_123", success.verification.id)
        val failure = results.filterIsInstance<OpenPushVerifyResult.Failure>().single()
        assertEquals(OpenPushVerifyError.Code.INVALID_NUMBER, failure.error.code)
    }

    @Test fun beforeInitializeNothingIsSent() {
        OpenPush.resetForTests()
        expectError(OpenPushVerifyError.Code.REFUSED) { OpenPush.Verify.start("+14155550123") }
        assertTrue(requests.isEmpty())
    }

    @Test fun theSmsRetrieverHashIsElevenBase64Characters() {
        val hash = SmsRetrieverHash.compute("com.example.game", "3082abcd")
        assertEquals(11, hash.length)
        assertTrue(hash.all { it.isLetterOrDigit() || it == '+' || it == '/' })
        assertEquals(hash, SmsRetrieverHash.compute("com.example.game", "3082abcd"))
        assertFalse(hash == SmsRetrieverHash.compute("com.example.other", "3082abcd"))
    }

    private companion object {
        fun verificationJson(status: String = "pending", attempts: Int = 0, channel: String = "sms") =
            """{"id":"vf_123","to":"+1415•••0123","channel":"$channel","status":"$status",""" +
                """"attempts":$attempts,"expires_at":"2026-10-23T10:25:30.000000+00:00"}"""

        fun pending() = OpenPush.Response(200, verificationJson())

        fun error(status: Int, code: String, message: String) =
            OpenPush.Response(status, """{"detail":{"code":"$code","message":"$message"}}""")
    }
}
