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
 * Keeps only what a numeric field can mean, in the locale the field is drawn in.
 *
 * Four rules. Digits are kept, whatever numbering system they belong to. [decimalSeparator]
 * is kept once; a second one is refused, because "1.2.3" would otherwise pass the filter,
 * fail to parse and blank the result card. Everything else is dropped. And the whole thing is
 * capped at [MAX_AMOUNT_LENGTH]; see there for why a paste is the case that matters.
 *
 * The separator is a parameter rather than a hardcoded '.' because the field is put on
 * [androidx.compose.ui.text.input.KeyboardType.Decimal], which offers a comma in de, es, fr,
 * it, pt-BR and ru — and [formatAmount] prints one back in those locales too. Accepting only
 * '.' meant a German user typing "8,5" got 8.5% silently rewritten to 85%, with no error and
 * no way to retype a figure this screen had just displayed.
 *
 * Dropping every other character is what makes a paste safe: a German "1.234,56" loses its
 * '.' grouping and keeps its ',' decimal, giving 1234,56 rather than a mangled 1,23456.
 *
 * @param decimal false for a field that counts things, where a separator has no meaning.
 */
internal fun sanitiseAmount(text: String, decimal: Boolean, decimalSeparator: Char): String {
    val kept = StringBuilder(minOf(text.length, MAX_AMOUNT_LENGTH))
    var separatorSeen = false
    for (character in text) {
        if (kept.length >= MAX_AMOUNT_LENGTH) break
        if (character.isDigit()) {
            kept.append(character)
        } else if (decimal && character == decimalSeparator && !separatorSeen) {
            separatorSeen = true
            kept.append(character)
        }
    }
    return kept.toString()
}

/**
 * Reads back what [sanitiseAmount] kept, or null when the field does not yet spell a number.
 *
 * [BigDecimal] parses one spelling only: ASCII digits around an ASCII point. The field holds
 * the user's own, so "8,5" and an Arabic-Indic "٨٫٥" both have to be translated before they
 * reach it. Null rather than an exception because a field mid-edit — empty, or holding just a
 * separator — is an ordinary state, not an error to report.
 */
internal fun parseAmount(text: String, decimalSeparator: Char): BigDecimal? {
    if (text.isBlank()) return null
    var pointSeen = false
    val normalised = buildString(text.length) {
        for (character in text) {
            when {
                character == decimalSeparator || character == '.' -> {
                    // An ASCII point is accepted alongside the locale's own separator
                    // because a stored value can never contain a *grouping* separator:
                    // sanitiseAmount keeps digits and the decimal separator and drops
                    // everything else, so a '.' here is always a decimal point that
                    // arrived from a seed or from a value saved under another locale.
                    // Rejecting it made the whole field read as "not a number".
                    if (pointSeen) return null
                    pointSeen = true
                    append('.')
                }
                character.isDigit() -> append(Character.digit(character, 10))
                else -> return null
            }
        }
    }
    return try {
        BigDecimal(normalised)
    } catch (e: NumberFormatException) {
        // Reached by a field holding only a separator, which is a state the user passes
        // through on the way to a number rather than a mistake worth a message.
        null
    }
}

/**
 * Rewrites a hard-coded default so the field can read it back.
 *
 * A seed is written in source with an ASCII point, but it is handed straight to the text
 * field as a *display* string and read back by [parseAmount] against the locale's own
 * separator. Left alone, "8.5" opened the loan tab reading "enter a valid number in each
 * field" in de, es, fr, it, pt-BR, ru and ar — every locale whose decimal mark is not a
 * point — before the user had touched anything. It also made the field un-repairable:
 * [sanitiseAmount] drops the foreign '.', so appending a digit turned 8.5 into 855.
 */
internal fun seedAmount(literal: String, decimalSeparator: Char): String =
    if (decimalSeparator == '.') literal else literal.replace('.', decimalSeparator)

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
