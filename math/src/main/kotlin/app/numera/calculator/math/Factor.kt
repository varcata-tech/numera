package app.numera.calculator.math

import java.math.BigInteger

/**
 * The irrational part of a [UnifiedReal], kept in symbolic form.
 *
 * The set is deliberately tiny. It is not an attempt at computer algebra — it is the
 * smallest collection of shapes that survives the operations people actually chain on a
 * keypad, chosen so that `√2 × √2`, `e^ln 5` and `π ÷ π` can each be *decided* rather than
 * merely approximated. Anything that falls outside becomes [Opaque], which is still
 * numerically correct, just no longer provably exact.
 */
sealed interface Factor {

    /** No irrational part: the number is exactly its rational factor. */
    data object One : Factor

    /**
     * `√radicand`, where [radicand] is a square-free integer greater than 1.
     *
     * Square-freeness is an invariant, not a convenience: it is what makes `√8` and `2√2`
     * the same object, and therefore what lets `√2 + √8` collapse to `3√2`.
     */
    data class Sqrt(val radicand: BigInteger) : Factor

    /** `e^exponent`, with a non-zero [exponent]. */
    data class Exp(val exponent: BoundedRational) : Factor

    /** `ln(arg)`, with [arg] positive and not 1. */
    data class Log(val arg: BoundedRational) : Factor

    /** `π^power`, with a non-zero [power]; negative powers arise from division. */
    data class Pi(val power: Int) : Factor

    /**
     * Anything else — an escape hatch holding a fully general constructive real.
     *
     * Correct to any precision asked of it, but two [Opaque]s can never be *proven* equal,
     * so reaching this state costs exactness even though it never costs accuracy.
     */
    data class Opaque(val value: ConstructiveReal) : Factor
}
