package app.numera.calculator.feature.calc

import app.numera.calculator.math.expr.CalculatorExpr
import app.numera.calculator.math.expr.KeyId
import app.numera.calculator.math.expr.Token
import java.text.DecimalFormatSymbols

/**
 * The most tokens the app will accept in one expression.
 *
 * Matched to `ExprCodec`'s decode ceiling. Its encoder has no limit, so without this a huge
 * pasted expression is accepted, displayed and persisted — and then decodes to `null` after
 * process death, leaving an empty formula line and no hint that anything was lost.
 */
internal const val MAX_EXPRESSION_TOKENS: Int = 10_000

/**
 * The most characters an expression's number literals may add up to.
 *
 * [MAX_EXPRESSION_TOKENS] bounds how many tokens there are, not how large they are, and the
 * two differ by three orders of magnitude: ten thousand tokens can be five thousand number
 * literals of the tokenizer's full 64 characters each. That is a third of a megabyte, which
 * `ExprCodec` re-encodes into a ~440 KB Base64 string on *every* subsequent key press — the
 * saved-state bundle is rewritten whole each time — and which the framework then carries
 * across a Binder transaction at `onSaveInstanceState`. Binder has a hard size limit and
 * exceeding it kills the process rather than dropping the state.
 */
internal const val MAX_EXPRESSION_LITERAL_CHARS: Int = 20_000

/**
 * True when [expr] is small enough to display, persist and restore.
 *
 * Both bounds, always together: an expression that passes one and fails the other is exactly
 * the one that looks fine on screen and then loses the user's work at the next save.
 */
internal fun fitsExpressionLimits(expr: CalculatorExpr): Boolean {
    if (expr.tokens.size > MAX_EXPRESSION_TOKENS) return false
    var literalChars = 0
    for (token in expr.tokens) {
        if (token is Token.Number) {
            literalChars += token.text.length
            if (literalChars > MAX_EXPRESSION_LITERAL_CHARS) return false
        }
    }
    return true
}

/**
 * Separators that appear *inside* a number in some locale or other.
 *
 * The tokeniser treats every one of these as ignorable whitespace, which splits `1 234 567`
 * into three number tokens — and adjacent number tokens are implicit multiplication, so the
 * paste evaluates to zero rather than to a million. Absorbing them here, only where they sit
 * between two digits, is what makes a grouped number pasted from another app mean what it
 * says. `'` and `’` are Swiss grouping, `_` is a programmer's digit separator, and the three
 * space variants are what `fr`, `ru` and friends group with.
 */
private const val GROUP_SEPARATORS: String = " \u00A0\u202F\u2009_'\u2019"

/**
 * Turns clipboard text into an expression, or `null` when it is not one.
 *
 * Three normalisations run first, and every one of them exists because the tokeniser is
 * written for text the *keypad* could have produced while the clipboard carries text other
 * programs produced. See [normalizeDigitSeparators], [normalizeExponentMarker] and
 * [normalizeMultiplicationCross].
 *
 * @param symbols the display locale's number symbols, from `LocalConfiguration` rather than
 *   `Locale.getDefault()` — the user's own decimal comma is the thing being interpreted here.
 */
internal fun parsePastedText(text: String, symbols: DecimalFormatSymbols): CalculatorExpr? {
    val normalised: String = normalizeMultiplicationCross(
        normalizeExponentMarker(normalizeDigitSeparators(text, symbols)),
    )
    val expr = CalculatorExpr.fromText(normalised) ?: return null
    if (!fitsExpressionLimits(expr)) return null
    // The tokeniser reads `x` as the graphing variable, and this calculator has no variable:
    // an expression holding one parses, displays, previews as a blank line and then answers
    // "Bad expression" at every press of equals, with no key on the pad that can remove the
    // offending token. Refusing the paste outright is the honest outcome — anything that
    // *is* multiplication has already been rewritten by normalizeMultiplicationCross.
    if (expr.tokens.any { it is Token.Key && it.key == KeyId.VARIABLE_X }) return null
    return expr
}

/**
 * Rewrites a number as the parser spells it: ASCII digits, `.` for the point, no grouping.
 *
 * Only characters *between two digits* are touched. A separator anywhere else is ordinary
 * text and rewriting it would change the meaning of the paste rather than preserve it.
 */
internal fun normalizeDigitSeparators(text: String, symbols: DecimalFormatSymbols): String {
    val ascii: String = toAsciiDigits(text, symbols.zeroDigit)
    val group: Char = symbols.groupingSeparator
    val decimal: Char = symbols.decimalSeparator

    return buildString(ascii.length) {
        for (index in ascii.indices) {
            val c: Char = ascii[index]
            val betweenDigits = index > 0 && index + 1 < ascii.length &&
                isAsciiDigit(ascii[index - 1]) && isAsciiDigit(ascii[index + 1])
            when {
                !betweenDigits -> append(c)
                // The decimal separator is tested first: in `de` the point groups and the
                // comma separates, so testing grouping first would delete the point of
                // `1.234,5` and then keep the comma, which is exactly backwards.
                c == decimal && decimal != '.' -> append('.')
                c == group || c in GROUP_SEPARATORS -> Unit
                else -> append(c)
            }
        }
    }
}

/**
 * Rewrites a lower-case exponent marker as the upper-case one the tokeniser reads.
 *
 * The tokeniser accepts only `E` for an exponent, deliberately: on the keypad a lower-case
 * `e` is Euler's number, so `2e` has to keep meaning `2 × e`. Nothing on the pad can produce
 * the character `e` at all, though — Euler's number arrives as a key token — so the rule only
 * ever applies to pasted text, which is exactly where `1e5` comes from. Left alone, `1e5`
 * parses as `1 × e × 5` and answers 13.59 with nothing on screen to say it was misread.
 *
 * Only an `e` with a digit before it and a signed or unsigned digit after it is touched, so
 * `2e` and `3e+π` still mean what the keypad means by them.
 */
internal fun normalizeExponentMarker(text: String): String {
    if (text.indexOf('e') < 0) return text
    return buildString(text.length) {
        for (index in text.indices) {
            val c: Char = text[index]
            val isExponent = c == 'e' &&
                index > 0 && isAsciiDigit(text[index - 1]) &&
                startsExponentDigits(text, index + 1)
            append(if (isExponent) 'E' else c)
        }
    }
}

/** True when an exponent's digits — optionally signed — begin at [start]. */
private fun startsExponentDigits(text: String, start: Int): Boolean {
    var index: Int = start
    if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
    return index < text.length && isAsciiDigit(text[index])
}

/**
 * Rewrites an `x` that is standing in for a multiplication sign.
 *
 * `1920 x 1080` and `2 x 3` are how a multiplication is written in a note or a chat message,
 * and the tokeniser maps both `x` and `X` to the graphing variable. Only an `x` with a value
 * on each side is rewritten; a bare `x`, or one inside a word, is left alone and
 * [parsePastedText] then refuses the paste rather than accepting an expression this
 * calculator can never evaluate.
 */
internal fun normalizeMultiplicationCross(text: String): String {
    if (text.indexOf('x') < 0 && text.indexOf('X') < 0) return text
    return buildString(text.length) {
        for (index in text.indices) {
            val c: Char = text[index]
            val isCross = (c == 'x' || c == 'X') &&
                endsValueBefore(text, index) && startsValueAfter(text, index)
            append(if (isCross) '×' else c)
        }
    }
}

/** True when the text before [index], ignoring spaces, is the end of a value. */
private fun endsValueBefore(text: String, index: Int): Boolean {
    var probe: Int = index - 1
    while (probe >= 0 && text[probe] == ' ') probe--
    if (probe < 0) return false
    val c: Char = text[probe]
    return isAsciiDigit(c) || c == ')' || c == '.'
}

/** True when the text after [index], ignoring spaces, is the start of a value. */
private fun startsValueAfter(text: String, index: Int): Boolean {
    var probe: Int = index + 1
    while (probe < text.length && text[probe] == ' ') probe++
    if (probe >= text.length) return false
    val c: Char = text[probe]
    return isAsciiDigit(c) || c == '(' || c == '.'
}

/**
 * Maps a locale's own digits onto ASCII.
 *
 * The tokeniser only accepts `0`–`9`, so an `ar` or `hi` user pasting `٧٥` — including text
 * this app itself put on the clipboard — would otherwise get nothing at all from the paste.
 */
private fun toAsciiDigits(text: String, zero: Char): String {
    if (zero == '0') return text
    val last: Char = zero + 9
    return buildString(text.length) {
        for (c in text) append(if (c in zero..last) '0' + (c - zero) else c)
    }
}

private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'
