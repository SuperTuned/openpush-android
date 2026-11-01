package ai.openpush.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsoTimestampTests {
    // 2026-10-23T10:15:30Z
    private val base = 1_792_750_530_000L

    @Test fun readsUtcWithAndWithoutAFraction() {
        assertEquals(base, IsoTimestamp.parseMillis("2026-10-23T10:15:30Z"))
        assertEquals(base + 123, IsoTimestamp.parseMillis("2026-10-23T10:15:30.123456+00:00"))
        assertEquals(base + 500, IsoTimestamp.parseMillis("2026-10-23T10:15:30.5Z"))
    }

    @Test fun appliesTheOffset() {
        assertEquals(base, IsoTimestamp.parseMillis("2026-10-23T15:45:30+05:30"))
        assertEquals(base, IsoTimestamp.parseMillis("2026-10-23T05:15:30-0500"))
    }

    @Test fun aTimestampWithoutAZoneIsUtc() {
        assertEquals(base, IsoTimestamp.parseMillis("2026-10-23T10:15:30"))
    }

    @Test fun anythingElseIsNull() {
        assertNull(IsoTimestamp.parseMillis(null))
        assertNull(IsoTimestamp.parseMillis(""))
        assertNull(IsoTimestamp.parseMillis("tomorrow"))
        assertNull(IsoTimestamp.parseMillis("2026-13-45T99:99:99Z"))
    }
}
