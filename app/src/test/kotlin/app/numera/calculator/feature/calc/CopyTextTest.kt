package app.numera.calculator.feature.calc

import java.text.DecimalFormatSymbols
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a copy actually puts on the clipboard.
 *
 * Two silent failures live here. In a locale that groups with a point, the ASCII point the
 * formatter writes is read as a thousands separator by every other program on the phone and
 * by this app's own paste handler — `0.333` copied in German came back as the integer 333
 * with nothing to say so. And a value the engine is happy to hold exactly can be longer than
 * a Binder transaction, so the copy of a large factorial was not slow but fatal.
 */
class CopyTextTest {

    private val german: DecimalFormatSymbols = DecimalFormatSymbols(Locale.GERMANY)
    private val english: DecimalFormatSymbols = DecimalFormatSymbols(Locale.US)

    @Test
    fun `the decimal point is written the way the display locale writes it`() {
        assertEquals("0,333", clipboardText("0.333", german.decimalSeparator, 60))
        assertEquals("-12,5", clipboardText("-12.5", german.decimalSeparator, 60))
        assertEquals("0.333", clipboardText("0.333", english.decimalSeparator, 60))
        // Integers carry no point and are untouched in every locale.
        assertEquals("1024", clipboardText("1024", german.decimalSeparator, 60))
    }

    @Test
    fun `a copy survives a trip through another app in a comma-decimal locale`() {
        // The exact expression in the clip extras is stripped by any other app, so the text
        // alone has to read back as the number it was. Before the point was localised,
        // normalizeDigitSeparators deleted it as grouping and the paste was a sixty-digit
        // integer.
        val copied: String = clipboardText("0.333333333333333333", german.decimalSeparator, 60)
        assertEquals("0.333333333333333333", parsePastedText(copied, german)?.display())
    }

    @Test
    fun `a value longer than the clipboard can carry is copied in scientific notation`() {
        val huge = "1" + "0".repeat(MAX_COPY_CHARS + 5)
        val copied: String = clipboardText(huge, english.decimalSeparator, 60)
        assertEquals("1E${MAX_COPY_CHARS + 5}", copied)
        assertTrue(copied.length < 100)
    }

    @Test
    fun `a value that fits is copied whole`() {
        val exact = "1" + "0".repeat(MAX_COPY_CHARS - 1)
        assertEquals(exact, clipboardText(exact, english.decimalSeparator, 60))
    }

    @Test
    fun `the scientific fallback keeps the requested digits, rounded like the display`() {
        // 60 significant digits kept; the 61st decides the rounding, half away from zero.
        val digits = "123456789012345678901234567890123456789012345678901234567891"
        assertEquals(
            "1.23456789012345678901234567890123456789012345678901234567891E70",
            plainScientific(digits + "4" + "0".repeat(10), 60),
        )
        assertEquals(
            "1.23456789012345678901234567890123456789012345678901234567892E70",
            plainScientific(digits + "5" + "0".repeat(10), 60),
        )
        // Trailing zeros of the mantissa are not digits of anything; 10^6 is `1E6`.
        assertEquals("1E6", plainScientific("1000000", 60))
    }

    @Test
    fun `a carry out of the mantissa moves the exponent instead of growing the mantissa`() {
        // 9.99…9 rounding up is 10.0…, which is not a mantissa; it is 1 with the exponent up
        // by one.
        assertEquals("1E4", plainScientific("9999.7", 3))
        assertEquals("-1E4", plainScientific("-9999.7", 3))
    }

    @Test
    fun `a small value's exponent counts the leading zeros`() {
        // An exact 2^−n has a short whole part and a fraction hundreds of thousands of
        // digits long; the magnitude comes from where the first non-zero digit sits.
        assertEquals("1.25E-4", plainScientific("0.000125", 60))
        assertEquals("5E-1", plainScientific("0.5", 60))
        assertEquals("0", plainScientific("0.000", 60))
    }

    @Test
    fun `the point in the fallback is localised too`() {
        val huge = "12" + "0".repeat(MAX_COPY_CHARS + 5)
        assertEquals("1,2E${MAX_COPY_CHARS + 6}", clipboardText(huge, german.decimalSeparator, 60))
    }
}
