package app.numera.calculator.feature.graphing

import java.text.DecimalFormatSymbols
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * How a graph coordinate is written for the reader in front of it.
 *
 * The padding is the whole point. Every readout on the screen is formatted for the current
 * locale and then trimmed, and a trim written against ASCII quietly does nothing in a locale
 * whose numbering system is not ASCII — so the digits are right and the presentation is
 * wrong, in exactly the one locale nobody testing in English would ever look at.
 */
class GraphReadoutTest {

    /** The default window, which is what most of these coordinates are read against. */
    private val defaultSpan = 20.0

    @Test
    fun `a round coordinate loses its padding`() {
        assertEquals("2", 2.0.pretty(Locale.US, defaultSpan))
        // A fraction that is worth showing keeps every digit it needs and nothing more.
        assertEquals("0.25", 0.25.pretty(Locale.US, defaultSpan))
    }

    @Test
    fun `a comma decimal separator is trimmed as well as a point`() {
        // de, fr, es, it, pt-BR and ru all print a comma here, so an ASCII-point-only trim
        // would leave half the shipped locales showing "2,0000".
        assertEquals("2", 2.0.pretty(Locale.GERMANY, defaultSpan))
        assertEquals("0,25", 0.25.pretty(Locale.GERMANY, defaultSpan))
    }

    @Test
    fun `a locale with its own digits is trimmed too`() {
        // ar formats through Arabic-Indic digits (U+0660 upward) with U+066B as the
        // separator, so `trimEnd('0').trimEnd('.', ',')` matched nothing at all and every
        // graphing readout in Arabic kept its padding: "x = ٢٫٠٠٠٠" at the default zoom, and
        // up to fourteen trailing zeros where the deepest zoom asks for more places.
        //
        // Asserted against the platform's own symbols rather than against literal Arabic
        // digits: the point is that the trim follows whatever numbering system the locale
        // supplies, on whichever JDK or Android release is underneath.
        val arabic: Locale = Locale.forLanguageTag("ar-EG")
        val symbols: DecimalFormatSymbols = DecimalFormatSymbols.getInstance(arabic)
        val text: String = 2.0.pretty(arabic, defaultSpan)
        assertFalse("padding survived in ar: $text", text.contains(symbols.decimalSeparator))
        assertFalse("padding survived in ar: $text", text.endsWith(symbols.zeroDigit))
        assertEquals(String.format(arabic, "%.0f", 2.0), text)
        // A fraction still survives, separator and all.
        val quarter: String = 0.25.pretty(arabic, defaultSpan)
        assertEquals(String.format(arabic, "%.2f", 0.25), quarter)
    }

    @Test
    fun `a whole power of ten keeps the zeros that carry its magnitude`() {
        // The separator is what stops the trim: without it, ten thousand million would be
        // trimmed all the way down to one.
        val large = 1e10
        assertEquals("10000000000", large.pretty(Locale.US, defaultSpan))
        val germanLarge: String = large.pretty(Locale.GERMANY, defaultSpan)
        assertEquals("10000000000", germanLarge)
    }

    @Test
    fun `a deep zoom still tells two neighbouring coordinates apart`() {
        // The count of decimals follows the span. Fixed at four places both of these print as
        // a flat "1", and two distinct roots read as one number.
        val span = 1e-8
        assertNotEquals(
            1.000000001.pretty(Locale.US, span),
            1.000000002.pretty(Locale.US, span),
        )
    }
}
