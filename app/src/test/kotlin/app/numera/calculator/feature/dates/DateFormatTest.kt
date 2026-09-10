package app.numera.calculator.feature.dates

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the dates screen prints a date, on the one range of dates the localized styles get
 * wrong.
 *
 * The medium styles write the year of era with no era, which is invisible for any date a
 * person would pick and wrong for the ones the Add/subtract tab can reach: subtracting 2024
 * years from 2024 printed "Jun 15, 1", subtracting 2025 printed "Jun 15, 2", and the year on
 * screen counted *up* as the offset grew. No crash, no notice — just a date that is not the
 * one computed.
 */
class DateFormatTest {

    @Test
    fun `a date before year one carries its era`() {
        val yearZero = formatDate(LocalDate.of(0, 6, 15), Locale.US)
        val yearMinusOne = formatDate(LocalDate.of(-1, 6, 15), Locale.US)
        assertEquals("Jun 15, 1 BC", yearZero)
        assertEquals("Jun 15, 2 BC", yearMinusOne)
        // Two different dates must never print the same, which is what happened when year 0
        // and year 1 both came out as "1".
        assertNotEquals(yearZero, formatDate(LocalDate.of(1, 6, 15), Locale.US))
    }

    @Test
    fun `an ordinary date is left exactly as the locale writes it`() {
        assertEquals("Jun 15, 2024", formatDate(LocalDate.of(2024, 6, 15), Locale.US))
        assertEquals("15.06.2024", formatDate(LocalDate.of(2024, 6, 15), Locale.GERMANY))
        // Year 1 itself is in the current era and needs no marker.
        assertFalse(formatDate(LocalDate.of(1, 6, 15), Locale.US).contains("BC"))
    }

    /**
     * The era is localized text, not an English suffix, and the digits still follow the
     * locale — the reason this formatter was split out of the composable in the first place.
     */
    @Test
    fun `the era and the digits both follow the locale`() {
        assertEquals("15.06.1 v. Chr.", formatDate(LocalDate.of(0, 6, 15), Locale.GERMANY))
        val arabic = formatDate(LocalDate.of(2024, 6, 15), Locale.forLanguageTag("ar-EG"))
        assertTrue("expected Arabic-Indic digits in $arabic", arabic.contains('\u0662'))
        assertFalse("expected no ASCII digits in $arabic", arabic.any { it in '0'..'9' })
    }
}
