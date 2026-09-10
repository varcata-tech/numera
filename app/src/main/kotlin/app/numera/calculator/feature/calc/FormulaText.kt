package app.numera.calculator.feature.calc

/**
 * Respells an expression for display in the user's locale: ASCII digits become the locale's
 * digits and the `.` decimal point becomes its decimal separator. Nothing else is touched.
 *
 * `CalculatorExpr.display()` is deliberately locale-free — it is the text `fromText()` parses
 * back and the text history stores — so it always says `1.5×2`. Shown as-is, that put a
 * Latin `7÷3` under a keypad whose keys read `٧` and above an answer written `٢٫٣٣…`, and in
 * German it put `1.5×2` above an answer of `3` when the user had typed `1,5`: in a
 * comma-decimal locale `1.5` reads as one thousand five hundred. The answer line was already
 * localised by the formatter; this is the same courtesy for the line above it.
 *
 * Display only. The returned text must never be parsed or stored: [zero] may be a digit the
 * tokeniser does not read, and the separator may be the grouping character of another locale.
 *
 * @param text the locale-free display form of an expression.
 * @param zero the locale's zero digit, from `DecimalFormatSymbols.zeroDigit`.
 * @param decimalSeparator the locale's decimal separator, from the same symbols.
 */
fun localiseFormula(text: String, zero: Char, decimalSeparator: Char): String {
    if (zero == '0' && decimalSeparator == '.') return text
    return buildString(text.length) {
        for (c in text) {
            append(
                when {
                    c in '0'..'9' -> zero + (c - '0')
                    c == '.' -> decimalSeparator
                    else -> c
                },
            )
        }
    }
}
