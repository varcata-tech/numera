package app.numera.calculator.feature.converter

import app.numera.calculator.math.UnifiedReal
import app.numera.calculator.math.expr.CalculatorExpr
import app.numera.calculator.math.expr.KeyId
import app.numera.calculator.math.expr.needsLeftOperand

/**
 * What the converter's active field holds: a typed expression, or an adopted exact value.
 *
 * The second shape is the reason this type exists. Swapping the two units — or moving the
 * cursor to the other field — moves the *computed* side into the input, and the computed
 * side exists on screen only as a formatted display string: localised digits, the locale's
 * decimal separator, grouping separators, an `E` exponent, a truncation `…`, and a U+2212
 * minus rather than a hyphen. Reading a number back out of that string is lossy in every
 * locale the app ships — it turned −40 °F into 40 °F in English, `2,54` into 254 in French
 * and `2.000` into 2 in German — so the exact [UnifiedReal] is carried instead. That is what
 * keeps the inch → cm → inch guarantee alive across a swap.
 *
 * @property expr the tokens typed so far; always empty when [exact] is set.
 * @property exact a value taken over from the other field that no keypad literal can spell.
 */
data class ConverterInput(
    val expr: CalculatorExpr = CalculatorExpr(),
    val exact: UnifiedReal? = null,
) {

    /** True when there is nothing to convert. */
    fun isEmpty(): Boolean = exact == null && expr.isEmpty()

    /**
     * The text the active field should show, or `null` to keep the text it already shows.
     *
     * An adopted [exact] value is already on screen in its formatted form. Re-rendering it
     * here would run a constructive-real approximation on the frame thread, which for an
     * angle or a parsec is exactly the work [kotlinx.coroutines.runInterruptible] exists to
     * keep off it.
     */
    fun displayOrNull(): String? = if (exact != null) null else expr.display()

    /**
     * Appends one key, or stays as it is when the expression refuses that key.
     *
     * An adopted value is dropped first. It is a number, not a token sequence, so appending
     * a digit to it would extend a *rendered* approximation as though the user had typed
     * those digits — silently promoting a truncated display to exact input.
     *
     * Which is why an operator does not drop it at all. `×`, `−` and `%` ask to *use* the
     * value in the field; there are no tokens for them to join to, so the old code answered
     * a request to multiply the converted value by throwing it away and leaving a bare `×`
     * — or, once a leading `−` began writing its own zero, by replacing 2.54 cm with `0−`.
     * A key that starts a fresh value still replaces the adopted one, exactly as before.
     */
    fun append(key: KeyId): ConverterInput {
        if (exact != null && key.needsLeftOperand) return this
        val typed = typedSoFar()
        if (!typed.accepts(key)) return this
        return ConverterInput(typed.append(key))
    }

    /** Removes one token, or the whole adopted value. */
    fun delete(): ConverterInput = ConverterInput(typedSoFar().deleteLastToken())

    /** Discards everything. */
    fun cleared(): ConverterInput = ConverterInput()

    /**
     * Takes over [value] when there is one, and otherwise stays exactly as it is.
     *
     * The passive field is empty whenever its conversion is undefined — 0 L/100 km is a
     * division by zero — or has not landed yet, and there is then no computed value to move
     * across. [adopt] turns that `null` into an *empty* input, which the view model reads as
     * "nothing to convert" and answers by blanking both fields: pressing swap in that window
     * deleted the number the user had just typed. Keeping the current input loses nothing,
     * because the only thing on screen is the input itself.
     */
    fun adopting(value: UnifiedReal?): ConverterInput =
        if (value == null) this else adopt(value)

    private fun typedSoFar(): CalculatorExpr = if (exact == null) expr else CalculatorExpr()

    companion object {

        /**
         * Longest adopted literal that is re-entered as typed tokens.
         *
         * Comfortably under [CalculatorExpr]'s own limit, which refuses a longer number
         * outright; past this the value is carried as itself rather than as digits.
         */
        private const val MAX_LITERAL_LENGTH: Int = 40

        /**
         * Takes over [value] as the new input, without rounding it.
         *
         * A value whose decimal expansion terminates and is short enough to be one literal
         * becomes a typed expression, so the user can carry on editing it. Everything else
         * — anything carrying π, and any expansion longer than [MAX_LITERAL_LENGTH] — is
         * held as the value itself, because the only way to make it typeable is to round
         * it, and rounding here is the precision loss the exact engine exists to prevent.
         */
        fun adopt(value: UnifiedReal?): ConverterInput {
            if (value == null) return ConverterInput()
            val decimal: String? = value.exactDecimalOrNull()
            if (decimal != null && decimal.length <= MAX_LITERAL_LENGTH) {
                val literal: CalculatorExpr? = CalculatorExpr.fromText(decimal)
                if (literal != null) return ConverterInput(literal)
            }
            return ConverterInput(exact = value)
        }
    }
}
