package app.numera.calculator.math

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exactness guarantees the whole app is built on.
 *
 * These cases are not arbitrary: each one is a place where a calculator using doubles, or
 * using BigDecimal with a fixed scale, gives a visibly wrong answer that users notice.
 */
class BoundedRationalTest {

    private fun r(n: Long, d: Long = 1L) = BoundedRational.of(n, d)

    @Test
    fun `a third times three is exactly one`() {
        val third = r(1) / r(3)
        val result = third!! * r(3)
        assertEquals(BoundedRational.ONE, result)
        assertEquals("1", result!!.toNiceString())
    }

    @Test
    fun `a tenth plus a fifth is exactly three tenths`() {
        // The canonical floating-point embarrassment: 0.1 + 0.2 == 0.30000000000000004.
        val sum = BoundedRational.parse("0.1") + BoundedRational.parse("0.2")
        assertEquals(r(3, 10), sum)
        assertEquals("0.3", sum!!.toNiceString())
    }

    @Test
    fun `fractions are reduced on construction`() {
        assertEquals(r(1, 2), r(50, 100))
        assertEquals(BigInteger.ONE, r(50, 100).num)
        assertEquals(BigInteger.TWO, r(50, 100).den)
    }

    @Test
    fun `a negative denominator moves its sign to the numerator`() {
        val value = BoundedRational.of(BigInteger.ONE, BigInteger.valueOf(-2L))
        assertEquals(BigInteger.valueOf(-1L), value.num)
        assertEquals(BigInteger.TWO, value.den)
    }

    @Test
    fun `terminating fractions know exactly how many digits they need`() {
        assertEquals(0, r(3).digitsRequired())
        assertEquals(2, r(1, 4).digitsRequired())      // 0.25
        assertEquals(1, r(1, 2).digitsRequired())      // 0.5
        assertEquals(3, r(1, 8).digitsRequired())      // 0.125
        assertEquals(2, r(1, 100).digitsRequired())    // 0.01
        // 1/7 never terminates, which is what puts the ellipsis on screen.
        assertNull(r(1, 7).digitsRequired())
        assertNull(r(1, 3).digitsRequired())
    }

    @Test
    fun `exact decimals render without rounding`() {
        assertEquals("0.25", r(1, 4).toExactDecimalString())
        assertEquals("0.125", r(1, 8).toExactDecimalString())
        assertEquals("-0.75", r(-3, 4).toExactDecimalString())
        assertEquals("2", r(2).toExactDecimalString())
        assertNull(r(1, 7).toExactDecimalString())
    }

    @Test
    fun `square roots stay exact only when both halves are perfect squares`() {
        assertEquals(r(3), r(9).sqrt())
        assertEquals(r(2, 3), r(4, 9).sqrt())
        // Irrational: null hands the value up to UnifiedReal, which keeps it as √2.
        assertNull(r(2).sqrt())
        assertNull(r(1, 2).sqrt())
    }

    @Test
    fun `a negative square root is a domain error rather than a null`() {
        try {
            r(-4).sqrt()
            throw AssertionError("expected NotANumberException")
        } catch (expected: NotANumberException) {
            // The distinction matters: null means "try another representation",
            // this means "there is no real answer".
        }
    }

    @Test
    fun `dividing by zero is an error the user is told about`() {
        try {
            r(1) / BoundedRational.ZERO
            throw AssertionError("expected DivideByZeroException")
        } catch (expected: DivideByZeroException) {
        }
    }

    @Test
    fun `integer powers are exact and negative exponents invert`() {
        assertEquals(r(1024), r(2).pow(BigInteger.valueOf(10L)))
        assertEquals(r(1, 8), r(2).pow(BigInteger.valueOf(-3L)))
        assertEquals(BoundedRational.ONE, r(7).pow(BigInteger.ZERO))
        assertEquals(r(8, 27), r(2, 3).pow(BigInteger.valueOf(3L)))
    }

    @Test
    fun `an oversized power gives up rather than allocating`() {
        // 2^(10^9) would need a gigabit of numerator. Returning null lets the caller fall
        // through to the constructive-real layer instead of the OOM killer.
        assertNull(r(2).pow(BigInteger.valueOf(1_000_000_000L)))
    }

    @Test
    fun `one hundred factorial is exact and has 158 digits`() {
        val value = r(100).factorial()
        val digits = value.asBigInteger()!!.toString()
        assertEquals(158, digits.length)
        assertTrue(digits.startsWith("93326215443944152681699238856266700490715968264381"))
        // 100! ends in exactly 24 zeros — one per factor of five below 100.
        assertTrue(digits.endsWith("0".repeat(24)))
    }

    @Test
    fun `parsing preserves the exact typed value including exponents`() {
        assertEquals(r(1, 10), BoundedRational.parse("0.1"))
        assertEquals(r(-3, 2), BoundedRational.parse("-1.5"))
        assertEquals(r(1500), BoundedRational.parse("1.5e3"))
        assertEquals(r(15, 10000), BoundedRational.parse("1.5e-3"))
        assertEquals(BoundedRational.ZERO, BoundedRational.parse("0.000"))
    }

    @Test
    fun `a denominator with a hundred thousand factors of five still terminates`() {
        // parse is the one entry point that never applies the size bound, and the parser
        // accepts exponents down to -100,000: this arrives as 5^100000 once the twos are
        // shifted out. Peeling one factor at a time is a hundred thousand divisions of a
        // number averaging 116,000 bits, on the keystroke that produced it.
        assertEquals(100_000, BoundedRational.parse("1E-100000").digitsRequired())
        // The ordinary cases have to keep agreeing after the change of algorithm.
        assertEquals(3, r(1, 40).digitsRequired())     // 0.025
        assertEquals(4, r(3, 625).digitsRequired())    // 0.0048
        assertNull(r(1, 30).digitsRequired())          // 1/30 has a factor of three
    }

    @Test
    fun `an interrupted digit count stops instead of finishing the denominator`() {
        // The five-stripping loop had no cancellation point at all, so a preview that the
        // very next keystroke had already invalidated still ran to completion — and
        // runInterruptible had nothing to interrupt.
        var thrown: Throwable? = null
        val worker = Thread {
            Thread.currentThread().interrupt()
            try {
                BoundedRational.parse("1E-100000").digitsRequired()
            } catch (t: Throwable) {
                thrown = t
            }
        }
        worker.start()
        worker.join(30_000)
        assertTrue("worker did not stop", !worker.isAlive)
        assertTrue("expected AbortedException but got $thrown", thrown is AbortedException)
    }

    @Test
    fun `comparison orders fractions without converting to double`() {
        assertTrue(r(1, 3) < r(1, 2))
        assertTrue(r(-1, 3) < r(1, 1000000))
        assertEquals(0, r(2, 4).compareTo(r(1, 2)))
    }

    @Test
    fun `arithmetic below the size bound stays exact`() {
        val big = r(1).pow(BigInteger.ONE)
        assertNotNull(big)
        val chained = ((r(1) / r(3))!! + r(1, 6))!! * r(2)
        assertEquals(BoundedRational.ONE, chained)
    }
}
