package app.numera.calculator.feature.graphing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a pan or pinch is allowed to cost a sample pass.
 *
 * The schedule used to be a pure restart-debounce, and a continuous drag never let it
 * expire: pointer moves arrive every frame, each one restarted the eighty milliseconds, and
 * the samples stayed exactly as they were until the finger lifted. Everything a long pan
 * revealed beyond the sampled margin was blank for the whole gesture. The wait is now
 * bounded from the first event of the gesture, so a pass runs at least every quarter second
 * while the finger keeps moving — and the tail of the gesture still coalesces into one pass.
 *
 * Tested through the pure delay function rather than the view model, which needs a main
 * dispatcher and a saved-state bundle that no JVM test has.
 */
class GraphingScheduleTest {

    @Test
    fun `the first event of a gesture waits the ordinary debounce`() {
        assertEquals(GraphingViewModel.RESAMPLE_DEBOUNCE_MS, resampleDelayMillis(pendingForMillis = 0L))
    }

    @Test
    fun `a gesture that keeps moving cannot postpone the pass past the bound`() {
        // Every frame of a drag reschedules; the wait must shrink toward the bound rather
        // than start over, or the pass never runs while the finger is down.
        val bound = GraphingViewModel.RESAMPLE_MAX_WAIT_MS
        val debounce = GraphingViewModel.RESAMPLE_DEBOUNCE_MS
        assertEquals(debounce, resampleDelayMillis(pendingForMillis = bound - debounce - 1))
        assertEquals(50L, resampleDelayMillis(pendingForMillis = bound - 50L))
        assertEquals(0L, resampleDelayMillis(pendingForMillis = bound))
        assertEquals(0L, resampleDelayMillis(pendingForMillis = bound + 1000L))
    }

    @Test
    fun `the wait is never negative or longer than the debounce`() {
        for (pending in listOf(0L, 1L, 79L, 80L, 170L, 249L, 250L, 251L, 10_000L)) {
            val delay = resampleDelayMillis(pendingForMillis = pending)
            assertEquals("pending $pending", true, delay in 0L..GraphingViewModel.RESAMPLE_DEBOUNCE_MS)
        }
    }
}
