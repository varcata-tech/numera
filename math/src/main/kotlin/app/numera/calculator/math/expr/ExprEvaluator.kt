package app.numera.calculator.math.expr

import app.numera.calculator.math.AbortedException
import app.numera.calculator.math.AngleMode
import app.numera.calculator.math.CalculationException
import app.numera.calculator.math.DivideByZeroException
import app.numera.calculator.math.NotANumberException
import app.numera.calculator.math.PrecisionOverflowException
import app.numera.calculator.math.TooMuchMemoryException
import app.numera.calculator.math.UnifiedReal

/**
 * Everything that can go wrong with an expression, as one closed set.
 *
 * Closed because each value maps to exactly one user-visible string; a failure with no
 * meaning here would otherwise reach the display as a crash or as a wrong answer.
 */
enum class EvalError {
    /** The tokens do not form an expression, or the engine could not decide a value. */
    SYNTAX,

    /** Division by a value proven to be exactly zero, including `tan(90°)`. */
    DIVIDE_BY_ZERO,

    /** A function was asked for a value outside its domain, such as `√−1`. */
    NOT_A_NUMBER,

    /** The result is too large to hold. */
    TOO_MUCH_MEMORY,

    /** The user typed another key; this result is stale and must be discarded. */
    ABORTED,

    /** A literal in the expression names a magnitude outside the representable range. */
    OVERFLOW,
}

/** The outcome of an evaluation: an exact value, or one reason it could not be produced. */
sealed interface EvalResult {

    /** The value, still exact wherever exactness survived. */
    data class Success(val value: UnifiedReal) : EvalResult

    /** Why no value could be produced. */
    data class Failure(val error: EvalError) : EvalResult
}

/**
 * Turns a token stream into a number.
 *
 * [evaluate] is synchronous and total: it returns a [EvalResult.Failure] for every failure
 * rather than throwing, because it is called from a background thread on every keystroke
 * and an escaped exception there is a crash with no expression on screen to explain it.
 */
object ExprEvaluator {

    private val HUNDRED = UnifiedReal.of(100L)
    private val TEN = UnifiedReal.of(10L)
    private val TWO = UnifiedReal.of(2L)

    /** Largest `n` for which `n!` is computed in `Double`; 171! overflows to infinity. */
    private const val MAX_DOUBLE_FACTORIAL = 170.0

    /**
     * Evaluates [expr] exactly. Never throws.
     *
     * An empty expression is a syntax failure rather than zero: showing `0` for nothing
     * typed makes the result line lie about what will happen when a digit is pressed.
     */
    fun evaluate(expr: CalculatorExpr, mode: AngleMode): EvalResult {
        if (expr.isEmpty()) return EvalResult.Failure(EvalError.SYNTAX)
        return try {
            EvalResult.Success(exact(ExprParser(expr.tokens).parse(), mode))
        } catch (e: SyntaxException) {
            EvalResult.Failure(EvalError.SYNTAX)
        } catch (e: LiteralOverflowException) {
            EvalResult.Failure(EvalError.OVERFLOW)
        } catch (e: CalculationException) {
            EvalResult.Failure(map(e))
        } catch (e: ArithmeticException) {
            // BigInteger raises a bare ArithmeticException when a result would exceed its
            // own supported range; it is an overflow, not a bug in the expression.
            EvalResult.Failure(EvalError.OVERFLOW)
        } catch (e: StackOverflowError) {
            EvalResult.Failure(EvalError.SYNTAX)
        } catch (e: OutOfMemoryError) {
            EvalResult.Failure(EvalError.TOO_MUCH_MEMORY)
        }
    }

    /**
     * Compiles [expr] into a plain `Double` function of `x`, or `null` if it does not parse.
     *
     * The graph samples a thousand points every frame and exact arithmetic is orders of
     * magnitude too slow for that, so this walks the same tree in hardware floating point.
     * The returned closure yields [Double.NaN] for anything out of domain — including
     * infinities — so the plotter can break the line instead of guarding every call site.
     */
    fun compileToDouble(expr: CalculatorExpr, mode: AngleMode): ((Double) -> Double)? {
        // Compiling is inside the guard, not just parsing. The parser bounds its own
        // *recursion* depth, but `1+1+1+…` is parsed by an iterative loop into a left-leaning
        // tree as deep as the term count, and `compile` then descends that tree recursively:
        // parse depth is bounded where tree depth is not, so this is the half that overflows
        // first. A graph that cannot be compiled is a blank plot, never a crash.
        val compiled = try {
            // Compiled once into a tree of closures rather than interpreted per sample: the
            // per-point cost is what decides whether a pinch-zoom holds sixty frames a second.
            compile(ExprParser(expr.tokens).parse(), mode)
        } catch (e: Exception) {
            return null
        } catch (e: StackOverflowError) {
            return null
        }
        return { x ->
            val y = try {
                compiled(x)
            } catch (e: ArithmeticException) {
                Double.NaN
            } catch (e: StackOverflowError) {
                // Each sample re-descends the same tree, on a Dispatchers.Default worker
                // whose stack is smaller than the one that compiled it — so a depth that
                // compiled can still overflow here. An Error escaping a sample loop is an
                // uncaught failure on viewModelScope rather than a gap in the plotted line.
                Double.NaN
            }
            if (y.isFinite()) y else Double.NaN
        }
    }

    private fun map(e: CalculationException): EvalError = when (e) {
        is AbortedException -> EvalError.ABORTED
        is DivideByZeroException -> EvalError.DIVIDE_BY_ZERO
        is NotANumberException -> EvalError.NOT_A_NUMBER
        is TooMuchMemoryException -> EvalError.TOO_MUCH_MEMORY
        // "I cannot decide" is not "the answer is wrong": the honest report to the user is
        // the same bad-expression message, never a value.
        is PrecisionOverflowException -> EvalError.SYNTAX
    }

    // ---------------------------------------------------------------- exact walk

    private fun exact(node: Node, mode: AngleMode): UnifiedReal = when (node) {
        is Node.Literal -> UnifiedReal.of(node.rational)
        Node.Pi -> UnifiedReal.PI
        Node.EulerE -> UnifiedReal.E
        // An expression with a free variable has no value; only the graph can use it.
        Node.Variable -> throw SyntaxException()
        is Node.Negate -> -exact(node.child, mode)
        is Node.Additive -> {
            val left = exact(node.left, mode)
            val right = exact(node.right, mode)
            // A percentage on the right of + or − is a percentage *of the left operand*,
            // so 100 + 10% is 110. Anywhere else the same token just means ÷ 100.
            val delta = if (node.relative) left * right else right
            if (node.subtract) left - delta else left + delta
        }
        is Node.Multiply -> exact(node.left, mode) * exact(node.right, mode)
        is Node.Divide -> exact(node.left, mode) / exact(node.right, mode)
        is Node.Power -> exact(node.base, mode).pow(exact(node.exponent, mode))
        is Node.Factorial -> exact(node.child, mode).factorial()
        is Node.Percent -> exact(node.child, mode) / HUNDRED
        is Node.Square -> exact(node.child, mode).pow(TWO)
        is Node.Root -> exact(node.child, mode).sqrt()
        is Node.Call -> {
            val argument = exact(node.argument, mode)
            when (node.function) {
                KeyId.SIN -> argument.sin(mode)
                KeyId.COS -> argument.cos(mode)
                KeyId.TAN -> argument.tan(mode)
                KeyId.ASIN -> argument.asin(mode)
                KeyId.ACOS -> argument.acos(mode)
                KeyId.ATAN -> argument.atan(mode)
                KeyId.LN -> argument.ln()
                KeyId.LOG -> argument.log10()
                KeyId.EXP10 -> TEN.pow(argument)
                KeyId.EXPE -> argument.exp()
                else -> throw SyntaxException()
            }
        }
    }

    // ---------------------------------------------------------------- double walk

    private fun compile(node: Node, mode: AngleMode): (Double) -> Double = when (node) {
        is Node.Literal -> constant(node.approx)
        Node.Pi -> constant(Math.PI)
        Node.EulerE -> constant(Math.E)
        Node.Variable -> { x -> x }
        is Node.Negate -> unary(compile(node.child, mode)) { -it }
        is Node.Additive -> compileAdditive(node, mode)
        is Node.Multiply ->
            binary(compile(node.left, mode), compile(node.right, mode)) { a, b -> a * b }
        is Node.Divide ->
            binary(compile(node.left, mode), compile(node.right, mode)) { a, b -> a / b }
        is Node.Power ->
            binary(compile(node.base, mode), compile(node.exponent, mode)) { a, b -> Math.pow(a, b) }
        is Node.Factorial -> unary(compile(node.child, mode)) { factorial(it) }
        is Node.Percent -> unary(compile(node.child, mode)) { it / 100.0 }
        is Node.Square -> unary(compile(node.child, mode)) { it * it }
        is Node.Root -> unary(compile(node.child, mode)) { Math.sqrt(it) }
        is Node.Call -> compileCall(node, mode)
    }

    private fun compileAdditive(node: Node.Additive, mode: AngleMode): (Double) -> Double {
        val left = compile(node.left, mode)
        val right = compile(node.right, mode)
        return when {
            node.relative && node.subtract -> { x -> left(x).let { it - it * right(x) } }
            node.relative -> { x -> left(x).let { it + it * right(x) } }
            node.subtract -> { x -> left(x) - right(x) }
            else -> { x -> left(x) + right(x) }
        }
    }

    private fun constant(value: Double): (Double) -> Double = { _ -> value }

    private inline fun unary(
        crossinline child: (Double) -> Double,
        crossinline apply: (Double) -> Double,
    ): (Double) -> Double = { x -> apply(child(x)) }

    private inline fun binary(
        crossinline left: (Double) -> Double,
        crossinline right: (Double) -> Double,
        crossinline apply: (Double, Double) -> Double,
    ): (Double) -> Double = { x -> apply(left(x), right(x)) }

    private fun compileCall(node: Node.Call, mode: AngleMode): (Double) -> Double {
        val argument = compile(node.argument, mode)
        val degrees = mode == AngleMode.DEGREES
        return when (node.function) {
            KeyId.SIN -> { x -> Math.sin(toRadians(argument(x), degrees)) }
            KeyId.COS -> { x -> Math.cos(toRadians(argument(x), degrees)) }
            KeyId.TAN -> { x -> Math.tan(toRadians(argument(x), degrees)) }
            KeyId.ASIN -> { x -> fromRadians(Math.asin(argument(x)), degrees) }
            KeyId.ACOS -> { x -> fromRadians(Math.acos(argument(x)), degrees) }
            KeyId.ATAN -> { x -> fromRadians(Math.atan(argument(x)), degrees) }
            KeyId.LN -> { x -> Math.log(argument(x)) }
            KeyId.LOG -> { x -> Math.log10(argument(x)) }
            KeyId.EXP10 -> { x -> Math.pow(10.0, argument(x)) }
            KeyId.EXPE -> { x -> Math.exp(argument(x)) }
            else -> { _ -> Double.NaN }
        }
    }

    private fun toRadians(value: Double, degrees: Boolean): Double =
        if (degrees) Math.toRadians(value) else value

    private fun fromRadians(value: Double, degrees: Boolean): Double =
        if (degrees) Math.toDegrees(value) else value

    /**
     * `n!` in floating point, defined only on the whole numbers that fit.
     *
     * No gamma function: the plotted curve has to agree with the exact keypad answer, and
     * a continuous interpolation would draw values the calculator itself refuses to give.
     */
    private fun factorial(value: Double): Double {
        if (value < 0.0 || value != Math.floor(value) || value > MAX_DOUBLE_FACTORIAL) {
            return Double.NaN
        }
        var product = 1.0
        var i = 2
        val n = value.toInt()
        while (i <= n) {
            product *= i
            i++
        }
        return product
    }
}
