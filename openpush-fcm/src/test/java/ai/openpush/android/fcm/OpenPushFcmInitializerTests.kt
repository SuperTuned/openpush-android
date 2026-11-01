package ai.openpush.android.fcm

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushBridge
import ai.openpush.android.OpenPushInternalApi
import ai.openpush.android.OpenPushTokenCallback
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(OpenPushInternalApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushFcmInitializerTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() {
        OpenPush.tokenProvider = null
        FirebaseApp.getApps(context).forEach(FirebaseApp::delete)
        setMetaData(Bundle())
    }

    @After fun clear() {
        OpenPush.tokenProvider = null
        FirebaseApp.getApps(context).forEach(FirebaseApp::delete)
    }

    private fun setMetaData(bundle: Bundle) {
        shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .applicationInfo!!.metaData = bundle
    }

    @Test fun installsTheFirebaseTokenProvider() {
        OpenPushFcmInitializer().create(context)

        assertTrue(OpenPush.tokenProvider is OpenPushFirebaseTokenProvider)
    }

    @Test fun theManifestSwitchLeavesTokenForwardingToTheHost() {
        setMetaData(Bundle().apply { putString(OpenPushFcmInitializer.META_AUTOMATIC_TOKEN, "false") })

        OpenPushFcmInitializer().create(context)

        assertNull(OpenPush.tokenProvider)
    }

    @Test fun aProviderTheHostInstalledIsKept() {
        val hostProvider = OpenPushFirebaseTokenProvider()
        OpenPush.tokenProvider = hostProvider

        OpenPushFcmInitializer().create(context)

        assertSame(hostProvider, OpenPush.tokenProvider)
    }

    @Test fun manifestValuesCreateTheDefaultFirebaseApp() {
        setMetaData(
            Bundle().apply {
                putString(OpenPushFirebaseApp.META_PROJECT_ID, "demo-project")
                putString(OpenPushFirebaseApp.META_APPLICATION_ID, "1:123456789012:android:abcdef0123456789")
                putString(OpenPushFirebaseApp.META_API_KEY, "AIzaDemoKey")
            },
        )

        OpenPushFcmInitializer().create(context)

        val app = FirebaseApp.getInstance()
        assertEquals("demo-project", app.options.projectId)
        assertEquals("1:123456789012:android:abcdef0123456789", app.options.applicationId)
    }

    @Test fun withoutAnyFirebaseConfigurationTheTokenIsUnavailable() {
        var unavailable: String? = null
        OpenPushFirebaseTokenProvider().requestToken(
            context,
            object : OpenPushTokenCallback {
                override fun onToken(token: String) = error("no token expected")
                override fun onUnavailable(reason: String) {
                    unavailable = reason
                }
            },
        )

        assertEquals(OpenPushFirebaseApp.MISSING_CONFIGURATION, unavailable)
    }

    /**
     * A fresh process that has rendered nothing: before, the shade manager arrived only with the
     * renderer's class, so `remove` logged and returned. The initializer now installs it at start.
     */
    @Test fun removeReachesANotificationAnEarlierProcessPostedBeforeAnyRender() {
        OpenPushBridge.shadeManager = null
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("c", "c", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(17, Notification.Builder(context, "c").setSmallIcon(android.R.drawable.ic_dialog_info).build())
        context.getSharedPreferences("openpush.posted", Context.MODE_PRIVATE).edit()
            .putString("notifications", """{"17":{"messageId":"m17","postedAt":1,"group":"orders"}}""")
            .commit()

        OpenPushFcmInitializer().create(context)
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") { OpenPush.Response(200, "{}") }
        OpenPush.Notifications.removeGroup("orders")

        assertSame(OpenPushFcmShadeManager, OpenPushBridge.shadeManager)
        assertTrue("the grouped notification is still shown", manager.activeNotifications.none { it.id == 17 })
    }

    @Test fun theShadeManagerIsInstalledAlsoWhenTheHostForwardsTokens() {
        OpenPushBridge.shadeManager = null
        setMetaData(Bundle().apply { putString(OpenPushFcmInitializer.META_AUTOMATIC_TOKEN, "false") })

        OpenPushFcmInitializer().create(context)

        assertSame(OpenPushFcmShadeManager, OpenPushBridge.shadeManager)
    }
}
