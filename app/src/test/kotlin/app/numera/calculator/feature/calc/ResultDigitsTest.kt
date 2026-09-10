package app.numera.calculator.feature.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the result line asks for as it is scrolled, and what it is allowed to claim.
 *
 * Both failures covered here are silent. A linear growth curve still shows the right digits,
 * it just dies a few hundred in; and a truncated expansion printed without an ellipsis is
 * indistinguishable from an exact answer, which is the single guarantee the exact engine
 * exists to make.
 */
class ResultDigitsTest {

    @Test
    fun `each expansion at least doubles, so a thousand digits costs ten requests`() {
        var digits = 0
        var requests = 0
        while (digits < 1_000) {
            digits = nextDigitTarget(digits)
            requests++
        }
        assertTrue("needed $requests requests to reach $digits", requests <= 10)
    }

    @Test
    fun `an early expansion still steps far enough to be worth the round trip`() {
        assertEquals(INITIAL_DIGITS, nextDigitTarget(0))
        // Doubling ten is not progress anybody would notice; the floor takes over.
        assertEquals(INITIAL_DIGITS, nextDigitTarget(10))
        assertEquals(200, nextDigitTarget(100))
    }

    @Test
    fun `a doubling that would overflow refuses instead of going negative`() {
        // A negative target reads as "no more digits" and would stall the scroll for good.
        val huge = Int.MAX_VALUE - 1
        assertEquals(huge, nextDigitTarget(huge))
    }

    @Test
    fun `a non-terminating value is always marked truncated`() {
        // One seventh never stops, so fifty digits of it must carry an ellipsis no matter
        // how many the user has already scrolled past.
        val request = digitRequest(digitsRequired = null, target = 50)
        assertEquals(50, request.digits)
        assertTrue(request.truncated)
    }

    @Test
    fun `a value that stops short is requested at its own length and claims no ellipsis`() {
        // 0.25 stops at two places. Asking for fifty would print it followed by forty-eight
        // zeros — correct, and a lie about how much was known.
        val request = digitRequest(digitsRequired = 2, target = 50)
        assertEquals(2, request.digits)
        assertFalse(request.truncated)
    }

    @Test
    fun `an exact integer asks for no decimal places at all`() {
        // digitsRequired is zero for 2^250: seventy-six digits and nothing after the point.
        val request = digitRequest(digitsRequired = 0, target = 50)
        assertEquals(0, request.digits)
        assertFalse(request.truncated)
    }

    @Test
    fun `a terminating value longer than the request is still truncated`() {
        val request = digitRequest(digitsRequired = 400, target = 50)
        assertEquals(50, request.digits)
        assertTrue(request.truncated)
    }

    @Test
    fun `a request already in flight is not restarted`() {
        // The scroll position emits per pixel and each emission asks for the same next
        // target; cancelling the running job on every one of them meant no digits arrived
        // until the finger stopped, because an interrupted approximation is not cached.
        assertFalse(expansionNeeded(target = 50, shown = 0, inFlight = 50))
    }

    @Test
    fun `a smaller request does not cancel a larger one`() {
        // After process death the restore asks for the depth the user had reached while the
        // display, a moment later, asks for the first step. The first step must lose.
        assertFalse(expansionNeeded(target = 50, shown = 0, inFlight = 3200))
        assertTrue(expansionNeeded(target = 6400, shown = 0, inFlight = 3200))
    }

    @Test
    fun `nothing in flight and nothing shown means the request is made`() {
        assertTrue(expansionNeeded(target = 50, shown = 0, inFlight = null))
        assertFalse(expansionNeeded(target = 50, shown = 50, inFlight = null))
        assertFalse(expansionNeeded(target = 40, shown = 50, inFlight = null))
    }
}
