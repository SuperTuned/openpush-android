package ai.openpush.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The same rule as the server's normalisation; each case here is one it decides the same way. */
class PhoneNumberFormatTests {
    @Test fun anE164NumberIsKeptAsWritten() {
        assertEquals("+14155550123", PhoneNumberFormat.normalized("+14155550123"))
    }

    @Test fun separatorsAndWhitespaceAreRemoved() {
        assertEquals("+14155550123", PhoneNumberFormat.normalized("+1 (415) 555-0123"))
        assertEquals("+14155550123", PhoneNumberFormat.normalized("+1-415-555-0123"))
        assertEquals("+14155550123", PhoneNumberFormat.normalized("+1.415.555.0123"))
        assertEquals("+447700900123", PhoneNumberFormat.normalized("  +44 7700 900123\n"))
    }

    @Test fun anInternational00PrefixIsReadAsPlus() {
        assertEquals("+14155550123", PhoneNumberFormat.normalized("0014155550123"))
        assertEquals("+919876543210", PhoneNumberFormat.normalized(" 00 91 98765 43210 "))
    }

    @Test fun eightAndFifteenDigitsAreTheBounds() {
        assertEquals("+12345678", PhoneNumberFormat.normalized("+12345678"))
        assertEquals("+123456789012345", PhoneNumberFormat.normalized("+123456789012345"))
        assertNull(PhoneNumberFormat.normalized("+1234567"))
        assertNull(PhoneNumberFormat.normalized("+1234567890123456"))
    }

    @Test fun aSecondPlusFails() {
        assertNull(PhoneNumberFormat.normalized("++14155550123"))
        assertNull(PhoneNumberFormat.normalized("+1415+5550123"))
        assertNull(PhoneNumberFormat.normalized("00+14155550123"))
    }

    @Test fun theFirstDigitAfterPlusMustNotBeZero() {
        assertNull(PhoneNumberFormat.normalized("+0123456789"))
        assertNull(PhoneNumberFormat.normalized("000123456789"))
    }

    @Test fun aNumberWithoutPlusOr00IsRefused() {
        listOf("", "   ", "415", "4155550123", "14155550123", "(415) 555-0123", "0 14155550123")
            .forEach { assertNull("'$it' should be refused", PhoneNumberFormat.normalized(it)) }
    }

    @Test fun lettersAreRemovedLikeAnyOtherSeparator() {
        assertEquals("+14155550123", PhoneNumberFormat.normalized("tel:+1 415 555 0123"))
    }

    @Test fun requireThrowsTheTypedInvalidNumberError() {
        val error = assertThrows(OpenPushVerifyError::class.java) { PhoneNumberFormat.require("555-0123") }
        assertEquals(OpenPushVerifyError.Code.INVALID_NUMBER, error.code)
        assertEquals(PhoneNumberFormat.INVALID_NUMBER_MESSAGE, error.message)
        assertNull(error.status)
    }

    @Test fun maskingHidesTheMiddleDigits() {
        assertEquals("+1415•••0123", PhoneNumberFormat.masked("+14155550123"))
    }
}
