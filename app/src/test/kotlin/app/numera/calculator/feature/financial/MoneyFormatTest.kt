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
        val kept = sanitiseAmount(pasted, decimal = true)
        assertEquals(MAX_AMOUNT_LENGTH, kept.length)
        // Cut, not rejected: the field keeps the leading digits rather than emptying itself
        // under the user, which would read as the paste having failed.
        assertEquals("9".repeat(MAX_AMOUNT_LENGTH), kept)
    }

    @Test
    fun `an amount a person could type is left alone`() {
        assertEquals("1000000", sanitiseAmount("1000000", decimal = true))
        assertEquals("8.5", sanitiseAmount("8.5", decimal = true))
    }

    @Test
    fun `a second decimal point is refused and a count field takes none at all`() {
        // "1.2.3" parses as nothing, and blanks the result card rather than saying why.
        assertEquals("1.23", sanitiseAmount("1.2.3", decimal = true))
        assertEquals("123", sanitiseAmount("1.2.3", decimal = false))
        assertEquals("240", sanitiseAmount("2 4 0 months", decimal = false))
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
}
