package app.numera.calculator.feature.calc

import app.numera.calculator.R
import app.numera.calculator.math.AbortedException
import app.numera.calculator.math.PrecisionOverflowException
import app.numera.calculator.math.TooMuchMemoryException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the calculator does when the *formatter*, not the evaluator, gives up.
 *
 * This is where the calculator's only known crash lived. A `UnifiedReal` is lazy: evaluating
 * it builds a tree and the first digit request runs the series, so every failure the engine
 * can raise — including the `AbortedException` thrown the instant the user presses another
 * key — comes out of the formatting call rather than out of `ExprEvaluator`, which is total.
 * The view model formatted inside `viewModelScope.launch` with no catch, and an
 * `ArithmeticException` is not a `CancellationException`, so the supervisor handed it to the
 * default uncaught handler and the process died.
 *
 * Each case below is the mapping that keeps the same calculation from reporting two different
 * things depending on which half of it happened to fail first.
 */
class FormattingErrorTest {

    @Test
    fun `an abort has no message of its own`() {
        // An interrupt means only that the thread was interrupted, and the exception cannot
        // say by what: the user's next key press, in which case the outcome is stale and must
        // vanish silently, or the caller's own deadline, which the caller reports as a
        // timeout. Giving it a message here would flash an error over an expression the user
        // is still typing towards.
        assertNull(formattingErrorRes(AbortedException()))
    }

    @Test
    fun `a value with no printable digits reports too much memory, not a timeout`() {
        assertEquals(
            R.string.error_too_much_memory,
            formattingErrorRes(TooMuchMemoryException()),
        )
    }

    @Test
    fun `an undecidable value reports a bad expression, matching the evaluator`() {
        // ExprEvaluator maps PrecisionOverflowException onto EvalError.SYNTAX. The same
        // failure reaching the display through the formatting half of the calculation has to
        // say the same thing, or `1÷(π−π)` reads differently depending on timing alone.
        assertEquals(R.string.error_syntax, formattingErrorRes(PrecisionOverflowException()))
    }

    @Test
    fun `a bare ArithmeticException from BigInteger is reported, not swallowed`() {
        // BigInteger raises an unadorned ArithmeticException when a value outgrows its own
        // supported range. It is not a CalculationException, so a `when` over the sealed
        // hierarchy alone would leave it unhandled and back on the uncaught-exception path.
        assertEquals(
            R.string.error_too_much_memory,
            formattingErrorRes(ArithmeticException("BigInteger would overflow supported range")),
        )
    }
}
