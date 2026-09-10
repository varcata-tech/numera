package app.numera.calculator.math

import java.math.BigInteger

/**
 * Every arithmetic failure the engine is allowed to report to a user.
 *
 * These are deliberately unchecked and deliberately narrow: the UI layer maps each one to
 * exactly one error string, so a new failure mode has to be given a user-visible meaning
 * here rather than leaking out as a generic crash.
 */
sealed class CalculationException(message: String) : ArithmeticException(message)

/**
 * The computation was cancelled, almost always because the user typed another key.
 *
 * Thrown from deep inside an approximation loop, so it must not be swallowed by an
 * intermediate `catch (e: ArithmeticException)` — the evaluator lets it propagate and
 * simply drops the result.
 */
class AbortedException : CalculationException("aborted")

/**
 * A value could not be shown to be non-zero within the precision budget.
 *
 * Equality of constructive reals is only semi-decidable: if a number really is zero, no
 * finite number of approximations will ever prove it. Rather than loop forever refining,
 * the engine gives up at a fixed depth and reports this, which the UI turns into
 * "Bad expression" — the honest answer being "I cannot decide".
 */
class PrecisionOverflowException : CalculationException("precision overflow")

/**
 * The result is larger than the operation that produced it is allowed to build.
 *
 * There is no single ceiling, because the routes to a large number are different cost
 * shapes rather than different sizes: see [CalculationLimits.MAX_EXP_BITS],
 * [CalculationLimits.MAX_PRODUCT_BITS], [CalculationLimits.MAX_RATIONAL_BITS] and
 * [CalculationLimits.MAX_EXP_ARGUMENT_BITS].
 */
class TooMuchMemoryException : CalculationException("requires too much memory")

/** Division by a value proven to be exactly zero. */
class DivideByZeroException : CalculationException("divide by zero")

/** A real-valued function was asked for a result outside its domain, e.g. `sqrt(-1)`. */
class NotANumberException : CalculationException("not a number")

/**
 * The bounds that keep a pocket calculator from becoming a denial-of-service device.
 *
 * A user can type `10^10^10` in four keystrokes. Nothing below is a performance tuning
 * knob; each one is the difference between an answer and an ANR.
 */
object CalculationLimits {

    /**
     * Above this, a [BoundedRational] stops being worth keeping exact.
     *
     * Exceeding it is not an error — the operation returns `null` and the caller falls
     * through to the constructive-real layer, which is slower but has no size ceiling.
     */
    const val MAX_RATIONAL_BITS: Int = 10_000

    /**
     * Ceiling on the width of a value reached through the exponential, in bits.
     *
     * Far below what an integer power is allowed, because the two are different cost
     * shapes rather than different sizes. An integer power is produced by repeated
     * squaring, which is close to linear in the width of its result; `e^x` is produced by
     * a Taylor series whose *iteration count* grows with the precision asked of it, so each
     * of those iterations multiplies numbers as wide as the whole answer. `e^(10^7)` is
     * only 14 million bits and is already past the point of no return — tens of millions of
     * multiplications on fourteen-million-bit integers is not slow, it is unreachable.
     */
    const val MAX_EXP_BITS: Long = 1_000_000L

    /**
     * Ceiling on `log2` of the *magnitude* of an exponential's argument.
     *
     * A separate bound from [MAX_EXP_BITS], and symmetric in sign where that one is not,
     * because it bounds a different thing: not how wide `e^x` is, but how deep the
     * procedure for producing it goes. [ConstructiveReal.exp] reduces its argument by
     * halving until `|x|` is under about 1/512, so it leaves behind a multiplication tree
     * `log2(|x|) + 10` levels deep whatever the sign of `x`, and approximating that tree
     * costs the same depth again in stack frames. The stage that expands it is the
     * *formatter*, where a `StackOverflowError` is an `Error`: it escapes `runInterruptible`,
     * `withTimeoutOrNull` and every catch in the view model, and ends the process rather
     * than the expression. `e^(0−10^600)` is ten keypresses.
     *
     * Three hundred is far looser than [MAX_EXP_BITS] permits on the positive side (an
     * exponent past ~693,147 is refused for its width, which is only 20 bits of
     * magnitude), so in practice it bounds the negative side — where the *value* is
     * perfectly representable as `0…` and only the procedure is not. It leaves roughly a
     * fivefold margin against the 1 MB stack a non-main Android thread is given. Both
     * bounds are enforced inside [ConstructiveReal.exp] itself, not only on the rational
     * exponent that `UnifiedReal.checkExpSize` sees: `e^(π×1E8)` is a 29-bit argument and
     * a 450-million-bit value, and it reaches the series through the opaque route.
     */
    const val MAX_EXP_ARGUMENT_BITS: Int = 300

    /**
     * Ceiling on the width of a value reached by ordinary multiplication, in bits.
     *
     * `log2(10^1000000)`, because that is the largest magnitude the result line can render:
     * past it the formatter has no power of ten to normalise a mantissa against and reports
     * [TooMuchMemoryException]. Multiplication is the only operation that can climb this
     * far — every other route is already bounded by [MAX_EXP_BITS] or [MAX_RATIONAL_BITS] —
     * and once a product turns opaque nothing else tracks its size, so the refusal has to
     * happen at the multiplication rather than at the display, which catches nothing.
     */
    const val MAX_PRODUCT_BITS: Int = 3_321_928

    /** `n!` beyond this is refused outright rather than attempted. */
    const val MAX_FACTORIAL: Int = 20_000

    /**
     * How far [ConstructiveReal.msd] will look before declaring a value undecidably zero.
     *
     * Negative because it is a binary precision: -2_000_000 means "approximated to two
     * million bits after the point and still indistinguishable from zero".
     */
    const val MIN_MSD_PRECISION: Int = -2_000_000

    /**
     * Cooperative cancellation checkpoint, called at the head of every approximation and
     * inside every iteration loop.
     *
     * Interruption rather than a flag of our own, because it is what `runInterruptible`
     * already raises when the surrounding coroutine is cancelled — so cancelling the job
     * is enough, and there is no second mechanism to keep in sync. Without a check this
     * cheap in the inner loops, cancelling a runaway `sin(10^500)` leaves a thread
     * spinning at full tilt until the process dies.
     */
    fun checkNotAborted() {
        if (Thread.currentThread().isInterrupted) throw AbortedException()
    }

    /**
     * Refuses a computation whose result is already known to be too large to hold.
     *
     * The estimate is a [BigInteger] rather than a `Long` because the estimate for
     * `e^(10^100000)` has thirty thousand digits of its own; narrowing it first would wrap
     * and let through exactly the case the check exists for. There is deliberately no
     * overload that defaults [limit]: which ceiling applies depends on how the value is
     * going to be produced, so the caller has to name it.
     */
    fun checkBits(bits: BigInteger, limit: Long) {
        if (bits > BigInteger.valueOf(limit)) throw TooMuchMemoryException()
    }
}
