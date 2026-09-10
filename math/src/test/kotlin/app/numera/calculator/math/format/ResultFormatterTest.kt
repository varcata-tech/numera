package app.numera.calculator.math.format

import app.numera.calculator.math.BoundedRational
import app.numera.calculator.math.ConstructiveReal
import app.numera.calculator.math.TooMuchMemoryException
import app.numera.calculator.math.UnifiedReal
import java.math.BigInteger
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
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

    @Test
    fun `formatPlain rounds, the line cuts, and they agree on every digit but the last`() {
        // This is what Copy puts on the clipboard. It carries no ellipsis, so it stands in
        // for the whole value and must be the nearest decimal of its width — and it used to
        // be produced by truncating an approximation that is only good to one unit in the
        // last place, which decides the final digit by luck and can land one *above* the
        // true expansion, printing a digit belonging to no rendering of the value.
        // e to seventeen places is 2.71828182845904523|5360…, so the copy rounds up while
        // the line, whose ellipsis promises the 5360… still to come, keeps the 3.
        val plain = ResultFormatter.formatPlain(UnifiedReal.E, 17)
        assertEquals("2.71828182845904524", plain)
        val line = digitsOf(ResultFormatter.formatWithDigits(UnifiedReal.E, 17, Locale.ROOT))
        assertEquals("271828182845904523", line)
        assertEquals(line.dropLast(1), digitsOf(plain).dropLast(1))
    }

    @Test
    fun `a negative that rounds to zero at the copied width keeps its sign`() {
        // toStringRounded drops the sign of a magnitude that rounds to nothing, so the copy
        // of −e^(−200) at sixty places was sixty zeros with no minus: a negative number put
        // on the clipboard as a positive one. The exact path never had the problem, since
        // the sign is part of the text; the opaque path has to go and find it.
        val opaqueTiny = UnifiedReal.of(-(ConstructiveReal.ONE / ConstructiveReal.valueOf(BigInteger.TEN.pow(60))))
        assertEquals("-0." + "0".repeat(50), ResultFormatter.formatPlain(opaqueTiny, 50))
        // And a positive one does not grow a sign it never had.
        val positiveTiny = UnifiedReal.of(ConstructiveReal.ONE / ConstructiveReal.valueOf(BigInteger.TEN.pow(60)))
        assertEquals("0." + "0".repeat(50), ResultFormatter.formatPlain(positiveTiny, 50))
    }

    // ------------------------------------------------------------ digits before an ellipsis

    @Test
    fun `the digits before an ellipsis are a prefix of the expansion`() {
        // An ellipsis promises that the digits continue from where the text stopped, so
        // the digit before it cannot be rounded: 2÷3 used to show 0.66666666666666667…,
        // and the next scroll redrew that 7 as the 6 it always was. The cut also cannot be a
        // bare truncation of the approximation, which is what printed e one low.
        assertEquals("0.66666666666666666…", short(rational(BigInteger.TWO, BigInteger.valueOf(3L))))
        assertEquals("2.71828182845904523…", short(UnifiedReal.E))
        // Scientific notation: 2^64 is 1.8446744073709551616E19, cut after the 5, not
        // rounded up to 6.
        assertEquals("1.84467440737095E19…", short(power(2L, 64)))
        // An exact value cut short by the scroll's digit count is cut the same way:
        // 0.125 to two places is 0.12…, never 0.13….
        assertEquals("0.12", ResultFormatter.formatWithDigits(rational(BigInteger.ONE, BigInteger.valueOf(8L)), 2, Locale.ROOT))
    }

    @Test
    fun `scrolling never changes a digit already on screen`() {
        // Each scroll step asks for more places; every rendering must extend the previous
        // one, or the user watches a digit they were shown change under their finger.
        val values = listOf(
            rational(BigInteger.TWO, BigInteger.valueOf(3L)),
            UnifiedReal.E,
            UnifiedReal.PI,
            UnifiedReal.of(2L).sqrt(),
        )
        for (value in values) {
            var previous = digitsOf(short(value).removeSuffix("…"))
            for (places in listOf(17, 25, 50, 100)) {
                val next = digitsOf(ResultFormatter.formatWithDigits(value, places, Locale.ROOT))
                assertTrue("$value: $previous is not a prefix of $next", next.startsWith(previous))
                previous = next
            }
        }
    }

    @Test
    fun `a negative value whose shown digits are all zero keeps its sign while scrolling`() {
        // 0 − 1 ÷ 10^60 shows −1E−60 and offers more digits. The first scroll asks for
        // fifty places, every one of which is a zero, and the line read 0.000…0… — a
        // negative number displayed as a positive zero expansion until the next scroll
        // reached the 1. The exact path cuts the sign-bearing text; the opaque path has
        // to look for the first non-zero digit to learn the sign.
        val zeros = "0".repeat(50)
        val exactTiny = rational(BigInteger.ONE.negate(), BigInteger.TEN.pow(60))
        assertEquals("−0.$zeros", ResultFormatter.formatWithDigits(exactTiny, 50, Locale.ROOT))
        val opaqueTiny = UnifiedReal.of(-(ConstructiveReal.ONE / ConstructiveReal.valueOf(BigInteger.TEN.pow(60))))
        assertEquals("−0.$zeros", ResultFormatter.formatWithDigits(opaqueTiny, 50, Locale.ROOT))
        // The full expansion, once the scroll reaches it, is still signed and still exact.
        val full = ResultFormatter.formatWithDigits(exactTiny, 100, Locale.ROOT)
        assertEquals("−0." + "0".repeat(59) + "1", full)
    }

    // ------------------------------------------------------------ locale pairing

    @Test
    fun `isExactlyDisplayable answers in the locale it is handed`() {
        // It is called on the same value as formatShort and decides whether the result line
        // offers more digits, and it measures the *grouped* width — which is locale
        // dependent, since grouping is not universally by threes. Reading a different locale
        // than the line was rendered in reports that nothing was dropped on a line that
        // shows an ellipsis, and scrolling for the missing digits then refuses to move.
        val quarter = rational(BigInteger.ONE, BigInteger.valueOf(4L))
        val seventh = rational(BigInteger.ONE, BigInteger.valueOf(7L))
        for (locale in listOf(Locale.ROOT, Locale.GERMANY, Locale.forLanguageTag("ar-EG"))) {
            val exact = ResultFormatter.formatShort(quarter, budget, locale)
            assertTrue("got $exact", ResultFormatter.isExactlyDisplayable(quarter, budget, locale))
            assertFalse("got $exact", exact.contains('…'))

            val truncated = ResultFormatter.formatShort(seventh, budget, locale)
            assertFalse(
                "got $truncated",
                ResultFormatter.isExactlyDisplayable(seventh, budget, locale),
            )
            assertTrue("got $truncated", truncated.contains('…'))
        }
    }

    // ------------------------------------------------------------ totality

    @Test
    fun `a value too deep to expand is reported rather than left to kill the process`() {
        // sin(1)+sin(1)+… a few thousand terms long evaluates in constant stack — each `+`
        // of two unlike irrationals only builds a node — and the tree is first descended,
        // recursively, when the display asks for a digit. The StackOverflowError that raised
        // is not an ArithmeticException, so it passed every catch between the formatter and
        // the process. The chain here is built iteratively so the test itself cannot
        // overflow, and formatted on a thread with a deliberately small stack so the depth
        // needed does not depend on the JVM running the tests.
        val sqrt2 = UnifiedReal.of(2L).sqrt()
        var chain: UnifiedReal = sqrt2 + UnifiedReal.of(3L).sqrt()
        repeat(100_000) { chain = chain + sqrt2 }
        val deep: UnifiedReal = chain

        val outcomes = arrayOfNulls<Any>(4)
        val escaped = AtomicReference<Throwable?>(null)
        val worker = Thread(
            null,
            Runnable {
                outcomes[0] = ResultFormatter.formatShortOrNull(deep, budget, Locale.ROOT)
                outcomes[1] = ResultFormatter.formatWithDigitsOrNull(deep, 50, Locale.ROOT)
                outcomes[2] = try {
                    ResultFormatter.formatShort(deep, budget, Locale.ROOT)
                } catch (e: ArithmeticException) {
                    e
                }
                outcomes[3] = try {
                    ResultFormatter.formatPlain(deep, 50)
                } catch (e: ArithmeticException) {
                    e
                }
            },
            "deep-format",
            256L * 1024,
        )
        worker.setUncaughtExceptionHandler { _, throwable -> escaped.set(throwable) }
        worker.start()
        worker.join()

        assertNull("escaped the formatter: ${escaped.get()}", escaped.get())
        assertNull(outcomes[0])
        assertNull(outcomes[1])
        // The throwing entry points report it as the engine's own failure, which is the
        // one every caller in the view model already maps to a message.
        assertTrue("got ${outcomes[2]}", outcomes[2] is TooMuchMemoryException)
        assertTrue("got ${outcomes[3]}", outcomes[3] is TooMuchMemoryException)
    }

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
