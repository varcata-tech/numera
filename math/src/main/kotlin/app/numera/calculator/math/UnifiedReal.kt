package app.numera.calculator.math

import java.math.BigInteger

/** Whether trigonometric arguments are degrees or radians. */
enum class AngleMode { DEGREES, RADIANS }

/**
 * A real number as `ratFactor × factor` — an exact rational multiplied by one symbolic term.
 *
 * This is the layer the user actually feels. [BoundedRational] alone cannot represent `√2`,
 * and [ConstructiveReal] alone can represent it but can never *prove* that `√2 × √2` is 2.
 * Splitting a number into a rational coefficient and one of a handful of known irrational
 * shapes gets both: the common chains stay decidable, and everything else degrades to a
 * correct-but-opaque constructive real rather than to a wrong answer.
 *
 * Derived from the design of AOSP ExactCalculator's `UnifiedReal` (Apache-2.0); see NOTICE.md.
 */
class UnifiedReal private constructor(
    val ratFactor: BoundedRational,
    val factor: Factor,
) {

    // ---------------------------------------------------------------- interrogation

    /** True when this is provably zero. A `false` means "not proven", never "non-zero". */
    fun definitelyZero(): Boolean = ratFactor.isZero

    /** True when the value is an exact rational, i.e. carries no irrational part. */
    val isRational: Boolean get() = factor == Factor.One

    /** The exact rational value, or `null` when an irrational factor is present. */
    fun asRational(): BoundedRational? = if (isRational) ratFactor else null

    /** The exact whole-number value, or `null`. */
    fun asBigInteger(): BigInteger? = asRational()?.asBigInteger()

    /**
     * Sign of the symbolic part alone.
     *
     * Every factor except [Factor.Log] and [Factor.Opaque] is positive by construction, so
     * this is usually free; `ln(1/2)` is the case that makes it necessary at all.
     */
    private fun factorSignum(): Int? = when (val f = factor) {
        Factor.One, is Factor.Sqrt, is Factor.Exp, is Factor.Pi -> 1
        is Factor.Log -> f.arg.compareTo(BoundedRational.ONE)
        is Factor.Opaque -> null
    }

    /** Full-precision value. Always available; exactness is what may have been lost. */
    fun toConstructiveReal(): ConstructiveReal {
        val rational = ConstructiveReal.valueOf(ratFactor)
        return when (val f = factor) {
            Factor.One -> rational
            is Factor.Sqrt -> rational * ConstructiveReal.valueOf(f.radicand).sqrt()
            is Factor.Exp -> rational * ConstructiveReal.valueOf(f.exponent).exp()
            is Factor.Log -> rational * ConstructiveReal.valueOf(f.arg).ln()
            is Factor.Pi ->
                if (f.power > 0) rational * piPower(f.power) else rational / piPower(f.power)
            is Factor.Opaque -> rational * f.value
        }
    }

    // ---------------------------------------------------------------- arithmetic

    operator fun unaryMinus(): UnifiedReal = UnifiedReal(-ratFactor, factor)

    operator fun plus(other: UnifiedReal): UnifiedReal {
        if (definitelyZero()) return other
        if (other.definitelyZero()) return this
        // Like terms add exactly; this is what turns 2√2 + 3√2 into 5√2 rather than into
        // a decimal that merely looks like 7.071.
        if (factor == other.factor) {
            val sum = ratFactor + other.ratFactor
            if (sum != null) return make(sum, factor)
        }
        return opaque(toConstructiveReal() + other.toConstructiveReal())
    }

    operator fun minus(other: UnifiedReal): UnifiedReal = this + (-other)

    operator fun times(other: UnifiedReal): UnifiedReal {
        if (definitelyZero() || other.definitelyZero()) return ZERO
        val rat = ratFactor * other.ratFactor
            ?: return boundedProduct(this, other)

        val a = factor
        val b = other.factor
        return when {
            a == Factor.One -> make(rat, b)
            b == Factor.One -> make(rat, a)

            // √a·√b = √(ab); the renormalisation pulls any perfect square back out into
            // the rational coefficient, which is exactly how √2·√2 becomes 2.
            a is Factor.Sqrt && b is Factor.Sqrt ->
                sqrtOfInteger(a.radicand * b.radicand).let { (coeff, f) ->
                    (rat * coeff)?.let { make(it, f) }
                        ?: boundedProduct(this, other)
                }

            a is Factor.Exp && b is Factor.Exp ->
                (a.exponent + b.exponent)?.let { make(rat, expFactor(it)) }
                    ?: boundedProduct(this, other)

            a is Factor.Pi && b is Factor.Pi ->
                make(rat, piFactor(a.power.toLong() + b.power.toLong()))

            else -> boundedProduct(this, other)
        }
    }

    /** Multiplicative inverse, exact wherever the factor shape allows it. */
    fun inverse(): UnifiedReal {
        if (definitelyZero()) throw DivideByZeroException()
        val invRat = ratFactor.inverse()
            ?: return opaque(toConstructiveReal().inverse())
        return when (val f = factor) {
            Factor.One -> make(invRat, Factor.One)
            // 1/√a = √a/a, keeping the root in the numerator where it can combine.
            is Factor.Sqrt -> (invRat / BoundedRational.of(f.radicand))
                ?.let { make(it, f) }
                ?: opaque(toConstructiveReal().inverse())
            is Factor.Exp -> make(invRat, expFactor(-f.exponent))
            is Factor.Pi -> make(invRat, piFactor(-f.power.toLong()))
            else -> opaque(toConstructiveReal().inverse())
        }
    }

    operator fun div(other: UnifiedReal): UnifiedReal {
        if (other.definitelyZero()) throw DivideByZeroException()
        return this * other.inverse()
    }

    /**
     * Square root.
     *
     * Exact whenever the value is rational — either landing on a rational root or creating
     * the [Factor.Sqrt] that later multiplications can cancel.
     */
    fun sqrt(): UnifiedReal {
        if (signum() < 0) throw NotANumberException()
        if (definitelyZero()) return ZERO
        if (isRational) {
            // √(p/q) = √(pq)/q, which turns any rational into an integer under the root.
            val (coeff, f) = sqrtOfInteger(ratFactor.num * ratFactor.den)
            val scaled = coeff / BoundedRational.of(ratFactor.den)
            if (scaled != null) return make(scaled, f)
        }
        return opaque(toConstructiveReal().sqrt())
    }

    /** Natural logarithm. Recognises `ln(e^r) = r` and `ln 1 = 0`. */
    fun ln(): UnifiedReal {
        if (signum() <= 0) throw NotANumberException()
        val f = factor
        if (f is Factor.Exp && ratFactor.isOne) return make(f.exponent, Factor.One)
        if (isRational) {
            if (ratFactor.isOne) return ZERO
            return make(BoundedRational.ONE, logFactor(ratFactor))
        }
        return opaque(toConstructiveReal().ln())
    }

    /** `e^this`. Recognises `e^ln r = r` and `e^0 = 1`. */
    fun exp(): UnifiedReal {
        val f = factor
        if (f is Factor.Log && ratFactor.isOne) return make(f.arg, Factor.One)
        if (isRational) {
            if (ratFactor.isZero) return ONE
            return make(BoundedRational.ONE, expFactor(ratFactor))
        }
        return opaque(toConstructiveReal().exp())
    }

    /**
     * Base-10 logarithm.
     *
     * Exact for exact powers of ten in either direction, so `log(1000)` is `3` and
     * `log(0.001)` is `-3` rather than `2.9999999999999996`.
     */
    fun log10(): UnifiedReal {
        if (signum() <= 0) throw NotANumberException()
        if (isRational) {
            powerOfTen(ratFactor)?.let { return make(BoundedRational.of(it.toLong()), Factor.One) }
        }
        return opaque(toConstructiveReal().ln() / ConstructiveReal.valueOf(10).ln())
    }

    /** `n!` for whole non-negative `n`. */
    fun factorial(): UnifiedReal {
        if (!isRational) throw NotANumberException()
        return make(ratFactor.factorial(), Factor.One)
    }

    /**
     * `this ^ exponent`.
     *
     * Integer exponents go through the exact path — including on irrational factors, where
     * repeated multiplication lets `(√2)^4` collapse to `4`. A *half*-integer exponent on a
     * rational base is split into a whole power times a [sqrt], so `2^0.5 × 2^0.5` cancels
     * and `9^1.5` is exactly 27 rather than a padded `27.0000000000000000…`.
     */
    fun pow(exponent: UnifiedReal): UnifiedReal {
        // `0^p` is 0 for every positive p, but nothing said so until the exponent had been
        // shown to be rational. A π exponent went straight to powViaExpLn, whose `ln(0)`
        // sends InvCR into a most-significant-bit search on a value that is identically
        // zero — undecidable by construction, so it gave up with a precision overflow and
        // the display reported "Bad expression" for a defined value, one keystroke away
        // from a `0^2` that answers 0. The sign comes from the factor where the factor
        // knows it, and from the constructive real otherwise: an opaque exponent used to
        // fall through to that same ln(0) on the reasoning that deciding its sign was an
        // undecidable search, but the search that is undecidable is the one on the *base*.
        // The exponent's sign is decided in a couple of probes for anything but an
        // exponent that is itself an undecidable zero, and `0^sin(1)` was "Bad expression"
        // while `0^π` was 0.
        if (definitelyZero()) {
            if (exponent.definitelyZero()) return ONE
            val exponentSign: Int = exponent.factorSignum()
                ?.let { it * exponent.ratFactor.signum }
                ?: exponent.toConstructiveReal().signum()
            if (exponentSign > 0) return ZERO
            throw DivideByZeroException()
        }
        // A base of exactly one, for any real exponent. 1^π went through powViaExpLn and
        // came back opaque, printing `1.000…` with an ellipsis for a value the engine can
        // prove; the integer route already special-cased it, so only the sign of the base
        // and the shape of the exponent decided whether the display was honest.
        if (isRational && ratFactor.isOne) return ONE
        if (!exponent.isRational) return opaque(powViaExpLn(exponent))
        val e = exponent.ratFactor

        if (e.isZero) return ONE
        if (e == BoundedRational.HALF) return sqrt()
        // Any other half-integer exponent, and only for a rational base: e = k/2 with k odd
        // is (k−1)/2 whole powers times one square root, both of which the exact machinery
        // already handles. Handing it to powViaExpLn instead produced a Factor.Opaque, and
        // an opaque value can never report that it terminates, so a whole-number answer
        // came back wearing an ellipsis and an unbounded run of zeros.
        if (isRational && e.den == ConstructiveReal.BIG2) {
            val wholeHalves = (e.num - BigInteger.ONE) / ConstructiveReal.BIG2
            return sqrt() * pow(of(BoundedRational.of(wholeHalves)))
        }

        // Any other rational exponent, on a positive rational base: exact exactly when the
        // base is a perfect den-th power. Only den == 2 was handled above, so 8^(1/3) — a
        // whole 2 — fell through to powViaExpLn and became a Factor.Opaque, which can never
        // report that it terminates. The result printed as 2.00000000000000000…, a whole
        // number wearing an ellipsis, and the same went for every cube, fourth and sixth
        // root. Negative bases are deliberately left out: (-8)^(1/3) has no principal real
        // root the rest of this class agrees on, and it already reports "not a number".
        if (isRational && signum() > 0 && e.den != BigInteger.ONE) {
            exactRootOfRational(ratFactor, e.den)?.let { root ->
                root.pow(e.num)?.let { return make(it, Factor.One) }
            }
        }

        val whole = e.asBigInteger()
        if (whole != null) {
            if (isRational) {
                ratFactor.pow(whole)?.let { return make(it, Factor.One) }
                // The exact path declined, and for an integer exponent the size of the
                // result is known before any work is done — so decide it here rather than
                // discovering it inside a lazy approximation later. Without this, 10^10^10
                // is four keystrokes that "succeed" and then wedge the display thread the
                // moment it asks for a digit. The bound is the exponential's, not the
                // general one, because the fall-through below is powViaExpLn: this result
                // is produced by a Taylor series, not by squaring.
                //
                // The estimate is signed, deliberately. Measuring the base by its widest
                // half and the exponent by its magnitude refused a large *negative*
                // exponent too, so 2^(−1000000) and (1/2)^1000000 — the same value, about
                // 10^−301030, which the display renders honestly as 0… — came back as
                // "requires too much memory". That contradicted the rule boundedProduct
                // states for exactly this situation: a value driven far below one is never
                // refused. Only a result that *grows* can be too large to hold. The trailing
                // |whole| is the slack in log2 of a ratio measured by bit lengths, added in
                // the direction that over-estimates.
                //
                // Whether the result grows is decided from the value, not from the
                // estimate. Bit lengths cannot tell 9/10 from 10/9 — both give a scale of
                // zero — so the slack alone refused 0.9^2000000, a value near 10^−91515
                // that renders as `0…`, with the same "requires too much memory" the rule
                // above had just been written to prevent. A power that shrinks is still
                // bounded, by the depth of the argument reduction in ConstructiveReal.exp.
                val shrinking =
                    (ratFactor.abs() < BoundedRational.ONE) == (whole.signum() > 0)
                if (!shrinking) {
                    val scale = ratFactor.num.abs().bitLength() - ratFactor.den.bitLength()
                    val estimatedBits =
                        whole * BigInteger.valueOf(scale.toLong()) + whole.abs()
                    CalculationLimits.checkBits(estimatedBits, CalculationLimits.MAX_EXP_BITS)
                }
                return powWithSign(whole, exponent)
            }
            // Irrational base: exact only while the repeated product stays cheap.
            if (whole.abs() <= BigInteger.valueOf(64L)) {
                val n = whole.toInt()
                var acc = ONE
                repeat(Math.abs(n)) { acc *= this }
                return if (n < 0) acc.inverse() else acc
            }
            return powWithSign(whole, exponent)
        }
        return opaque(powViaExpLn(exponent))
    }

    /**
     * [powViaExpLn] for an exponent already known to be a whole number.
     *
     * `ln` has no value at a negative argument, so [powViaExpLn] refuses a negative base
     * outright — which is right for a fractional exponent and wrong for a whole one. The
     * exact path declines as soon as `width(base) × exponent` passes
     * [CalculationLimits.MAX_RATIONAL_BITS], so base −3 needs only an exponent of 5000 and
     * base −10 only 2500: `(−10)^3000` is a few keystrokes with parentheses, and it was
     * answered "not a number" even though 10^3000 is an ordinary integer. With a whole
     * exponent the sign is decided by parity, not by the domain of `ln`, so the magnitude
     * is raised from `|base|` and the sign put back afterwards.
     */
    private fun powWithSign(whole: BigInteger, exponent: UnifiedReal): UnifiedReal {
        if (signum() >= 0) return opaque(powViaExpLn(exponent))
        val magnitude = opaque((-this).powViaExpLn(exponent))
        return if (whole.testBit(0)) -magnitude else magnitude
    }

    private fun powViaExpLn(exponent: UnifiedReal): ConstructiveReal {
        if (signum() < 0) throw NotANumberException()
        return (exponent.toConstructiveReal() * toConstructiveReal().ln()).exp()
    }

    // ---------------------------------------------------------------- trigonometry

    /**
     * Sine, exact on the whole standard table.
     *
     * The exact cases are found by converting to degrees first, in both angle modes: in
     * radians that means the argument has to be a rational multiple of π, which is exactly
     * the form `sin(π)` and `cos(π/2)` arrive in. Everything else goes to the series.
     */
    fun sin(mode: AngleMode): UnifiedReal {
        exactDegrees(mode)?.let { degrees ->
            sinOfExactDegrees(degrees)?.let { return it }
        }
        return opaque(toRadians(mode).sin())
    }

    fun cos(mode: AngleMode): UnifiedReal {
        exactDegrees(mode)?.let { degrees ->
            // cos θ = sin(θ + 90°), so one table serves both.
            sinOfExactDegrees(degrees + 90)?.let { return it }
        }
        return opaque(toRadians(mode).cos())
    }

    /**
     * Tangent.
     *
     * `tan(90°)` must be an error, not the 1.6e16 that a floating-point calculator prints
     * because its π is slightly wrong. Going through the exact table makes the zero
     * denominator visible, and [div] turns it into a reported division by zero.
     */
    fun tan(mode: AngleMode): UnifiedReal {
        exactDegrees(mode)?.let { degrees ->
            val sin = sinOfExactDegrees(degrees)
            val cos = sinOfExactDegrees(degrees + 90)
            if (sin != null && cos != null) {
                if (cos.definitelyZero()) throw DivideByZeroException()
                return sin / cos
            }
        }
        val radians = toRadians(mode)
        val cos = radians.cos()
        return opaque(radians.sin() / cos)
    }

    fun asin(mode: AngleMode): UnifiedReal =
        inverseFromTable(mode, -90..90) { sinOfExactDegrees(it) }
            ?: opaque(fromRadians(toConstructiveReal().asin(), mode))

    fun acos(mode: AngleMode): UnifiedReal =
        inverseFromTable(mode, 0..180) { sinOfExactDegrees(it + 90) }
            ?: opaque(fromRadians(toConstructiveReal().acos(), mode))

    /**
     * Arctangent.
     *
     * The table lookup is what keeps `atan(1)` at exactly `45` rather than
     * `45.0000000000000000…`. [asin] and [acos] have always had one; this did not, so of the
     * three inverse functions only this one handed back an opaque constructive real for an
     * answer that is a whole number of degrees — and that inexact form is what went on to be
     * written into history.
     */
    fun atan(mode: AngleMode): UnifiedReal =
        inverseFromTable(mode, -90..90) { tanOfExactDegrees(it) }
            ?: opaque(fromRadians(toConstructiveReal().atan(), mode))

    /**
     * `tan` of a whole number of degrees, or null where there is no exact value.
     *
     * Null at ±90 rather than throwing: to [inverseFromTable] "no value at this angle" and
     * "a value that does not match" are the same answer, and a pole is simply not a table
     * entry that any arctangent can return.
     */
    private fun tanOfExactDegrees(degrees: Int): UnifiedReal? {
        val sin = sinOfExactDegrees(degrees) ?: return null
        val cos = sinOfExactDegrees(degrees + 90) ?: return null
        if (cos.definitelyZero()) return null
        return sin / cos
    }

    /**
     * Searches the exact table for an angle in [principal] whose [value] matches this one.
     *
     * The range is a parameter rather than a constant because sine and cosine do not share
     * a principal branch: both -60 and +60 degrees have a cosine of 1/2, and only the
     * positive one is what acos is defined to return.
     */
    private inline fun inverseFromTable(
        mode: AngleMode,
        principal: IntRange,
        value: (Int) -> UnifiedReal?,
    ): UnifiedReal? {
        if (!isRational && factor !is Factor.Sqrt) return null
        for (degrees in EXACT_DEGREES) {
            if (degrees !in principal) continue
            val candidate = value(degrees) ?: continue
            if (candidate.factor == factor && candidate.ratFactor == ratFactor) {
                return degreesToMode(degrees, mode)
            }
        }
        return null
    }

    /**
     * This value as a whole number of degrees reduced to `0..359`, when it is exactly one.
     *
     * The reduction happens here rather than being left to [sinOfExactDegrees] because
     * [cos] and [tan] add 90 to whatever comes back. [BoundedRational.asIntOrNull] accepts
     * anything up to [Int.MAX_VALUE], so that addition can overflow — and since 2^32 is
     * 256 mod 360, the wrap does not land outside the table, it lands on a *different*
     * table angle. `cos(2147483596°)` would then be answered as exactly −1, with all the
     * confidence of the exact path, instead of cos(76°).
     */
    private fun exactDegrees(mode: AngleMode): Int? {
        val whole: Int? = when (mode) {
            AngleMode.DEGREES -> ratFactor.takeIf { isRational }?.asIntOrNull()
            // In radians only rational multiples of π can be exact: (r·π) rad = r·180 degrees.
            AngleMode.RADIANS -> {
                val f = factor
                when {
                    f is Factor.Pi && f.power == 1 ->
                        (ratFactor * BoundedRational.of(180L))?.asIntOrNull()
                    isRational && ratFactor.isZero -> 0
                    else -> null
                }
            }
        }
        return whole?.let { Math.floorMod(it, 360) }
    }

    private fun toRadians(mode: AngleMode): ConstructiveReal = when (mode) {
        AngleMode.RADIANS -> toConstructiveReal()
        AngleMode.DEGREES ->
            toConstructiveReal() * ConstructiveReal.PI / ConstructiveReal.valueOf(180)
    }

    // ---------------------------------------------------------------- comparison

    /** True when the two can be compared exactly rather than by refining approximations. */
    fun isComparable(other: UnifiedReal): Boolean =
        factor == other.factor && factorSignum() != null ||
            definitelyZero() && other.factorSignum() != null ||
            other.definitelyZero() && factorSignum() != null

    fun signum(): Int {
        if (definitelyZero()) return 0
        val fs = factorSignum() ?: return toConstructiveReal().signum()
        return ratFactor.signum * fs
    }

    operator fun compareTo(other: UnifiedReal): Int {
        if (factor == other.factor) {
            val fs = factorSignum()
            if (fs != null) return ratFactor.compareTo(other.ratFactor) * fs
        }
        if (definitelyZero()) return -other.signum()
        if (other.definitelyZero()) return signum()
        return toConstructiveReal().compareTo(other.toConstructiveReal())
    }

    /** Exact equality when decidable; falls back to a bounded numeric comparison. */
    override fun equals(other: Any?): Boolean =
        other is UnifiedReal && ratFactor == other.ratFactor && factor == other.factor

    override fun hashCode(): Int = 31 * ratFactor.hashCode() + factor.hashCode()

    // ---------------------------------------------------------------- display

    /**
     * Digits after the point needed to render this exactly, or `null` if no finite number
     * will do. Only a purely rational value can ever terminate.
     */
    fun digitsRequired(): Int? = if (isRational) ratFactor.digitsRequired() else null

    /** The exact decimal expansion when there is one. Drives the absence of an ellipsis. */
    fun exactDecimalOrNull(): String? = if (isRational) ratFactor.toExactDecimalString() else null

    /** Diagnostic and test-facing symbolic rendering, e.g. `3√2` or `1/2`. */
    fun toNiceString(): String {
        if (isRational) return ratFactor.toNiceString()
        val coefficient = when {
            ratFactor.isOne -> ""
            ratFactor == BoundedRational.MINUS_ONE -> "-"
            else -> ratFactor.toNiceString()
        }
        val symbol = when (val f = factor) {
            Factor.One -> ""
            is Factor.Sqrt -> "√${f.radicand}"
            is Factor.Exp -> "e^(${f.exponent.toNiceString()})"
            is Factor.Log -> "ln(${f.arg.toNiceString()})"
            is Factor.Pi -> if (f.power == 1) "π" else "π^${f.power}"
            is Factor.Opaque -> "~"
        }
        return coefficient + symbol
    }

    override fun toString(): String = toNiceString()

    companion object {
        val ZERO = UnifiedReal(BoundedRational.ZERO, Factor.One)
        val ONE = UnifiedReal(BoundedRational.ONE, Factor.One)

        /** Degrees whose sine and cosine are exactly representable as `rational × √n`. */
        private val EXACT_DEGREES: IntArray =
            intArrayOf(-90, -60, -45, -30, 0, 30, 45, 60, 90, 120, 135, 150, 180)

        fun of(value: BoundedRational): UnifiedReal = make(value, Factor.One)

        fun of(value: Long): UnifiedReal = of(BoundedRational.of(value))

        fun of(value: ConstructiveReal): UnifiedReal =
            UnifiedReal(BoundedRational.ONE, Factor.Opaque(value))

        /** `π`, the only way the constant enters an expression. */
        val PI = UnifiedReal(BoundedRational.ONE, Factor.Pi(1))

        /** `e`. */
        val E = UnifiedReal(BoundedRational.ONE, Factor.Exp(BoundedRational.ONE))

        /**
         * Canonicalising constructor.
         *
         * Collapsing a zero coefficient and a trivial factor here, once, is what lets
         * [equals] be a structural comparison everywhere else.
         */
        private fun make(rat: BoundedRational, factor: Factor): UnifiedReal = when {
            rat.isZero -> ZERO
            factor is Factor.Sqrt && factor.radicand == BigInteger.ONE ->
                UnifiedReal(rat, Factor.One)
            factor is Factor.Exp && factor.exponent.isZero -> UnifiedReal(rat, Factor.One)
            factor is Factor.Pi && factor.power == 0 -> UnifiedReal(rat, Factor.One)
            factor is Factor.Log && factor.arg.isOne -> ZERO
            else -> UnifiedReal(rat, factor)
        }

        private fun opaque(value: ConstructiveReal): UnifiedReal =
            UnifiedReal(BoundedRational.ONE, Factor.Opaque(value))

        /**
         * The product of two values that could not stay exact, refused once it has grown
         * past anything that could ever be displayed.
         *
         * [pow] and [exp] each bound the magnitude they are able to produce; plain
         * multiplication was bounded by nothing. The moment a product outgrows
         * [CalculationLimits.MAX_RATIONAL_BITS] it becomes a [Factor.Opaque] whose size
         * nothing tracks any more, so a dozen further presses of × on `1E100000` build a
         * value more than a million decimal digits wide. Nothing rejected it until the
         * *formatter* tried to normalise a mantissa against `10^1000000` and threw — and
         * formatting runs outside the evaluator's catch, so that exception ended the
         * process instead of the expression. Deciding it here, where the evaluator is
         * still holding the exception, turns it back into the error string it was meant
         * to be.
         *
         * Addition needs no such bound: it can add at most one bit per keystroke, so the
         * only way to reach these magnitudes at all is through a product.
         *
         * Only the large side is bounded. A product driven far *below* one is never
         * refused: the display searches a couple of thousand places, finds every one of
         * them zero, and prints `0…` — the honest answer, at no cost.
         *
         * The bound is measured on the *operands*, each on its own, and never by probing
         * the product at a fixed precision. `product.msd(0)` asks the product to within
         * one unit, and MultCR can only deliver that by asking the smaller operand for
         * `msd(larger) + 3` bits after the point — for `sin(1) × 1E100000` that is a cosine
         * series carried to 332,000 bits, inside the evaluator, for a value the display
         * shows as `8.4E99999`. The sum of the operands' leading-bit positions is the
         * product's to within a bit, and each is found at its own scale.
         */
        private fun boundedProduct(a: UnifiedReal, b: UnifiedReal): UnifiedReal {
            val left = a.toConstructiveReal()
            val right = b.toConstructiveReal()
            // Bounded searches, so an operand that cannot be separated from zero answers
            // Int.MIN_VALUE instead of refining until the budget runs out — and a product
            // with such an operand cannot be too large to show.
            val msdLeft = left.estimateMsd(ConstructiveReal.HINT_FLOOR)
            val msdRight = right.estimateMsd(ConstructiveReal.HINT_FLOOR)
            if (msdLeft != Int.MIN_VALUE && msdRight != Int.MIN_VALUE) {
                val estimate = msdLeft.toLong() + msdRight.toLong()
                if (estimate > CalculationLimits.MAX_PRODUCT_BITS) throw TooMuchMemoryException()
            }
            return opaque(left * right)
        }

        private fun expFactor(exponent: BoundedRational): Factor {
            if (exponent.isZero) return Factor.One
            checkExpSize(exponent)
            return Factor.Exp(exponent)
        }

        /**
         * Refuses `e^r` whose result could never be materialised.
         *
         * [pow] refuses an oversized power before doing any work, but the e^x key does not
         * go through [pow]: `exp` would build `Factor.Exp(10^7)` for free and leave the
         * *formatter* to discover, fourteen million bits in, that it cannot finish — and
         * the formatting stage has neither a timeout nor a catch around it.
         *
         * Two different sizes are bounded, and only one of them cares about the sign.
         *
         * The *width of the answer* is a problem only when the exponent is positive:
         * `e^(−10^7)` is a value the approximation layer disposes of in microseconds,
         * because every operand is indistinguishable from zero at the precision asked for,
         * and refusing it would report an error for an answer that is simply zero to every
         * digit anyone can scroll to.
         *
         * The *magnitude of the exponent* is a problem for either sign. That was the hole:
         * [ConstructiveReal.exp] halves its argument until it is under about 1/512, so the
         * multiplication tree it leaves behind is `log2(|r|)` levels deep regardless of
         * which way the exponent points, and the stage that expands that tree is the
         * formatter — where a `StackOverflowError` is an `Error` that escapes every catch
         * in the app and kills the process. `e^(0−10^600)` is ten keypresses, and it was
         * waved through by a guard reasoning about a width that was never the cost.
         */
        private fun checkExpSize(exponent: BoundedRational) {
            // A strict upper bound on log2(|r|): num < 2^bitLength(num) and den ≥
            // 2^(bitLength(den) − 1). Costs nothing beside the arithmetic that built r.
            val magnitudeBits =
                exponent.num.abs().bitLength() - exponent.den.bitLength() + 1
            if (magnitudeBits > CalculationLimits.MAX_EXP_ARGUMENT_BITS) {
                throw TooMuchMemoryException()
            }
            if (exponent.signum <= 0) return
            // log2(e) < 1.443, so this over-estimates the width of e^r rather than
            // under-estimating it, which is the safe direction for a refusal.
            val bits = (exponent.num * BigInteger.valueOf(1443L)) /
                (exponent.den * BigInteger.valueOf(1000L))
            CalculationLimits.checkBits(bits, CalculationLimits.MAX_EXP_BITS)
        }

        private fun logFactor(arg: BoundedRational): Factor =
            if (arg.isOne) Factor.One else Factor.Log(arg)

        /**
         * Ceiling on the exponent of a symbolic `π`.
         *
         * `π^n` has no closed form, so it is expanded into `n` multiplications by π the
         * moment a digit is asked for. Nothing bounded that exponent, and every press of
         * the x² key doubles it, so a dozen keystrokes built a multiplication chain deep
         * enough to overflow the stack inside the formatter — where, unlike the evaluator,
         * nothing catches a StackOverflowError. A thousand is already orders of magnitude
         * past any expression a keypad produces on purpose.
         */
        private const val MAX_PI_POWER: Long = 1_000L

        /**
         * Builds a `π^power` factor, refusing an exponent that could not be expanded.
         *
         * Takes a `Long` so that the caller's addition of two powers cannot itself wrap:
         * an overflow to [Int.MIN_VALUE] used to survive all the way to [piPower], where
         * the negation of it is itself, and the value silently became 1.
         */
        private fun piFactor(power: Long): Factor = when {
            power == 0L -> Factor.One
            power > MAX_PI_POWER || power < -MAX_PI_POWER -> throw TooMuchMemoryException()
            else -> Factor.Pi(power.toInt())
        }

        /**
         * `π^|power|`, by repeated squaring.
         *
         * A left-nested chain of `|power|` multiplications is not merely slow:
         * [ConstructiveReal] approximates a product by recursing into its operands, so the
         * chain costs stack frames proportional to its length and overflows long before it
         * produces a digit. Squaring keeps both the work and the recursion at log₂.
         */
        private fun piPower(power: Int): ConstructiveReal {
            // Widened before negating: Math.abs(Int.MIN_VALUE) is negative, and a negative
            // count would silently produce 1 instead of refusing.
            var remaining = Math.abs(power.toLong())
            var squared = ConstructiveReal.PI
            var acc = ConstructiveReal.ONE
            while (remaining > 0L) {
                CalculationLimits.checkNotAborted()
                if (remaining and 1L == 1L) acc = acc * squared
                remaining = remaining shr 1
                if (remaining > 0L) squared = squared * squared
            }
            return acc
        }

        /**
         * The exact `index`-th root of a positive rational, or null when there is not one.
         *
         * Both halves of the fraction have to come out whole: 8/27 has an exact cube root
         * because 8 and 27 both do, while 8/26 does not. The rational is already in lowest
         * terms, so a common factor cannot rescue a root that the parts do not have.
         */
        private fun exactRootOfRational(value: BoundedRational, index: BigInteger): BoundedRational? {
            if (index.signum() <= 0 || index.bitLength() > 31) return null
            val n = index.toInt()
            if (n == 1) return value
            val num = exactIntegerRoot(value.num, n) ?: return null
            val den = exactIntegerRoot(value.den, n) ?: return null
            return BoundedRational.of(num, den)
        }

        /**
         * The exact `n`-th root of a non-negative integer, or null when it is not one.
         *
         * Newton's method converging downward from an upper bound, then verified by raising
         * the answer back: the verification is what makes "exact" mean exact rather than
         * "the nearest integer to the root". Anything with an index past the value's bit
         * length is refused first — a root of two or more would need the value to be at
         * least 2^n — which also keeps `x.pow(n - 1)` inside the loop from being asked for
         * an absurd power when the user types an exponent like 1/1000000.
         */
        private fun exactIntegerRoot(value: BigInteger, n: Int): BigInteger? {
            if (value.signum() < 0) return null
            if (value.signum() == 0) return BigInteger.ZERO
            if (value == BigInteger.ONE) return BigInteger.ONE
            if (n > value.bitLength()) return null

            val nBig = BigInteger.valueOf(n.toLong())
            val nMinusOne = BigInteger.valueOf((n - 1).toLong())
            var x = BigInteger.ONE.shiftLeft(value.bitLength() / n + 1)
            while (true) {
                CalculationLimits.checkNotAborted()
                val next = (nMinusOne * x + value / x.pow(n - 1)) / nBig
                if (next >= x) break
                x = next
            }
            return if (x.pow(n) == value) x else null
        }

        /**
         * How far `√n` is trial-divided in search of a square factor.
         *
         * Small on purpose: users type `√8` and `√50`, not `√(p²q)` for six-digit primes,
         * and an unbounded factorisation would be a much better way to hang the calculator
         * than any arithmetic in it.
         */
        private const val TRIAL_DIVISION_BOUND: Long = 10_000L

        /**
         * Splits `√n` into `coefficient × √(square-free part)`.
         *
         * Square-freeness of what comes back is what [Factor.Sqrt] promises and what
         * [plus] relies on, so the trial division to [TRIAL_DIVISION_BOUND] is backed by
         * two further tests that cost one square root each: the whole remainder may itself
         * be a perfect square, and — once the small primes still present are known — so may
         * the part of it built from nothing but large ones. What escapes all three is
         * documented on [Factor.Sqrt].
         */
        private fun sqrtOfInteger(n: BigInteger): Pair<BoundedRational, Factor> {
            require(n.signum() > 0)
            var remaining = n
            var coefficient = BigInteger.ONE
            // The distinct primes below the trial bound that survive in the remainder, each
            // to the first power. Every square factor the loop could not reach is therefore
            // contained in `remaining / smallPart`, which is what lets one probe decide it.
            var smallPart = BigInteger.ONE
            var d = 2L
            while (d <= TRIAL_DIVISION_BOUND) {
                CalculationLimits.checkNotAborted()
                val divisor = BigInteger.valueOf(d)
                // d is at most 10,000, so d² cannot overflow a Long on the way in.
                val square = BigInteger.valueOf(d * d)
                if (square > remaining) break
                if (remaining.mod(divisor).signum() == 0) {
                    if (remaining.mod(square).signum() == 0) {
                        // Peeling one square at a time is linear in the *count* of factors,
                        // and the count is unbounded: √(1E100000) arrives here as 10^100000,
                        // whose hundred thousand factors of two cost seven seconds of
                        // BigInteger division — inside the one loop in this module with no
                        // cancellation point, so an interrupted preview held a core long
                        // after the UI had moved on. stripFactors peels `d^(2^k)` blocks
                        // instead, one division per bit of the count, and checks for
                        // cancellation at every step.
                        val (count, rest) = BoundedRational.stripFactors(remaining, divisor)
                        coefficient *= divisor.pow(count / 2)
                        remaining = if (count % 2 == 1) rest * divisor else rest
                    }
                    // d survives at most once now. Only a divisor coprime to what has
                    // already been collected can be prime — 6 divides only when 2 and 3
                    // both do, and both were taken at their own step — so this stays a
                    // product of distinct primes, which is what makes it square-free.
                    if (remaining.mod(divisor).signum() == 0 &&
                        smallPart.gcd(divisor) == BigInteger.ONE
                    ) {
                        smallPart *= divisor
                    }
                }
                d++
            }
            // The part built only from primes past the bound. A square factor the loop
            // could not see lives entirely here, so if this is a perfect square the whole
            // radicand normalises — which is how √(2·10007²) becomes 10007√2 rather than
            // staying an un-normalised √200280098 that 10007√2 can never be shown to equal.
            if (smallPart > BigInteger.ONE) {
                val cofactor = remaining / smallPart
                val cofactorRoot = BoundedRational.integerSqrt(cofactor)
                if (cofactorRoot * cofactorRoot == cofactor) {
                    coefficient *= cofactorRoot
                    remaining = smallPart
                }
            }
            // What is left may still be a large perfect square that trial division missed.
            val root = BoundedRational.integerSqrt(remaining)
            if (root * root == remaining) {
                coefficient *= root
                remaining = BigInteger.ONE
            }
            val factor = if (remaining == BigInteger.ONE) Factor.One else Factor.Sqrt(remaining)
            return BoundedRational.of(coefficient) to factor
        }

        /** The exponent when [value] is an exact power of ten, otherwise `null`. */
        private fun powerOfTen(value: BoundedRational): Int? {
            fun exponentOf(i: BigInteger): Int? {
                if (i <= BigInteger.ZERO) return null
                // Shared with digitsRequired for the same reason: `log(1E-100000)` reaches
                // here as 10^100000, and dividing by ten a hundred thousand times is both
                // slow and — because the loop had no cancellation point — unstoppable.
                val (count, remaining) = BoundedRational.stripFactors(i, BigInteger.TEN)
                return if (remaining == BigInteger.ONE) count else null
            }
            if (value.isInteger) return exponentOf(value.num)
            if (value.num == BigInteger.ONE) return exponentOf(value.den)?.let { -it }
            return null
        }

        /**
         * `sin` of a whole number of degrees, exactly, or `null` when the angle is not on
         * the standard table.
         */
        private fun sinOfExactDegrees(degrees: Int): UnifiedReal? {
            val d = ((degrees % 360) + 360) % 360
            // Reduce to the first quadrant and remember the sign, halving the table.
            val (reduced, negate) = when {
                d <= 90 -> d to false
                d <= 180 -> (180 - d) to false
                d <= 270 -> (d - 180) to true
                else -> (360 - d) to true
            }
            val value = when (reduced) {
                0 -> ZERO
                30 -> of(BoundedRational.HALF)
                45 -> make(BoundedRational.HALF, Factor.Sqrt(ConstructiveReal.BIG2))
                60 -> make(BoundedRational.HALF, Factor.Sqrt(BigInteger.valueOf(3L)))
                90 -> ONE
                else -> return null
            }
            return if (negate) -value else value
        }

        private fun degreesToMode(degrees: Int, mode: AngleMode): UnifiedReal = when (mode) {
            AngleMode.DEGREES -> of(BoundedRational.of(degrees.toLong()))
            // Exact in radians too: 30 degrees is π/6, not 0.5235987755982988.
            AngleMode.RADIANS -> make(BoundedRational.of(degrees.toLong(), 180L), Factor.Pi(1))
        }

        private fun fromRadians(value: ConstructiveReal, mode: AngleMode): ConstructiveReal =
            when (mode) {
                AngleMode.RADIANS -> value
                AngleMode.DEGREES -> value * ConstructiveReal.valueOf(180) / ConstructiveReal.PI
            }
    }
}
