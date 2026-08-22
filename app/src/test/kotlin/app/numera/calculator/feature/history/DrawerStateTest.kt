package app.numera.calculator.feature.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a released drawer lands.
 *
 * These are the cases that feel wrong rather than look wrong: a flick that snaps back, a
 * drag that runs past the end, a rotation that strands the drawer half open. None of them
 * would be caught by rendering the screen and looking at it.
 */
class DrawerStateTest {

    private val closed = DrawerState(offset = 0f, maxOffset = 600f)

    @Test
    fun `dragging cannot pull the drawer past either end`() {
        assertEquals(600f, closed.drag(10_000f).offset, 0f)
        assertEquals(0f, closed.drag(-10_000f).offset, 0f)
        assertEquals(150f, closed.drag(150f).offset, 0f)
    }

    @Test
    fun `progress is a clean zero to one and survives being unmeasured`() {
        assertEquals(0f, closed.progress, 0f)
        assertEquals(0.5f, closed.drag(300f).progress, 1e-4f)
        assertEquals(1f, closed.opened().progress, 0f)
        // Before layout runs maxOffset is zero; dividing by it must not produce NaN.
        assertEquals(0f, DrawerState().progress, 0f)
        assertFalse(DrawerState().isOpen)
    }

    @Test
    fun `a slow release settles to whichever end is nearer`() {
        assertTrue(closed.drag(400f).settle(velocity = 0f).isOpen)
        assertTrue(closed.drag(200f).settle(velocity = 0f).isClosed)
        // Exactly halfway opens, so a deliberate half-pull is not treated as a cancel.
        assertTrue(closed.drag(300f).settle(velocity = 0f).isOpen)
    }

    @Test
    fun `a flick beats position in both directions`() {
        // Barely pulled but thrown downward: the user clearly meant to open it.
        assertTrue(closed.drag(60f).settle(velocity = 2_000f).isOpen)
        // Nearly fully open but thrown back up: equally clearly meant to close it.
        assertTrue(closed.drag(560f).settle(velocity = -2_000f).isClosed)
    }

    @Test
    fun `a gentle drift is not mistaken for a flick`() {
        val justUnder = DrawerState.FLING_THRESHOLD - 1f
        assertTrue(closed.drag(100f).settle(velocity = justUnder).isClosed)
        assertTrue(closed.drag(500f).settle(velocity = -justUnder).isOpen)
    }

    @Test
    fun `resizing keeps the open or closed state rather than the pixel offset`() {
        // A rotation changes the height. Keeping the raw offset would leave a drawer that
        // was open stranded part-way down the new, taller screen.
        val open = closed.opened().resized(900f)
        assertTrue(open.isOpen)
        assertEquals(900f, open.offset, 0f)

        val stillClosed = closed.resized(900f)
        assertTrue(stillClosed.isClosed)
        assertEquals(900f, stillClosed.maxOffset, 0f)
    }

    @Test
    fun `a rotation reopens the drawer from the flag rather than from the lost offset`() {
        // After a rotation the composition is brand new: the offset starts at zero and the
        // height has not been measured yet, so the state cannot tell that it was open. Only
        // the saved flag can, which is why the caller may say so explicitly.
        val fresh = DrawerState()
        val reopened = fresh.resized(900f, open = true)
        assertTrue(reopened.isOpen)
        assertEquals(900f, reopened.offset, 0f)

        // And the same call closes it when the flag says it was closed, rather than leaving
        // a stale offset that the first drag would then jump away from.
        val reclosed = closed.drag(600f).resized(300f, open = false)
        assertTrue(reclosed.isClosed)
        assertEquals(300f, reclosed.maxOffset, 0f)
    }

    @Test
    fun `an unmeasured drawer settles closed instead of dividing by zero`() {
        assertTrue(DrawerState().settle(velocity = 5_000f).isClosed)
    }
}
