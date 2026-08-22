package app.numera.calculator.data

import app.numera.calculator.math.expr.CalculatorExpr
import app.numera.calculator.math.expr.KeyId
import app.numera.calculator.math.expr.Token
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which repeated calculation is a duplicate and which is a different question.
 *
 * The interesting case is the one the drawer cannot show: matching on the formula alone,
 * `sin(30` evaluated in degrees and then in radians looks like the same row twice, so the
 * second is dropped and the drawer is left advertising an answer the app would no longer
 * produce for that formula.
 */
class HistoryStoreDedupeTest {

    private fun entry(formula: String, result: String): HistoryEntry = HistoryEntry(
        id = 1L,
        expression = CalculatorExpr(listOf(Token.Key(KeyId.PI))),
        formula = formula,
        result = result,
        timestamp = 0L,
    )

    @Test
    fun `pressing equals twice does not record the same row twice`() {
        assertTrue(isConsecutiveDuplicate(entry("1+1", "2"), "1+1", "2"))
    }

    @Test
    fun `the same formula with a different answer is a different calculation`() {
        // Degrees then radians. Both are real results the user watched appear.
        assertTrue(
            isConsecutiveDuplicate(entry("sin(30", "0.5"), "sin(30", "0.5"),
        )
        assertFalse(
            isConsecutiveDuplicate(entry("sin(30", "0.5"), "sin(30", "−0.988031624…"),
        )
    }

    @Test
    fun `a different formula is never a duplicate`() {
        assertFalse(isConsecutiveDuplicate(entry("1+1", "2"), "1+2", "3"))
    }

    @Test
    fun `an empty list has nothing to duplicate`() {
        // Reached on a cold start before the drawer's first load has landed.
        assertFalse(isConsecutiveDuplicate(null, "1+1", "2"))
    }
}
