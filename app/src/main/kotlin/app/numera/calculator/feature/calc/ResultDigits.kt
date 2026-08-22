package app.numera.calculator.feature.calc

/**
 * How many decimal places the result line should ask for next, and whether what comes back
 * is still an approximation.
 *
 * Split out of the view model because this is the part that goes wrong without *looking*
 * wrong. Two failures live here and neither is visible in a screenshot: growing the request
 * by a fixed step makes scrolling one seventh quadratic and it dies a few hundred digits in,
 * and printing a truncated expansion with no `…` makes it indistinguishable from a value
 * that genuinely stops there — which is the one distinction the exact engine exists for.
 */
internal data class DigitRequest(
    /** Decimal places to ask [app.numera.calculator.math.format.ResultFormatter] for. */
    val digits: Int,
    /** True when digits remain beyond [digits], so the display must carry an ellipsis. */
    val truncated: Boolean,
)

/** First expansion, in decimal places. Comfortably more than the display's own budget. */
internal const val INITIAL_DIGITS: Int = 50

/** Floor on each expansion, so an early doubling still makes visible progress. */
internal const val MIN_DIGIT_STEP: Int = 30

/**
 * The next digit count to ask for, having already shown [shown] places.
 *
 * Doubling is the whole reason a thousand digits of one seventh are reachable at all: a
 * fixed increment would need one evaluation per screenful and the cost of each one grows
 * with the digits already produced.
 */
internal fun nextDigitTarget(shown: Int): Int {
    // A doubling that overflows would come back negative and be silently treated as "no
    // more digits", stalling the scroll for good. Unreachable in practice; cheap to refuse.
    if (shown >= Int.MAX_VALUE / 2) return shown
    return maxOf(shown * 2, shown + MIN_DIGIT_STEP, INITIAL_DIGITS)
}

/**
 * Reconciles what was asked for with what the value actually has.
 *
 * @param digitsRequired `UnifiedReal.digitsRequired()`: the number of decimal places at
 *   which the value stops, or `null` when it never does.
 * @param target the number of places the scroll position asked for.
 *
 * A value that stops earlier than [target] is requested at its own length, not at [target]:
 * asking a 76-digit integer for fifty decimal places renders it followed by a point and
 * fifty zeros, which is correct, misleading, and needlessly wide.
 */
internal fun digitRequest(digitsRequired: Int?, target: Int): DigitRequest = when {
    digitsRequired == null -> DigitRequest(digits = target, truncated = true)
    digitsRequired > target -> DigitRequest(digits = target, truncated = true)
    else -> DigitRequest(digits = digitsRequired, truncated = false)
}
