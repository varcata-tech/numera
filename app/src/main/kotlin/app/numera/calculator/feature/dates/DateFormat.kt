package app.numera.calculator.feature.dates

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoField
import java.util.Locale

// How the dates screen prints a date, kept out of the Compose file so the one case that
// goes wrong can be pinned in a JVM test against a fixed Locale.

/**
 * [date] in [locale]'s medium date style, with the era added once the year needs one.
 *
 * The localized patterns write the year as `y`, the year *of era*, and carry no `G`. That is
 * right for every date a person will ever pick, and silently wrong for the ones the
 * Add/subtract tab can compute: [DateMath.MAX_OFFSET] lets a hundred thousand years be taken
 * off a picker date, and proleptic year 0 is 1 BC, year −1 is 2 BC. Printed as year-of-era
 * alone, subtracting 2024 years from 2024 read "Jun 15, 1", subtracting 2025 read "Jun 15, 2",
 * and the year on screen counted *up* as the offset grew, with two different inputs landing
 * on the same "1". The era is appended only for those years so an ordinary date is left
 * exactly as the locale writes it, rather than every result gaining an "AD".
 *
 * The digits follow [DecimalStyle] rather than defaulting to ASCII, for the reason the
 * screen's KDoc gives: `withLocale` settles the month names and field order but not the
 * digit set, and the Dates screen once printed "٣٠ يوم" one line under "26/08/2026".
 */
internal fun formatDate(date: LocalDate, locale: Locale): String {
    val builder = DateTimeFormatterBuilder().appendLocalized(FormatStyle.MEDIUM, null)
    if (date.year <= 0) {
        builder.appendLiteral(' ').appendText(ChronoField.ERA, TextStyle.SHORT)
    }
    val formatter: DateTimeFormatter = builder
        .toFormatter(locale)
        .withDecimalStyle(DecimalStyle.of(locale))
    return date.format(formatter)
}
