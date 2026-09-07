package app.numera.calculator.math.format

import app.numera.calculator.math.ConstructiveReal
import app.numera.calculator.math.TooMuchMemoryException
import app.numera.calculator.math.UnifiedReal
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/**
 * Renders a computed value as the string the result line shows.
 *
 * The one rule everything here serves: the display must never claim more than it knows.
 * An exact value is printed with no ellipsis and no rounding; anything truncated says so
 * with a `…`. That distinction is the whole point of the exact engine underneath — a
 * calculator that prints `0.333333333` for a third and `0.25` for a quarter with the same
 * confidence has thrown the guarantee away at the last step.
 */
object ResultFormatter {

    /** Shown when digits were dropped. */
    private const val ELLIPSIS = '…'

    /** U+2212. A hyphen is a different, visibly shorter glyph and is not a minus sign. */
    private const val MINUS = '−'

    /**
     * Leading zeros tolerated after the point before switching to scientific notation.
     *
     * `0.0000001` is still readable; past that the zeros stop carrying information and the
     * user has to count them to learn the magnitude, which is what an exponent is for.
     */
    private const val MAX_LEADING_ZEROS = 6

    /** No sensible display is narrower than this; a smaller request is clamped up to it. */
    private const val MIN_BUDGET = 6

    /** How far to search for the first significant digit before calling a value zero. */
    private const val MAX_ZERO_SEARCH_DIGITS = 2048

    /** Refuses an exponent whose power of ten would be a megabyte of BigInteger. */
    private const val MAX_SCIENTIFIC_EXPONENT = 1_000_000

    /**
     * The most recent value-to-real conversion.
     *
     * Result scrolling calls [formatWithDigits] repeatedly on the same value with a growing
     * digit count, and a [ConstructiveReal] caches its own best approximation — but only
     * for as long as the object lives. Rebuilding it per call would restart every series
     * from scratch and turn a smooth scroll into a stutter.
     */
    @Volatile
    private var memo: Memo? = null

    private class Memo(val value: UnifiedReal, val real: ConstructiveReal)

    /**
     * Formats [value] to fit within [maxChars], truncating with a `…` only when it must.
     *
     * Switches to scientific notation when the magnitude no longer fits, or when the value
     * is so small that it would show as a row of zeros.
     */
    fun formatShort(value: UnifiedReal, maxChars: Int, locale: Locale = Locale.getDefault()): String {
        val budget = maxOf(maxChars, MIN_BUDGET)
        val exactDecimal = value.exactDecimalOrNull()
        exactOrNull(exactDecimal, budget, locale)?.let { return it }

        val real = realOf(value)
        if (exactDecimal != null) {
            // A terminating value knows its own magnitude; asking the approximation instead
            // is what made 10^−3000 — an exact rational, five keystrokes away — print as a
            // bare "0", because the digit search gives up after MAX_ZERO_SEARCH_DIGITS
            // places and every one of them really is a zero. A null here means the exact
            // decimal is all zeros, which is the one case where "0" is the whole truth.
            val exactExponent = exponentOf(exactDecimal)
                ?: return localize("0", locale, grouping = true)
            return render(real, exactExponent, budget, locale, significantDigits(exactDecimal))
        }
        // Indistinguishable from zero as far as the search looked, but not proven to be
        // zero — no finite number of digits can prove that. The ellipsis is the difference
        // between "this is zero" and "every digit examined was a zero", and only the second
        // is something the engine actually knows.
        val exponent = decimalExponent(real)
            ?: return localize("0", locale, grouping = true) + ELLIPSIS
        return render(real, exponent, budget, locale, significant = null)
    }

    /** Picks fixed-point or scientific for a value whose decimal exponent is now known. */
    private fun render(
        real: ConstructiveReal,
        exponent: Int,
        budget: Int,
        locale: Locale,
        significant: Int?,
    ): String {
        if (exponent < -MAX_LEADING_ZEROS || exponent >= budget) {
            return scientific(real, exponent, budget, locale, significant)
        }
        return fixed(real, exponent, budget, locale, significant)
    }

    /**
     * [formatShort] as a total function: `null` instead of an exception.
     *
     * Formatting is where a [UnifiedReal]'s deferred work actually happens — the value is a
     * lazy tree and the first digit request is what runs the series — so every failure the
     * engine can raise surfaces here rather than in the evaluator, including the
     * `AbortedException` thrown the moment the user presses another key. A caller that
     * treats formatting as total lets that escape a coroutine as an uncaught exception,
     * which ends the process instead of the job.
     *
     * A `null` means "no honest rendering is available". It never means zero, and it never
     * means the previous answer still stands: the caller must show nothing.
     */
    fun formatShortOrNull(
        value: UnifiedReal,
        maxChars: Int,
        locale: Locale = Locale.getDefault(),
    ): String? = try {
        formatShort(value, maxChars, locale)
    } catch (e: ArithmeticException) {
        // Every CalculationException — aborted, precision overflow, too much memory — is
        // one of these, as is the bare ArithmeticException BigInteger raises on its own.
        null
    }

    /**
     * Formats [value] with exactly [digits] places after the point.
     *
     * This is what result scrolling calls with an ever larger [digits]; it is cheap to call
     * repeatedly because the underlying approximation is refined rather than recomputed.
     *
     * The count is clamped to what the value actually has. The underlying conversion pads to
     * the width it is asked for, so scrolling an exact integer such as `2^100` would otherwise
     * append fifty zeros that are not digits of anything — and then a hundred, and then two
     * hundred, as the scroll doubles its request.
     *
     * Rounded rather than truncated: see [ConstructiveReal.toStringRounded]. A truncating
     * conversion left the final digit to whichever side the approximation happened to fall on,
     * which is how `e` came to print one low while π, √2 and ln 2 printed correctly.
     */
    fun formatWithDigits(value: UnifiedReal, digits: Int, locale: Locale = Locale.getDefault()): String {
        val requested = maxOf(digits, 0)
        val required = value.digitsRequired()
        val places = if (required == null) requested else minOf(requested, required)
        return localize(realOf(value).toStringRounded(places), locale, grouping = true)
    }

    /** [formatWithDigits] as a total function; see [formatShortOrNull] for what `null` means. */
    fun formatWithDigitsOrNull(
        value: UnifiedReal,
        digits: Int,
        locale: Locale = Locale.getDefault(),
    ): String? = try {
        formatWithDigits(value, digits, locale)
    } catch (e: ArithmeticException) {
        null
    }

    /**
     * The value as a plain ASCII decimal: no grouping, no localised digits, no ellipsis.
     *
     * For machine readers rather than for people: anything that has to hand the number to a
     * tokenizer — this one's or another program's — rather than to a screen. Grouping
     * separators are the reason it exists separately from [formatWithDigits]: every other
     * entry point here groups, including under `Locale.ROOT`, and a grouped `1,745.13` is
     * not a number any tokenizer will take back — it is two of them. Exact when the value
     * terminates; otherwise cut to [digits] places, silently, because the caller is not a
     * display and an ellipsis would not survive being read back either.
     *
     * This is what `CalculatorViewModel.clipboardPayload` copies. Rounded rather than
     * truncated for the same reason [formatWithDigits] is: [ConstructiveReal.toStringTruncated]
     * truncates an approximation that is only good to one unit in the last place, so its final
     * digit is decided by luck and can sit one *above* the true expansion — a digit belonging
     * to no rendering of the number. It would also disagree with the correctly rounded result
     * line about half the time, so a value copied out of Numera would not match the value
     * Numera was showing.
     */
    fun formatPlain(value: UnifiedReal, digits: Int): String =
        value.exactDecimalOrNull() ?: realOf(value).toStringRounded(maxOf(digits, 0))

    /**
     * True when [value] fits in [maxChars] with every digit it has — no ellipsis needed.
     *
     * [locale] must be the one the matching [formatShort] call was given. The verdict is
     * genuinely locale-dependent, because the width being measured is the *grouped* width and
     * grouping is not universal — `hi-IN` writes a fifteen-digit integer with six separators
     * where `en` writes four. Answering under one locale for a line rendered in another
     * produces a result that shows an ellipsis while reporting that nothing was dropped, and
     * scrolling for more digits then refuses to move.
     */
    fun isExactlyDisplayable(
        value: UnifiedReal,
        maxChars: Int,
        locale: Locale = Locale.getDefault(),
    ): Boolean =
        exactOrNull(value.exactDecimalOrNull(), maxOf(maxChars, MIN_BUDGET), locale) != null

    /**
     * Formats an inexact [Double], for the converters and the graph readout.
     *
     * No ellipsis is appended: a `Double` never carried exact digits in the first place, so
     * marking it as truncated would imply a precision guarantee that does not exist.
     */
    fun formatDouble(value: Double, maxChars: Int, locale: Locale = Locale.getDefault()): String {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        if (value.isNaN()) return symbols.naN
        if (value.isInfinite()) {
            return if (value < 0) "${minusSign(symbols)}${symbols.infinity}" else symbols.infinity
        }
        val budget = maxOf(maxChars, MIN_BUDGET)
        if (value == 0.0) return localize("0", locale, grouping = true)

        val exponent = floor(log10(abs(value))).toInt()
        if (exponent < -MAX_LEADING_ZEROS || exponent >= budget) {
            return scientificDouble(value, exponent, budget, locale)
        }
        val sign = if (value < 0) 1 else 0
        var places = (budget - maxOf(exponent + 1, 1) - 1 - sign).coerceAtLeast(0)
        while (true) {
            val text = localize(plainDecimal(value, places), locale, grouping = true)
            if (text.length <= budget) return text
            if (places == 0) return scientificDouble(value, exponent, budget, locale)
            places -= maxOf(text.length - budget, 1)
            if (places < 0) places = 0
        }
    }

    // ---------------------------------------------------------------- exact path

    /**
     * The value written out in full, or `null` when that is impossible or does not fit.
     *
     * Driven by [UnifiedReal.exactDecimalOrNull] — passed in as [exact] so a value that
     * costs three thousand digits to write out is only written out once — rather than by
     * whether the string happened to be short: `1/3` truncated to eight places is eight
     * correct digits and still must carry an ellipsis, while `0.25` is complete at four
     * characters and must not.
     */
    private fun exactOrNull(exact: String?, budget: Int, locale: Locale): String? {
        if (exact == null) return null
        if (leadingZeros(exact) > MAX_LEADING_ZEROS) return null
        // Localising cannot shorten a string — digits map one for one, grouping only inserts
        // separators, and the minus sign stays one character — so an ASCII form already wider
        // than the budget proves the localised form is too. Measuring first is what keeps
        // `20000!` from re-parsing its 77,338 digits into a BigInteger and formatting them
        // into a 103,000-character grouped string, on the frame that publishes the answer,
        // only to discard it for being longer than twenty characters.
        if (exact.length > budget) return null
        val text = localize(exact, locale, grouping = true)
        return if (text.length <= budget) text else null
    }

    /**
     * How many digits of [decimal] are significant: first non-zero through last non-zero.
     *
     * Not the same thing as [UnifiedReal.digitsRequired], which counts digits *after the
     * point* and is zero for every integer — a count that says nothing about a mantissa.
     * Scientific notation shows `value / 10^exponent`, so what decides whether it is
     * complete is the significant-digit count: by that measure 10^30 is one digit and fits
     * comfortably, while 2^100 is thirty-one and cannot.
     */
    private fun significantDigits(decimal: String?): Int? {
        if (decimal == null) return null
        val digits = decimal.filter { it in '0'..'9' }
        val first = digits.indexOfFirst { it != '0' }
        if (first < 0) return 0
        return digits.indexOfLast { it != '0' } - first + 1
    }

    private fun leadingZeros(decimal: String): Int {
        val body = decimal.removePrefix("-")
        val point = body.indexOf('.')
        if (point < 0) return 0
        if (body.substring(0, point).trimStart('0').isNotEmpty()) return 0
        val first = body.substring(point + 1).indexOfFirst { it != '0' }
        return if (first < 0) 0 else first
    }

    // ---------------------------------------------------------------- truncated path

    /**
     * Fixed-point rendering with a `…`, fitted to the budget by trial.
     *
     * The number of places is fitted rather than computed because grouping separators and
     * localised digits change the width: an estimate that ignores them overflows the
     * display in exactly the locales nobody tests in.
     *
     * [significant] is carried through untouched for the fallback below. Losing it there —
     * passing `null` as this once did — made every exact value wide enough to reach the
     * fallback claim it had been truncated: `10^19` printed `1.00000000000000E19…`, fifteen
     * manufactured zeros and an ellipsis on a number with one significant digit, while the
     * neighbouring `10^20` printed `1E20`. Same failure as an unmarked truncation, in the
     * other direction, and just as much a lie about what the engine knows.
     */
    private fun fixed(
        real: ConstructiveReal,
        exponent: Int,
        budget: Int,
        locale: Locale,
        significant: Int?,
    ): String {
        var places = budget - maxOf(exponent + 1, 1) - 2
        while (places > 0) {
            val text = localize(real.toStringRounded(places), locale, grouping = true) + ELLIPSIS
            if (text.length <= budget) return text
            places -= maxOf(text.length - budget, 1)
        }
        val whole = localize(real.toStringTruncated(0), locale, grouping = true) + ELLIPSIS
        if (whole.length <= budget) return whole
        return scientific(real, exponent, budget, locale, significant)
    }

    /**
     * Scientific notation, `d.dddE±nn`, with a `…` whenever the mantissa was cut short.
     *
     * [significant] is how many significant digits the value has, or `null` when it does
     * not terminate at all. It is the only way to know whether trailing zeros in the
     * mantissa are the real digits of `1E−9` or merely where the truncation landed — and
     * therefore the only way to know whether the ellipsis belongs. Getting that test wrong
     * in either direction breaks the one guarantee this class exists to keep: an exact
     * `10^30` and a truncated `2^100` must not render identically.
     */
    private fun scientific(
        real: ConstructiveReal,
        estimate: Int,
        budget: Int,
        locale: Locale,
        significant: Int?,
    ): String {
        var exponent = estimate
        var negative = false
        var fallback = ""
        // The exponent estimate comes from a floating-point approximation, so it can be one
        // out at a power of ten; each pass corrects it from the mantissa it actually got.
        repeat(4) {
            val suffix = exponentSuffix(exponent, locale)
            val room = (budget - suffix.length - 2 - (if (negative) 1 else 0)).coerceAtLeast(0)
            // A mantissa of n significant digits needs n−1 places after the point. When it
            // does not fit, one character of the budget goes to the ellipsis instead of to
            // a digit: a dropped digit that says so is worth more than a silent one.
            val exact = significant != null && significant - 1 <= room
            val places = if (exact) room else (room - 1).coerceAtLeast(0)
            val mantissa = mantissaOf(real, exponent, places)
            val shift = magnitudeCorrection(mantissa)
            val mantissaIsNegative = mantissa.startsWith("-")
            val body = localize(if (exact) trimZeros(mantissa) else mantissa, locale, false)
            val text = if (exact) body + suffix else body + suffix + ELLIPSIS
            if (shift == 0 && mantissaIsNegative == negative) return text
            fallback = text
            negative = mantissaIsNegative
            exponent += shift
        }
        return fallback
    }

    private fun mantissaOf(real: ConstructiveReal, exponent: Int, places: Int): String {
        // Past this the power of ten that normalises the mantissa is megabytes of
        // BigInteger. Returning the un-normalised value instead, as this used to, produced
        // a string that was not a mantissa at all: a million characters wide and with the
        // wrong digits in front of the exponent. There is no honest short rendering of such
        // a number, so report it as what it is.
        if (abs(exponent) > MAX_SCIENTIFIC_EXPONENT) throw TooMuchMemoryException()
        val scale = ConstructiveReal.valueOf(BigInteger.TEN.pow(abs(exponent)))
        val mantissa = if (exponent >= 0) real / scale else real * scale
        return mantissa.toStringRounded(places)
    }

    /** −1 when the mantissa slipped below 1, +1 when it reached 10, 0 when it is in range. */
    private fun magnitudeCorrection(mantissa: String): Int {
        val body = mantissa.removePrefix("-")
        val point = body.indexOf('.')
        val whole = (if (point < 0) body else body.substring(0, point)).trimStart('0')
        return when {
            whole.isEmpty() -> -1
            whole.length > 1 -> 1
            else -> 0
        }
    }

    private fun trimZeros(decimal: String): String {
        if (!decimal.contains('.')) return decimal
        return decimal.trimEnd('0').trimEnd('.')
    }

    private fun exponentSuffix(exponent: Int, locale: Locale): String {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val sign = if (exponent < 0) minusSign(symbols).toString() else ""
        return "E$sign${mapDigits(abs(exponent).toString(), symbols)}"
    }

    // ---------------------------------------------------------------- magnitude

    /**
     * The power of ten of the leading digit, or `null` when the value cannot be told from
     * zero.
     *
     * Deliberately never asks the engine for a sign or a most-significant bit. Both are
     * only semi-decidable — on a value that really is zero they refine until the precision
     * budget runs out and then throw — whereas asking for a fixed number of digits always
     * terminates.
     *
     * That keeps *this* routine from failing, but it does not make the class total: every
     * digit request runs engine code that can still abort or run out of room. Callers that
     * cannot tolerate an exception use [formatShortOrNull] and [formatWithDigitsOrNull].
     */
    private fun decimalExponent(real: ConstructiveReal): Int? {
        val approx = real.toDouble()
        if (approx.isFinite() && approx != 0.0) return floor(log10(abs(approx))).toInt()
        if (approx.isInfinite()) {
            val digits = real.toStringTruncated(0).removePrefix("-").trimStart('0')
            return if (digits.isEmpty()) null else digits.length - 1
        }
        var places = 32
        while (places <= MAX_ZERO_SEARCH_DIGITS) {
            exponentOf(real.toStringTruncated(places))?.let { return it }
            places *= 4
        }
        return null
    }

    private fun exponentOf(decimal: String): Int? {
        val body = decimal.removePrefix("-")
        val point = body.indexOf('.')
        val whole = (if (point < 0) body else body.substring(0, point)).trimStart('0')
        if (whole.isNotEmpty()) return whole.length - 1
        val fraction = if (point < 0) "" else body.substring(point + 1)
        val first = fraction.indexOfFirst { it != '0' }
        return if (first < 0) null else -(first + 1)
    }

    // ---------------------------------------------------------------- doubles

    private fun plainDecimal(value: Double, places: Int): String {
        var decimal = BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP)
        decimal = decimal.stripTrailingZeros()
        if (decimal.scale() < 0) decimal = decimal.setScale(0)
        return decimal.toPlainString()
    }

    private fun scientificDouble(value: Double, estimate: Int, budget: Int, locale: Locale): String {
        var exponent = estimate
        var result = ""
        repeat(3) {
            val suffix = exponentSuffix(exponent, locale)
            val sign = if (value < 0) 1 else 0
            val places = (budget - suffix.length - 2 - sign).coerceAtLeast(0)
            val mantissa = BigDecimal.valueOf(value)
                .movePointLeft(exponent)
                .setScale(places, RoundingMode.HALF_UP)
            val text = trimZeros(mantissa.toPlainString())
            val shift = magnitudeCorrection(text)
            result = localize(text, locale, grouping = false) + suffix
            if (shift == 0) return result
            exponent += shift
        }
        return result
    }

    // ---------------------------------------------------------------- localisation

    private fun realOf(value: UnifiedReal): ConstructiveReal {
        memo?.let { if (it.value === value) return it.real }
        val real = value.toConstructiveReal()
        memo = Memo(value, real)
        return real
    }

    /**
     * Rewrites an ASCII decimal into the locale's own digits, separators and minus sign.
     *
     * The engine speaks ASCII and the user does not. Every digit goes through
     * [DecimalFormatSymbols], so an Arabic locale gets Arabic-Indic digits rather than a
     * half-translated display of Western numerals with Arabic labels around them.
     */
    private fun localize(decimal: String, locale: Locale, grouping: Boolean): String {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val negative = decimal.startsWith("-")
        val body = if (negative) decimal.substring(1) else decimal
        val point = body.indexOf('.')
        val whole = (if (point < 0) body else body.substring(0, point)).ifEmpty { "0" }
        val fraction = if (point < 0) "" else body.substring(point + 1)

        return buildString {
            if (negative) append(minusSign(symbols))
            append(if (grouping) groupInteger(whole, integerFormat(locale)) else mapDigits(whole, symbols))
            if (fraction.isNotEmpty()) {
                append(symbols.decimalSeparator)
                append(mapDigits(fraction, symbols))
            }
        }
    }

    /**
     * Groups the digits of a whole number the way [locale] does.
     *
     * The grouping is delegated to the platform's own formatter rather than done here with
     * a group size of three, because three is not universal: `hi-IN` groups as `12,34,567`.
     * Android backs `java.text.DecimalFormat` with ICU, so this picks up the primary *and*
     * secondary group sizes on device; a plain JVM has only the primary one.
     */
    internal fun groupInteger(digits: String, format: NumberFormat): String =
        format.format(BigInteger(digits.ifEmpty { "0" }))

    /**
     * The locale's integer formatter.
     *
     * The integer-digit ceiling is lifted explicitly: `DecimalFormat` silently drops
     * high-order digits past its maximum, which would turn `100!` into a plausible-looking
     * but wrong 309-digit number rather than into an error anyone would notice.
     */
    private fun integerFormat(locale: Locale): NumberFormat =
        NumberFormat.getIntegerInstance(locale).apply {
            isGroupingUsed = true
            maximumIntegerDigits = Int.MAX_VALUE
            maximumFractionDigits = 0
        }

    private fun mapDigits(digits: String, symbols: DecimalFormatSymbols): String {
        val zero = symbols.zeroDigit
        if (zero == '0') return digits
        return buildString(digits.length) {
            for (c in digits) append(if (c in '0'..'9') zero + (c - '0') else c)
        }
    }

    /** The locale's minus sign, upgraded from a hyphen to a real one where it is one. */
    private fun minusSign(symbols: DecimalFormatSymbols): Char =
        if (symbols.minusSign == '-') MINUS else symbols.minusSign
}
