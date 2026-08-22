package app.numera.calculator.math

import app.numera.calculator.math.CalculationLimits.MAX_FACTORIAL
import app.numera.calculator.math.CalculationLimits.MAX_RATIONAL_BITS
import java.math.BigInteger

/**
 * An exact rational number, always fully reduced, with a denominator that is always positive.
 *
 * This is the layer that makes `1÷3×3` come out as exactly `1`. Every arithmetic method
 * returns `BoundedRational?`, and a `null` never means "error" — it means *"staying exact
 * here would cost more than it is worth; fall through to [ConstructiveReal]"*. Callers must
 * treat `null` as a request to change representation, not as a failure.
 *
 * The bound is what stops a four-keystroke expression from exhausting the heap: without it,
 * repeated squaring of a rational doubles its size each time and nothing ever stops it.
 *
 * Derived from the algorithms in AOSP's `ExactCalculator` (Apache-2.0); see NOTICE.md.
 */
class BoundedRational private constructor(
    /** Signed numerator. */
    val num: BigInteger,
    /** Strictly positive denominator, sharing no factor with [num]. */
    val den: BigInteger,
) : Comparable<BoundedRational> {

    val signum: Int get() = num.signum()

    val isZero: Boolean get() = num.signum() == 0

    val isOne: Boolean get() = num == BigInteger.ONE && den == BigInteger.ONE

    /** True when the denominator has been reduced away, i.e. this is a whole number. */
    val isInteger: Boolean get() = den == BigInteger.ONE

    /** The whole-number value, or `null` when this is a genuine fraction. */
    fun asBigInteger(): BigInteger? = if (isInteger) num else null

    /** The value as an `Int`, or `null` if it is fractional or does not fit. */
    fun asIntOrNull(): Int? {
        val i = asBigInteger() ?: return null
        return if (i.bitLength() < 32) i.toInt() else null
    }

    // ---------------------------------------------------------------- arithmetic

    operator fun unaryMinus(): BoundedRational = BoundedRational(num.negate(), den)

    fun abs(): BoundedRational = if (signum < 0) -this else this

    operator fun plus(other: BoundedRational): BoundedRational? =
        of(num * other.den + other.num * den, den * other.den).bounded()

    operator fun minus(other: BoundedRational): BoundedRational? = this + (-other)

    operator fun times(other: BoundedRational): BoundedRational? =
        of(num * other.num, den * other.den).bounded()

    /**
     * Exact division.
     *
     * Throws rather than returning `null` when [other] is zero: a zero divisor is a real
     * error to report to the user, not an invitation to retry in another representation.
     */
    operator fun div(other: BoundedRational): BoundedRational? {
        if (other.isZero) throw DivideByZeroException()
        return of(num * other.den, den * other.num).bounded()
    }

    /** Reciprocal, or `null` if it would be too large. Throws on zero. */
    fun inverse(): BoundedRational? {
        if (isZero) throw DivideByZeroException()
        return of(den, num).bounded()
    }

    /**
     * Integer power, by repeated squaring.
     *
     * The size of the result is known before any work is done — `bitLength × |exponent|` —
     * so an impossible power is refused up front instead of being discovered halfway
     * through by the allocator.
     */
    fun pow(exponent: BigInteger): BoundedRational? {
        if (exponent.signum() == 0) return ONE
        if (isOne) return ONE
        if (isZero) {
            if (exponent.signum() < 0) throw DivideByZeroException()
            return ZERO
        }
        if (exponent.signum() < 0) return inverse()?.pow(exponent.negate())
        if (exponent.bitLength() > 31) return null

        val e = exponent.toInt()
        val widest = maxOf(num.bitLength(), den.bitLength()).toLong()
        if (widest * e > MAX_RATIONAL_BITS) return null

        return of(num.pow(e), den.pow(e)).bounded()
    }

    /**
     * Exact square root, defined only when both halves are perfect squares.
     *
     * `null` here is the common case and the important one: `sqrt(2)` is irrational, so
     * this returns `null` and [UnifiedReal] keeps it symbolically as `√2` rather than
     * collapsing it to a decimal — which is what lets `√2 × √2` come back as exactly `2`.
     */
    fun sqrt(): BoundedRational? {
        if (signum < 0) throw NotANumberException()
        if (isZero) return ZERO
        val rootNum = num.sqrt()
        if (rootNum * rootNum != num) return null
        val rootDen = den.sqrt()
        if (rootDen * rootDen != den) return null
        return of(rootNum, rootDen)
    }

    /** `n!` for whole non-negative `n`. Anything else is a domain error. */
    fun factorial(): BoundedRational {
        val n = asBigInteger() ?: throw NotANumberException()
        if (n.signum() < 0) throw NotANumberException()
        if (n.bitLength() > 31 || n.toInt() > MAX_FACTORIAL) throw TooMuchMemoryException()

        // Deliberately not subject to MAX_RATIONAL_BITS. There is no constructive-real
        // fallback for a factorial, so returning null would turn "big" into "unsupported";
        // 100! is a headline test case and is only 525 bits.
        var acc = BigInteger.ONE
        for (i in 2..n.toInt()) {
            CalculationLimits.checkNotAborted()
            acc *= BigInteger.valueOf(i.toLong())
        }
        return of(acc, BigInteger.ONE)
    }

    // ---------------------------------------------------------------- display

    /**
     * How many digits after the decimal point render this value *exactly*, or `null` when
     * no finite number of them will.
     *
     * A reduced fraction terminates in base 10 exactly when its denominator is of the form
     * `2^a · 5^b`, and then needs `max(a, b)` digits. This single fact is what decides
     * whether the display shows `0.25` or `0.142857…` — the ellipsis is driven from here,
     * not from whether the string happened to hit the width limit.
     */
    fun digitsRequired(): Int? {
        if (isInteger) return 0
        val twos = den.lowestSetBit
        val (fives, remaining) = stripFactors(den.shiftRight(twos), FIVE)
        return if (remaining == BigInteger.ONE) maxOf(twos, fives) else null
    }

    /**
     * The exact decimal expansion, or `null` when the value does not terminate.
     *
     * Exact because it scales by a power of ten *before* dividing, so the division is
     * remainderless and no rounding decision is ever made.
     */
    fun toExactDecimalString(): String? {
        val digits = digitsRequired() ?: return null
        if (digits == 0) return num.toString()
        val scaled = (num * BigInteger.TEN.pow(digits)) / den
        val negative = scaled.signum() < 0
        val text = scaled.abs().toString().padStart(digits + 1, '0')
        val whole = text.substring(0, text.length - digits)
        val frac = text.substring(text.length - digits).trimEnd('0')
        val sign = if (negative) "-" else ""
        return if (frac.isEmpty()) "$sign$whole" else "$sign$whole.$frac"
    }

    /** Exact decimal when there is one, otherwise `n/d`. Diagnostic and test-facing. */
    fun toNiceString(): String = toExactDecimalString() ?: "$num/$den"

    fun toDouble(): Double = num.toDouble() / den.toDouble()

    // ---------------------------------------------------------------- comparison

    override fun compareTo(other: BoundedRational): Int =
        (num * other.den).compareTo(other.num * den)

    override fun equals(other: Any?): Boolean =
        other is BoundedRational && num == other.num && den == other.den

    override fun hashCode(): Int = 31 * num.hashCode() + den.hashCode()

    override fun toString(): String = toNiceString()

    /** `this` unless it has outgrown the exact layer, in which case `null`. */
    private fun bounded(): BoundedRational? =
        if (num.bitLength() > MAX_RATIONAL_BITS || den.bitLength() > MAX_RATIONAL_BITS) null else this

    companion object {
        val ZERO = BoundedRational(BigInteger.ZERO, BigInteger.ONE)
        val ONE = BoundedRational(BigInteger.ONE, BigInteger.ONE)
        val MINUS_ONE = BoundedRational(BigInteger.ONE.negate(), BigInteger.ONE)
        val HALF = BoundedRational(BigInteger.ONE, BigInteger.TWO)
        val TWO = BoundedRational(BigInteger.TWO, BigInteger.ONE)
        val TEN = BoundedRational(BigInteger.TEN, BigInteger.ONE)

        private val FIVE: BigInteger = BigInteger.valueOf(5L)

        /**
         * Removes every factor of [base] from [value], returning how many there were and
         * what is left.
         *
         * Peeling one factor at a time is linear in the *count*, which is unbounded:
         * [parse] is the one entry point that never applies [bounded], so `1E-100000`
         * arrives here as `5^100000` and a one-at-a-time loop performs a hundred thousand
         * divisions of a number averaging 116,000 bits — seconds of BigInteger work, for a
         * result the display then discards. Peeling `base^(2^k)` blocks from the largest
         * downwards costs one division per bit of the count instead, on operands that
         * shrink geometrically. Every step is a cancellation point, because the caller may
         * be a preview that the next keystroke has already made irrelevant.
         */
        internal fun stripFactors(
            value: BigInteger,
            base: BigInteger,
        ): Pair<Int, BigInteger> {
            var remaining = value
            // base^(2^k) for each k, up to the largest that could still divide. The size
            // cap is what keeps the `1 shl index` below inside Int; it is unreachable in
            // practice, since index 31 would need a value of half a gigabyte.
            val powers = ArrayList<BigInteger>()
            var power = base
            while (power.bitLength() <= remaining.bitLength() && powers.size < 31) {
                CalculationLimits.checkNotAborted()
                powers.add(power)
                power = power.multiply(power)
            }
            var count = 0
            for (index in powers.indices.reversed()) {
                CalculationLimits.checkNotAborted()
                val split = remaining.divideAndRemainder(powers[index])
                if (split[1].signum() == 0) {
                    remaining = split[0]
                    count += 1 shl index
                }
            }
            return count to remaining
        }

        /** Reduces, and moves any sign onto the numerator. */
        fun of(num: BigInteger, den: BigInteger = BigInteger.ONE): BoundedRational {
            if (den.signum() == 0) throw DivideByZeroException()
            if (num.signum() == 0) return ZERO
            var n = num
            var d = den
            if (d.signum() < 0) {
                n = n.negate()
                d = d.negate()
            }
            val g = n.gcd(d)
            if (g != BigInteger.ONE) {
                n /= g
                d /= g
            }
            return BoundedRational(n, d)
        }

        fun of(num: Long, den: Long = 1L): BoundedRational =
            of(BigInteger.valueOf(num), BigInteger.valueOf(den))

        /**
         * Parses a typed number: optional sign, digits, optional fraction, optional exponent.
         *
         * Exact by construction — `0.1` becomes `1/10`, never the nearest double. That is
         * the entire reason `0.1 + 0.2` can print `0.3`.
         */
        fun parse(text: String): BoundedRational {
            val s = text.trim()
            require(s.isNotEmpty()) { "empty number" }

            val eIndex = s.indexOfFirst { it == 'e' || it == 'E' }
            val mantissa = if (eIndex < 0) s else s.substring(0, eIndex)
            val exponent = if (eIndex < 0) 0 else s.substring(eIndex + 1).toInt()

            val dot = mantissa.indexOf('.')
            val digits = if (dot < 0) mantissa else mantissa.removeRange(dot, dot + 1)
            val fractionDigits = if (dot < 0) 0 else mantissa.length - dot - 1

            val n = if (digits.isEmpty() || digits == "-" || digits == "+") {
                BigInteger.ZERO
            } else {
                BigInteger(digits)
            }

            val scale = exponent - fractionDigits
            return if (scale >= 0) {
                of(n * BigInteger.TEN.pow(scale), BigInteger.ONE)
            } else {
                of(n, BigInteger.TEN.pow(-scale))
            }
        }
    }
}
