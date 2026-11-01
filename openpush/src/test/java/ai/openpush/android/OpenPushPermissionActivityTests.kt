package ai.openpush.android

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(OpenPushInternalApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushPermissionActivityTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val bridgeResults = mutableListOf<Boolean>()
    private val logs = mutableListOf<String>()

    @Before fun reset() {
        OpenPush.resetForTests()
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.onLog = { logs += it }
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") { OpenPush.Response(200, "{}") }
        OpenPushBridge.addPermissionResultListener { bridgeResults += it }
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun clear() {
        OpenPush.onLog = {}
    }

    private fun host(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test fun aGrantReachesTheCallbackWithoutTheHostForwardingAnything() {
        val host = host()
        val results = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(host, callback = results::add)

        // No in-Activity prompt on the host: the request runs in OpenPush's own Activity.
        assertNull(shadowOf(host).lastRequestedPermission)
        val prompt = PermissionPrompt.started(host).get()
        assertTrue(results.isEmpty())

        PermissionPrompt.answer(prompt, granted = true)

        idleMain()
        assertEquals(listOf(true), results)
        assertEquals(listOf(true), bridgeResults)
        assertTrue(OpenPush.Notifications.permission)
        assertTrue(prompt.isFinishing)
    }

    @Test fun aDenialReachesTheCallback() {
        val host = host()
        val results = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(host, callback = results::add)

        val prompt = PermissionPrompt.answer(host, granted = false)

        idleMain()
        assertEquals(listOf(false), results)
        assertEquals(listOf(false), bridgeResults)
        assertTrue(prompt.isFinishing)
    }

    @Test fun aPermanentDenialWithFallbackToSettingsOpensSettingsAndReportsOnReturn() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val host = controller.get()
        OpenPush.Notifications.requestPermission(host) {}
        PermissionPrompt.answer(host, granted = false)

        // Asked once and the rationale is no longer shown: only system settings can change it now.
        val results = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(host, fallbackToSettings = true, callback = results::add)

        // Robolectric keeps one started-Activity queue, so the first prompt's own system request is in it too.
        val started = generateSequence { shadowOf(host).nextStartedActivity }.toList()
        assertTrue(started.any { it.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS })
        assertTrue(started.none { it.component?.className == OpenPushPermissionActivity::class.java.name })
        assertTrue(results.isEmpty())

        controller.pause().stop()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        controller.start().resume()

        idleMain()
        assertEquals(listOf(true), results)
    }

    @Test fun aLaterUnrelatedForwardIsStillDelivered() {
        val host = host()
        val results = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(host, callback = results::add)
        PermissionPrompt.answer(host, granted = false)

        // A wrapper SDK's own prompt (Unity's AndroidPermissionRequester) later reports a grant.
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )

        idleMain()
        assertEquals(listOf(false), results)
        assertEquals(listOf(false, true), bridgeResults)
        assertTrue(OpenPush.Notifications.permission)
    }

    @Test fun aFinishingHostStartsNoPromptAndReportsTheCurrentPermission() {
        val host = host()
        host.finish()
        val results = mutableListOf<Boolean>()

        OpenPush.Notifications.requestPermission(host, callback = results::add)

        idleMain()
        assertEquals(listOf(false), results)
        val started = generateSequence { shadowOf(host).nextStartedActivity }.toList()
        assertTrue(started.none { it.component?.className == OpenPushPermissionActivity::class.java.name })
    }

    @Test fun aPromptStartedFromAStoppedHostThatNeverAppearsCompletesWhenTheAppReturns() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controller.pause().stop()
        val results = mutableListOf<Boolean>()

        // Android drops the start from the background: the prompt is never created here.
        OpenPush.Notifications.requestPermission(controller.get(), callback = results::add)
        assertTrue(results.isEmpty())

        controller.start().resume()

        idleMain()
        assertEquals(listOf(false), results)
    }

    @Test fun aPromptStartedFromOnCreateIsNotCompletedByTheHostsFirstResume() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val results = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(controller.get(), callback = results::add)

        controller.start().resume()
        assertTrue(results.isEmpty())

        PermissionPrompt.answer(controller.get(), granted = true)
        idleMain()
        assertEquals(listOf(true), results)
    }

    @Test fun aRecreatedPromptWithNoPendingAnswerReportsTheDecidedPermissionAndFinishes() {
        val host = host()
        val results = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(host, callback = results::add)
        val controller = PermissionPrompt.started(host)
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        controller.recreate()

        assertEquals(listOf(true), results)
        assertTrue(controller.get().isFinishing)
    }

    @Test fun aForwardThatArrivesFirstWinsAndTheActivityAnswerIsDropped() {
        val host = host()
        val results = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(host, callback = results::add)
        val prompt = PermissionPrompt.started(host).get()

        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )
        PermissionPrompt.answer(prompt, granted = true)

        idleMain()
        assertEquals(listOf(true), results)
        assertEquals(listOf(true), bridgeResults)
    }

    @Test fun aPromptDismissedWithoutAnAnswerReportsTheCurrentPermission() {
        val host = host()
        val results = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(host, callback = results::add)
        val controller = PermissionPrompt.started(host)

        controller.get().finish()
        controller.pause().stop().destroy()

        idleMain()
        assertEquals(listOf(false), results)
    }

    @Test fun withoutThePermissionActivityTheLegacyPromptAndForwardStillWork() {
        shadowOf(context.packageManager)
            .removeActivity(ComponentName(context, OpenPushPermissionActivity::class.java))
        val host = host()
        val results = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(host, callback = results::add)

        val request = shadowOf(host).lastRequestedPermission
        assertEquals(OpenPush.Notifications.PERMISSION_REQUEST_CODE, request.requestCode)
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )

        idleMain()
        assertEquals(listOf(true), results)
    }
}
