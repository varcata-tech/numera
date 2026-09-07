package app.numera.calculator.data

import app.numera.calculator.math.AngleMode
import app.numera.calculator.math.expr.CalculatorExpr
import app.numera.calculator.math.expr.KeyId
import app.numera.calculator.math.expr.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which repeated calculation is a duplicate, which is a different question, and what the
 * in-memory list does when the table is full.
 *
 * The interesting case is the one the drawer cannot show: `sin(30` evaluated in degrees and
 * then in radians is two different calculations with one formula, so matching on the formula
 * alone drops the second and leaves the drawer advertising an answer the app would no longer
 * produce. Matching on the *rendered* answer instead — which is what this used to do — trades
 * that for a subtler failure, because the rendering is localised: after a language change the
 * same sum compares unequal to itself and is written twice, in two numbering systems.
 *
 * The store itself needs a `Context` and a real SQLite database, so what is testable on the
 * JVM is the pure decision each of its paths turns on; that is why these helpers are separate
 * functions rather than private methods.
 */
class HistoryStoreDedupeTest {

    private fun entry(
        formula: String,
        result: String,
        angleMode: AngleMode = AngleMode.DEGREES,
        id: Long = 1L,
    ): HistoryEntry = HistoryEntry(
        id = id,
        expression = CalculatorExpr(listOf(Token.Key(KeyId.PI))),
        formula = formula,
        result = result,
        angleMode = angleMode,
        timestamp = 0L,
    )

    @Test
    fun `pressing equals twice does not record the same row twice`() {
        assertTrue(isConsecutiveDuplicate(entry("1+1", "2"), "1+1", AngleMode.DEGREES))
    }

    @Test
    fun `the same formula in the other angle unit is a different calculation`() {
        // Degrees then radians. Both are real results the user watched appear, and nothing
        // else on the row would tell them apart.
        assertTrue(
            isConsecutiveDuplicate(
                entry("sin(30", "0.5", AngleMode.DEGREES),
                "sin(30",
                AngleMode.DEGREES,
            ),
        )
        assertFalse(
            isConsecutiveDuplicate(
                entry("sin(30", "0.5", AngleMode.DEGREES),
                "sin(30",
                AngleMode.RADIANS,
            ),
        )
    }

    @Test
    fun `the same calculation rendered in another locale is still a duplicate`() {
        // ٢ and 2 are the same answer. Comparing the rendered strings wrote a second row the
        // moment the app language changed, so the drawer showed one sum twice in two
        // numbering systems.
        assertTrue(isConsecutiveDuplicate(entry("1+1", "٢"), "1+1", AngleMode.DEGREES))
    }

    @Test
    fun `a different formula is never a duplicate`() {
        assertFalse(isConsecutiveDuplicate(entry("1+1", "2"), "1+2", AngleMode.DEGREES))
    }

    @Test
    fun `an empty list has nothing to duplicate`() {
        // Reached on a cold start before the drawer's first load has landed.
        assertFalse(isConsecutiveDuplicate(null, "1+1", AngleMode.DEGREES))
    }

    @Test
    fun `a stored unit is read back by name, and an unknown one does not throw`() {
        // Rows written before the column existed carry the schema default; a row written by
        // a newer version carries a name this build may not have. Neither may crash the
        // drawer, which is why this is not `valueOf`.
        assertSame(AngleMode.RADIANS, angleModeNamed("RADIANS"))
        assertSame(AngleMode.DEGREES, angleModeNamed("DEGREES"))
        assertSame(AngleMode.DEGREES, angleModeNamed(null))
        assertSame(AngleMode.DEGREES, angleModeNamed("GRADIANS"))
    }

    @Test
    fun `the newest row is prepended and the list never grows past the cap`() {
        // The list the drawer observes is grown in memory rather than re-read, so this is
        // the only thing keeping it bounded once the table reaches MAX_ENTRIES.
        val existing = List(4) { entry("$it+1", "x", id = it.toLong()) }
        val fresh = entry("9+9", "18", id = 99L)

        val capped = prependCapped(fresh, existing, max = 3)
        assertEquals(3, capped.size)
        assertSame(fresh, capped[0])
        assertSame(existing[0], capped[1])

        val room = prependCapped(fresh, existing, max = 500)
        assertEquals(5, room.size)
        assertSame(existing[3], room[4])
    }
}
