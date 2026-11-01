package ai.openpush.android.fcm

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushPostedNotificationsTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val active = mutableSetOf<Int>()

    @Before fun reset() {
        context.getSharedPreferences("openpush.posted", Context.MODE_PRIVATE).edit().clear().commit()
        manager.cancelAll()
        active.clear()
    }

    private fun registry() = OpenPushPostedNotifications(context, manager) { active.toSet() }

    @Test fun recordsSurviveANewRegistryInstance() {
        active += 17
        registry().record(17, "message-17", "orders", postedAt = 42)

        assertEquals(
            OpenPushPostedNotifications.Entry(17, "message-17", "orders", 42),
            registry().entries().single(),
        )
    }

    @Test fun entriesNoLongerInTheSystemShadeArePruned() {
        active += listOf(1, 2)
        registry().record(1, "one", null, postedAt = 1)
        registry().record(2, "two", "news", postedAt = 2)

        active.remove(1)

        assertEquals(listOf(2), registry().entries().map { it.notificationId })
        val stored = context.getSharedPreferences("openpush.posted", Context.MODE_PRIVATE)
            .getString("notifications", "")!!
        assertFalse(stored.contains("\"1\""))
    }

    @Test fun theRegistryKeepsOnlyTheNewestHundredEntries() {
        active += 0..100
        val registry = registry()
        (0..100).forEach { registry.record(it, "message-$it", null, postedAt = it.toLong()) }

        val entries = registry.entries()
        assertEquals(100, entries.size)
        assertFalse(entries.any { it.notificationId == 0 })
        assertTrue(entries.any { it.notificationId == 100 })
    }
}
