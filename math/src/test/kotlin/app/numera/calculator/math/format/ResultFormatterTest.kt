package app.numera.calculator.math.format

import app.numera.calculator.math.BoundedRational
import app.numera.calculator.math.UnifiedReal
import java.math.BigInteger
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last step, where the exact engine finally has to tell the truth about itself.
 *
 * Everything the layers underneath guarantee is worth nothing if the display cannot
 * distinguish a complete answer from a truncated one. These tests are all about that single
 * distinction: an exact value prints every digit it has and no `…`, and a value that lost
 * digits says so — including in scientific notation, which is where the marker used to be
 * dropped silently.
 *
 * `Locale.ROOT` throughout, so a test machine's locale cannot change what is asserted.
 */
class ResultFormatterTest {

    /** The result line's real width, so the cases match what a user actually sees. */
    private val budget = 20

    private fun short(value: UnifiedReal): String =
        ResultFormatter.formatShort(value, budget, Locale.ROOT)

    private fun rational(num: BigInteger, den: BigInteger = BigInteger.ONE): UnifiedReal =
        UnifiedReal.of(BoundedRational.of(num, den))

    private fun power(base: Long, exponent: Int): UnifiedReal =
        rational(BigInteger.valueOf(base).pow(exponent))

    /** Only the digits, so an assertion does not depend on grouping conventions. */
    private fun digitsOf(text: String): String = text.filter { it in '0'..'9' }

    // ------------------------------------------------------------ the ellipsis rule

    @Test
    fun `a value that fits exactly carries no ellipsis and one that does not carries one`() {
        assertEquals("0.25", short(rational(BigInteger.ONE, BigInteger.valueOf(4L))))

        val seventh = short(rational(BigInteger.ONE, BigInteger.valueOf(7L)))
        assertTrue("got $seventh", seventh.startsWith("0.1428571428571428"))
        assertTrue("got $seventh", seventh.endsWith("…"))
        assertTrue("got $seventh", seventh.length <= budget)
    }

    @Test
    fun `scientific notation says when it dropped digits`() {
        // 2^100 is 1267650600228229401496703205376. Fifteen of its digits do not fit, and
        // the display used to drop them with no marker at all — indistinguishable from an
        // exact answer of the same magnitude.
        val text = short(power(2L, 100))
        assertTrue("got $text", text.startsWith("1.2676506002282"))
        assertTrue("got $text", text.endsWith("E30…"))
        assertTrue("got $text", text.length <= budget)
    }

    @Test
    fun `an exact power of ten is neither padded out nor marked as truncated`() {
        // The exactness test used to be arithmetically impossible to satisfy for a positive
        // exponent, so this printed 1.000000000000000E30 — fifteen zeros that exist only
        // because that is where truncation would have landed, on a number never truncated.
        assertEquals("1E30", short(power(10L, 30)))
    }

    @Test
    fun `an exact value just too wide for fixed-point is not marked as truncated`() {
        // 10^19's exponent is under the budget, so the fixed-point branch is chosen — and
        // then its 26 grouped characters do not fit, so it falls through to scientific. That
        // fallback used to drop the significant-digit count on the way, which is the only
        // evidence that the mantissa's zeros are real digits: the answer printed as
        // 1.00000000000000E19…, an ellipsis on a number that has one significant digit,
        // while 10^20 — one keystroke away, and taking the scientific branch directly —
        // printed 1E20.
        assertEquals("1E19", short(power(10L, 19)))
        assertEquals("1E20", short(power(10L, 20)))
    }

    @Test
    fun `a truncated value in that same fallback still carries its ellipsis`() {
        // The other half of the pair: 2^64 shares 10^19's exponent and takes the identical
        // path, but has twenty significant digits and genuinely loses six of them. Fixing
        // the false ellipsis must not have removed the true one.
        val text = short(power(2L, 64))
        // One digit short of the mantissa's full width: the last place of a truncation is
        // where an approximation is allowed to differ by one, and that is not what is
        // under test here.
        assertTrue("got $text", text.startsWith("1.8446744073709"))
        assertTrue("got $text", text.endsWith("E19…"))
        assertTrue("got $text", text.length <= budget)
    }

    @Test
    fun `an exact and a truncated value of the same magnitude cannot be confused`() {
        // The whole point of the exact engine, at the one place it is finally visible.
        assertNotEquals(short(power(10L, 30)), short(power(2L, 100)))
    }

    @Test
    fun `a small exact value keeps its own mantissa digits`() {
        val one = short(rational(BigInteger.ONE, BigInteger.TEN.pow(9)))
        assertEquals("1E−9", one)

        val oneAndAHalf = short(rational(BigInteger.valueOf(15L), BigInteger.TEN.pow(10)))
        assertEquals("1.5E−9", oneAndAHalf)
    }

    @Test
    fun `a factorial too wide for the line is truncated visibly`() {
        // 100! is 158 digits; only the first few can be shown, and the user has to be able
        // to tell that from a value that ended where it appears to end.
        val text = short(UnifiedReal.of(100L).factorial())
        assertTrue("got $text", text.endsWith("E157…"))
        assertTrue("got $text", text.startsWith("9.332621544394"))
    }

    // ------------------------------------------------------------ the zero rule

    @Test
    fun `zero is the only value printed as a bare zero`() {
        assertEquals("0", short(UnifiedReal.ZERO))
    }

    @Test
    fun `a value far below the digit-search horizon is not claimed to be zero`() {
        // 10^-3000 is five keystrokes and stays exact — under MAX_RATIONAL_BITS — but the
        // magnitude search gives up after 2048 places, every one of which is a zero. Taking
        // its answer printed "0": an unmarked, wrong, apparently-exact result, which is the
        // one thing this class exists to prevent.
        assertEquals("1E−3000", short(rational(BigInteger.ONE, BigInteger.TEN.pow(3000))))
    }

    // ------------------------------------------------------------ scrolling

    @Test
    fun `scrolling an exact integer does not manufacture trailing zeros`() {
        // toStringTruncated pads to whatever width it is asked for, so dragging the result
        // line used to append fifty zeros to 2^100 — then a hundred, then two hundred, as
        // the request doubles — none of which are digits of anything.
        val text = ResultFormatter.formatWithDigits(power(2L, 100), 50, Locale.ROOT)
        assertFalse("got $text", text.contains('.'))
        assertEquals("1267650600228229401496703205376", digitsOf(text))
    }

    @Test
    fun `scrolling a non-terminating value still yields every digit asked for`() {
        val text = ResultFormatter.formatWithDigits(
            rational(BigInteger.ONE, BigInteger.valueOf(7L)),
            30,
            Locale.ROOT,
        )
        assertEquals(30, text.substringAfter('.').length)
        assertTrue("got $text", text.startsWith("0.142857142857"))
    }

    // ------------------------------------------------------------ machine-readable form

    @Test
    fun `formatPlain groups nothing, so the value can be read back as one number`() {
        // Every other entry point groups, including under Locale.ROOT, and a grouped
        // "1,745.13" is not a number any tokenizer will take back — it is two of them.
        val value = UnifiedReal.of(1234L) * UnifiedReal.of(2L).sqrt()
        val plain = ResultFormatter.formatPlain(value, 40)
        assertFalse("got $plain", plain.contains(','))
        assertTrue("got $plain", plain.startsWith("1745."))
    }

    // ------------------------------------------------------------ totality

    @Test
    fun `a cancelled computation is reported rather than thrown at the caller`() {
        // A UnifiedReal is a lazy tree, so the series only run when the display asks for
        // digits: an interrupt lands inside the formatter, not inside the evaluator. On a
        // coroutine an escaping AbortedException is an uncaught exception on viewModelScope,
        // which ends the process rather than the job.
        val value = UnifiedReal.of(2L).sqrt()
        try {
            Thread.currentThread().interrupt()
            assertNull(ResultFormatter.formatShortOrNull(value, budget, Locale.ROOT))
        } finally {
            // Clears the flag so the interrupt cannot leak into the next test on this thread.
            Thread.interrupted()
        }
    }
}
