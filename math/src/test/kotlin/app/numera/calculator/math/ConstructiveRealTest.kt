package app.numera.calculator.math

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Digit-level checks against independently known expansions.
 *
 * A scaling mistake in the approximation layer does not throw — it quietly returns wrong
 * digits somewhere far to the right of the decimal point, where no eyeball would find it.
 * Comparing against reference constants to a hundred places is the only thing that catches
 * that class of bug, so these constants are the real test suite for the engine.
 */
class ConstructiveRealTest {

    /** First 100 digits after the point, from published expansions. */
    private companion object {
        const val PI_100 =
            "3.1415926535897932384626433832795028841971693993751058209749" +
                "445923078164062862089986280348253421170679"
        const val E_100 =
            "2.7182818284590452353602874713526624977572470936999595749669" +
                "676277240766303535475945713821785251664274"
        const val SQRT2_100 =
            "1.4142135623730950488016887242096980785696718753769480731766" +
                "797379907324784621070388503875343276415727"
        const val LN2_100 =
            "0.6931471805599453094172321214581765680755001343602552541206" +
                "800094933936219696947156058633269964186875"
    }

    /** Truncates rather than compares the final digit, which [toStringTruncated] may round. */
    private fun firstDigits(value: ConstructiveReal, digits: Int): String =
        value.toStringTruncated(digits + 5).dropLast(5)

    @Test
    fun `pi matches the published expansion to a hundred places`() {
        assertEquals(PI_100, firstDigits(ConstructiveReal.PI, 100))
    }

    @Test
    fun `e matches the published expansion to a hundred places`() {
        assertEquals(E_100, firstDigits(ConstructiveReal.E, 100))
    }

    @Test
    fun `the square root of two matches the published expansion`() {
        val root = ConstructiveReal.valueOf(2).sqrt()
        assertEquals(SQRT2_100, firstDigits(root, 100))
    }

    @Test
    fun `ln two matches the published expansion`() {
        assertEquals(LN2_100, firstDigits(ConstructiveReal.LN2, 100))
    }

    @Test
    fun `one seventh repeats correctly far past double precision`() {
        val seventh = ConstructiveReal.ONE / ConstructiveReal.valueOf(7)
        val text = seventh.toStringTruncated(1002)
        assertTrue(text.startsWith("0.142857142857"))
        // 1000 digits of a six-digit repeat: every block must still be "142857".
        val fraction = text.substringAfter('.').take(996)
        assertEquals(166, fraction.length / 6)
        assertTrue(fraction.chunked(6).all { it == "142857" })
    }

    @Test
    fun `exp and ln invert each other`() {
        val five = ConstructiveReal.valueOf(5)
        val roundTrip = five.ln().exp()
        // Agreement to 50 places is far beyond coincidence.
        assertEquals(firstDigits(five, 50), firstDigits(roundTrip, 50))
    }

    @Test
    fun `sqrt of a square comes back exactly`() {
        val nine = ConstructiveReal.valueOf(9)
        assertEquals("3.0000000000", nine.sqrt().toStringTruncated(10))
    }

    @Test
    fun `transcendental functions agree with the JDK across the reduction branches`() {
        // Values chosen to straddle every argument-reduction cutoff: below 1/2, around 1,
        // past 2 where exp halves, and large enough to force modular reduction in cos.
        val samples = listOf(0.25, 0.5, 1.0, 1.5, 2.0, 3.0, 7.5, 12.25, 100.5)
        for (x in samples) {
            val cr = ConstructiveReal.valueOf(BoundedRational.parse(x.toString()))
            assertClose(Math.sin(x), cr.sin(), "sin($x)")
            assertClose(Math.cos(x), cr.cos(), "cos($x)")
            if (x < 20.0) assertClose(Math.exp(x), cr.exp(), "exp($x)")
            assertClose(Math.log(x), cr.ln(), "ln($x)")
            assertClose(Math.atan(x), cr.atan(), "atan($x)")
        }
    }

    @Test
    fun `the exponential's argument reduction is a loop and still lands on the same value`() {
        // exp halves its argument until the Taylor series converges and squares the result
        // back up. Doing that by recursion cost a stack frame per halving while *building*
        // the chain, and the tree it left behind cost the same depth again when anything
        // approximated it — log2(|x|) + 10 levels whatever the sign of x. Rewriting it as a
        // loop removes half of that; what it must not change is the answer. Thirty-seven and
        // five hundred sit either side of enough halvings to matter.
        for (x in listOf(-500L, -37L, 37L, 500L)) {
            val value = ConstructiveReal.valueOf(x).exp()
            if (x < 0) {
                // Compared through the reciprocal: e^-500 underflows a double long before
                // its digits stop being meaningful, so toDouble would compare 0.0 to 0.0.
                assertClose(Math.exp(-x.toDouble()), value.inverse(), "1/exp($x)")
            } else {
                assertClose(Math.exp(x.toDouble()), value, "exp($x)")
            }
        }
        // Round-tripping across the reduction, where a lost or extra halving would show up
        // as a factor of e^250 rather than as a rounding difference.
        val product = ConstructiveReal.valueOf(-500).exp() * ConstructiveReal.valueOf(500).exp()
        assertClose(1.0, product, "e^-500 x e^500")
        // e is the one value the reduction is easiest to get subtly wrong.
        assertEquals(E_100, firstDigits(ConstructiveReal.E, 100))
    }

    @Test
    fun `an exponential argument too large to reduce is refused rather than overflowing`() {
        // The bound has to live here, not in UnifiedReal: this is the one place every route
        // to an exponential passes through — the e^x key on an opaque value, powViaExpLn and
        // 10^x all arrive with no check of their own. Refusing is the only safe answer,
        // because the tree is expanded by the *formatter*, where a StackOverflowError is an
        // Error that escapes runInterruptible and every catch in the app and ends the
        // process instead of the expression.
        val huge = ConstructiveReal.valueOf(BigInteger.TWO.pow(2000))
        try {
            huge.unaryMinus().exp()
            throw AssertionError("expected TooMuchMemoryException for a negative argument")
        } catch (expected: TooMuchMemoryException) {
        }
        try {
            huge.exp()
            throw AssertionError("expected TooMuchMemoryException for a positive argument")
        } catch (expected: TooMuchMemoryException) {
        }
    }

    @Test
    fun `negative arguments reduce correctly too`() {
        for (x in listOf(-0.25, -1.0, -2.5, -7.0)) {
            val cr = ConstructiveReal.valueOf(BoundedRational.parse(x.toString()))
            assertClose(Math.sin(x), cr.sin(), "sin($x)")
            assertClose(Math.cos(x), cr.cos(), "cos($x)")
            assertClose(Math.exp(x), cr.exp(), "exp($x)")
            assertClose(Math.atan(x), cr.atan(), "atan($x)")
        }
    }

    @Test
    fun `asin and acos land on the right quadrant`() {
        for (x in listOf(-0.9, -0.5, 0.0, 0.25, 0.5, 0.9)) {
            val cr = ConstructiveReal.valueOf(BoundedRational.parse(x.toString()))
            assertClose(Math.asin(x), cr.asin(), "asin($x)")
            assertClose(Math.acos(x), cr.acos(), "acos($x)")
        }
    }

    @Test
    fun `sin of pi is small enough to be indistinguishable from zero`() {
        // It cannot be *proven* zero here — that is UnifiedReal's job — but the constructive
        // value must still agree with zero to many places, or the reduction is wrong.
        val text = ConstructiveReal.PI.sin().toStringTruncated(40)
        assertTrue("sin(pi) = $text", text.startsWith("0.0000000000000000000000000000000"))
    }

    @Test
    fun `comparison decides unequal values`() {
        val a = ConstructiveReal.valueOf(2).sqrt()
        val b = ConstructiveReal.valueOf(BoundedRational.parse("1.41421356"))
        assertTrue(a > b)
        assertTrue(b < a)
        assertEquals(1, a.signum())
    }

    @Test
    fun `asin just short of one keeps the digits the singularity puts there`() {
        // asin has a square-root singularity at 1: asin(1-e) = pi/2 - sqrt(2e). Deciding
        // the endpoint at a fixed precision is therefore not a rounding question — an
        // argument that the old 2^-101 probe declared to be 1 still differs from pi/2 in
        // the 16th decimal place, and in every digit the user scrolls to after it.
        val nearOne = ConstructiveReal.valueOf(
            BoundedRational.parse("0.99999999999999999999999999999999"),
        )
        val gap = ConstructiveReal.PI.shiftRight(1) - nearOne.asin()
        // sqrt(2 x 10^-32), i.e. sqrt(2) x 10^-16, to sixteen significant figures.
        val text = gap.toStringTruncated(40)
        assertTrue(
            "pi/2 - asin(1-1e-32) was $text",
            text.startsWith("0.0000000000000001414213562373095"),
        )
    }

    @Test
    fun `dividing by a zero that cannot be proven zero is reported, not divided`() {
        // Two structurally distinct expressions that happen to be equal can never be shown
        // equal, so the reciprocal's msd search has to give up. Handing Int.MIN_VALUE back
        // instead did not fail loudly: `1 - msd` overflowed into a plausible-looking
        // precision and the division ran with a divisor of exactly zero, throwing a raw
        // BigInteger ArithmeticException lazily at format time where nothing catches it.
        val undecidableZero = ConstructiveReal.ONE - ConstructiveReal.valueOf(1)
        try {
            undecidableZero.inverse().toBigInteger()
            throw AssertionError("expected PrecisionOverflowException")
        } catch (expected: PrecisionOverflowException) {
        }
    }

    @Test
    fun `a value that cannot be separated from zero gives up inside the msd budget`() {
        // Not a wrong answer before, but the doubling only stopped where the Int shift
        // counts overflowed, at 268 million bits — a hundred times past the budget the msd
        // search uses for the very same undecidable-zero problem.
        val undecidableZero = ConstructiveReal.ONE - ConstructiveReal.valueOf(1)
        try {
            undecidableZero.signum()
            throw AssertionError("expected PrecisionOverflowException")
        } catch (expected: PrecisionOverflowException) {
        }
        try {
            ConstructiveReal.ONE.compareTo(ConstructiveReal.valueOf(1))
            throw AssertionError("expected PrecisionOverflowException")
        } catch (expected: PrecisionOverflowException) {
        }
    }

    @Test
    fun `a shared operand refined by other threads still yields the same digits`() {
        // getAppr is synchronised, but msd and knownMsd read the same three non-volatile
        // fields without the monitor, and MultCR calls knownMsd on an operand that another
        // evaluation job may be refining at that instant. A torn (minPrec, maxAppr) pair
        // understates the msd, and MultCR budgets its *other* operand from that number —
        // so the failure is silently wrong low-order digits, not an exception. A stress
        // test can only notice the race, never prove it gone; it is here so that removing
        // the synchronisation has at least one thing standing in its way.
        val three = ConstructiveReal.valueOf(3)
        // A separate, uncontended instance: what the single-threaded engine answers.
        val expected = (ConstructiveReal.valueOf(2).sqrt() * three).toStringTruncated(400)

        val shared = ConstructiveReal.valueOf(2).sqrt()
        val workers = 8
        val rounds = 4
        val seen = arrayOfNulls<String>(workers * rounds)
        val failed = arrayOfNulls<Throwable>(workers)
        val threads = (0 until workers).map { index ->
            Thread {
                try {
                    for (round in 0 until rounds) {
                        // Deliberately mismatched precisions, so the threads keep rewriting
                        // the shared cache underneath each other instead of settling.
                        shared.toStringTruncated(37 * index + 11 * round + 1)
                        seen[index * rounds + round] =
                            (shared * three).toStringTruncated(400)
                    }
                } catch (t: Throwable) {
                    failed[index] = t
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(60_000) }
        assertTrue("a worker threw ${failed.filterNotNull()}", failed.all { it == null })
        assertTrue("workers disagreed with $expected: ${seen.toSet()}", seen.all { it == expected })
    }

    @Test
    fun `an interrupted computation aborts promptly instead of spinning`() {
        // The whole point of the cooperative check: cancelling must actually stop the work.
        var thrown: Throwable? = null
        val worker = Thread {
            try {
                // Deep enough that it cannot possibly finish before the interrupt lands.
                ConstructiveReal.PI.toStringTruncated(2_000_000)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        worker.start()
        Thread.sleep(200)
        worker.interrupt()
        worker.join(10_000)
        assertTrue("worker did not stop", !worker.isAlive)
        assertTrue("expected AbortedException but got $thrown", thrown is AbortedException)
    }

    private fun assertClose(expected: Double, actual: ConstructiveReal, label: String) {
        val got = actual.toDouble()
        val tolerance = Math.max(Math.abs(expected) * 1e-12, 1e-12)
        assertTrue(
            "$label: expected $expected but was $got",
            Math.abs(expected - got) <= tolerance,
        )
    }
}
