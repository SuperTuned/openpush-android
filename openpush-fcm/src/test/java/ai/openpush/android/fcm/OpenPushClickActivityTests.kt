package ai.openpush.android.fcm

import ai.openpush.android.ClickEvent
import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushNotification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushClickActivityTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clicks = Collections.synchronizedList(mutableListOf<ClickEvent>())
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val launcher = ComponentName(context.packageName, "${context.packageName}.HostLauncherActivity")

    @Before fun reset() {
        OpenPush::class.java.declaredMethods
            .first { it.name.startsWith("resetForTests") }
            .invoke(OpenPush)
        clicks.clear()
        logs.clear()
        OpenPush.onLog = { logs += it }
        declareHostLauncher()
    }

    /**
     * Robolectric's package manager ships no launcher Activity for the test package, so
     * `getLaunchIntentForPackage` would answer null and the launch branch would never run.
     */
    private fun declareHostLauncher() {
        val shadowPackageManager = shadowOf(context.packageManager)
        shadowPackageManager.addActivityIfNotPresent(launcher)
        shadowPackageManager.addIntentFilterForActivity(
            launcher,
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                addCategory(Intent.CATEGORY_DEFAULT)
            },
        )
    }

    private fun tap(vararg extras: Pair<String, String>): Intent =
        Intent(context, OpenPushClickActivity::class.java).apply {
            extras.forEach { (key, value) -> putExtra(key, value) }
        }

    @Test fun anActionTapReportsItsActionIdAndOpensTheApp() {
        OpenPush.Notifications.addClickListener { clicks += it }

        val controller = Robolectric.buildActivity(
            OpenPushClickActivity::class.java,
            tap(
                "op_message_id" to "m1",
                OpenPush.ACTION_ID_KEY to "reply",
                "title" to "hi",
            ),
        ).create()
        val activity = controller.get()

        val click = clicks.single()
        assertEquals("reply", click.actionId)
        assertEquals("m1", click.messageId)
        assertEquals("hi", click.notification.title)
        assertFalse(click.launchUrlOpened)

        val launch = shadowOf(activity).nextStartedActivity
        assertNotNull(launch)
        assertEquals(launcher, launch.component)
        assertEquals("m1", launch.getStringExtra("op_message_id"))
        assertEquals("reply", launch.getStringExtra(OpenPush.ACTION_ID_KEY))
        assertEquals("hi", launch.getStringExtra("title"))

        // Launcher semantics, not CLEAR_TOP: the host's back stack survives the tap.
        assertTrue(launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(launch.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0)
        assertEquals(0, launch.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
        assertEquals(0, launch.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP)

        assertTrue(activity.isFinishing)
    }

    @Test fun serverUtmUrlSurvivesBodyAndActionPendingIntents() {
        OpenPush.Notifications.addClickListener { clicks += it }
        val url =
            "https://example.com/offers?utm_source=owned&utm_medium=push" +
                "&utm_campaign=Launch+day&utm_term=a%2Bb&coupon=&coupon=SAVE#details"
        for ((index, action) in listOf<String?>(null, "open").withIndex()) {
            val data = mapOf("op_message_id" to "utm-$index", "deep_link" to url) +
                (action?.let { mapOf(OpenPush.ACTION_ID_KEY to it) } ?: emptyMap())
            val intent = shadowOf(OpenPushClickActivity.pendingIntent(context, data, 800 + index)).savedIntent
            val activity = Robolectric.buildActivity(OpenPushClickActivity::class.java, intent).create().get()
            assertEquals(url, clicks.last().notification.launchUrl)
            assertEquals(action, clicks.last().actionId)
            assertEquals(url, shadowOf(activity).nextStartedActivity.dataString)
        }
    }

    @Test fun aTapWithALaunchUrlOpensTheUrlInsteadOfTheApp() {
        OpenPush.Notifications.addClickListener { clicks += it }

        val activity = Robolectric.buildActivity(
            OpenPushClickActivity::class.java,
            tap("op_message_id" to "m4", "title" to "hi", "deep_link" to "  https://example.com/sale  "),
        ).create().get()

        val opened = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://example.com/sale", opened.dataString)
        assertTrue(opened.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull("the app is not launched as well", shadowOf(activity).nextStartedActivity)
        val click = clicks.single()
        assertTrue(click.launchUrlOpened)
        assertFalse(
            "the marker is not host data",
            click.notification.additionalData.containsKey("op_launch_url_opened"),
        )
        assertTrue(activity.isFinishing)
    }

    @Test fun anActionTapWithALaunchUrlAlsoOpensTheUrl() {
        OpenPush.Notifications.addClickListener { clicks += it }

        val activity = Robolectric.buildActivity(
            OpenPushClickActivity::class.java,
            tap("op_message_id" to "m5", OpenPush.ACTION_ID_KEY to "go", "deep_link" to "game://store"),
        ).create().get()

        assertEquals("game://store", shadowOf(activity).nextStartedActivity.dataString)
        assertEquals("go", clicks.single().actionId)
    }

    private val buttonActions =
        """[{"id":"shop","label":"Shop","url":"https://example.com/shop"},{"id":"later","label":"Later"}]"""

    @Test fun aButtonWithAUrlOpensItInsteadOfTheLaunchUrl() {
        OpenPush.Notifications.addClickListener { clicks += it }

        val activity = Robolectric.buildActivity(
            OpenPushClickActivity::class.java,
            tap(
                "op_message_id" to "m8",
                OpenPush.ACTION_ID_KEY to "shop",
                "deep_link" to "game://home",
                "op_actions" to buttonActions,
            ),
        ).create().get()

        val opened = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://example.com/shop", opened.dataString)
        assertNull("the app is not launched as well", shadowOf(activity).nextStartedActivity)
        val click = clicks.single()
        assertEquals("shop", click.actionId)
        assertEquals("https://example.com/shop", click.actionUrl)
        assertTrue(click.launchUrlOpened)
    }

    @Test fun aButtonWithoutAUrlStillOpensTheLaunchUrl() {
        OpenPush.Notifications.addClickListener { clicks += it }

        val activity = Robolectric.buildActivity(
            OpenPushClickActivity::class.java,
            tap(
                "op_message_id" to "m9",
                OpenPush.ACTION_ID_KEY to "later",
                "deep_link" to "game://home",
                "op_actions" to buttonActions,
            ),
        ).create().get()

        assertEquals("game://home", shadowOf(activity).nextStartedActivity.dataString)
        assertEquals("later", clicks.single().actionId)
        assertNull(clicks.single().actionUrl)
    }

    @Test fun aBodyTapIgnoresButtonUrls() {
        OpenPush.Notifications.addClickListener { clicks += it }

        val activity = Robolectric.buildActivity(
            OpenPushClickActivity::class.java,
            tap("op_message_id" to "m10", "deep_link" to "game://home", "op_actions" to buttonActions),
        ).create().get()

        assertEquals("game://home", shadowOf(activity).nextStartedActivity.dataString)
        assertNull(clicks.single().actionUrl)
    }

    @Test fun aSuppressedButtonUrlIsReportedAndTheAppOpens() {
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
            .applicationInfo!!.metaData = Bundle().apply {
            putBoolean(OpenPushClickActivity.META_SUPPRESS_LAUNCH_URLS, true)
        }
        OpenPush.Notifications.addClickListener { clicks += it }

        val activity = Robolectric.buildActivity(
            OpenPushClickActivity::class.java,
            tap("op_message_id" to "m11", OpenPush.ACTION_ID_KEY to "shop", "op_actions" to buttonActions),
        ).create().get()

        assertEquals(launcher, shadowOf(activity).nextStartedActivity.component)
        assertEquals("https://example.com/shop", clicks.single().actionUrl)
        assertFalse(clicks.single().launchUrlOpened)
    }

    @Test fun aRenderedButtonTapOpensItsUrl() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val rendered = OpenPushNotificationRenderer(context).render(
            OpenPushNotification.fromData(
                mapOf("op_message_id" to "m12", "title" to "hi", "op_actions" to buttonActions),
            ),
        )
        assertNotNull(rendered)

        val button = shadowOf(manager).allNotifications.single().actions[0]
        val activity = Robolectric.buildActivity(
            OpenPushClickActivity::class.java,
            shadowOf(button.actionIntent).savedIntent,
        ).create().get()

        assertEquals("https://example.com/shop", shadowOf(activity).nextStartedActivity.dataString)
    }

    @Test fun suppressedLaunchUrlsOpenTheAppInstead() {
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
            .applicationInfo!!.metaData = Bundle().apply {
            putBoolean(OpenPushClickActivity.META_SUPPRESS_LAUNCH_URLS, true)
        }
        OpenPush.Notifications.addClickListener { clicks += it }

        val activity = Robolectric.buildActivity(
            OpenPushClickActivity::class.java,
            tap("op_message_id" to "m6", "deep_link" to "https://example.com/sale"),
        ).create().get()

        val launch = shadowOf(activity).nextStartedActivity
        assertEquals(launcher, launch.component)
        assertEquals("https://example.com/sale", launch.getStringExtra("deep_link"))
        assertFalse(clicks.single().launchUrlOpened)
    }

    @Test fun aLaunchUrlNothingCanOpenFallsBackToTheApp() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        OpenPush.Notifications.addClickListener { clicks += it }

        val activity = Robolectric.buildActivity(
            OpenPushClickActivity::class.java,
            tap("op_message_id" to "m7", "deep_link" to "nohandler://x"),
        ).create().get()

        assertEquals(launcher, shadowOf(activity).nextStartedActivity.component)
        assertFalse(clicks.single().launchUrlOpened)
        assertTrue(logs.any { it.contains("launch url") })
    }

    @Test fun aBodyTapCarriesNoActionId() {
        OpenPush.Notifications.addClickListener { clicks += it }

        Robolectric.buildActivity(
            OpenPushClickActivity::class.java,
            tap("op_message_id" to "m2", "title" to "hi"),
        ).create()

        val click = clicks.single()
        assertNull(click.actionId)
        assertEquals("m2", click.messageId)
    }

    @Test fun collapseKeyReachesTheClickWithoutEnteringRawDataOrTheHostIntent() {
        OpenPush.Notifications.addClickListener { clicks += it }
        val raw = mapOf(
            "op_message_id" to "m3",
            "title" to "hi",
            OpenPushClickActivity.EXTRA_COLLAPSE_KEY to "legitimate-payload-value",
        )
        val intent = shadowOf(
            OpenPushClickActivity.pendingIntent(context, raw, 303, "orders"),
        ).savedIntent

        val activity = Robolectric.buildActivity(OpenPushClickActivity::class.java, intent).create().get()

        val click = clicks.single()
        assertEquals("orders", click.notification.collapseKey)
        assertEquals(raw, click.notification.rawData)
        assertEquals("legitimate-payload-value", click.data[OpenPushClickActivity.EXTRA_COLLAPSE_KEY])
        val launch = shadowOf(activity).nextStartedActivity
        assertEquals(
            "legitimate-payload-value",
            launch.getStringExtra(OpenPushClickActivity.EXTRA_COLLAPSE_KEY),
        )
        assertNull(launch.getBundleExtra(OpenPushClickActivity.EXTRA_RAW_DATA))
    }

    @Test fun aTapWithoutAMessageIdIsNotReportedButStillOpensTheApp() {
        OpenPush.Notifications.addClickListener { clicks += it }

        val activity = Robolectric.buildActivity(
            OpenPushClickActivity::class.java,
            tap("title" to "someone else's push"),
        ).create().get()

        assertTrue(clicks.isEmpty())
        assertTrue(logs.any { it.contains("OpenPush did not send") })
        assertNotNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }
}
