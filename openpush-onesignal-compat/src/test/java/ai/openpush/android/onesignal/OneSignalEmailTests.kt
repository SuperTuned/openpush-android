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
class OneSignalEmailTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val emailBodies = Collections.synchronizedList(mutableListOf<JSONObject>())

    @Volatile private var gate = CountDownLatch(0)

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush::class.java.declaredMethods
            .first { it.name.startsWith("resetForTests") }
            .invoke(OpenPush)
        OpenPush.onLog = {}
        emailBodies.clear()
        OneSignal.initialize(context, appId = "demo", sdkKey = "sdk") { request ->
            val body = JSONObject(request.body!!)
            if (body.optString("platform") == "email") {
                emailBodies += body
                gate.countDown()
            }
            OpenPush.Response(200, """{"id":"sub_email","status":1}""")
        }
    }

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }

    @Test fun addEmailForwardsToOpenPushAsAnEmailSubscription() {
        val sent = expect(1)
        assertTrue(OneSignal.User.addEmail("Ana@Example.com"))
        assertTrue(sent.await(5, TimeUnit.SECONDS))

        val body = emailBodies.single()
        assertEquals("ana@example.com", body.getString("token"))
        assertEquals("email", body.getString("platform"))
        assertFalse(body.has("status"))
    }

    @Test fun removeEmailForwardsAsARemoval() {
        val sent = expect(1)
        assertTrue(OneSignal.User.removeEmail("ana@example.com"))
        assertTrue(sent.await(5, TimeUnit.SECONDS))

        assertEquals(-2, emailBodies.single().getInt("status"))
    }

    @Test fun anInvalidAddressIsRefusedThroughTheFacadeToo() {
        assertFalse(OneSignal.User.addEmail("not-an-address"))
    }

    @Test fun theRefusalListNoLongerHasEmail() {
        val refused = Class.forName("ai.openpush.android.onesignal.OneSignalUnavailableKt").declaredMethods.map {
            it.name
        }
        assertFalse(refused.any { it == "addEmail" || it == "removeEmail" })
    }
}
