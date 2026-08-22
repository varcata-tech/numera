package app.numera.calculator.math.expr

import app.numera.calculator.math.AngleMode
import app.numera.calculator.math.BoundedRational
import app.numera.calculator.math.UnifiedReal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end checks from keystrokes to value.
 *
 * The engine's own tests prove the arithmetic; these prove that what the keypad produces
 * actually reaches it intact. A precedence or percent bug here would give a wrong answer
 * with a perfectly exact engine underneath it, which is the harder kind of bug to see.
 */
class ExprEvaluatorTest {

    private fun expr(vararg keys: KeyId): CalculatorExpr =
        keys.fold(CalculatorExpr()) { acc, key -> acc.append(key) }

    private fun eval(expr: CalculatorExpr, mode: AngleMode = AngleMode.DEGREES): EvalResult =
        ExprEvaluator.evaluate(expr, mode)

    private fun value(expr: CalculatorExpr, mode: AngleMode = AngleMode.DEGREES): UnifiedReal {
        val result = eval(expr, mode)
        assertTrue("expected success but got $result", result is EvalResult.Success)
        return (result as EvalResult.Success).value
    }

    private fun assertRational(expected: BoundedRational, expr: CalculatorExpr, label: String) {
        val actual = value(expr)
        assertTrue("$label: not rational, got ${actual.toNiceString()}", actual.isRational)
        assertEquals(label, expected, actual.asRational())
    }

    private fun error(expr: CalculatorExpr, mode: AngleMode = AngleMode.DEGREES): EvalError {
        val result = eval(expr, mode)
        assertTrue("expected failure but got $result", result is EvalResult.Failure)
        return (result as EvalResult.Failure).error
    }

    // ------------------------------------------------------------ exactness end to end

    @Test
    fun `one divided by three times three is exactly one`() {
        assertRational(
            BoundedRational.ONE,
            expr(KeyId.D1, KeyId.DIVIDE, KeyId.D3, KeyId.MULTIPLY, KeyId.D3),
            "1÷3×3",
        )
    }

    @Test
    fun `root two times root two is exactly two`() {
        assertRational(
            BoundedRational.of(2L),
            expr(KeyId.SQRT, KeyId.D2, KeyId.MULTIPLY, KeyId.SQRT, KeyId.D2),
            "√2×√2",
        )
    }

    @Test
    fun `a tenth plus a fifth is exactly three tenths`() {
        assertRational(
            BoundedRational.of(3L, 10L),
            expr(
                KeyId.D0, KeyId.POINT, KeyId.D1, KeyId.ADD,
                KeyId.D0, KeyId.POINT, KeyId.D2,
            ),
            "0.1+0.2",
        )
    }

    // ------------------------------------------------------------ precedence

    @Test
    fun `unary minus binds looser than exponentiation`() {
        // -2^2 is -(2^2) = -4, not (-2)^2 = 4. Getting this backwards is the single most
        // common precedence bug in hand-written calculators.
        assertRational(
            BoundedRational.of(-4L),
            expr(KeyId.SUBTRACT, KeyId.D2, KeyId.POWER, KeyId.D2),
            "-2^2",
        )
    }

    @Test
    fun `exponentiation is right associative`() {
        // 2^(3^2) = 2^9 = 512, not (2^3)^2 = 64.
        assertRational(
            BoundedRational.of(512L),
            expr(KeyId.D2, KeyId.POWER, KeyId.D3, KeyId.POWER, KeyId.D2),
            "2^3^2",
        )
    }

    @Test
    fun `multiplication binds tighter than addition`() {
        assertRational(
            BoundedRational.of(14L),
            expr(KeyId.D2, KeyId.ADD, KeyId.D3, KeyId.MULTIPLY, KeyId.D4),
            "2+3*4",
        )
    }

    @Test
    fun `implicit multiplication works for constants and parentheses`() {
        // 2π must not be a syntax error, and 3(4+5) must be 27.
        val twoPi = value(expr(KeyId.D2, KeyId.PI))
        assertEquals(BoundedRational.of(2L), twoPi.ratFactor)

        assertRational(
            BoundedRational.of(27L),
            expr(
                KeyId.D3, KeyId.LEFT_PAREN, KeyId.D4, KeyId.ADD, KeyId.D5, KeyId.RIGHT_PAREN,
            ),
            "3(4+5)",
        )
    }

    // ------------------------------------------------------------ percent

    @Test
    fun `percent is relative when it is the right operand of plus or minus`() {
        // Google Calculator gives 110 here, because 10% means "10% of the 100".
        assertRational(
            BoundedRational.of(110L),
            expr(KeyId.D1, KeyId.D0, KeyId.D0, KeyId.ADD, KeyId.D1, KeyId.D0, KeyId.PERCENT),
            "100+10%",
        )
        assertRational(
            BoundedRational.of(90L),
            expr(KeyId.D1, KeyId.D0, KeyId.D0, KeyId.SUBTRACT, KeyId.D1, KeyId.D0, KeyId.PERCENT),
            "100-10%",
        )
    }

    @Test
    fun `percent standing alone is simply a hundredth`() {
        assertRational(
            BoundedRational.of(1L, 2L),
            expr(KeyId.D5, KeyId.D0, KeyId.PERCENT),
            "50%",
        )
    }

    @Test
    fun `a percent inside a product does not make the whole term relative`() {
        // The same two numbers multiplied in the other order used to give different answers:
        // the flag rode out of the term with whichever factor was parsed last, so 100+2×50%
        // was 200 and 100+50%×2 was 101. Only a bare % on the additive right operand is
        // relative to the left one, which is what Node.Additive documents.
        assertRational(
            BoundedRational.of(101L),
            expr(
                KeyId.D1, KeyId.D0, KeyId.D0, KeyId.ADD,
                KeyId.D2, KeyId.MULTIPLY, KeyId.D5, KeyId.D0, KeyId.PERCENT,
            ),
            "100+2×50%",
        )
        assertRational(
            BoundedRational.of(101L),
            expr(
                KeyId.D1, KeyId.D0, KeyId.D0, KeyId.ADD,
                KeyId.D5, KeyId.D0, KeyId.PERCENT, KeyId.MULTIPLY, KeyId.D2,
            ),
            "100+50%×2",
        )
    }

    @Test
    fun `a percent divisor is a hundredth rather than a relative change`() {
        // 100+20÷10% used to answer 20100 — 100 + 100×(20÷0.1) — which is no reading of the
        // expression at all. Dividing by ten percent is dividing by a tenth.
        assertRational(
            BoundedRational.of(300L),
            expr(
                KeyId.D1, KeyId.D0, KeyId.D0, KeyId.ADD,
                KeyId.D2, KeyId.D0, KeyId.DIVIDE, KeyId.D1, KeyId.D0, KeyId.PERCENT,
            ),
            "100+20÷10%",
        )
    }

    // ------------------------------------------------------------ trigonometry

    @Test
    fun `sine of thirty degrees is exactly one half`() {
        assertRational(BoundedRational.HALF, expr(KeyId.SIN, KeyId.D3, KeyId.D0), "sin30")
    }

    @Test
    fun `sine of pi radians is exactly zero`() {
        assertTrue(value(expr(KeyId.SIN, KeyId.PI), AngleMode.RADIANS).definitelyZero())
    }

    @Test
    fun `tangent of ninety degrees is a reported error rather than a huge number`() {
        assertEquals(EvalError.DIVIDE_BY_ZERO, error(expr(KeyId.TAN, KeyId.D9, KeyId.D0)))
    }

    // ------------------------------------------------------------ editing

    @Test
    fun `the contextual paren key opens then closes`() {
        var e = CalculatorExpr().appendSmartParen()
        assertEquals(1, e.unclosedParens())
        e = e.append(KeyId.D1).appendSmartParen()
        assertEquals(0, e.unclosedParens())
    }

    @Test
    fun `backspace removes one digit from a number but a whole function at once`() {
        val number = expr(KeyId.D1, KeyId.D2, KeyId.D3).deleteLastToken()
        assertEquals(listOf<Token>(Token.Number("12")), number.tokens)

        // "sin(" must disappear in a single press rather than leaving a stray paren.
        val function = expr(KeyId.D1, KeyId.ADD, KeyId.SIN).deleteLastToken()
        assertEquals(2, function.tokens.size)
    }

    @Test
    fun `unmatched opening parens are closed automatically at evaluation`() {
        assertRational(
            BoundedRational.of(3L),
            expr(KeyId.LEFT_PAREN, KeyId.D1, KeyId.ADD, KeyId.D2),
            "(1+2",
        )
    }

    @Test
    fun `pasted text round trips through the tokenizer`() {
        val pasted = CalculatorExpr.fromText("12+34")
        assertNotNull(pasted)
        assertRational(BoundedRational.of(46L), pasted!!, "pasted 12+34")
        // Anything that is not an expression must be refused rather than half-parsed.
        assertNull(CalculatorExpr.fromText("hello world"))
    }

    // ------------------------------------------------------------ error mapping

    @Test
    fun `every failure mode maps to the error the display knows how to show`() {
        assertEquals(EvalError.SYNTAX, error(CalculatorExpr()))
        assertEquals(EvalError.SYNTAX, error(expr(KeyId.D1, KeyId.ADD)))
        assertEquals(
            EvalError.DIVIDE_BY_ZERO,
            error(expr(KeyId.D1, KeyId.DIVIDE, KeyId.D0)),
        )
        assertEquals(
            EvalError.NOT_A_NUMBER,
            error(expr(KeyId.SQRT, KeyId.LEFT_PAREN, KeyId.SUBTRACT, KeyId.D1, KeyId.RIGHT_PAREN)),
        )
    }

    @Test
    fun `an absurdly large power is refused instead of exhausting memory`() {
        // 10^10^10 is four keystrokes and would otherwise be an ANR.
        val result = eval(
            expr(
                KeyId.D1, KeyId.D0, KeyId.POWER,
                KeyId.D1, KeyId.D0, KeyId.POWER,
                KeyId.D1, KeyId.D0,
            ),
        )
        assertTrue("expected a failure, got $result", result is EvalResult.Failure)
    }

    // ------------------------------------------------------------ graphing path

    @Test
    fun `the double compiler evaluates the same expression fast`() {
        val f = ExprEvaluator.compileToDouble(
            expr(KeyId.SIN, KeyId.VARIABLE_X),
            AngleMode.RADIANS,
        )
        assertNotNull(f)
        assertEquals(Math.sin(1.0), f!!(1.0), 1e-12)
        assertEquals(Math.sin(-2.5), f(-2.5), 1e-12)
    }

    @Test
    fun `the double compiler reports out of domain as NaN rather than throwing`() {
        // The plotter relies on this to break the line instead of crashing the frame.
        val f = ExprEvaluator.compileToDouble(
            expr(KeyId.D1, KeyId.DIVIDE, KeyId.VARIABLE_X),
            AngleMode.RADIANS,
        )
        assertNotNull(f)
        val atZero = f!!(0.0)
        assertTrue("expected NaN or infinity at x=0, got $atZero", !atZero.isFinite())
    }
}
