package app.numera.calculator.math.expr

import app.numera.calculator.math.AngleMode
import app.numera.calculator.math.BoundedRational
import app.numera.calculator.math.UnifiedReal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What may become an expression, and what must be refused.
 *
 * Every case here is one where the old behaviour produced an *answer* rather than a
 * refusal. That is the failure worth testing for: a rejected paste is visible and
 * recoverable, whereas `1,234` quietly read as `1 × 234` is a plausible wrong number with
 * nothing on screen to reveal it.
 */
class CalculatorExprTest {

    private fun rationalOf(expr: CalculatorExpr): BoundedRational {
        val result = ExprEvaluator.evaluate(expr, AngleMode.DEGREES)
        assertTrue("expected success but got $result", result is EvalResult.Success)
        val value = (result as EvalResult.Success).value
        assertTrue("not rational: ${value.toNiceString()}", value.isRational)
        return value.asRational()!!
    }

    // ------------------------------------------------------------ separators

    @Test
    fun `a number split by a separator is refused rather than read as a product`() {
        // Every one of these characters is skipped as insignificant, and every one of them
        // is a digit-grouping or decimal separator in a locale Numera ships in. Joining
        // them is not possible: "1,234" is one thousand two hundred and thirty-four in en
        // and one point two three four in de, and nothing in the string says which.
        assertNull(CalculatorExpr.fromText("1,234"))
        assertNull(CalculatorExpr.fromText("1.234,56"))
        assertNull(CalculatorExpr.fromText("1 234"))
        assertNull(CalculatorExpr.fromText("1\u00A0234"))
        assertNull(CalculatorExpr.fromText("1\u202F234"))
        assertNull(CalculatorExpr.fromText("1,5"))
        assertNull(CalculatorExpr.fromText("1_000"))
    }

    @Test
    fun `whitespace between an operator and its operands is still insignificant`() {
        // The refusal above must not cost the ordinary case: "12 + 34" is one expression.
        val spaced = CalculatorExpr.fromText("12 + 34")
        assertNotNull(spaced)
        assertEquals(BoundedRational.of(46L), rationalOf(spaced!!))

        val tight = CalculatorExpr.fromText("2 × ( 3 + 4 )")
        assertNotNull(tight)
        assertEquals(BoundedRational.of(14L), rationalOf(tight!!))
    }

    // ------------------------------------------------------------ pasted depth and size

    @Test
    fun `a deep run of radicals is refused instead of overflowing the stack`() {
        // The depth counter used to live only in parseExpr, which the √ cycle never passes
        // through, so a clipboard full of radicals was bounded only by the JVM stack — and
        // fromText caught Exception, which a StackOverflowError is not.
        assertNull(CalculatorExpr.fromText("√".repeat(5_000) + "1"))

        // A run any human could produce still evaluates.
        val shallow = CalculatorExpr.fromText("√".repeat(50) + "1")
        assertNotNull(shallow)
        assertEquals(BoundedRational.ONE, rationalOf(shallow!!))
    }

    @Test
    fun `nesting a human could type still parses`() {
        // The depth budget is now spent twice per group, so this is the case that proves
        // the budget was raised to match rather than quietly halving what is accepted.
        val nested = CalculatorExpr.fromText("(".repeat(150) + "7" + ")".repeat(150))
        assertNotNull(nested)
        assertEquals(BoundedRational.of(7L), rationalOf(nested!!))
    }

    @Test
    fun `an absurd number of tokens is refused before it is parsed`() {
        assertNull(CalculatorExpr.fromText("1" + "+1".repeat(10_000)))
    }

    // ------------------------------------------------------------ dropped keystrokes

    @Test
    fun `a keystroke that cannot become a token reports itself as no edit at all`() {
        // append quietly returns the same expression for a key it refuses. A caller that
        // clears the display first and appends second cannot tell that apart from a real
        // edit: pressing ")" with a result on screen wiped the answer and left the whole
        // display blank. accepts is how that caller finds out before it clears anything.
        val empty = CalculatorExpr()
        assertFalse(empty.accepts(KeyId.RIGHT_PAREN))
        assertTrue(empty.accepts(KeyId.D1))
        assertTrue(empty.accepts(KeyId.POINT))
        assertTrue(empty.accepts(KeyId.SIN))
        assertTrue(empty.accepts(KeyId.PI))

        // Once there is something to close, the same key is an edit again.
        val open = empty.append(KeyId.LEFT_PAREN).append(KeyId.D1)
        assertTrue(open.accepts(KeyId.RIGHT_PAREN))

        // The other two keys append drops: a second point, and a digit past the cap.
        val point = empty.append(KeyId.D1).append(KeyId.POINT)
        assertFalse(point.accepts(KeyId.POINT))
        var long = empty
        // Well past the cap, so this does not have to name the constant to reach it.
        repeat(200) { long = long.append(KeyId.D9) }
        assertFalse(long.accepts(KeyId.D9))
    }

    // ------------------------------------------------------------ seeding from a result

    @Test
    fun `a negative seed is parenthesised so a following square binds to the whole value`() {
        // Seeded as a bare −5, the ² binds tighter than the minus in parseFactor and the
        // answer is −25: the calculator squaring something other than what it displayed.
        val seed = CalculatorExpr.seedFromDecimal("-5")
        assertNotNull(seed)
        assertEquals("(−5)", seed!!.display())
        assertEquals(BoundedRational.of(25L), rationalOf(seed.append(KeyId.SQUARE)))
    }

    @Test
    fun `a seed may be far longer than any literal the keypad accepts`() {
        // 100! is a 158-digit exact integer. The 64-character cap exists to refuse paste
        // accidents; applying it to a generated literal dropped the value silently and left
        // the next keystroke to be answered on its own.
        val factorial = UnifiedReal.of(100L).factorial()
        val text = factorial.exactDecimalOrNull()
        assertNotNull(text)
        assertTrue("expected a long literal, got ${text!!.length}", text.length > 64)

        val seed = CalculatorExpr.seedFromDecimal(text)
        assertNotNull(seed)
        assertEquals(text, seed!!.display())

        val continued = rationalOf(seed.append(KeyId.ADD).append(KeyId.D1))
        assertEquals((factorial.asRational()!! + BoundedRational.ONE), continued)
    }

    @Test
    fun `a seed too long for the codec is compacted rather than corrupted`() {
        // A number token carries a single length byte, so a 301-character literal would be
        // written with a wrapped length and read back as a different number. Moving the
        // uninformative zeros into an exponent is exact.
        val seed = CalculatorExpr.seedFromDecimal("1" + "0".repeat(300))
        assertNotNull(seed)
        assertEquals(listOf<Token>(Token.Number("1E300")), seed!!.tokens)

        val restored = ExprCodec.decode(ExprCodec.encode(seed))
        assertNotNull(restored)
        assertEquals(seed.tokens, restored!!.tokens)
    }

    @Test
    fun `a seed that is not a plain decimal is refused rather than silently emptied`() {
        // Returning an empty expression here is what turns a lost value into a wrong
        // answer: the operator the user pressed next gets applied to nothing.
        assertNull(CalculatorExpr.seedFromDecimal(""))
        assertNull(CalculatorExpr.seedFromDecimal("1,745.13"))
        assertNull(CalculatorExpr.seedFromDecimal("1e5"))
        assertNull(CalculatorExpr.seedFromDecimal("nan"))
        assertNull(CalculatorExpr.seedFromDecimal("--5"))
    }
}
