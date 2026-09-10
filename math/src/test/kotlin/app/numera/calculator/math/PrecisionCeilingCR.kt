package app.numera.calculator.math

import java.math.BigInteger

/**
 * A constructive real that refuses to be approximated finer than [floor] bits.
 *
 * Tests use it where the failure being guarded against is not a wrong digit but an
 * *unaffordable* request: a magnitude probe on `x × 10^100000` that asks `x` for three
 * hundred thousand bits after the point. A timing assertion cannot tell that apart from a
 * slow machine; an operand that throws the moment it is asked for more than it should be
 * can. The value is `numerator / 2^10`, so 861 is about 0.84 — the size of `sin(1)`.
 */
internal class PrecisionCeilingCR(
    numerator: Long,
    private val floor: Int,
) : ConstructiveReal() {

    private val scaledValue: BigInteger = BigInteger.valueOf(numerator)

    override fun approximate(precision: Int): BigInteger {
        if (precision < floor) {
            throw AssertionError("asked for precision $precision, below the ceiling of $floor")
        }
        return ConstructiveReal.scale(scaledValue, -10 - precision)
    }
}
