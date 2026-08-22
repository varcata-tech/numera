package app.numera.calculator.feature.financial

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

// Reading and writing the numbers on the financial screen, kept out of the Compose file so
// each function can be tested against a fixed Locale. Every bug guarded against here is
// invisible in en-US — a decimal comma, a non-ASCII digit set, a paste nobody would type by
// hand — so only a test that pins the locale, or an input longer than a person's patience,
// can see them.

/**
 * Longest amount a field accepts, in characters.
 *
 * Typing cannot reach this, but pasting can. An unbounded field accepts a several-thousand
 * digit principal, [formatAmount] expands it to a string of the same length, and the loan tab
 * then formats four of those per schedule row for up to
 * [FinanceMath.MAX_LOAN_MONTHS] rows synchronously inside composition — the frame never
 * lands. Eighteen characters still spells a quadrillion to the subunit.
 */
internal const val MAX_AMOUNT_LENGTH: Int = 18

/**
 * Keeps only what a numeric field can mean.
 *
 * Three rules. Non-numeric characters are dropped. A second decimal point is refused, because
 * "1.2.3" would otherwise pass the filter, fail to parse and blank the result card. And the
 * whole thing is capped at [MAX_AMOUNT_LENGTH]; see there for why a paste is the case that
 * matters.
 *
 * @param decimal false for a field that counts things, where a '.' has no meaning at all.
 */
internal fun sanitiseAmount(text: String, decimal: Boolean): String {
    val kept = StringBuilder(minOf(text.length, MAX_AMOUNT_LENGTH))
    var pointSeen = false
    for (character in text) {
        if (kept.length >= MAX_AMOUNT_LENGTH) break
        if (character.isDigit()) {
            kept.append(character)
        } else if (decimal && character == '.' && !pointSeen) {
            pointSeen = true
            kept.append(character)
        }
    }
    return kept.toString()
}

/** Formats an amount using [locale]'s number conventions; no exchange rate is involved. */
internal fun formatAmount(value: BigDecimal, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(value)

/**
 * Formats [value] — a percentage already expressed out of 100, so 28.00 means 28% — the way
 * [locale] writes one.
 *
 * Built through [NumberFormat] rather than as `"$value%"`. `BigDecimal.toString` always emits
 * ASCII digits and an ASCII point, so a hand-built percentage read "28.00%" beside a "28,00"
 * from [formatAmount] in fr and ru, and beside Arabic-Indic digits in ar — two conventions in
 * one result card. The percent instance also places the sign where the locale places it —
 * hard against the digits in en, behind a space in fr and ru.
 */
internal fun formatPercent(value: BigDecimal, locale: Locale): String =
    NumberFormat.getPercentInstance(locale).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(value.movePointLeft(2))
