package app.numera.calculator.feature.graphing

import kotlin.math.ln
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to the crosshair and the roots line when the plot underneath them is deleted.
 *
 * Both readouts describe the *first* plot, and both are written at a different moment from
 * the list they describe: the trace when the user taps, the roots when a background pass
 * lands. Deleting the first of two functions changes which curve "the first plot" means
 * without touching either readout, so the screen kept drawing a filled dot and printing a
 * coordinate for a curve that was no longer on the canvas, and captioned one function's roots
 * with another function's name. Nothing recomputed them: the crosshair survived pans, zooms,
 * squaring the axes and every resample, and could only be removed by finding the "Clear
 * trace" button.
 *
 * Tested through the pure helper the view model applies rather than through the view model,
 * which needs a main dispatcher and a saved-state bundle that no JVM test has.
 */
class GraphingReadoutStateTest {

    private val parabola = Plot("x^2", { x: Double -> x * x })
    private val wave = Plot("sin(x)", { x: Double -> sin(x) })

    /** Two functions plotted, with the crosshair and the roots both taken from the first. */
    private val traced = GraphingUiState(
        plots = listOf(parabola, wave),
        trace = TracePoint(x = 3.0, y = 9.0, subject = "x^2"),
        roots = RootsReadout(subject = "x^2", values = listOf(0.0)),
    )

    @Test
    fun `deleting the traced function takes its crosshair and its roots with it`() {
        val afterDelete = traced.copy(plots = listOf(wave)).withoutStaleReadouts()
        // Left behind, the readout said "x = 3, y = 9" of sin(x), whose value at 3 is 0.1411,
        // and listed a root of the parabola under the name sin(x).
        assertNull(afterDelete.trace)
        assertNull(afterDelete.roots)
    }

    @Test
    fun `deleting a function that was not the subject leaves the readouts alone`() {
        // The crosshair belongs to the plot that is still first, so removing the one below it
        // must not clear a reading the user deliberately took.
        val afterDelete = traced.copy(plots = listOf(parabola)).withoutStaleReadouts()
        assertNotNull(afterDelete.trace)
        assertEquals(3.0, afterDelete.trace?.x ?: 0.0, 0.0)
        assertEquals("x^2", afterDelete.roots?.subject)
    }

    @Test
    fun `deleting the last function clears both readouts`() {
        val empty = traced.copy(plots = emptyList()).withoutStaleReadouts()
        assertNull(empty.trace)
        assertNull(empty.roots)
    }

    @Test
    fun `a tap where the function has no value keeps a crosshair rather than deleting it`() {
        // Writing null for an undefined column removed whatever crosshair was already on
        // the graph: tapping left of the axis on ln(x) blanked the readout and hid the
        // "Clear trace" button, exactly as if it had been pressed, and nothing said why.
        val log = Plot("ln(x)", { x: Double -> ln(x) })
        val undefined = traceFor(log, -2.0)
        assertTrue("y should be NaN for ln(-2), was ${undefined.y}", undefined.y.isNaN())
        assertEquals(-2.0, undefined.x, 0.0)
        assertEquals("ln(x)", undefined.subject)
        // And a defined column is the ordinary reading.
        val defined = traceFor(log, 1.0)
        assertEquals(0.0, defined.y, 0.0)
        // A closure that throws rather than returning NaN is treated the same way.
        val throwing = Plot("1/x", { _: Double -> throw ArithmeticException("divide by zero") })
        assertTrue(traceFor(throwing, 0.0).y.isNaN())
    }

    @Test
    fun `a readout is matched by the function it describes, not by its position`() {
        // Re-adding the same expression makes the readout true again, which is the point of
        // matching on the expression rather than on an index: the curve is identical.
        assertTrue(describesFirstPlot("x^2", listOf(parabola, wave)))
        assertFalse(describesFirstPlot("x^2", listOf(wave, parabola)))
        assertFalse(describesFirstPlot("x^2", emptyList()))
        assertFalse(describesFirstPlot(null, listOf(parabola)))
    }
}
