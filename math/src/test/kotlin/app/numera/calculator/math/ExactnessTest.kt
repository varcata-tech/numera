package app.numera.calculator.math

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The promises the calculator makes to its user, asserted on structure rather than on text.
 *
 * Checking the rendered string would pass for the wrong reason: a value that is merely
 * *close* to 1 prints as "1" once it is rounded to ten places. Asserting that the result
 * is rational with a numerator of 1 instead proves the engine actually knows the answer is
 * one, which is the whole difference between this calculator and a floating-point one.
 */
class ExactnessTest {

    private fun n(value: Long) = UnifiedReal.of(value)
    private fun frac(a: Long, b: Long) = UnifiedReal.of(BoundedRational.of(a, b))
    private fun parse(text: String) = UnifiedReal.of(BoundedRational.parse(text))

    private fun assertExactly(expected: BoundedRational, actual: UnifiedReal, label: String) {
        assertTrue("$label: expected a rational but got ${actual.toNiceString()}", actual.isRational)
        assertEquals(label, expected, actual.asRational())
    }

    @Test
    fun `one third times three is exactly one`() {
        assertExactly(BoundedRational.ONE, (n(1) / n(3)) * n(3), "1/3*3")
    }

    @Test
    fun `root two times root two is exactly two`() {
        val root2 = n(2).sqrt()
        assertEquals(Factor.Sqrt(BigInteger.TWO), root2.factor)
        assertExactly(BoundedRational.of(2L), root2 * root2, "√2*√2")
    }

    @Test
    fun `a tenth plus a fifth is exactly three tenths`() {
        assertExactly(BoundedRational.of(3L, 10L), parse("0.1") + parse("0.2"), "0.1+0.2")
    }

    @Test
    fun `root eight is normalised to two root two`() {
        val root8 = n(8).sqrt()
        assertEquals(Factor.Sqrt(BigInteger.TWO), root8.factor)
        assertEquals(BoundedRational.of(2L), root8.ratFactor)
    }

    @Test
    fun `root two plus root eight collapses to three root two`() {
        // Only possible because both are normalised to the same square-free radicand.
        val sum = n(2).sqrt() + n(8).sqrt()
        assertEquals(Factor.Sqrt(BigInteger.TWO), sum.factor)
        assertEquals(BoundedRational.of(3L), sum.ratFactor)
    }

    @Test
    fun `two to the power of a half squared is exactly two`() {
        val half = frac(1, 2)
        val root = n(2).pow(half)
        assertExactly(BoundedRational.of(2L), root * root, "2^0.5 squared")
    }

    @Test
    fun `root two to the fourth power is exactly four`() {
        assertExactly(BoundedRational.of(4L), n(2).sqrt().pow(n(4)), "(√2)^4")
    }

    @Test
    fun `two to the tenth is exactly 1024`() {
        assertExactly(BoundedRational.of(1024L), n(2).pow(n(10)), "2^10")
    }

    @Test
    fun `pi divided by pi is exactly one`() {
        assertExactly(BoundedRational.ONE, UnifiedReal.PI / UnifiedReal.PI, "π/π")
    }

    @Test
    fun `e times e is e squared and dividing it back gives one`() {
        val eSquared = UnifiedReal.E * UnifiedReal.E
        assertEquals(Factor.Exp(BoundedRational.of(2L)), eSquared.factor)
        assertExactly(BoundedRational.ONE, eSquared / eSquared, "e²/e²")
    }

    @Test
    fun `the log of e is exactly one`() {
        assertExactly(BoundedRational.ONE, UnifiedReal.E.ln(), "ln e")
    }

    @Test
    fun `e to the power of the log of five is exactly five`() {
        assertExactly(BoundedRational.of(5L), n(5).ln().exp(), "e^ln5")
    }

    @Test
    fun `base ten logs of exact powers of ten are exact`() {
        assertExactly(BoundedRational.of(3L), n(1000).log10(), "log 1000")
        assertExactly(BoundedRational.of(-3L), parse("0.001").log10(), "log 0.001")
        assertExactly(BoundedRational.ZERO, n(1).log10(), "log 1")
    }

    @Test
    fun `sine of thirty degrees is exactly one half`() {
        assertExactly(BoundedRational.HALF, n(30).sin(AngleMode.DEGREES), "sin 30°")
    }

    @Test
    fun `sine of forty five degrees is exactly root two over two`() {
        val value = n(45).sin(AngleMode.DEGREES)
        assertEquals(Factor.Sqrt(BigInteger.TWO), value.factor)
        assertEquals(BoundedRational.HALF, value.ratFactor)
    }

    @Test
    fun `sine of pi radians is exactly zero and cosine is exactly minus one`() {
        assertTrue(UnifiedReal.PI.sin(AngleMode.RADIANS).definitelyZero())
        assertExactly(
            BoundedRational.MINUS_ONE,
            UnifiedReal.PI.cos(AngleMode.RADIANS),
            "cos π",
        )
    }

    @Test
    fun `sine of a rational multiple of pi is exact in radian mode`() {
        // π/6 radians is 30 degrees, and must land on the table rather than the series.
        val sixthOfPi = UnifiedReal.PI * frac(1, 6)
        assertExactly(BoundedRational.HALF, sixthOfPi.sin(AngleMode.RADIANS), "sin(π/6)")
    }

    @Test
    fun `tangent of ninety degrees is reported as a division by zero`() {
        // A floating-point calculator prints 1.633e16 here, because its pi is slightly off.
        try {
            n(90).tan(AngleMode.DEGREES)
            throw AssertionError("expected DivideByZeroException")
        } catch (expected: DivideByZeroException) {
        }
    }

    @Test
    fun `inverse sine and cosine invert the exact table`() {
        assertExactly(
            BoundedRational.of(30L),
            frac(1, 2).asin(AngleMode.DEGREES),
            "asin(0.5)",
        )
        // The principal branch matters: cos is 1/2 at both -60 and +60 degrees.
        assertExactly(
            BoundedRational.of(60L),
            frac(1, 2).acos(AngleMode.DEGREES),
            "acos(0.5)",
        )
        assertExactly(
            BoundedRational.of(-30L),
            frac(-1, 2).asin(AngleMode.DEGREES),
            "asin(-0.5)",
        )
    }

    @Test
    fun `inverse sine is exact in radians as a multiple of pi`() {
        val result = frac(1, 2).asin(AngleMode.RADIANS)
        assertEquals(Factor.Pi(1), result.factor)
        assertEquals(BoundedRational.of(1L, 6L), result.ratFactor)
    }

    @Test
    fun `an angle near the Int ceiling reaches the table entry it actually names`() {
        // cos and tan consult the table as sin(θ + 90°), and asIntOrNull admits every angle
        // up to Int.MAX_VALUE, so that addition can overflow. 2^32 is 256 mod 360, which
        // means the wrap does not fall off the table — it lands on a *different* entry and
        // answers a different question with the full confidence of the exact path.
        assertExactly(
            BoundedRational.ZERO,
            n(2147483610).cos(AngleMode.DEGREES),
            "cos(2147483610°)",
        )
        try {
            n(2147483610).tan(AngleMode.DEGREES)
            throw AssertionError("expected DivideByZeroException")
        } catch (expected: DivideByZeroException) {
        }

        // 2147483596° is 76°, which is not on the table at all: this one has to fall
        // through to the series rather than come back as the −1 the wrapped angle produced.
        val offTable = n(2147483596).cos(AngleMode.DEGREES)
        assertFalse("cos(2147483596°) was answered from the table", offTable.isRational)
        val computed = offTable.toConstructiveReal().toDouble()
        val expected = Math.cos(Math.toRadians(76.0))
        assertTrue("cos(2147483596°) was $computed", Math.abs(computed - expected) < 1e-9)
    }

    @Test
    fun `half integer powers stay exact instead of turning opaque`() {
        // 9^1.5 is exactly 27. Every half exponent other than +1/2 used to go through
        // exp/ln and come back as a Factor.Opaque, which can never report that it
        // terminates — so a whole number was displayed padded with zeros and an ellipsis.
        assertExactly(BoundedRational.of(27L), n(9).pow(frac(3, 2)), "9^1.5")
        assertExactly(BoundedRational.HALF, n(4).pow(frac(-1, 2)), "4^-0.5")
        assertExactly(BoundedRational.of(32L), n(4).pow(frac(5, 2)), "4^2.5")
        // Still exact when the root does not come out whole: 2^1.5 is 2√2, not 2.828…
        val twoRoot2 = n(2).pow(frac(3, 2))
        assertEquals(Factor.Sqrt(BigInteger.TWO), twoRoot2.factor)
        assertEquals(BoundedRational.of(2L), twoRoot2.ratFactor)
    }

    @Test
    fun `an exponential too large to ever be shown is refused before any work`() {
        // The e^x key does not go through pow, so exp used to build Factor.Exp(10^7) for
        // free and leave the *formatter* to discover, fourteen million bits later, that it
        // could not finish — in the one stage with neither a timeout nor a catch round it.
        try {
            n(10000000).exp()
            throw AssertionError("expected TooMuchMemoryException")
        } catch (expected: TooMuchMemoryException) {
        }
        // 10^x reaches the same Taylor series by another route and must be refused with it.
        try {
            n(10).pow(n(10000000))
            throw AssertionError("expected TooMuchMemoryException")
        } catch (expected: TooMuchMemoryException) {
        }
        // A negative exponent is deliberately not refused: e^(−10^7) is zero to every digit
        // anyone can scroll to, and the approximation layer settles it without allocating.
        assertEquals(Factor.Exp(BoundedRational.of(-10000000L)), n(-10000000).exp().factor)
        // What was answerable before must stay answerable.
        assertExactly(BoundedRational.of(1024L), n(2).pow(n(10)), "2^10")
        assertFalse(n(2).pow(n(100000)).isRational)
    }

    @Test
    fun `a symbolic power of pi is expanded correctly and bounded`() {
        // The exponent doubles on every press of x², and nothing capped it. Expanding it
        // into that many chained multiplications builds a product nested deep enough that
        // approximating it overflows the stack inside the formatter, which — unlike the
        // evaluator — catches nothing.
        val piCubed = UnifiedReal.PI * UnifiedReal.PI * UnifiedReal.PI
        assertEquals(Factor.Pi(3), piCubed.factor)
        val cubed = piCubed.toConstructiveReal().toDouble()
        assertTrue("π³ was $cubed", Math.abs(cubed - Math.PI * Math.PI * Math.PI) < 1e-9)

        val reciprocal = UnifiedReal.ONE / (UnifiedReal.PI * UnifiedReal.PI)
        assertEquals(Factor.Pi(-2), reciprocal.factor)
        val inverse = reciprocal.toConstructiveReal().toDouble()
        assertTrue("1/π² was $inverse", Math.abs(inverse - 1.0 / (Math.PI * Math.PI)) < 1e-9)

        var value = UnifiedReal.PI
        try {
            repeat(20) { value *= value }
            throw AssertionError("expected TooMuchMemoryException")
        } catch (expected: TooMuchMemoryException) {
        }
    }

    @Test
    fun `a negative base raised to a whole power keeps its sign instead of failing`() {
        // BoundedRational.pow declines any result wider than MAX_RATIONAL_BITS, and the
        // fall-through is powViaExpLn, whose first act is to refuse a negative base because
        // ln has no value there. That is right for a fractional exponent and wrong for a
        // whole one: (−1024)^1200 was reported as "not a number" even though it is the
        // ordinary positive integer 2^12000. The threshold is width(base) × exponent, so
        // base −10 crosses it at exponent 2500 — well inside what parentheses can type.
        val even = n(-1024).pow(n(1200))
        val expected = BigInteger.TWO.pow(12000).toString()
        val computed = even.toConstructiveReal().toStringTruncated(0)
        assertEquals("(-1024)^1200 had the wrong width", expected.length, computed.length)
        assertEquals(expected.substring(0, 40), computed.substring(0, 40))

        // Parity, not the domain of ln, is what decides the sign.
        assertEquals("(-1024)^1201 must be negative", -1, n(-1024).pow(n(1201)).signum())

        // An exponent small enough to stay exact never reached the broken path, and its
        // answer must not have moved.
        assertExactly(BoundedRational.of(-8L), n(-2).pow(n(3)), "(-2)^3")

        // An irrational base falls through the same way once the exponent is too large to
        // unroll as repeated multiplication: (−√2)^100 is +2^50.
        val root = (-n(2).sqrt()).pow(n(100))
        assertEquals(1, root.signum())
        val ratio = root.toConstructiveReal().toDouble() / Math.pow(2.0, 50.0)
        assertTrue("(-√2)^100 / 2^50 was $ratio", Math.abs(ratio - 1.0) < 1e-9)
    }

    @Test
    fun `a product too large to ever be rendered is refused inside the evaluator`() {
        // pow and exp each bound what they can produce; repeated multiplication bounded
        // nothing. Once a product outgrows MAX_RATIONAL_BITS it is an opaque constructive
        // real whose size nothing tracks, so further presses of × grow it without limit —
        // and the first thing to object was the formatter, trying to normalise a mantissa
        // against 10^1000000. Formatting runs outside the evaluator's catch, so that threw
        // the process away instead of the expression. From the keypad the route is eleven
        // copies of 1E100000 — the parser's largest literal — chained with ×; a wider
        // literal is used here only to reach the same magnitude in two multiplications.
        val big = parse("1E400000")
        val squared = big * big
        // Still perfectly displayable, so it must not be refused.
        assertFalse(squared.isRational)
        try {
            squared * big
            throw AssertionError("expected TooMuchMemoryException")
        } catch (expected: TooMuchMemoryException) {
        }

        // The small side is deliberately unbounded: a product driven far below one is
        // shown as 0… after a bounded digit search, which is the honest answer and cheap.
        val tiny = parse("1E-400000")
        assertFalse((tiny * tiny).isRational)
    }

    @Test
    fun `the square root of a negative number is a domain error`() {
        try {
            n(-1).sqrt()
            throw AssertionError("expected NotANumberException")
        } catch (expected: NotANumberException) {
        }
    }

    @Test
    fun `dividing by an exact zero is reported rather than approximated`() {
        try {
            n(1) / UnifiedReal.ZERO
            throw AssertionError("expected DivideByZeroException")
        } catch (expected: DivideByZeroException) {
        }
    }

    @Test
    fun `exact values know whether they can be written out in full`() {
        assertEquals(2, frac(1, 4).digitsRequired())
        assertEquals("0.25", frac(1, 4).exactDecimalOrNull())
        // 1/7 never terminates, and √2 is not rational at all: both must decline.
        assertNull(frac(1, 7).digitsRequired())
        assertNull(n(2).sqrt().digitsRequired())
        assertNull(n(2).sqrt().exactDecimalOrNull())
    }

    @Test
    fun `one hundred factorial stays exact`() {
        val value = n(100).factorial().asBigInteger()!!
        assertEquals(158, value.toString().length)
    }

    @Test
    fun `comparison of like terms is decided without approximating`() {
        val twoRoot2 = n(2).sqrt() * n(2)
        val threeRoot2 = n(2).sqrt() * n(3)
        assertTrue(twoRoot2.isComparable(threeRoot2))
        assertTrue(twoRoot2 < threeRoot2)
        assertEquals(1, threeRoot2.signum())
        assertFalse(threeRoot2.definitelyZero())
    }

    @Test
    fun `values that fall outside the symbolic forms are still numerically right`() {
        // sin(1 radian) has no exact form, so it becomes opaque — but must still be correct.
        val value = n(1).sin(AngleMode.RADIANS)
        assertFalse(value.isRational)
        val expected = Math.sin(1.0)
        assertTrue(Math.abs(value.toConstructiveReal().toDouble() - expected) < 1e-12)
    }
}
