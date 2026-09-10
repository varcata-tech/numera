package app.numera.calculator.ui.common

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision that keeps keypad keys at or above the 48dp touch target.
 *
 * Getting it wrong in either direction is silent. Too generous, and a landscape phone gets
 * 31dp keys again with no test to say so; too strict by one gap's worth, and a pad that had
 * exactly enough room switches to fixed rows and grows a scrollbar for nothing. The cases sit
 * on the boundary on purpose, and the unbounded case pins the behaviour that stops weighted
 * rows from measuring to zero inside an unconstrained parent.
 */
class KeypadLayoutTest {

    private val key: Dp = 48.dp
    private val gap: Dp = 8.dp

    @Test
    fun `five rows need five keys and four gaps, not five`() {
        assertEquals(272.dp, keypadMinHeight(rows = 5, minRowHeight = key, spacing = gap))
        assertEquals(48.dp, keypadMinHeight(rows = 1, minRowHeight = key, spacing = gap))
    }

    @Test
    fun `rows fit at exactly the minimum and stop fitting one dp under it`() {
        assertTrue(keypadRowsFit(available = 272.dp, rows = 5, minRowHeight = key, spacing = gap))
        assertFalse(keypadRowsFit(available = 271.dp, rows = 5, minRowHeight = key, spacing = gap))
    }

    @Test
    fun `a landscape phone's share of the calculator does not fit the numeric pad`() {
        // Roughly what the pad's 1.4-of-2.4 weight leaves on a 411dp-tall window.
        assertFalse(keypadRowsFit(available = 216.dp, rows = 5, minRowHeight = key, spacing = gap))
        // The three-row advanced pad beside it does.
        assertTrue(keypadRowsFit(available = 216.dp, rows = 3, minRowHeight = key, spacing = gap))
    }

    @Test
    fun `an unbounded height is not a fit, because weighted rows would measure to zero`() {
        assertFalse(keypadRowsFit(available = Dp.Infinity, rows = 5, minRowHeight = key, spacing = gap))
    }
}
