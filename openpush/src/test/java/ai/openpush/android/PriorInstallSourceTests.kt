package ai.openpush.android

import ai.openpush.android.SharedPreferencesPriorInstallSource.Companion.PRIOR_LEGACY_ID_KEY
import ai.openpush.android.SharedPreferencesPriorInstallSource.Companion.PRIOR_LEGACY_STATE_KEY
import ai.openpush.android.SharedPreferencesPriorInstallSource.Companion.PRIOR_PREFS_FILE
import ai.openpush.android.SharedPreferencesPriorInstallSource.Companion.PRIOR_SUBSCRIPTIONS_KEY
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Reading the previous push SDK's preferences: what counts, what is ignored, and that it never throws. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PriorInstallSourceTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val source = SharedPreferencesPriorInstallSource(context)

    private fun write(vararg entries: Pair<String, Any>) {
        val editor = context.getSharedPreferences(PRIOR_PREFS_FILE, Context.MODE_PRIVATE).edit()
        entries.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                else -> error("unsupported $value")
            }
        }
        editor.commit()
    }

    private fun subscriptions(vararg models: String) = PRIOR_SUBSCRIPTIONS_KEY to models.joinToString(",", "[", "]")

    @Test fun thePushEntryGivesTokenAndId() {
        write(
            subscriptions(
                """{"id":"email-1","type":"EMAIL","address":"a@b.c"}""",
                """{"id":"9f1c-sub","type":"PUSH","address":"fcm-old","optedIn":true}""",
            ),
        )
        assertEquals(PriorInstall("fcm-old", "9f1c-sub"), source.read())
    }

    @Test fun aLocalIdIsIgnoredAndTheTokenKept() {
        write(subscriptions("""{"id":"local-1234","type":"PUSH","address":"fcm-old"}"""))
        assertEquals(PriorInstall("fcm-old", null), source.read())
    }

    @Test fun aBlankAddressIsIgnoredAndTheIdKept() {
        write(subscriptions("""{"id":"9f1c-sub","type":"PUSH","address":"  "}"""))
        assertEquals(PriorInstall(null, "9f1c-sub"), source.read())
    }

    @Test fun aPushEntryWithNothingUsableIsNothing() {
        write(subscriptions("""{"id":"local-1","type":"PUSH","address":""}"""))
        assertNull(source.read())
    }

    @Test fun noPushEntryIsNothing() {
        write(subscriptions("""{"id":"sms-1","type":"SMS","address":"+15550100"}"""))
        assertNull(source.read())
    }

    @Test fun malformedJsonIsNothing() {
        write(PRIOR_SUBSCRIPTIONS_KEY to "[{not json")
        assertNull(source.read())
    }

    @Test fun aNonStringValueIsNothing() {
        write(PRIOR_SUBSCRIPTIONS_KEY to 7, PRIOR_LEGACY_ID_KEY to 9)
        assertNull(source.read())
    }

    @Test fun theOlderLayoutGivesTheIdAlone() {
        write(PRIOR_LEGACY_ID_KEY to "legacy-player")
        assertEquals(PriorInstall(null, "legacy-player"), source.read())
    }

    @Test fun theOlderLayoutGivesTheTokenFromTheSyncedState() {
        write(
            PRIOR_LEGACY_ID_KEY to "legacy-player",
            PRIOR_LEGACY_STATE_KEY to """{"identifier":"fcm-v4","notification_types":1}""",
        )
        assertEquals(PriorInstall("fcm-v4", "legacy-player"), source.read())
    }

    @Test fun theOlderLayoutFillsWhatTheCurrentOneLacks() {
        write(
            subscriptions("""{"id":"local-1","type":"PUSH","address":"fcm-new"}"""),
            PRIOR_LEGACY_ID_KEY to "legacy-player",
            PRIOR_LEGACY_STATE_KEY to """{"identifier":"fcm-v4"}""",
        )
        assertEquals(PriorInstall("fcm-new", "legacy-player"), source.read())
    }

    @Test fun noFileIsNothingAndNoFileIsCreated() {
        assertNull(source.read())
        val file = File(context.applicationInfo.dataDir, "shared_prefs/$PRIOR_PREFS_FILE.xml")
        assertFalse(file.exists())
    }

    @Test fun theJsonOmitsAMissingField() {
        assertEquals("""{"token":"t"}""", PriorInstall("t", null).toJson().toString())
        assertEquals("""{"subscription_id":"s"}""", PriorInstall(null, "s").toJson().toString())
        assertEquals("""{"token":"t","subscription_id":"s"}""", PriorInstall("t", "s").toJson().toString())
    }
}
