package app.numera.calculator.feature.calc

import java.text.DecimalFormatSymbols
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The formula line must agree with the keypad below it and the answer under it.
 *
 * Each case is a locale that was actually wrong on the emulator: Arabic showed Latin digits
 * between Arabic-Indic keys and an Arabic-Indic answer, and German showed `1.5` — one
 * thousand five hundred to a German reader — for the `1,5` the user had typed. The Latin
 * case pins that the common path is untouched, and that operators and function names survive
 * every locale, since a `٢` inside `sin` would be as wrong as a `7` outside it.
 */
class FormulaTextTest {

    private fun symbols(tag: String) = DecimalFormatSymbols.getInstance(Locale.forLanguageTag(tag))

    @Test
    fun `an English locale returns the same string`() {
        val s = symbols("en-US")
        val text = "1.5×sin(30)+2^10"
        assertSame(text, localiseFormula(text, s.zeroDigit, s.decimalSeparator))
    }

    @Test
    fun `German respells the decimal point as a comma and leaves the digits`() {
        val s = symbols("de-DE")
        assertEquals("1,5×2", localiseFormula("1.5×2", s.zeroDigit, s.decimalSeparator))
    }

    @Test
    fun `Arabic uses Arabic-Indic digits and the Arabic decimal separator`() {
        val s = symbols("ar-EG")
        assertEquals("٧÷٣٫٥", localiseFormula("7÷3.5", s.zeroDigit, s.decimalSeparator))
    }

    @Test
    fun `function names, constants and operators are never respelt`() {
        val s = symbols("ar-EG")
        assertEquals("sin(٣٠)×π+e^٢!", localiseFormula("sin(30)×π+e^2!", s.zeroDigit, s.decimalSeparator))
    }

    @Test
    fun `Hindi keeps Latin digits because that is what its symbols say`() {
        val s = symbols("hi-IN")
        assertEquals("1÷7", localiseFormula("1÷7", s.zeroDigit, s.decimalSeparator))
    }
}
