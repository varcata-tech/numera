package app.numera.calculator.feature.calc

import java.text.DecimalFormatSymbols
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the app is willing to accept from the clipboard.
 *
 * The case that matters most produces no error at all. The tokeniser treats a grouping
 * separator as ignorable whitespace, so `1,234` becomes two adjacent number tokens, and
 * adjacent number tokens are implicit multiplication: the formula line reads `1234` and the
 * answer is 234. Everything here is aimed at that class of silently wrong paste.
 */
class PastedTextTest {

    private val english: DecimalFormatSymbols = DecimalFormatSymbols(Locale.US)
    private val german: DecimalFormatSymbols = DecimalFormatSymbols(Locale.GERMANY)

    @Test
    fun `a grouped number pastes as one number, not as a product of its groups`() {
        // 1,234,567 was becoming 1 × 234 × 567.
        assertEquals("1234567", parsePastedText("1,234,567", english)?.display())
        assertEquals("1234.5", parsePastedText("1,234.5", english)?.display())
    }

    @Test
    fun `a locale that groups with a point and separates with a comma is read its way`() {
        // In de-DE, 1.234,5 is one thousand two hundred and thirty-four and a half. Testing
        // grouping before the decimal separator would delete the point and keep the comma,
        // which is exactly backwards.
        assertEquals("1234.5", parsePastedText("1.234,5", german)?.display())
        assertEquals("0.5", parsePastedText("0,5", german)?.display())
    }

    @Test
    fun `spaces and underscores between digits group too`() {
        // The tokeniser ignores all of these, so `1 000 000` was evaluating to zero.
        val group: Char = DecimalFormatSymbols(Locale.FRANCE).groupingSeparator
        assertEquals("1000000", parsePastedText("1${group}000${group}000", english)?.display())
        assertEquals("1000000", parsePastedText("1 000 000", english)?.display())
        assertEquals("1000000", parsePastedText("1_000_000", english)?.display())
        assertEquals("1000000", parsePastedText("1'000'000", english)?.display())
    }

    @Test
    fun `a separator that is not between two digits is left alone`() {
        // Only a character with a digit on each side can be part of a number; rewriting any
        // other would change what the pasted text means rather than preserve it.
        assertEquals("1+2", parsePastedText("1, +2", english)?.display())
        assertEquals("1×2", parsePastedText("1 × 2", english)?.display())
    }

    @Test
    fun `a locale's own digits are accepted`() {
        // Set explicitly rather than taken from a locale, because which numbering system a
        // JDK hands back for ar depends on its CLDR data.
        val arabic = DecimalFormatSymbols(Locale.US).apply {
            zeroDigit = '٠'
            groupingSeparator = '٬'
            decimalSeparator = '٫'
        }
        // ٧٥٫٥ — seventy-five and a half. Without the mapping this is not a number at all
        // and the paste silently does nothing, including for text this app itself copied.
        assertEquals("75.5", parsePastedText("٧٥٫٥", arabic)?.display())
    }

    @Test
    fun `an expression too large to survive process death is refused rather than shown`() {
        // ExprCodec encodes any size but refuses to decode past MAX_EXPRESSION_TOKENS, so
        // anything larger would display, persist, and come back as an empty screen.
        val accepted = "1" + "+1".repeat((MAX_EXPRESSION_TOKENS - 3) / 2)
        assertNotNull(parsePastedText(accepted, english))

        val refused = "1" + "+1".repeat(MAX_EXPRESSION_TOKENS)
        assertNull(parsePastedText(refused, english))
    }

    @Test
    fun `an expression that is small in tokens but huge in characters is refused too`() {
        // The token count is not a size bound. Three hundred tokens of sixty-four digits each
        // sail past MAX_EXPRESSION_TOKENS while being twenty thousand characters, which
        // ExprCodec re-encodes and Base64s into the saved-state bundle on *every* subsequent
        // key press — and that bundle crosses a Binder transaction whose size limit kills the
        // process rather than dropping the state.
        val wide = "1".repeat(64)
        val accepted = List(MAX_EXPRESSION_LITERAL_CHARS / 64) { wide }.joinToString("+")
        assertNotNull(parsePastedText(accepted, english))

        val refused = List(MAX_EXPRESSION_LITERAL_CHARS / 64 + 1) { wide }.joinToString("+")
        // Far below the token ceiling, so only the character bound can refuse it.
        assertNull(parsePastedText(refused, english))
    }

    @Test
    fun `text that is not an expression is still refused`() {
        assertNull(parsePastedText("hello", english))
        assertNull(parsePastedText("", english))
    }
}
