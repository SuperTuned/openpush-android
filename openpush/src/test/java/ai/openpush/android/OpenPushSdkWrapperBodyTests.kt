package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/** The registration body names the wrapper SDK, as the iOS SDK's does, so the server can store it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(OpenPushInternalApi::class)
class OpenPushSdkWrapperBodyTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val requests = Collections.synchronizedList(mutableListOf<OpenPush.Request>())

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.sdkWrapper = null
        OpenPush.scheduler = TestScheduler()
        OpenPush.onLog = {}
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
    }

    @After fun restore() {
        OpenPush.sdkWrapper = null
        OpenPush.scheduler = OpenPush.RealScheduler
        OpenPush.resetForTests()
    }

    private fun registerOnce() {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk-public", credential = { "opdev" }) {
            requests += it
            OpenPush.Response(200, """{"id":"sub_1","status":1}""")
        }
        OpenPush.registerBlocking("provider-token")
    }

    private fun registrationBody(): JSONObject {
        val request = synchronized(requests) { requests.last { it.url.endsWith("/subscriptions") } }
        return JSONObject(request.body!!)
    }

    @Test fun bodyCarriesTheWrapperWhenOneIsSet() {
        OpenPush.sdkWrapper = "unity/1.1.5"
        registerOnce()
        assertEquals("unity/1.1.5", registrationBody().getString("sdk_wrapper"))
    }

    @Test fun bodyOmitsTheWrapperWhenNoneIsSet() {
        registerOnce()
        assertFalse(registrationBody().has("sdk_wrapper"))
    }

    @Test fun blankWrapperIsNotSent() {
        OpenPush.sdkWrapper = "  "
        registerOnce()
        assertFalse(registrationBody().has("sdk_wrapper"))
    }
}
