package app.numera.calculator.math

import app.numera.calculator.math.CalculationLimits.checkNotAborted
import app.numera.calculator.math.ConstructiveReal.Companion.BIG1
import app.numera.calculator.math.ConstructiveReal.Companion.boundLog2
import app.numera.calculator.math.ConstructiveReal.Companion.scale
import java.math.BigInteger

/**
 * The operation nodes behind [ConstructiveReal].
 *
 * Every class here answers the same question — "give me this value to precision `p`" — by
 * working out how much precision it needs *from its own operands* to make its own answer
 * good to one unit in the last place. The scaling arithmetic is unforgiving: an off-by-one
 * in a shift count does not crash, it silently returns wrong digits far to the right of
 * the decimal point, which is precisely what the digit-level tests exist to catch.
 *
 * Kotlin reimplementation of Hans-J. Boehm's constructive reals. See NOTICE.md.
 */

/** Plain shift, without [scale]'s rounding, for places where the bias cannot accumulate. */
private fun shiftBI(k: BigInteger, n: Int): BigInteger = when {
    n == 0 -> k
    n < 0 -> k.shiftRight(-n)
    else -> k.shiftLeft(n)
}

/** An exact integer. The base case for everything else. */
internal class IntCR(private val value: BigInteger) : ConstructiveReal() {
    override fun approximate(precision: Int): BigInteger = scale(value, -precision)
}

/** Sum. Each operand is asked for two extra bits so the two roundings cannot combine. */
internal class AddCR(
    private val op1: ConstructiveReal,
    private val op2: ConstructiveReal,
) : ConstructiveReal() {
    override fun approximate(precision: Int): BigInteger =
        scale(op1.getAppr(precision - 2) + op2.getAppr(precision - 2), -2)
}

internal class NegCR(private val op: ConstructiveReal) : ConstructiveReal() {
    override fun approximate(precision: Int): BigInteger = op.getAppr(precision).negate()
}

/** Multiplication by `2^shift`, which is exact: it only moves the precision request. */
internal class ShiftedCR(
    private val op: ConstructiveReal,
    private val shift: Int,
) : ConstructiveReal() {
    override fun approximate(precision: Int): BigInteger = op.getAppr(precision - shift)
}

/**
 * Product.
 *
 * The precision each operand needs depends on how *large the other one is*, so the msd of
 * one is found first and used to budget the other. When neither operand can be shown to be
 * non-zero at the working precision, the product is small enough that zero is a legal
 * answer — that early return is what stops `0 × (something expensive)` from being expensive.
 */
internal class MultCR(
    private var op1: ConstructiveReal,
    private var op2: ConstructiveReal,
) : ConstructiveReal() {
    override fun approximate(precision: Int): BigInteger {
        val halfPrec = (precision shr 1) - 1
        var msdOp1 = op1.msd(halfPrec)

        if (msdOp1 == Int.MIN_VALUE) {
            val msdOp2 = op2.msd(halfPrec)
            if (msdOp2 == Int.MIN_VALUE) return BigInteger.ZERO
            // Order them so the operand known to be large is the one we budget against.
            val tmp = op1
            op1 = op2
            op2 = tmp
            msdOp1 = msdOp2
        }

        val prec2 = precision - msdOp1 - 3
        val appr2 = op2.getAppr(prec2)
        if (appr2.signum() == 0) return BigInteger.ZERO
        val msdOp2 = op2.knownMsd()
        val prec1 = precision - msdOp2 - 3
        val appr1 = op1.getAppr(prec1)
        return scale(appr1 * appr2, prec1 + prec2 - precision)
    }
}

/**
 * Reciprocal.
 *
 * Needs the msd of the operand before it can choose a precision, and
 * [ConstructiveReal.requireMsd] is what refuses to search forever when the operand is
 * actually zero — turning `1÷(e^π−e^π)` into a reported error rather than a hang. It has to
 * be `requireMsd` and not `iterMsd`: an undecided [Int.MIN_VALUE] would flow into
 * `1 − msd` below, wrap to a plausible precision, and end at a BigInteger divide by zero.
 */
internal class InvCR(private val op: ConstructiveReal) : ConstructiveReal() {
    override fun approximate(precision: Int): BigInteger {
        val msd = op.requireMsd()
        val invMsd = 1 - msd
        val digitsNeeded = invMsd - precision + 3
        val precNeeded = msd - digitsNeeded
        val logScaleFactor = -precision - precNeeded
        if (logScaleFactor < 0) return BigInteger.ZERO

        val dividend = BIG1.shiftLeft(logScaleFactor)
        val scaledDivisor = op.getAppr(precNeeded)
        val absDivisor = scaledDivisor.abs()
        // Adding half the divisor before dividing turns truncation into round-to-nearest.
        val adjustedDividend = dividend + absDivisor.shiftRight(1)
        val result = adjustedDividend / absDivisor
        return if (scaledDivisor.signum() < 0) result.negate() else result
    }
}

/**
 * Square root, by Newton iteration on integers.
 *
 * Each step doubles the number of correct bits, and the recursive [getAppr] call at half
 * the precision is what seeds it — so the cost is dominated by the final iteration rather
 * than by the whole ladder. Below the double-precision threshold it just borrows
 * `Math.sqrt`, which is exact enough to be a legal 1-ulp answer at that size.
 */
internal class SqrtCR(private val op: ConstructiveReal) : ConstructiveReal() {

    private companion object {
        const val FP_PREC = 50
        const val FP_OP_PREC = 60
    }

    override fun approximate(precision: Int): BigInteger {
        val maxOpPrecNeeded = 2 * precision - 1
        val msd = op.iterMsd(maxOpPrecNeeded)
        if (msd <= maxOpPrecNeeded) return BigInteger.ZERO

        val resultMsd = msd / 2
        val resultDigits = resultMsd - precision

        return if (resultDigits > FP_PREC) {
            val apprDigits = resultDigits / 2 + 6
            val apprPrec = resultMsd - apprDigits
            val prodPrec = 2 * apprPrec
            val opAppr = op.getAppr(prodPrec)
            val lastAppr = getAppr(apprPrec)
            // (last² + op) / last / 2, with the scaling folded into the division.
            val numerator = lastAppr * lastAppr + opAppr
            val scaledNumerator = scale(numerator, apprPrec - precision)
            val shiftedResult = scaledNumerator / lastAppr
            shiftedResult.add(BIG1).shiftRight(1)
        } else {
            val opPrec = (msd - FP_OP_PREC) and 1.inv()
            val workingPrec = opPrec - FP_OP_PREC
            val scaledBi = op.getAppr(opPrec).shiftLeft(FP_OP_PREC)
            val scaledAppr = scaledBi.toDouble()
            if (scaledAppr < 0.0) throw NotANumberException()
            val scaledSqrt = BigInteger.valueOf(Math.sqrt(scaledAppr).toLong())
            shiftBI(scaledSqrt, workingPrec / 2 - precision)
        }
    }
}

/**
 * `e^x` by Taylor series, valid for `|x|` up to about 2.
 *
 * The caller ([ConstructiveReal.exp]) is responsible for halving larger arguments; the
 * iteration budget here assumes the reduction has already happened.
 */
internal class PrescaledExpCR(private val op: ConstructiveReal) : ConstructiveReal() {
    override fun approximate(precision: Int): BigInteger {
        if (precision >= 1) return BigInteger.ZERO
        val iterationsNeeded = -precision / 2 + 2
        val calcPrecision = precision - boundLog2(2 * iterationsNeeded) - 4
        val opPrec = precision - 3
        val opAppr = op.getAppr(opPrec)

        val scaledOne = BIG1.shiftLeft(-calcPrecision)
        var currentTerm = scaledOne
        var currentSum = scaledOne
        var n = 0
        val maxTruncError = BIG1.shiftLeft(precision - 4 - calcPrecision)

        while (currentTerm.abs() >= maxTruncError) {
            checkNotAborted()
            n++
            currentTerm = scale(currentTerm * opAppr, opPrec) / BigInteger.valueOf(n.toLong())
            currentSum += currentTerm
        }
        return scale(currentSum, calcPrecision - precision)
    }
}

/** `cos x` by Taylor series, valid for `|x| ≤ 1`; larger arguments are reduced by the caller. */
internal class PrescaledCosCR(private val op: ConstructiveReal) : ConstructiveReal() {
    override fun approximate(precision: Int): BigInteger {
        if (precision >= 1) return BigInteger.ZERO
        val iterationsNeeded = -precision / 2 + 4
        val calcPrecision = precision - boundLog2(2 * iterationsNeeded) - 4
        val opPrec = precision - 2
        val opAppr = op.getAppr(opPrec)

        var currentTerm = BIG1.shiftLeft(-calcPrecision)
        var currentSum = currentTerm
        var n = 0
        val maxTruncError = BIG1.shiftLeft(precision - 4 - calcPrecision)

        while (currentTerm.abs() >= maxTruncError) {
            checkNotAborted()
            n += 2
            currentTerm = scale(currentTerm * opAppr, opPrec)
            currentTerm = scale(currentTerm * opAppr, opPrec)
            val divisor = BigInteger.valueOf(-n.toLong()) * BigInteger.valueOf((n - 1).toLong())
            currentTerm /= divisor
            currentSum += currentTerm
        }
        return scale(currentSum, calcPrecision - precision)
    }
}

/** `ln(1 + x)` by Taylor series, valid for `|x| < 1/2`. */
internal class PrescaledLnCR(private val op: ConstructiveReal) : ConstructiveReal() {
    override fun approximate(precision: Int): BigInteger {
        if (precision >= 0) return BigInteger.ZERO
        val iterationsNeeded = -precision
        val calcPrecision = precision - boundLog2(2 * iterationsNeeded) - 4
        val opPrec = precision - 3
        val opAppr = op.getAppr(opPrec)

        var xNth = scale(opAppr, opPrec - calcPrecision)
        var currentTerm = xNth
        var currentSum = currentTerm
        var n = 1
        var currentSign = 1
        val maxTruncError = BIG1.shiftLeft(precision - 4 - calcPrecision)

        while (currentTerm.abs() >= maxTruncError) {
            checkNotAborted()
            n++
            currentSign = -currentSign
            xNth = scale(xNth * opAppr, opPrec)
            currentTerm = xNth / BigInteger.valueOf((n * currentSign).toLong())
            currentSum += currentTerm
        }
        return scale(currentSum, calcPrecision - precision)
    }
}

/** `atan x` by Taylor series, valid for `|x| ≤ 1/2`. */
internal class PrescaledAtanCR(private val op: ConstructiveReal) : ConstructiveReal() {
    override fun approximate(precision: Int): BigInteger {
        if (precision >= 0) return BigInteger.ZERO
        val iterationsNeeded = -precision / 2 + 2
        val calcPrecision = precision - boundLog2(2 * iterationsNeeded) - 4
        val opPrec = precision - 3
        val opAppr = op.getAppr(opPrec)

        var xNth = scale(opAppr, opPrec - calcPrecision)
        var currentTerm = xNth
        var currentSum = currentTerm
        var n = 1
        var currentSign = 1
        val maxTruncError = BIG1.shiftLeft(precision - 4 - calcPrecision)

        while (currentTerm.abs() >= maxTruncError) {
            checkNotAborted()
            n += 2
            currentSign = -currentSign
            // Advance by x², so each loop produces the next odd power.
            xNth = scale(xNth * opAppr, opPrec)
            xNth = scale(xNth * opAppr, opPrec)
            currentTerm = xNth / BigInteger.valueOf((n * currentSign).toLong())
            currentSum += currentTerm
        }
        return scale(currentSum, calcPrecision - precision)
    }
}

/**
 * `atan(1/n)` for a small whole `n`, computed entirely in integers.
 *
 * Machin's formula for π is built from two of these, and keeping the argument an exact
 * reciprocal means the series never has to approximate its own input.
 */
internal class IntegralAtanCR(private val op: Int) : ConstructiveReal() {
    override fun approximate(precision: Int): BigInteger {
        if (precision >= 1) return BigInteger.ZERO
        val iterationsNeeded = -precision / 2 + 2
        val calcPrecision = precision - boundLog2(2 * iterationsNeeded) - 2

        val scaledOne = BIG1.shiftLeft(-calcPrecision)
        val bigOp = BigInteger.valueOf(op.toLong())
        val bigOpSquared = BigInteger.valueOf((op.toLong() * op.toLong()))
        val opInverse = scaledOne / bigOp

        var currentPower = opInverse
        var currentTerm = opInverse
        var currentSum = opInverse
        var currentSign = 1
        var n = 1
        val maxTruncError = BIG1.shiftLeft(precision - 2 - calcPrecision)

        while (currentTerm.abs() >= maxTruncError) {
            checkNotAborted()
            n += 2
            currentPower /= bigOpSquared
            currentSign = -currentSign
            currentTerm = currentPower / BigInteger.valueOf((currentSign * n).toLong())
            currentSum += currentTerm
        }
        return scale(currentSum, calcPrecision - precision)
    }
}
