package app.numera.calculator.feature.graphing

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the plot and the controls under it share a short screen.
 *
 * The canvas is the only weighted child of the graphing screen's Column, so it is handed
 * whatever the fixed-height controls leave. In landscape that was a strip a few dp tall
 * with two functions plotted and nothing at all with three, and with the keyboard up the
 * Column overflowed and the expression field being typed into was laid out out of sight.
 * The controls are capped and scroll inside the cap; these cases pin down what the cap
 * gives to which side, in the geometry real phones produce.
 */
class GraphingLayoutTest {

    @Test
    fun `a portrait phone lets the controls take their natural height`() {
        // About 700dp of content in portrait: the cap sits far above what the controls
        // need, so the canvas simply gets the remainder as before.
        val cap = controlsHeightCap(700.dp)
        assertEquals(540f, cap.value, 1e-3f)
    }

    @Test
    fun `a landscape phone keeps a usable plot`() {
        // Roughly 300dp remain under the app bar in landscape; three functions worth of
        // controls would have taken all of it.
        val cap = controlsHeightCap(300.dp)
        assertEquals(140f, cap.value, 1e-3f)
        assertTrue("the canvas keeps at least 160dp", 300f - cap.value >= 160f)
    }

    @Test
    fun `with the keyboard up the field wins, one dp at a time`() {
        // Landscape with the keyboard open leaves about 100dp. The controls get all of it
        // rather than a share that cannot show the field; and between the two regimes the
        // canvas gives way gradually, so rotating with the keyboard half-animated does not
        // jump the plot between 160dp and nothing.
        assertEquals(100f, controlsHeightCap(100.dp).value, 1e-3f)
        assertEquals(120f, controlsHeightCap(200.dp).value, 1e-3f)
        assertEquals(120f, controlsHeightCap(280.dp).value, 1e-3f)
        var previous = controlsHeightCap(0.dp).value
        for (available in 1..800) {
            val cap = controlsHeightCap(available.dp).value
            assertTrue("cap fell from $previous to $cap at $available", cap >= previous)
            assertTrue("cap $cap exceeds available $available", cap <= available.toFloat())
            previous = cap
        }
    }
}
