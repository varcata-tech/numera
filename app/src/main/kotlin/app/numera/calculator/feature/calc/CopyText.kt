package app.numera.calculator.feature.calc

import java.math.BigInteger

/**
 * The longest text a copy may put on the clipboard.
 *
 * The clipboard is a Binder call, and Binder carries the text as UTF-16 through a buffer of
 * about a megabyte that every transaction in the process shares. The engine permits exact
 * integers of a million digits and opaque products of six hundred thousand, so an unbounded
 * copy of one of those is not a slow paste but a `TransactionTooLargeException` that ends the
 * process from a menu tap. A hundred thousand characters is two hundred kilobytes on the wire,
 * comfortably inside the budget, and still every digit of anything a person could read.
 */
internal const val MAX_COPY_CHARS: Int = 100_000

/**
 * What the clipboard carries for a value the formatter has written out in plain ASCII.
 *
 * Two rewrites, each for a reader the plain form gets wrong.
 *
 * The decimal point becomes the display locale's own, because that locale is the one every
 * other program on the phone reads numbers in. With an ASCII point in every locale, `1÷3`
 * copied in German and pasted into a spreadsheet read as the integer 333 with a thousands
 * separator — and pasted back into this app by way of any other, it read the same way, since
 * the paste handler rightly treats a point between digits as grouping where the locale does.
 * Only the point is localised: the digits stay ASCII so that the text remains something a
 * tokeniser will take, and grouping is never added for the same reason.
 *
 * A value too long for the clipboard is copied in scientific notation instead of not at all;
 * see [MAX_COPY_CHARS] and [plainScientific].
 *
 * @param plain the value as `ResultFormatter.formatPlain` writes it: ASCII digits, `.`, `-`.
 * @param decimalSeparator the display locale's decimal separator, from the same
 *   `LocalConfiguration`-derived symbols the paste path reads with.
 * @param significantDigits how many digits of mantissa a scientific fallback keeps.
 */
internal fun clipboardText(plain: String, decimalSeparator: Char, significantDigits: Int): String {
    val bounded: String =
        if (plain.length > MAX_COPY_CHARS) plainScientific(plain, significantDigits) else plain
    return if (decimalSeparator == '.') bounded else bounded.replace('.', decimalSeparator)
}

/**
 * Rewrites a plain decimal as `d.dddE±n` with [significantDigits] digits of mantissa.
 *
 * Built from the digit string rather than by parsing it back into a number: the strings this
 * is for are hundreds of thousands of digits long, and `BigInteger(String)` on one of those
 * costs seconds against a copy deadline of ten. Only the sixty-odd digits that are kept are
 * ever turned into a number, and only to round them.
 *
 * Rounded half away from zero, as the result line rounds its own mantissa, so the copied
 * value agrees with the value on screen. A carry that overflows the mantissa — all nines
 * rounding up — moves the exponent rather than growing the mantissa to a leading `10`.
 */
internal fun plainScientific(plain: String, significantDigits: Int): String {
    val negative: Boolean = plain.startsWith("-")
    val body: String = if (negative) plain.substring(1) else plain
    val point: Int = body.indexOf('.')
    val whole: String = if (point < 0) body else body.substring(0, point)
    val fraction: String = if (point < 0) "" else body.substring(point + 1)
    val digits: String = whole + fraction
    val first: Int = digits.indexOfFirst { it != '0' }
    if (first < 0) return "0"

    val keep: Int = maxOf(significantDigits, 1)
    var exponent: Int = whole.length - 1 - first
    var mantissa: String = digits.substring(first, minOf(first + keep, digits.length))
    val next: Char = if (first + keep < digits.length) digits[first + keep] else '0'
    if (next >= '5') {
        val rounded: String = (BigInteger(mantissa) + BigInteger.ONE).toString()
        if (rounded.length > mantissa.length) {
            // 999 became 1000: one more digit than was kept, all but the first of them zero.
            exponent += 1
            mantissa = rounded.substring(0, mantissa.length)
        } else {
            mantissa = rounded
        }
    }
    mantissa = mantissa.trimEnd('0')

    return buildString(mantissa.length + 12) {
        if (negative) append('-')
        append(mantissa[0])
        if (mantissa.length > 1) {
            append('.')
            append(mantissa, 1, mantissa.length)
        }
        append('E')
        append(exponent)
    }
}
