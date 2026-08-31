package app.numera.calculator.feature.calc

import app.numera.calculator.math.expr.CalculatorExpr
import app.numera.calculator.math.expr.KeyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a key press does to the expression, mode by mode.
 *
 * These cases are the ones where the *display* was the casualty rather than the arithmetic:
 * a key the expression refuses is not a small edit, it is no edit at all, and a view model
 * that clears or seeds before appending turns it into a wiped answer. The view model itself
 * needs a device to build, which is exactly why this decision was moved out of it.
 */
class KeyPressTest {

    private fun typed(vararg keys: KeyId): CalculatorExpr =
        keys.fold(CalculatorExpr()) { acc, key -> acc.append(key) }

    /** The exact answer a result is continued from, standing in for the real seed. */
    private val seed = typed(KeyId.D7)

    private fun press(
        mode: DisplayMode,
        expr: CalculatorExpr,
        key: KeyId,
        seed: () -> CalculatorExpr = { this.seed },
    ): CalculatorExpr? = KeyPress.apply(mode, expr, key, seed)

    // ------------------------------------------------------------ a refused key is no edit

    @Test
    fun `a key the expression refuses leaves a result on screen untouched`() {
        // The bug this exists for: `)` does not continue from a result, so the expression
        // was cleared, the append was refused, and the display kept neither the answer nor
        // the expression that produced it — with no way back but the history drawer.
        assertNull(press(DisplayMode.RESULT, CalculatorExpr(), KeyId.RIGHT_PAREN))

        // `(` after a result is a real edit — it opens the next calculation — and it starts
        // fresh, so it must not pay for building the seed from an exact answer that can run
        // to tens of thousands of digits.
        val opened = press(DisplayMode.RESULT, typed(KeyId.D5), KeyId.LEFT_PAREN, seed = {
            throw AssertionError("a fresh-start key must not pay for the seed")
        })
        assertEquals("(", opened?.display())
    }

    @Test
    fun `an operator with nothing typed changes nothing at all`() {
        val empty = CalculatorExpr()
        assertNull(press(DisplayMode.INPUT, empty, KeyId.MULTIPLY))
        assertNull(press(DisplayMode.INPUT, empty, KeyId.DIVIDE))
        assertNull(press(DisplayMode.INPUT, empty, KeyId.ADD))
        assertNull(press(DisplayMode.INPUT, empty, KeyId.PERCENT))
        assertNull(press(DisplayMode.INPUT, empty, KeyId.RIGHT_PAREN))

        // `−` is the one that does something, because it brings the display's 0 with it.
        assertEquals("0−", press(DisplayMode.INPUT, empty, KeyId.SUBTRACT)?.display())
    }

    @Test
    fun `pressing the same operator twice is a no-op rather than a second operator`() {
        val minus = typed(KeyId.D5, KeyId.SUBTRACT)
        assertNull(press(DisplayMode.INPUT, minus, KeyId.SUBTRACT))
        assertEquals("5×", press(DisplayMode.INPUT, minus, KeyId.MULTIPLY)?.display())
    }

    // ------------------------------------------------------------ what a press applies to

    @Test
    fun `a digit after a result starts a new calculation`() {
        // 5 = then 3 is the start of the next sum, not `53` and not `5 × 3`.
        val next = press(DisplayMode.RESULT, typed(KeyId.D5), KeyId.D3) {
            throw AssertionError("a digit must not build the seed")
        }
        assertEquals("3", next?.display())
    }

    @Test
    fun `an operator after a result continues from the exact answer`() {
        val next = press(DisplayMode.RESULT, typed(KeyId.D5), KeyId.MULTIPLY)
        assertEquals("7×", next?.display())

        // The postfix keys continue from it too: `=` then `%` is a percentage of the answer.
        assertEquals("7%", press(DisplayMode.RESULT, CalculatorExpr(), KeyId.PERCENT)?.display())
    }

    @Test
    fun `an error keeps the expression that caused it, so it can be corrected`() {
        // 1÷0 is corrected by reaching for the 0, not by retyping the whole thing.
        val failed = typed(KeyId.D1, KeyId.DIVIDE, KeyId.D0)
        assertEquals("1÷02", press(DisplayMode.ERROR, failed, KeyId.D2)?.display())
        assertEquals("1÷0×", press(DisplayMode.ERROR, failed, KeyId.MULTIPLY)?.display())
    }

    @Test
    fun `typing continues from what is already there`() {
        val started = typed(KeyId.D1, KeyId.D2)
        assertEquals("12+", press(DisplayMode.INPUT, started, KeyId.ADD)?.display())
        assertEquals("123", press(DisplayMode.INPUT, started, KeyId.D3)?.display())
    }

    // ------------------------------------------------------------ backspace, the other half

    @Test
    fun `deleting a leading sign takes the zero it brought with it`() {
        // `−` writes `0−` on one press, so one press has to undo it. Leaving the `0` behind
        // costs a press that redraws the display exactly as it was, because an empty line
        // draws a `0` of its own.
        val opened = typed(KeyId.SUBTRACT)
        assertEquals("0−", opened.display())
        assertTrue(opened.deleteLastToken().isEmpty())

        val inGroup = typed(KeyId.LEFT_PAREN, KeyId.SUBTRACT)
        assertEquals("(0−", inGroup.display())
        assertEquals("(", inGroup.deleteLastToken().display())

        // A digit typed after the sign still comes off one at a time.
        val typedInto = typed(KeyId.SUBTRACT, KeyId.D5)
        assertEquals("0−", typedInto.deleteLastToken().display())

        // And an ordinary minus keeps the number in front of it.
        assertEquals("5", typed(KeyId.D5, KeyId.SUBTRACT).deleteLastToken().display())
    }

    @Test
    fun `deleting never leaves a sign standing in front of nothing`() {
        // The keypad cannot type this, but pasting `-2` and deleting the `2` can, and every
        // operator key would then be refused against a leading `−` with no way to explain it.
        val pasted = CalculatorExpr.fromText("-2")
        assertNotNull(pasted)
        assertEquals("−2", pasted!!.display())
        assertTrue(pasted.deleteLastToken().isEmpty())

        val inGroup = CalculatorExpr.fromText("(-2)")
        assertNotNull(inGroup)
        val openAgain = inGroup!!.deleteLastToken().deleteLastToken()
        assertEquals("(", openAgain.display())
        assertFalse(openAgain.isEmpty())
    }
}
