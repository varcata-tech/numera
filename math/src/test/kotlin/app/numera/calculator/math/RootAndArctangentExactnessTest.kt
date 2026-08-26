package app.numera.calculator.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three exactness holes the emulator pass found, asserted on structure rather than text.
 *
 * All three had the same shape: a value the engine could have known exactly instead arrived
 * as an opaque constructive real, which can never report that it terminates, so the display
 * printed a whole number followed by seventeen zeros and an ellipsis. Structure is what these
 * assert, because a merely *close* value renders as "2" once it is rounded and would pass a
 * string check for the wrong reason.
 */
class RootAndArctangentExactnessTest {

    private fun n(value: Long) = UnifiedReal.of(value)
    private fun frac(a: Long, b: Long) = UnifiedReal.of(BoundedRational.of(a, b))

    private fun assertExactly(expected: Long, actual: UnifiedReal, label: String) {
        assertTrue("$label: expected a rational, got ${actual.toNiceString()}", actual.isRational)
        assertEquals(label, BoundedRational.of(expected), actual.asRational())
    }

    // ------------------------------------------------------------------ nth roots

    @Test
    fun `cube root of a perfect cube is exact`() {
        assertExactly(2L, n(8).pow(frac(1, 3)), "8^(1/3)")
        assertExactly(3L, n(27).pow(frac(1, 3)), "27^(1/3)")
        assertExactly(10L, n(1000).pow(frac(1, 3)), "1000^(1/3)")
    }

    @Test
    fun `fourth and sixth roots are exact too`() {
        assertExactly(2L, n(16).pow(frac(1, 4)), "16^(1/4)")
        assertExactly(2L, n(64).pow(frac(1, 6)), "64^(1/6)")
        assertExactly(3L, n(81).pow(frac(1, 4)), "81^(1/4)")
    }

    @Test
    fun `a numerator other than one still lands exactly`() {
        assertExactly(4L, n(8).pow(frac(2, 3)), "8^(2/3)")
        assertExactly(8L, n(16).pow(frac(3, 4)), "16^(3/4)")
    }

    @Test
    fun `a negative exponent inverts the exact root`() {
        assertEquals(
            "8^(-1/3)",
            BoundedRational.of(1L, 2L),
            n(8).pow(frac(-1, 3)).asRational(),
        )
    }

    @Test
    fun `both halves of a fraction must be perfect powers`() {
        assertEquals(
            "(8/27)^(1/3)",
            BoundedRational.of(2L, 3L),
            UnifiedReal.of(BoundedRational.of(8L, 27L)).pow(frac(1, 3)).asRational(),
        )
        // 26 is not a cube, so there is no exact answer to find and the engine must not
        // invent one by rounding the root of either half.
        assertTrue(
            "(8/26)^(1/3) should stay inexact",
            !UnifiedReal.of(BoundedRational.of(8L, 26L)).pow(frac(1, 3)).isRational,
        )
    }

    @Test
    fun `a value that is not a perfect power stays inexact`() {
        assertTrue("7^(1/3)", !n(7).pow(frac(1, 3)).isRational)
        assertTrue("2^(1/3)", !n(2).pow(frac(1, 3)).isRational)
        assertTrue("9^(1/3)", !n(9).pow(frac(1, 3)).isRational)
    }

    @Test
    fun `the square root path still works`() {
        assertExactly(2L, n(4).pow(frac(1, 2)), "4^(1/2)")
        assertExactly(8L, n(4).pow(frac(3, 2)), "4^(3/2)")
        assertExactly(27L, n(9).pow(frac(3, 2)), "9^(3/2)")
    }

    @Test
    fun `an enormous root index is refused rather than attempted`() {
        // A root of two or more would need a base of at least 2^n, so this cannot be exact —
        // and the guard is what stops x.pow(n - 1) being asked for a millionth power.
        assertTrue("8^(1/1000000)", !n(8).pow(frac(1, 1_000_000)).isRational)
    }

    // ------------------------------------------------------------------ arctangent

    @Test
    fun `arctangent of one is exactly forty five degrees`() {
        assertExactly(45L, n(1).atan(AngleMode.DEGREES), "atan 1")
        assertExactly(-45L, n(-1).atan(AngleMode.DEGREES), "atan -1")
    }

    @Test
    fun `arctangent of zero is exactly zero`() {
        assertExactly(0L, n(0).atan(AngleMode.DEGREES), "atan 0")
    }

    @Test
    fun `arctangent agrees with the other two inverse functions`() {
        // tan 60 is √3, so atan √3 must come back as 60 rather than as an approximation.
        assertExactly(60L, n(3).sqrt().atan(AngleMode.DEGREES), "atan √3")
        assertExactly(30L, (n(1) / n(3).sqrt()).atan(AngleMode.DEGREES), "atan 1/√3")
    }

    @Test
    fun `arctangent has no exact answer where there is none`() {
        assertTrue("atan 2", !n(2).atan(AngleMode.DEGREES).isRational)
    }

    @Test
    fun `arctangent in radians keeps pi symbolic`() {
        val quarterPi = n(1).atan(AngleMode.RADIANS)
        assertEquals("atan 1 in radians", Factor.Pi(1), quarterPi.factor)
        assertEquals("atan 1 in radians", BoundedRational.of(1L, 4L), quarterPi.ratFactor)
    }
}
