package app.numera.calculator.feature.converter

import app.numera.calculator.math.AngleMode
import app.numera.calculator.math.BoundedRational
import app.numera.calculator.math.UnifiedReal
import app.numera.calculator.math.expr.CalculatorExpr
import app.numera.calculator.math.expr.KeyId
import app.numera.calculator.math.format.ResultFormatter
import app.numera.calculator.units.Dimension
import app.numera.calculator.units.UnitCatalog
import app.numera.calculator.units.UnitDef
import java.text.DecimalFormatSymbols
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one conversion hands the screen, and in particular what it hands the *active* field.
 *
 * The computed field and the sibling strip are re-rendered on every locale change, because
 * they exist on screen only as strings. The active field normally needs no such thing — its
 * text is the typed tokens, which draw themselves in whatever locale the keypad is in. A
 * carried exact value is the exception: it too exists on screen only as the string it was
 * last formatted into, and the view model outlives the activity a per-app language switch
 * recreates. Before [convertForDisplay] re-rendered it, switching to Arabic after a swap left
 * the to-field reading `١` under a from-field still reading `3.28083989501312…` — two
 * numbering systems on one screen, which is exactly what the locale plumbing exists to stop.
 */
class ConverterConversionTest {

    private val arabic: Locale = Locale.forLanguageTag("ar-EG")

    private fun unit(id: String): UnitDef = requireNotNull(UnitCatalog.byId(id)) { "missing $id" }

    private fun v(text: String): UnifiedReal = UnifiedReal.of(BoundedRational.parse(text))

    private fun screen(from: String, to: String, editingFrom: Boolean): ConverterUiState =
        ConverterUiState(
            dimension = Dimension.LENGTH,
            fromUnit = unit(from),
            toUnit = unit(to),
            editingFrom = editingFrom,
        )

    private fun render(value: UnifiedReal, locale: Locale): String =
        ResultFormatter.formatShort(value, CONVERTER_VALUE_BUDGET, locale)

    /** True when [text] spells at least one digit in [locale]'s own numbering system. */
    private fun usesDigitsOf(locale: Locale, text: String): Boolean {
        val zero: Char = DecimalFormatSymbols.getInstance(locale).zeroDigit
        return text.any { it in zero..(zero + 9) }
    }

    @Test
    fun `a carried exact value is re-rendered in the locale of the conversion`() {
        // 1 m is 1250/381 ft, which does not terminate, so a swap carries it as a value
        // rather than as digits — the shape of input that has no text of its own.
        val feet: UnifiedReal = UnifiedReal.of(BoundedRational.of(1250L, 381L))
        val carried = ConverterInput(exact = feet)

        val conversion: Conversion = convertForDisplay(
            carried,
            screen("foot", "metre", editingFrom = true),
            AngleMode.DEGREES,
            arabic,
        )

        val active: String? = conversion.activeText
        assertNotNull("the carried value was not re-rendered", active)
        assertEquals(render(feet, arabic), active)
        // Asserted against the platform's own symbols rather than literal Arabic-Indic digits,
        // so the check follows whatever numbering system the JDK underneath gives ar-EG.
        assertTrue("not in the locale's digits: $active", usesDigitsOf(arabic, active!!))
        // The conversion itself is untouched by the re-rendering: 1250/381 ft is exactly 1 m.
        assertEquals(render(UnifiedReal.ONE, arabic), conversion.text)
        val value: UnifiedReal? = conversion.value
        assertNotNull(value)
        assertTrue(
            "1250/381 ft → m gave ${value!!.toNiceString()}",
            value.asRational() == v("1").asRational(),
        )
    }

    @Test
    fun `a carried value in the to-field is re-rendered as well`() {
        // The same failure with the focus on the other side: the from-field is the computed
        // one and the to-field holds the carried value.
        val feet: UnifiedReal = UnifiedReal.of(BoundedRational.of(1250L, 381L))
        val conversion: Conversion =
            convertForDisplay(
                ConverterInput(exact = feet),
                screen("metre", "foot", editingFrom = false),
                AngleMode.DEGREES,
                arabic,
            )
        assertEquals(render(feet, arabic), conversion.activeText)
        assertEquals(render(UnifiedReal.ONE, arabic), conversion.text)
    }

    @Test
    fun `typed input keeps the text it already shows`() {
        // Tokens draw themselves through the keypad's locale, so re-rendering them here would
        // be redundant at best — and at worst would replace `2+3` with `5` under the user's
        // fingers. Null is the contract for "leave the field alone".
        val typed = ConverterInput(requireNotNull(CalculatorExpr.fromText("1")))
        val conversion: Conversion = convertForDisplay(
            typed,
            screen("metre", "foot", editingFrom = true),
            AngleMode.DEGREES,
            Locale.US,
        )
        assertNull(conversion.activeText)
        assertNotNull(conversion.value)
        assertEquals(render(v("1250") / v("381"), Locale.US), conversion.text)
    }

    @Test
    fun `an expression that does not parse lands as an empty result`() {
        // `1+` is what the input looks like between two keystrokes. It has no value, so the
        // passive field goes blank rather than keeping the answer to the previous keystroke.
        val one: CalculatorExpr = requireNotNull(CalculatorExpr.fromText("1"))
        val partial = ConverterInput(one.append(KeyId.ADD))
        val conversion: Conversion = convertForDisplay(
            partial,
            screen("metre", "foot", editingFrom = true),
            AngleMode.DEGREES,
            Locale.US,
        )
        assertNull(conversion.value)
        assertNull(conversion.activeText)
        assertEquals("", conversion.text)
        assertTrue(conversion.siblings.isEmpty())
    }
}
