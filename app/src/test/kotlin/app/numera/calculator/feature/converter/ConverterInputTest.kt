package app.numera.calculator.feature.converter

import app.numera.calculator.math.AngleMode
import app.numera.calculator.math.BoundedRational
import app.numera.calculator.math.UnifiedReal
import app.numera.calculator.math.expr.EvalResult
import app.numera.calculator.math.expr.ExprEvaluator
import app.numera.calculator.math.expr.KeyId
import app.numera.calculator.units.Dimension
import app.numera.calculator.units.UnitCatalog
import app.numera.calculator.units.UnitConverter
import app.numera.calculator.units.UnitDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moving a value from one converter field to the other must not go through the display.
 *
 * The swap button used to rebuild its input by filtering the formatted string on screen down
 * to digits, `.` and ASCII `-`. Every assertion here is a case that filter got wrong: the
 * result line writes its minus as U+2212, writes an exponent as `E`, marks truncation with
 * `…`, and in most of the twelve shipped locales uses `,` for the decimal point and `.` for
 * grouping. None of that survives being read back as a number, so the fix carries the exact
 * value across instead — and these are the cases that prove it still does.
 */
class ConverterInputTest {

    private fun unit(id: String): UnitDef = requireNotNull(UnitCatalog.byId(id)) { "missing $id" }

    private fun v(text: String): UnifiedReal = UnifiedReal.of(BoundedRational.parse(text))

    /** The exact number an input stands for, however it is being carried. */
    private fun valueOf(input: ConverterInput): UnifiedReal {
        input.exact?.let { return it }
        val result = ExprEvaluator.evaluate(input.expr, AngleMode.RADIANS)
        assertTrue("`${input.expr.display()}` did not evaluate", result is EvalResult.Success)
        return (result as EvalResult.Success).value
    }

    private fun convert(amount: UnifiedReal, from: String, to: String): UnifiedReal =
        UnitConverter.convert(amount, unit(from), unit(to))

    @Test
    fun `adopting a negative value keeps its sign`() {
        // −40 °C is −40 °F, and both fields render it with U+2212. The old character filter
        // dropped that glyph, so one tap of swap turned −40 into 40 on a default en-US
        // device — a wrong answer with nothing on screen to hint at it.
        val minusForty = convert(v("-40"), "celsius", "fahrenheit")
        assertEquals(v("-40"), minusForty)
        assertEquals(v("-40"), valueOf(ConverterInput.adopt(minusForty)))
    }

    @Test
    fun `swapping an inch to centimetre conversion returns the identical inch`() {
        // The headline guarantee, exercised across the swap: 1 in is exactly 2.54 cm, and
        // adopting that value and converting it back must land on 1 and not near it.
        val centimetres = convert(v("1"), "inch", "centimetre")
        assertEquals(v("2.54"), centimetres)
        val swapped = ConverterInput.adopt(centimetres)
        assertEquals(v("1"), convert(valueOf(swapped), "centimetre", "inch"))
    }

    @Test
    fun `a value with no terminating decimal is carried rather than rounded`() {
        // 1 m is 5000/127 in, which the field can only show truncated as 39.3700787401574…
        // Re-entering those digits would quietly substitute a rounded number for an exact
        // one, so the value itself is carried and the round trip stays lossless.
        val inches = convert(v("1"), "metre", "inch")
        val swapped = ConverterInput.adopt(inches)
        assertNotNull("a non-terminating value must be carried exactly", swapped.exact)
        assertTrue(swapped.expr.isEmpty())
        assertEquals(v("1"), convert(valueOf(swapped), "inch", "metre"))
    }

    @Test
    fun `a value carrying pi survives the swap symbolically`() {
        // 180° is π radians exactly. No literal can spell that, and rounding it here would
        // cost the thousand-digit scroll the angle category exists to offer.
        val radians = convert(v("180"), "degree", "radian")
        assertEquals(UnifiedReal.PI, radians)
        val swapped = ConverterInput.adopt(radians)
        assertEquals(UnifiedReal.PI, swapped.exact)
        assertEquals(v("180"), convert(valueOf(swapped), "radian", "degree"))
    }

    @Test
    fun `an adopted literal can still be typed into`() {
        // The point of turning a terminating value back into tokens: the user can carry on
        // editing the number the swap handed them.
        val centimetres = convert(v("1"), "inch", "centimetre")
        val edited = ConverterInput.adopt(centimetres).append(KeyId.D1)
        assertNull(edited.exact)
        assertEquals(v("2.541"), valueOf(edited))
    }

    @Test
    fun `typing over a carried value starts a new number instead of extending it`() {
        // A carried value is a number, not a token sequence. Appending a digit to the
        // *rendering* of 5000/127 would promote a truncated display to exact input.
        val inches = convert(v("1"), "metre", "inch")
        val retyped = ConverterInput.adopt(inches).append(KeyId.D5)
        assertNull(retyped.exact)
        assertEquals("5", retyped.expr.display())
    }

    @Test
    fun `deleting a carried value empties the field in one press`() {
        val inches = convert(v("1"), "metre", "inch")
        val deleted = ConverterInput.adopt(inches).delete()
        assertTrue(deleted.isEmpty())
        assertTrue(ConverterInput.adopt(null).isEmpty())
    }

    @Test
    fun `a transfer with nothing computed keeps what the user typed`() {
        // Fuel economy, from L/100 km: pressing 0 leaves the to-field correctly blank,
        // because 100 ÷ 0 raises rather than answering. There is then no computed value to
        // move, and `adopt(null)` is an *empty* input — which the view model reads as
        // "nothing to convert" and answers by blanking *both* fields, so one tap of swap
        // deleted the 0 the user had just typed. The same window is open on any swap or
        // focus change taken before the first conversion has landed.
        val typed = ConverterInput().append(KeyId.D0)
        assertTrue("adopt(null) is what empties the input", ConverterInput.adopt(null).isEmpty())
        assertFalse(typed.adopting(null).isEmpty())
        assertEquals(typed, typed.adopting(null))
    }

    @Test
    fun `a transfer with a computed value still takes that value over`() {
        // The fallback above must not cost the swap its whole purpose: when there *is* a
        // computed value it is still adopted exactly, replacing whatever was typed.
        val centimetres = convert(v("1"), "inch", "centimetre")
        val typed = ConverterInput().append(KeyId.D9)
        assertEquals(v("2.54"), valueOf(typed.adopting(centimetres)))
    }

    @Test
    fun `a conversion result is only valid for the pair it was started from`() {
        // A background conversion that lands after the user moved on used to be written
        // into whichever field was active by then, so a length answer could appear beneath
        // a °C label, or clobber a number the user had just typed.
        val started = ConverterUiState()
        assertTrue(started.describesSameConversion(started.copy(fromText = "5", toText = "500")))
        assertFalse(
            "a category change invalidates it",
            started.describesSameConversion(started.copy(dimension = Dimension.TEMPERATURE)),
        )
        assertFalse(
            "a focus change invalidates it",
            started.describesSameConversion(started.copy(editingFrom = false)),
        )
        assertFalse(
            "a unit change invalidates it",
            started.describesSameConversion(
                started.copy(toUnit = UnitCatalog.defaultFrom(Dimension.LENGTH)),
            ),
        )
    }
}
