package ai.openpush.android.inappmessages

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ISO-8601 `end_time` values read the same below API 26 (no java.time) as above it. */
@RunWith(RobolectricTestRunner::class)
internal class IamEndTimeApi24Tests {
    private val cases = mapOf(
        "2026-09-29T08:00:00Z" to 1_790_668_800_000L,
        "2026-09-29T08:00:00.5Z" to 1_790_668_800_500L,
        "2026-09-29T08:00:00.123456789Z" to 1_790_668_800_123L,
        "2026-09-29T10:00:00+02:00" to 1_790_668_800_000L,
        "2026-09-29T08:00:00" to 1_790_668_800_000L,
        "1790668800" to 1_790_668_800_000L,
    )

    private fun endTime(value: String): Long? =
        IamEngine().parse(JSONObject().put("id", "m").put("end_time", value))?.endTimeMs

    private fun assertAllCases() {
        cases.forEach { (text, expected) -> assertEquals(text, expected, endTime(text)) }
        assertNull(endTime("next tuesday"))
    }

    @Test
    @Config(sdk = [24])
    fun readsIsoEndTimesOnApi24() = assertAllCases()

    @Test
    @Config(sdk = [34])
    fun readsIsoEndTimesOnApi34() = assertAllCases()
}
