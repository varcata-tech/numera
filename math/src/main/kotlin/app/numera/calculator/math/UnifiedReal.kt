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
        if (!exponent.isRational) return opaque(powViaExpLn(exponent))
        val e = exponent.ratFactor

        if (e.isZero) return ONE
        if (e == BoundedRational.HALF) return sqrt()
        // Any other half-integer exponent, and only for a rational base: e = k/2 with k odd
        // is (k−1)/2 whole powers times one square root, both of which the exact machinery
        // already handles. Handing it to powViaExpLn instead produced a Factor.Opaque, and
        // an opaque value can never report that it terminates, so a whole-number answer
        // came back wearing an ellipsis and an unbounded run of zeros.
        if (isRational && e.den == BigInteger.TWO) {
            val wholeHalves = (e.num - BigInteger.ONE) / BigInteger.TWO
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
                // The exact path declined. For an integer exponent that means only one
                // thing: the result is too big. Its size is known before any work is done
                // — bits are roughly exponent times the width of the base — so check it
                // here rather than discovering it inside a lazy approximation later.
                // Without this, 10^10^10 is four keystrokes that "succeed" and then wedge
                // the display thread the moment it asks for a digit. The bound is the
                // exponential's, not the general one, because the fall-through below is
                // powViaExpLn: this result is produced by a Taylor series, not by squaring.
                val widest = maxOf(ratFactor.num.bitLength(), ratFactor.den.bitLength())
                val estimatedBits = whole.abs() * BigInteger.valueOf(widest.toLong())
                CalculationLimits.checkBits(estimatedBits, CalculationLimits.MAX_EXP_BITS)
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
         */
        private fun boundedProduct(a: UnifiedReal, b: UnifiedReal): UnifiedReal {
            val product = a.toConstructiveReal() * b.toConstructiveReal()
            // Asked for at a fixed precision, so a value that cannot be separated from
            // zero answers Int.MIN_VALUE instead of refining until the budget runs out.
            val msd = product.msd(0)
            if (msd != Int.MIN_VALUE && msd > CalculationLimits.MAX_PRODUCT_BITS) {
                throw TooMuchMemoryException()
            }
            return opaque(product)
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
         * Only a positive exponent is bounded. `e^(−10^7)` is a value the approximation
         * layer disposes of in microseconds, because every operand is indistinguishable
         * from zero at the precision asked for; refusing it would report an error for an
         * answer that is simply zero to every digit anyone can scroll to.
         */
        private fun checkExpSize(exponent: BoundedRational) {
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
         * Splits `√n` into `coefficient × √(square-free part)`.
         *
         * The trial division bound is small on purpose: users type `√8` and `√50`, not
         * `√(p²q)` for six-digit primes, and an unbounded factorisation would be a much
         * better way to hang the calculator than any arithmetic in it.
         */
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

        private fun sqrtOfInteger(n: BigInteger): Pair<BoundedRational, Factor> {
            require(n.signum() > 0)
            var remaining = n
            var coefficient = BigInteger.ONE
            var d = 2L
            while (d <= 10_000L) {
                CalculationLimits.checkNotAborted()
                val square = BigInteger.valueOf(d * d)
                if (square > remaining) break
                while (remaining.mod(square).signum() == 0) {
                    remaining /= square
                    coefficient *= BigInteger.valueOf(d)
                }
                d++
            }
            // What is left may still be a large perfect square that trial division missed.
            val root = remaining.sqrt()
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
                45 -> make(BoundedRational.HALF, Factor.Sqrt(BigInteger.TWO))
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
