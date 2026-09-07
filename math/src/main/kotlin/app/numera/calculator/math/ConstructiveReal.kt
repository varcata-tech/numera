package app.numera.calculator.math

import app.numera.calculator.math.CalculationLimits.checkNotAborted
import java.math.BigInteger

/**
 * A real number represented not by digits but by a *procedure* for producing digits.
 *
 * The entire library rests on one contract, [approximate]: given a binary precision `p`,
 * return an integer `n` such that `|n·2^p − value| ≤ 2^p`. Nothing is ever rounded early,
 * so asking for a thousand digits of `1÷7` gives a thousand *correct* digits rather than
 * fifteen correct ones followed by noise.
 *
 * The price is that equality is only semi-decidable. Comparing two constructive reals that
 * happen to be equal can refine forever without ever proving it, which is why every
 * comparison here is bounded and why [UnifiedReal] sits on top to decide the cases that
 * actually arise from a keypad.
 *
 * Kotlin reimplementation of Hans-J. Boehm's constructive reals (`com.hp.creals`), as
 * used by AOSP's ExactCalculator. See NOTICE.md.
 */
abstract class ConstructiveReal {

    /** Precision of [maxAppr], meaningful only while [apprValid]. */
    private var minPrec: Int = 0

    /** Best approximation computed so far, cached so more digits cost only the difference. */
    private var maxAppr: BigInteger? = null

    private var apprValid: Boolean = false

    /**
     * Returns `n` with `|n·2^precision − value| ≤ 2^precision`.
     *
     * Implementations may assume nothing about call order and must not cache — caching is
     * [getAppr]'s job, and doing it twice is how the two copies drift apart.
     */
    protected abstract fun approximate(precision: Int): BigInteger

    /**
     * Cached, monotone [approximate].
     *
     * A request coarser than what is already known is served by shifting the cached value
     * down, which is what makes scrolling a result *incremental*: the digits already on
     * screen are never recomputed.
     *
     * Synchronised because shared constants like [PI] are touched from whichever thread
     * happens to be evaluating, and a torn read of the cache pair yields a wrong answer
     * rather than an exception.
     */
    @Synchronized
    fun getAppr(precision: Int): BigInteger {
        checkPrecision(precision)
        val cached = maxAppr
        if (apprValid && cached != null && precision >= minPrec) {
            return scale(cached, minPrec - precision)
        }
        checkNotAborted()
        val result = approximate(precision)
        minPrec = precision
        maxAppr = result
        apprValid = true
        return result
    }

    /**
     * Position of the most significant bit, given a valid approximation. Requires |appr| > 1.
     *
     * Synchronised for the same reason [getAppr] is, and it is the same three fields at
     * risk. Reading them outside the monitor has no happens-before edge against another
     * thread refining a shared constant, so the pair can be observed torn — a fine
     * `minPrec` alongside a coarse `maxAppr` — which understates the msd by the difference.
     * [MultCR] budgets its other operand from that number, so the understatement does not
     * throw: it silently fetches the other operand thousands of bits short and returns a
     * product whose low digits are noise.
     */
    @Synchronized
    internal fun knownMsd(): Int {
        val appr = maxAppr!!
        val length = if (appr.signum() >= 0) appr.bitLength() else appr.negate().bitLength()
        return minPrec + length - 1
    }

    /**
     * Most-significant-bit position, or [Int.MIN_VALUE] when the value is still
     * indistinguishable from zero at precision [precision].
     *
     * Synchronised for the reason given on [knownMsd]; the monitor is reentrant, so the
     * calls it makes into [getAppr] and [knownMsd] are free.
     */
    @Synchronized
    fun msd(precision: Int): Int {
        val appr = maxAppr
        if (!apprValid || (appr != null && appr <= BIG1 && appr >= BIG_MINUS1)) {
            getAppr(precision - 1)
            if (maxAppr!!.abs() <= BIG1) return Int.MIN_VALUE
        }
        return knownMsd()
    }

    /**
     * [msd], refined until it is known or until [limit] is reached.
     *
     * Returns [Int.MIN_VALUE] when [limit] is reached without deciding. That is an answer,
     * not a failure: the caller asked for a bounded search, and "smaller than 2^limit" is
     * exactly what [SqrtCR] needs in order to return zero. A caller with no such fallback
     * wants [requireMsd] instead.
     */
    fun iterMsd(limit: Int): Int {
        var prec = 0
        while (prec > limit + 30) {
            checkNotAborted()
            val found = msd(prec)
            if (found != Int.MIN_VALUE) return found
            checkPrecision(prec)
            prec = prec * 3 / 2 - 16
        }
        return msd(limit)
    }

    /**
     * [iterMsd] for a caller that has no meaning for "still looks like zero".
     *
     * The give-up is the point: for a value that really is zero, refining can never decide,
     * so the search stops at [CalculationLimits.MIN_MSD_PRECISION] and reports
     * [PrecisionOverflowException] — the honest "I cannot tell". Handing [Int.MIN_VALUE]
     * back to [InvCR] instead does not fail loudly; `1 − Int.MIN_VALUE` overflows to a
     * plausible-looking precision, and the reciprocal ends in a raw BigInteger divide by
     * zero, thrown lazily at format time where nothing catches it.
     */
    fun requireMsd(): Int {
        val found = iterMsd(CalculationLimits.MIN_MSD_PRECISION)
        if (found == Int.MIN_VALUE) throw PrecisionOverflowException()
        return found
    }

    // ---------------------------------------------------------------- arithmetic

    operator fun plus(other: ConstructiveReal): ConstructiveReal = AddCR(this, other)

    operator fun minus(other: ConstructiveReal): ConstructiveReal = AddCR(this, NegCR(other))

    operator fun times(other: ConstructiveReal): ConstructiveReal = MultCR(this, other)

    operator fun unaryMinus(): ConstructiveReal = NegCR(this)

    fun inverse(): ConstructiveReal = InvCR(this)

    operator fun div(other: ConstructiveReal): ConstructiveReal = MultCR(this, InvCR(other))

    /** Multiplication by a power of two, which is exact and free. */
    fun shiftLeft(n: Int): ConstructiveReal = ShiftedCR(this, n)

    fun shiftRight(n: Int): ConstructiveReal = ShiftedCR(this, -n)

    fun abs(): ConstructiveReal = if (signum() < 0) NegCR(this) else this

    fun sqrt(): ConstructiveReal = SqrtCR(this)

    /**
     * `e^this`, by halving the argument until the Taylor series converges quickly and then
     * squaring back up. Without the reduction, `exp(50)` would need hundreds of terms.
     *
     * The reduction is a loop, and it is bounded, for the same reason. Its depth is
     * `log2(|x|) + 10` *whatever the sign of `x`*, so writing it as a recursion paid that
     * depth in stack frames twice — once building the chain and once approximating it —
     * while the only guard on it lived in [UnifiedReal] and looked at the sign. The half
     * that could not be bounded there is closed here instead, because this is the one place
     * every route to an exponential passes through: [UnifiedReal.exp] on an opaque value,
     * `powViaExpLn`, and `10^x` all arrive at this method with no check of their own.
     *
     * The failure it prevents is not an error but a crash: the tree is expanded when the
     * *formatter* asks for a digit, and a `StackOverflowError` there is an `Error` that no
     * catch in the app is looking for.
     */
    fun exp(): ConstructiveReal {
        // In units of 2^-10, so this halves whenever |x| exceeds about 1/512. Deliberately
        // far more conservative than the series strictly needs: halving is cheap, and a
        // series asked to converge near the edge of its stated range is how wrong digits
        // appear hundreds of places to the right where nothing will notice them.
        val rough = getAppr(-10).abs()
        if (rough <= BIG2) return PrescaledExpCR(this)
        // rough is |x| scaled by 2^10, so its bit length past ten is log2(|x|).
        if (rough.bitLength() - 10 > CalculationLimits.MAX_EXP_ARGUMENT_BITS) {
            throw TooMuchMemoryException()
        }
        // The smallest k with rough/2^k ≤ 2 — the same count the recursion would have
        // reached one frame at a time — and a single shift rather than k nested ones.
        val halvings = rough.subtract(BIG1).bitLength() - 1
        var result: ConstructiveReal = PrescaledExpCR(shiftRight(halvings))
        repeat(halvings) {
            checkNotAborted()
            result = result * result
        }
        return result
    }

    /**
     * Natural logarithm, by pulling the argument into `[0.5, 1.5]` where `ln(1+x)`
     * converges, and paying for the shift in multiples of `ln 2`.
     */
    fun ln(): ConstructiveReal {
        // Approximated in sixteenths, which is enough to pick a branch.
        val rough = getAppr(-4)
        if (rough.signum() < 0) throw NotANumberException()
        if (rough <= BigInteger.valueOf(8L)) return inverse().ln().unaryMinus()
        if (rough >= BigInteger.valueOf(24L)) {
            return if (rough <= BigInteger.valueOf(64L)) {
                sqrt().sqrt().ln().shiftLeft(2)
            } else {
                val extraBits = rough.bitLength() - 3
                shiftRight(extraBits).ln() + (valueOf(extraBits) * LN2)
            }
        }
        return PrescaledLnCR(this - ONE)
    }

    /**
     * `atan(this)`, reduced by the half-angle identity
     * `atan(x) = 2·atan(x / (1 + √(1+x²)))` until the series argument is at most ½.
     */
    fun atan(): ConstructiveReal {
        val rough = getAppr(-4)
        // 8 sixteenths = 1/2. Above that the plain Taylor series converges too slowly.
        return if (rough.abs() > BigInteger.valueOf(8L)) {
            val reduced = this / (ONE + (ONE + this * this).sqrt())
            reduced.atan().shiftLeft(1)
        } else {
            PrescaledAtanCR(this)
        }
    }

    /** `asin(this)` as `atan(x / √(1−x²))`, with the endpoints handled exactly. */
    fun asin(): ConstructiveReal {
        val rough = getAppr(-10)
        val one = BIG1.shiftLeft(10)
        if (rough.abs() > one.add(BigInteger.valueOf(4L))) throw NotANumberException()
        val oneMinusSquare = ONE - this * this
        // asin has a square-root singularity at ±1: asin(1−ε) = π/2 − √(2ε). So deciding
        // the endpoint at a fixed precision is not a rounding question — 1−x² merely below
        // 2^-101 still leaves the true answer 2^-50 away from π/2, i.e. wrong in the 16th
        // decimal place and in every digit scrolled to after it. The test has to be the
        // engine's own limit of decidability instead, where π/2 is right to ~2^-1000000.
        // It cannot simply be dropped either: for an argument that really is ±1 the
        // division below is 1/0, which no amount of refining will ever resolve.
        if (oneMinusSquare.iterMsd(CalculationLimits.MIN_MSD_PRECISION) == Int.MIN_VALUE) {
            return if (signum() > 0) PI.shiftRight(1) else PI.shiftRight(1).unaryMinus()
        }
        return (this / oneMinusSquare.sqrt()).atan()
    }

    /** `acos(this)` as `π/2 − asin(this)`. */
    fun acos(): ConstructiveReal = PI.shiftRight(1) - asin()

    /** `cos(this)`, reduced by whole multiples of π and then by the double-angle identity. */
    fun cos(): ConstructiveReal {
        val halfPiMultiples = (this / PI).getAppr(-1)
        if (halfPiMultiples.abs() >= BIG2) {
            // Subtract the nearest multiple of π — not 2π. Reducing by 2π leaves anything
            // just past π still outside the range, so the next call reduces it straight
            // back and the two recurse against each other until the stack runs out.
            // cos(x − kπ) = (−1)^k·cos(x) is what makes the odd multiples safe.
            val piMultiples = scale(halfPiMultiples, -1)
            val reduced = (this - PI * valueOf(piMultiples)).cos()
            return if (piMultiples.testBit(0)) -reduced else reduced
        }
        return if (getAppr(-1).abs() >= BIG2) {
            // cos(2x) = 2cos²x − 1, halving until the series argument is below 1.
            val half = shiftRight(1).cos()
            (half * half).shiftLeft(1) - ONE
        } else {
            PrescaledCosCR(this)
        }
    }

    /** `sin(this)` as `cos(π/2 − this)`. */
    fun sin(): ConstructiveReal = (PI.shiftRight(1) - this).cos()

    // ---------------------------------------------------------------- comparison

    /** Sign, decided at precision [precision]; `0` means "not yet decided", not "zero". */
    fun signumOrZero(precision: Int): Int = getAppr(precision - 1).signum()

    /**
     * Exact sign.
     *
     * Refines until the sign is known, and throws [PrecisionOverflowException] rather than
     * looping forever if the value is in fact zero.
     */
    fun signum(): Int {
        var a = -20
        while (true) {
            checkNotAborted()
            checkPrecision(a)
            val result = signumOrZero(a)
            if (result != 0) return result
            // The same budget the msd search uses, for the same problem. Without it the
            // doubling only stops where checkPrecision throws, at 268 million bits — a
            // hundred times past the point at which the module has already decided
            // elsewhere that a value it cannot separate from zero is undecidable rather
            // than merely stubborn.
            if (a <= CalculationLimits.MIN_MSD_PRECISION) throw PrecisionOverflowException()
            a = maxOf(a * 2, CalculationLimits.MIN_MSD_PRECISION)
        }
    }

    /** Comparison decided at precision [precision]; `0` means "too close to call". */
    fun compareToOrZero(other: ConstructiveReal, precision: Int): Int {
        val needed = precision - 1
        val a = getAppr(needed)
        val b = other.getAppr(needed)
        if (a > b.add(BIG1)) return 1
        if (a < b.subtract(BIG1)) return -1
        return 0
    }

    /** Exact comparison; throws rather than hanging when the two values are equal. */
    operator fun compareTo(other: ConstructiveReal): Int {
        var a = -20
        while (true) {
            checkNotAborted()
            checkPrecision(a)
            val result = compareToOrZero(other, a)
            if (result != 0) return result
            // Bounded for the reason given in signum(): two equal values can never be
            // separated, and the last decade of the doubling is spent allocating
            // 268-million-bit approximations that cannot change the outcome.
            if (a <= CalculationLimits.MIN_MSD_PRECISION) throw PrecisionOverflowException()
            a = maxOf(a * 2, CalculationLimits.MIN_MSD_PRECISION)
        }
    }

    // ---------------------------------------------------------------- rendering

    /** Nearest integer, as used for whole-number display. */
    fun toBigInteger(): BigInteger = getAppr(0)

    fun toDouble(): Double {
        val appr = getAppr(-53)
        return appr.toDouble() * Math.pow(2.0, -53.0)
    }

    /**
     * The value truncated to [digits] places after the point.
     *
     * This is the method the display calls when the user scrolls: ask for more digits and
     * the cached approximation is refined rather than restarted.
     */
    fun toStringTruncated(digits: Int, radix: Int = 10): String {
        val scaled: ConstructiveReal = if (radix == 16) {
            shiftLeft(4 * digits)
        } else {
            this * IntCR(BigInteger.valueOf(radix.toLong()).pow(digits))
        }
        val scaledInt = scaled.getAppr(0)
        var text = scaledInt.abs().toString(radix)
        val result: String
        if (digits == 0) {
            result = text
        } else {
            if (text.length <= digits) text = text.padStart(digits + 1, '0')
            val whole = text.substring(0, text.length - digits)
            val fraction = text.substring(text.length - digits)
            result = "$whole.$fraction"
        }
        return if (scaledInt.signum() < 0) "-$result" else result
    }

    /**
     * Base-ten digits with the last one correctly rounded, rather than however the
     * approximation happened to land.
     *
     * [toStringTruncated] asks [getAppr] for a value good to within one unit in the last
     * place and then *truncates* it, so the final digit falls either side by luck. Measured
     * against published expansions at eighteen significant digits, π, √2, ln 2, 1/7 and 2/3
     * all came out correctly rounded and `e` came out one low — the same code, a different
     * accident. A calculator that advertises exact arithmetic cannot have its last digit
     * decided that way.
     *
     * Guard digits are what make it deterministic: compute [GUARD_DIGITS] further than asked,
     * then round half away from zero and drop them. That is not a proof — no finite
     * approximation can correctly round a value sitting exactly on a rounding boundary, which
     * is the table-maker's dilemma — but it moves the failure from "roughly one digit in two"
     * to "the true value agrees with a boundary for four consecutive digits".
     */
    fun toStringRounded(digits: Int): String {
        require(digits >= 0) { "digits must not be negative" }
        val scale = BigInteger.TEN.pow(digits + GUARD_DIGITS)
        val scaledInt = (this * IntCR(scale)).getAppr(0)
        val negative = scaledInt.signum() < 0

        val guard = BigInteger.TEN.pow(GUARD_DIGITS)
        val (whole, remainder) = scaledInt.abs().divideAndRemainder(guard)
        // Half away from zero, matching the rounding a reader expects of a decimal display.
        val rounded =
            if (remainder * BIG2 >= guard) whole + BIG1 else whole

        var text = rounded.toString()
        val result: String
        if (digits == 0) {
            result = text
        } else {
            // The carry out of rounding can lengthen the string — 9.99 to four places is
            // 10.00 — so the split has to happen after rounding, never before.
            if (text.length <= digits) text = text.padStart(digits + 1, '0')
            result = text.substring(0, text.length - digits) + "." + text.substring(text.length - digits)
        }
        return if (negative && rounded.signum() != 0) "-$result" else result
    }

    override fun toString(): String = toStringTruncated(10)

    companion object {
        /** Extra digits computed and then rounded away; see [toStringRounded]. */
        private const val GUARD_DIGITS = 4

        internal val BIG1: BigInteger = BigInteger.ONE
        internal val BIG_MINUS1: BigInteger = BigInteger.ONE.negate()
        internal val BIG2: BigInteger = BigInteger.TWO

        val ZERO: ConstructiveReal = IntCR(BigInteger.ZERO)
        val ONE: ConstructiveReal = IntCR(BigInteger.ONE)

        fun valueOf(n: Long): ConstructiveReal = IntCR(BigInteger.valueOf(n))

        fun valueOf(n: Int): ConstructiveReal = IntCR(BigInteger.valueOf(n.toLong()))

        fun valueOf(n: BigInteger): ConstructiveReal = IntCR(n)

        /** Exact conversion: a rational is a perfectly good procedure for producing digits. */
        fun valueOf(r: BoundedRational): ConstructiveReal =
            IntCR(r.num) / IntCR(r.den)

        /**
         * `ln 2`, from `7·ln(10/9) − 2·ln(25/24) + 3·ln(81/80)`.
         *
         * Chosen because every argument is close to 1, so all three series converge fast,
         * and because computing it from [ln] directly would recurse into itself.
         */
        val LN2: ConstructiveReal by lazy {
            val a = PrescaledLnCR(valueOf(BoundedRational.of(1L, 9L)))
            val b = PrescaledLnCR(valueOf(BoundedRational.of(1L, 24L)))
            val c = PrescaledLnCR(valueOf(BoundedRational.of(1L, 80L)))
            valueOf(7) * a - valueOf(2) * b + valueOf(3) * c
        }

        /** `π`, by Machin's formula `16·atan(1/5) − 4·atan(1/239)`. */
        val PI: ConstructiveReal by lazy {
            IntegralAtanCR(5).shiftLeft(4) - IntegralAtanCR(239).shiftLeft(2)
        }

        /** `e`. */
        val E: ConstructiveReal by lazy { ONE.exp() }

        /**
         * Multiplies by `2^n`, rounding when `n` is negative.
         *
         * The rounding is `(k >> (-n-1) + 1) >> 1` rather than a plain shift, because a
         * truncating shift biases every approximation toward zero and the bias accumulates
         * through a long expression.
         */
        internal fun scale(k: BigInteger, n: Int): BigInteger =
            if (n >= 0) {
                k.shiftLeft(n)
            } else {
                k.shiftRight(-n - 1).add(BIG1).shiftRight(1)
            }

        /** `ceil(log2(|n| + 1))`, used to budget iterations for the Taylor series. */
        internal fun boundLog2(n: Int): Int {
            val abs = Math.abs(n)
            return Math.ceil(Math.log((abs + 1).toDouble()) / Math.log(2.0)).toInt()
        }

        /**
         * Refuses a precision so extreme that the shift counts would overflow `Int`.
         *
         * Reached in practice only by a value the engine cannot prove non-zero, which is
         * exactly the case that must fail loudly instead of spinning.
         */
        internal fun checkPrecision(n: Int) {
            val high = n shr 28
            val highShifted = n shr 29
            if (high xor highShifted != 0) throw PrecisionOverflowException()
        }
    }
}
