package app.numera.calculator.feature.calc

import app.numera.calculator.math.expr.CalculatorExpr
import app.numera.calculator.math.expr.KeyId

/**
 * What one key press does to the expression, given what the display is showing.
 *
 * Lifted out of the view model because this is the part of a key press that can be wrong,
 * and the view model itself cannot be tested on the JVM: it needs a `SavedStateHandle`,
 * `SharedPreferences` and a database, none of which exist without a device, and this project
 * ships no `androidTest` source set. Pure and Android-free, it is testable the way
 * `DrawerState` and `BitwiseEngine` are — which is the point of extracting it.
 */
internal object KeyPress {

    /**
     * The expression a press of [key] should leave behind, or `null` when the press must
     * change nothing at all.
     *
     * The order matters. What a key applies to depends on the mode — a digit after a result
     * starts a new calculation, an operator continues from the answer — so the base is
     * chosen first and only then asked whether it accepts the key. Appending to the base
     * *after* assigning it is what wiped the display: pressing `)` with a result showing
     * reset the expression to empty, appended nothing, and left a blank formula, a blank
     * result and no way back to the answer but the history drawer.
     *
     * [seed] is a lambda because building it from an exact result is real work — a factorial
     * runs to tens of thousands of digits — and no key that starts fresh should pay for it.
     */
    fun apply(
        mode: DisplayMode,
        expr: CalculatorExpr,
        key: KeyId,
        seed: () -> CalculatorExpr,
    ): CalculatorExpr? {
        val base = when (mode) {
            // An error leaves the expression that caused it on screen, and the next key is
            // almost always the correction to it. Clearing here would throw away `1÷0` at
            // the moment the user reaches for the `0` to fix, which is what backspace out of
            // an error already refuses to do.
            DisplayMode.INPUT, DisplayMode.ERROR -> expr
            DisplayMode.RESULT -> if (key.continuesFromResult()) seed() else CalculatorExpr()
        }
        if (!base.accepts(key)) return null
        return base.append(key)
    }
}

/**
 * Whether this key carries on from the answer on screen rather than starting a new sum.
 *
 * The operators and the postfix keys do: `=` then `×` means "times the answer". A digit does
 * not — it is the first digit of the next calculation, which is what every calculator does
 * and what stops `5 = 3` from reading as `53`.
 */
internal fun KeyId.continuesFromResult(): Boolean = when (this) {
    KeyId.ADD, KeyId.SUBTRACT, KeyId.MULTIPLY, KeyId.DIVIDE,
    KeyId.POWER, KeyId.FACTORIAL, KeyId.PERCENT, KeyId.SQUARE,
    -> true
    else -> false
}
