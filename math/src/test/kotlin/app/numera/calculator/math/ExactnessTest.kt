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

    /**
     * Runs [body] on a thread with the 1 MB stack ART gives a non-main thread.
     *
     * The JVM running these tests hands the main thread eight megabytes, which is enough to
     * hide a reduction depth that kills the app on a device — and it kills it rather than
     * failing it, because a `StackOverflowError` is an `Error` that no catch in the engine
     * or the view model is looking for. Anything asserting a depth bound has to be measured
     * against the stack the work will actually run on.
     */
    private fun onAOneMegabyteStack(label: String, body: () -> Unit) {
        var thrown: Throwable? = null
        val worker = Thread(
            null,
            {
                try {
                    body()
                } catch (t: Throwable) {
                    thrown = t
                }
            },
            label,
            1L shl 20,
        )
        worker.start()
        worker.join(60_000)
        assertTrue("$label did not finish", !worker.isAlive)
        thrown?.let { throw AssertionError("$label threw $it", it) }
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
    fun `a square factor past the trial division bound is still pulled out of the radicand`() {
        // Trial division stops at ten thousand, so 10007² — one prime past it — was left
        // inside the radicand and √200280098 became a different object from 10007√2. The
        // value was never wrong; what was lost is decidability, and with it the app's
        // headline claim for these inputs: a difference that is exactly zero came back as
        // 0… with an ellipsis instead of as an exact 0. Once the small primes still present
        // are known, the part of the remainder built only from large ones can be tested for
        // being a perfect square in a single step, which settles this without factoring.
        val normalised = n(200280098).sqrt()
        assertEquals(Factor.Sqrt(BigInteger.TWO), normalised.factor)
        assertEquals(BoundedRational.of(10007L), normalised.ratFactor)
        val byHand = n(10007) * n(2).sqrt()
        assertEquals(byHand, normalised)
        val difference = normalised - byHand
        assertTrue(
            "√200280098 − 10007√2 was ${difference.toNiceString()}",
            difference.definitelyZero(),
        )
        // The square-free part may be a product of several distinct small primes rather
        // than one: 10007²·6 is 600,840,294, and must come back as 10007√6.
        val several = n(600840294L).sqrt()
        assertEquals(Factor.Sqrt(BigInteger.valueOf(6L)), several.factor)
        assertEquals(BoundedRational.of(10007L), several.ratFactor)
        // The ordinary cases have to keep agreeing after the change of algorithm.
        assertEquals(BoundedRational.of(5L), n(50).sqrt().ratFactor)
        assertEquals(Factor.Sqrt(BigInteger.TWO), n(50).sqrt().factor)
        assertExactly(BoundedRational.of(3L), n(9).sqrt(), "√9")
        assertExactly(BoundedRational.of(1L, 2L), frac(1, 4).sqrt(), "√0.25")
    }

    @Test
    fun `an interrupted square root stops instead of finishing the factorisation`() {
        // The inner peel-one-square-at-a-time loop was the only loop in this module with no
        // cancellation point, and it is where all the time went: the outer loop runs at most
        // ten thousand cheap iterations, the inner one runs once per factor and is unbounded
        // in the size of the input. √(1E100000) spent seven seconds there, four of them
        // *after* the interrupt landed — so withTimeoutOrNull returned, the UI moved on, and
        // a Dispatchers.Default thread kept a core pinned for every later keystroke.
        var thrown: Throwable? = null
        val worker = Thread {
            Thread.currentThread().interrupt()
            try {
                UnifiedReal.of(BoundedRational.parse("1E100000")).sqrt()
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
        // A negative exponent of an ordinary size is still not refused for its *width*:
        // e^(−10^7) is zero to every digit anyone can scroll to. Asserting that the factor
        // was built is not enough on its own, though — that was the shape of the assertion
        // that let the crash below through — so the value has to yield a digit as well.
        assertEquals(Factor.Exp(BoundedRational.of(-10000000L)), n(-10000000).exp().factor)
        val tiny = n(-10000000).exp().toConstructiveReal().toStringTruncated(20)
        assertEquals("0.00000000000000000000", tiny)
        // What was answerable before must stay answerable.
        assertExactly(BoundedRational.of(1024L), n(2).pow(n(10)), "2^10")
        assertFalse(n(2).pow(n(100000)).isRational)
    }

    @Test
    fun `a negative exponential too deep to reduce is refused instead of killing the process`() {
        // The old guard returned early for any non-positive exponent, on the reasoning that
        // e^(−10^7) is zero to every digit anyone can scroll to. That reasoning is about the
        // *width* of the answer; the cost that actually bites is the *depth* of the argument
        // reduction in ConstructiveReal.exp, which halves until |x| is under about 1/512 and
        // is therefore log2(|x|) levels deep whatever the sign — symmetric where the guard
        // was not. e^(0−10^600) is ten keypresses.
        //
        // What makes it a crash rather than an error is when the recursion runs. The
        // evaluator returns in microseconds, because Factor.Exp is only expanded once the
        // *formatter* asks for a digit — and a StackOverflowError is an Error, so it escapes
        // runInterruptible, withTimeoutOrNull and every catch in the view model and reaches
        // the platform handler. It fires from the preview job while typing, before = is ever
        // pressed. The only safe answer is a refusal the evaluator can still report.
        val huge = UnifiedReal.of(BoundedRational.parse("1E600"))
        try {
            (UnifiedReal.ZERO - huge).exp()
            throw AssertionError("expected TooMuchMemoryException")
        } catch (expected: TooMuchMemoryException) {
        }

        // And the bound has to leave room, or it only moves the crash: the largest argument
        // still accepted must render on the 1 MB stack a non-main Android thread is given.
        val nearTheLimit = UnifiedReal.of(
            BoundedRational.of(BigInteger.TWO.pow(299), BigInteger.ONE),
        )
        onAOneMegabyteStack("e^(−2^299)") {
            val text = (UnifiedReal.ZERO - nearTheLimit).exp()
                .toConstructiveReal()
                .toStringTruncated(20)
            assertEquals("0.00000000000000000000", text)
        }
    }

    @Test
    fun `an oversized exponent is refused the same way once it stops being rational`() {
        // BoundedRational gives up above MAX_RATIONAL_BITS, so 1E10000 is an exact rational
        // and 1E10000 + 1 is a Factor.Opaque — one keypress apart. exp() bounded only the
        // rational branch, so the second one descended thirty thousand halvings and was
        // reported as "Bad expression", by way of ExprEvaluator's StackOverflowError catch,
        // for a value whose actual problem is its size. Two adjacent expressions, two
        // different diagnoses, one of them wrong. The bound belongs where every route passes.
        val big = UnifiedReal.of(BoundedRational.parse("1E10000"))
        try {
            big.exp()
            throw AssertionError("expected TooMuchMemoryException from the rational branch")
        } catch (expected: TooMuchMemoryException) {
        }

        val opaque = big + UnifiedReal.ONE
        assertFalse("1E10000 + 1 was expected to fall through to Factor.Opaque", opaque.isRational)
        try {
            opaque.exp()
            throw AssertionError("expected TooMuchMemoryException from the opaque branch")
        } catch (expected: TooMuchMemoryException) {
        }
        // 10^x and y^x reach the same Taylor series through powViaExpLn, with no check of
        // their own, so they have to be refused by the same bound.
        try {
            n(10).pow(opaque)
            throw AssertionError("expected TooMuchMemoryException from powViaExpLn")
        } catch (expected: TooMuchMemoryException) {
        }
    }

    @Test
    fun `a power driven far below one is answered rather than refused`() {
        // The mirror image of the exponential's sign bug. The pre-emptive size check
        // measured the exponent by its magnitude, so it refused a large *negative* one too:
        // 2^(−1000000) and (1/2)^1000000 are the same value, about 10^−301030, and both
        // reported "requires too much memory" — while the identically sized e^(−1000000)
        // printed 0…, and boundedProduct's own KDoc promises that a product driven far below
        // one is never refused. Reachable by typing 2 ^ ( 0 − 1 0 0 0 0 0 0 ).
        val zeros = "0." + "0".repeat(50)
        assertEquals(zeros, n(2).pow(n(-1000000)).toConstructiveReal().toStringTruncated(50))
        assertEquals(zeros, frac(1, 2).pow(n(1000000)).toConstructiveReal().toStringTruncated(50))
        // The large side must still be refused, and by the same estimate.
        try {
            n(2).pow(n(1000000))
            throw AssertionError("expected TooMuchMemoryException")
        } catch (expected: TooMuchMemoryException) {
        }
    }

    @Test
    fun `zero raised to an irrational power is zero rather than a bad expression`() {
        // pow consulted definitelyZero only by accident, through BoundedRational.pow on the
        // whole-exponent path. A non-rational exponent went straight to powViaExpLn, whose
        // ln(0) sends InvCR into a most-significant-bit search on a value that is identically
        // zero — a search that can never decide, so it gave up with a precision overflow and
        // the display said "Bad expression" for a defined value sitting one keystroke from
        // the 0^2 that answers 0.
        assertEquals(UnifiedReal.ZERO, UnifiedReal.ZERO.pow(UnifiedReal.PI))
        assertEquals(UnifiedReal.ZERO, UnifiedReal.ZERO.pow(frac(1, 3)))
        assertEquals(UnifiedReal.ZERO, UnifiedReal.ZERO.pow(n(2)))
        // 0^0 is 1, matching BoundedRational.pow rather than contradicting it.
        assertEquals(UnifiedReal.ONE, UnifiedReal.ZERO.pow(UnifiedReal.ZERO))
        // A negative exponent is a reciprocal of zero, and must be reported as one — it
        // threw a precision overflow before, i.e. the wrong error as well as a late one.
        try {
            UnifiedReal.ZERO.pow(-UnifiedReal.PI)
            throw AssertionError("expected DivideByZeroException")
        } catch (expected: DivideByZeroException) {
        }
    }

    @Test
    fun `an exponential wide beyond the display is refused whichever route builds it`() {
        // MAX_EXP_BITS was enforced only by checkExpSize, i.e. only for a rational
        // exponent. π×1E8 is Factor.Pi, so exp() took the opaque branch, and there the only
        // refusal was the 2^300 bound on the argument's *magnitude* — 29 bits, waved
        // through — for a value 450 million bits wide. Nothing failed in the evaluator;
        // the formatter asked for a digit and the process was gone.
        val wide = UnifiedReal.PI * n(100_000_000)
        try {
            wide.exp()
            throw AssertionError("expected TooMuchMemoryException from exp on an opaque value")
        } catch (expected: TooMuchMemoryException) {
        }
        try {
            n(10).pow(wide)
            throw AssertionError("expected TooMuchMemoryException from powViaExpLn")
        } catch (expected: TooMuchMemoryException) {
        }
        // An opaque base with a whole exponent past 64 is the third route: 65 × ln(1E100000)
        // is about 1.5e7, and 1E100000 + 1 is opaque because it is too wide to stay rational.
        try {
            (parse("1E100000") + UnifiedReal.ONE).pow(n(65))
            throw AssertionError("expected TooMuchMemoryException from powWithSign")
        } catch (expected: TooMuchMemoryException) {
        }
        // Inside the bound — 453,000 bits — the value must still be built. Only evaluation
        // is asserted: rendering it would run the series at that width, which is minutes.
        assertFalse((UnifiedReal.PI * n(100_000)).exp().isRational)
    }

    @Test
    fun `a product with a huge rational factor is bounded at the operands' own scale`() {
        // boundedProduct probed the *product* at precision zero. MultCR budgets its small
        // operand from the size of the large one, so `sin(1) × 1E100000` asked sin(1) for
        // 332,195 bits after the point — a cosine series carried to a third of a million
        // bits, inside the evaluator, for a value the display shows as 8.4E99999. The
        // 1 s preview never appeared and the 15 s evaluation reported "timed out". The
        // operand here throws if it is asked for more than 200 bits.
        val small = UnifiedReal.of(PrecisionCeilingCR(861, -200))
        val huge = parse("1E100000")
        val product = small * huge
        assertFalse(product.isRational)
        val real = product.toConstructiveReal()
        // Within a bit of floor(log2(0.84 × 10^100000)): an msd read off an approximation
        // can be one high, as in creals.
        val msd = real.estimateMsd(-64)
        assertTrue("msd was $msd", Math.abs(msd - 332192) <= 1)
        assertTrue(real.toDouble().isInfinite())
        // The other operand order, and a product of two opaque values, budget the same way.
        assertTrue((huge * small).toConstructiveReal().toDouble().isInfinite())
        val opaqueHuge = huge + UnifiedReal.ONE
        val opaqueMsd = (small * opaqueHuge).toConstructiveReal().estimateMsd(-64)
        assertTrue("msd was $opaqueMsd", Math.abs(opaqueMsd - 332192) <= 1)
        // The bound itself still holds, from the operands: 1E800000 × 1E400000 is refused
        // and 1E100 × sin(1) is an ordinary double.
        try {
            (parse("1E400000") * parse("1E400000")) * parse("1E400000")
            throw AssertionError("expected TooMuchMemoryException")
        } catch (expected: TooMuchMemoryException) {
        }
        val moderate = (n(1).sin(AngleMode.RADIANS) * parse("1E100")).toConstructiveReal().toDouble()
        assertTrue("sin(1) × 1E100 was $moderate", Math.abs(moderate / (Math.sin(1.0) * 1e100) - 1.0) < 1e-12)
    }

    @Test
    fun `powers of a unit base stay exact for any exponent`() {
        // (0−1)^20000 printed 1.000… with an ellipsis and (0−1)^2000000 was "requires too
        // much memory", while 1^2000000 was exact: BoundedRational.pow short-circuited only
        // +1, and the width test that followed measures −1 as one bit times the exponent.
        // 1^π went to powViaExpLn and came back opaque for the same reason one step up.
        assertExactly(BoundedRational.ONE, n(-1).pow(n(20000)), "(−1)^20000")
        assertExactly(BoundedRational.MINUS_ONE, n(-1).pow(n(2000001)), "(−1)^2000001")
        assertExactly(BoundedRational.ONE, n(-1).pow(n(2000000)), "(−1)^2000000")
        assertExactly(BoundedRational.MINUS_ONE, n(-1).pow(n(-3)), "(−1)^−3")
        assertExactly(BoundedRational.ONE, n(1).pow(UnifiedReal.PI), "1^π")
        assertExactly(BoundedRational.ONE, n(1).pow(n(1).sin(AngleMode.RADIANS)), "1^sin(1)")
        assertExactly(BoundedRational.ONE, n(1).pow(n(2000000)), "1^2000000")
        // A negative base with a fractional exponent is still not a real number.
        try {
            n(-1).pow(UnifiedReal.PI)
            throw AssertionError("expected NotANumberException")
        } catch (expected: NotANumberException) {
        }
    }

    @Test
    fun `zero raised to an opaque power is decided from the exponent's sign`() {
        // The zero-base guard read the exponent's sign from its factor and fell through for
        // Factor.Opaque, reasoning that deciding it was an undecidable search. The search
        // that is undecidable is ln(0) on the *base*, which is exactly where the
        // fall-through went — so 0^sin(1) was "Bad expression" while 0^π was 0.
        val sine = n(1).sin(AngleMode.RADIANS)
        assertFalse(sine.isRational)
        assertEquals(UnifiedReal.ZERO, UnifiedReal.ZERO.pow(sine))
        try {
            UnifiedReal.ZERO.pow(-sine)
            throw AssertionError("expected DivideByZeroException")
        } catch (expected: DivideByZeroException) {
        }
    }

    @Test
    fun `a rational power that shrinks is answered whatever the base's width`() {
        // The size estimate measures the base by bit lengths, which cannot tell 9/10 from
        // 10/9: both give a scale of zero, so the |whole| slack alone refused 0.9^2000000 —
        // about 10^−91515, which renders as 0… — with the very error the rule beside it
        // had been written to prevent. Whether a power grows is a property of the value.
        val zeros = "0." + "0".repeat(50)
        assertEquals(zeros, frac(9, 10).pow(n(2000000)).toConstructiveReal().toStringTruncated(50))
        assertEquals(zeros, frac(3, 4).pow(n(1500000)).toConstructiveReal().toStringTruncated(50))
        assertEquals(zeros, frac(10, 9).pow(n(-2000000)).toConstructiveReal().toStringTruncated(50))
        // Growth in either direction of the sign is still refused.
        try {
            frac(1, 2).pow(n(-2000000))
            throw AssertionError("expected TooMuchMemoryException")
        } catch (expected: TooMuchMemoryException) {
        }
        try {
            n(2).pow(n(2000000))
            throw AssertionError("expected TooMuchMemoryException")
        } catch (expected: TooMuchMemoryException) {
        }
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
