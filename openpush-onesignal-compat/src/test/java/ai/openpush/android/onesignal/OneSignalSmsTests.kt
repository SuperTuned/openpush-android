package ai.openpush.android.onesignal

import ai.openpush.android.OpenPush
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OneSignalSmsTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val smsBodies = Collections.synchronizedList(mutableListOf<JSONObject>())

    @Volatile private var gate = CountDownLatch(0)

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush::class.java.declaredMethods
            .first { it.name.startsWith("resetForTests") }
            .invoke(OpenPush)
        OpenPush.onLog = {}
        smsBodies.clear()
        OneSignal.initialize(context, appId = "demo", sdkKey = "sdk") { request ->
            val body = JSONObject(request.body!!)
            if (body.optString("platform") == "sms") {
                smsBodies += body
                gate.countDown()
            }
            OpenPush.Response(200, """{"id":"sub_sms","status":0}""")
        }
    }

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }

    @Test fun addSmsForwardsToOpenPushAsAnSmsSubscription() {
        val sent = expect(1)
        assertTrue(OneSignal.User.addSms("+14155550123"))
        assertTrue(sent.await(5, TimeUnit.SECONDS))

        val body = smsBodies.single()
        assertEquals("+14155550123", body.getString("token"))
        assertEquals("sms", body.getString("platform"))
        assertFalse(body.has("status"))
    }

    @Test fun removeSmsForwardsAsAnOptOut() {
        val sent = expect(1)
        assertTrue(OneSignal.User.removeSms("+14155550123"))
        assertTrue(sent.await(5, TimeUnit.SECONDS))

        assertEquals(-2, smsBodies.single().getInt("status"))
    }

    @Test fun aNumberThatIsNotE164IsRefusedThroughTheFacadeToo() {
        assertFalse(OneSignal.User.addSms("415 555 0123"))
    }
}
