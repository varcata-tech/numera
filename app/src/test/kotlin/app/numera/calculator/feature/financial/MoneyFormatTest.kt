package app.numera.calculator.feature.financial

import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the financial screen accepts from a field and what it writes back out.
 *
 * Both halves fail invisibly in en-US. The formatting bug only appears in a locale that does
 * not use an ASCII point, and the input bug only appears on a paste, which is why neither
 * survived being tried out by hand.
 */
class MoneyFormatTest {

    @Test
    fun `a pasted amount is cut to a length the schedule can format`() {
        val pasted = "9".repeat(5_000)
        val kept = sanitiseAmount(pasted, decimal = true, decimalSeparator = '.')
        assertEquals(MAX_AMOUNT_LENGTH, kept.length)
        // Cut, not rejected: the field keeps the leading digits rather than emptying itself
        // under the user, which would read as the paste having failed.
        assertEquals("9".repeat(MAX_AMOUNT_LENGTH), kept)
    }

    @Test
    fun `an amount a person could type is left alone`() {
        assertEquals("1000000", sanitiseAmount("1000000", decimal = true, decimalSeparator = '.'))
        assertEquals("8.5", sanitiseAmount("8.5", decimal = true, decimalSeparator = '.'))
    }

    @Test
    fun `a second decimal point is refused and a count field takes none at all`() {
        // "1.2.3" parses as nothing, and blanks the result card rather than saying why.
        assertEquals("1.23", sanitiseAmount("1.2.3", decimal = true, decimalSeparator = '.'))
        assertEquals("123", sanitiseAmount("1.2.3", decimal = false, decimalSeparator = '.'))
        assertEquals("240", sanitiseAmount("2 4 0 months", decimal = false, decimalSeparator = '.'))
    }

    @Test
    fun `an amount and a percentage use the same conventions in one locale`() {
        val amount = formatAmount(BigDecimal("28.00"), Locale.FRANCE)
        val percent = formatPercent(BigDecimal("28.00"), Locale.FRANCE)
        assertEquals("28,00", amount)
        // The old rendering put BigDecimal.toString's "28.00%" beside that comma.
        assertTrue("expected a decimal comma in $percent", percent.contains("28,00"))
    }

    @Test
    fun `a percentage out of a hundred is not multiplied by a hundred again`() {
        // 28% must not become 2800%: NumberFormat's percent instance scales by 100 itself.
        assertEquals("28.00%", formatPercent(BigDecimal("28.00"), Locale.US))
        assertEquals("0.00%", formatPercent(BigDecimal.ZERO, Locale.US))
    }

    @Test
    fun `a decimal comma is a decimal point in the locales that use one`() {
        // Observed on the emulator in de-DE: KeyboardType.Decimal offers a comma, the filter
        // dropped it, and an 8,5% rate silently became 85% on the loan card.
        assertEquals("8,5", sanitiseAmount("8,5", decimal = true, decimalSeparator = ','))
        assertEquals(BigDecimal("8.5"), parseAmount("8,5", ','))
    }

    @Test
    fun `a figure the screen printed can be typed back into it`() {
        // formatAmount writes "1.234,56" in de; the field has to survive being handed that
        // back, or the app disagrees with its own output.
        val printed = formatAmount(BigDecimal("1234.56"), Locale.GERMANY)
        assertEquals("1.234,56", printed)
        val kept = sanitiseAmount(printed, decimal = true, decimalSeparator = ',')
        // The grouping '.' goes, the decimal ',' stays — not a mangled "1,23456".
        assertEquals("1234,56", kept)
        assertEquals(BigDecimal("1234.56"), parseAmount(kept, ','))
    }

    @Test
    fun `a non-ASCII digit set parses to the same number`() {
        // Arabic-Indic digits reach the field from the ar keyboard, and BigDecimal takes
        // exactly one spelling.
        assertEquals(BigDecimal("8.5"), parseAmount("\u0668\u066b\u0665", '\u066b'))
    }

    @Test
    fun `a field that is only a separator is not yet a number`() {
        // Passed through on the way to "0,5"; must read as incomplete, never as an error.
        assertEquals(null, parseAmount(",", ','))
        assertEquals(null, parseAmount("", ','))
        assertEquals(null, parseAmount("12x", ','))
    }

    @Test
    fun `a seeded default is spelled in the locale that has to read it back`() {
        // The loan tab opened on "enter a valid number in each field" in every
        // comma-decimal locale, untouched, because the seed "8.5" is a display string and
        // the parser is locale-aware. Seed and parser have to agree.
        val german = seedAmount("8.5", ',')
        assertEquals("8,5", german)
        assertEquals(BigDecimal("8.5"), parseAmount(german, ','))
        // ...and appending a digit must extend it, not silently delete the separator.
        assertEquals("8,55", sanitiseAmount(german + "5", decimal = true, decimalSeparator = ','))
        // An en seed is left exactly as written.
        assertEquals("8.5", seedAmount("8.5", '.'))
    }

    @Test
    fun `a value saved under another locale still reads as a number`() {
        // Switching the app language leaves whatever was typed before in the field. A
        // stored '.' can only ever be a decimal point — sanitiseAmount drops grouping
        // separators — so it must parse rather than blank the card.
        assertEquals(BigDecimal("8.5"), parseAmount("8.5", ','))
        assertEquals(BigDecimal("8.5"), parseAmount("8,5", ','))
        // Two decimal marks are still nonsense, whichever spelling they use.
        assertEquals(null, parseAmount("1.2,3", ','))
        assertEquals(null, parseAmount("1.2.3", ','))
    }
}
