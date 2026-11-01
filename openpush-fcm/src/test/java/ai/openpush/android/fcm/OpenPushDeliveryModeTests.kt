package ai.openpush.android.fcm

import ai.openpush.android.OpenPush
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushDeliveryModeTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val service = ComponentName(context, OpenPushMessagingService::class.java)
    private val logs = mutableListOf<String>()

    @Before fun reset() {
        OpenPush.tokenProvider = null
        OpenPush.onLog = { logs += it }
        FirebaseApp.getApps(context).forEach(FirebaseApp::delete)
        context.getSharedPreferences("openpush.fcm", Context.MODE_PRIVATE).edit().clear().commit()
        context.packageManager.setComponentEnabledSetting(
            service,
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP,
        )
        setMetaData(null)
    }

    @After fun clear() {
        OpenPush.tokenProvider = null
        OpenPush.onLog = {}
        FirebaseApp.getApps(context).forEach(FirebaseApp::delete)
    }

    private fun setMetaData(delivery: String?) {
        shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .applicationInfo!!.metaData = Bundle().apply {
            if (delivery != null) putString(OpenPushDeliveryMode.META_DELIVERY, delivery)
        }
    }

    private fun serviceState() = context.packageManager.getComponentEnabledSetting(service)

    @Test fun automaticIsTheDefaultAndLeavesTheServiceAlone() {
        OpenPushFcmInitializer().create(context)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, serviceState())
        assertFalse(logs.contains(OpenPushDeliveryMode.FORWARD_LOG))
    }

    @Test fun forwardDisablesTheServiceAndKeepsTheAutomaticToken() {
        setMetaData("forward")

        OpenPushFcmInitializer().create(context)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, serviceState())
        assertTrue(OpenPush.tokenProvider is OpenPushFirebaseTokenProvider)
        assertTrue(logs.contains(OpenPushDeliveryMode.FORWARD_LOG))
    }

    @Test fun switchingBackToAutomaticOnTheNextLaunchReenablesTheService() {
        setMetaData("forward")
        OpenPushFcmInitializer().create(context)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, serviceState())

        setMetaData("automatic")
        OpenPushFcmInitializer().create(context)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, serviceState())
    }

    @Test fun removingTheValueOnTheNextLaunchAlsoReenablesTheService() {
        setMetaData("forward")
        OpenPushFcmInitializer().create(context)

        setMetaData(null)
        OpenPushFcmInitializer().create(context)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, serviceState())
    }

    @Test fun aServiceTheHostDisabledStaysDisabledInAutomatic() {
        context.packageManager.setComponentEnabledSetting(
            service,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )

        setMetaData("automatic")
        OpenPushFcmInitializer().create(context)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, serviceState())
    }

    @Test fun forwardOverAHostDisabledServiceDoesNotClaimIt() {
        context.packageManager.setComponentEnabledSetting(
            service,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        setMetaData("forward")
        OpenPushFcmInitializer().create(context)

        setMetaData(null)
        OpenPushFcmInitializer().create(context)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, serviceState())
    }

    @Test fun automaticWithNothingToUndoLogsNothing() {
        setMetaData("automatic")

        OpenPushFcmInitializer().create(context)

        assertTrue(logs.none { it.contains("delivery mode") })
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, serviceState())
    }

    @Test fun anUnknownValueIsLoggedAndTreatedAsAutomatic() {
        setMetaData("sideways")

        OpenPushFcmInitializer().create(context)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, serviceState())
        assertTrue(logs.any { it.contains("\"sideways\"") })
    }
}
