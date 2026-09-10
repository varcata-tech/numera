package app.numera.calculator.feature.converter

import app.numera.calculator.math.AbortedException
import app.numera.calculator.math.AngleMode
import app.numera.calculator.math.CalculationException
import app.numera.calculator.math.UnifiedReal
import app.numera.calculator.math.expr.EvalResult
import app.numera.calculator.math.expr.ExprEvaluator
import app.numera.calculator.math.format.ResultFormatter
import app.numera.calculator.units.Dimension
import app.numera.calculator.units.UnitCatalog
import app.numera.calculator.units.UnitConverter
import app.numera.calculator.units.UnitDef
import java.util.Locale

/** How many characters a converted number may take before the formatter truncates it. */
internal const val CONVERTER_VALUE_BUDGET: Int = 18

/** How many sibling units the strip under the result shows. */
private const val COMMON_COUNT: Int = 4

/**
 * One finished conversion: the exact result, its rendering, and the sibling strip.
 *
 * @property value the exact converted amount, or `null` when the input does not parse or
 *   the conversion is undefined, in which case [text] is empty and [siblings] are none.
 * @property activeText the *input* re-rendered in the locale this conversion was made in,
 *   present only when the input is a carried exact value. Such a value exists on screen only
 *   as the string it was last formatted into, and the view model survives the activity
 *   recreation a per-app language switch causes — so without this the passive field and
 *   the strip re-render in Arabic-Indic digits while the active field keeps the previous
 *   locale's Latin ones. `null` for typed input, whose text is the tokens themselves and
 *   needs no re-rendering.
 */
internal class Conversion(
    val value: UnifiedReal?,
    val text: String,
    val siblings: List<Pair<UnitDef, String>>,
    val activeText: String?,
) {
    internal companion object {
        /** What lands when there is nothing to show: both derived fields blank. */
        val NONE: Conversion = Conversion(null, "", emptyList(), null)
    }
}

/**
 * Converts [source] as the screen described by [snapshot] asks, rendering every number in
 * [renderIn].
 *
 * Pure and blocking on purpose: it is the body the view model runs under
 * `runInterruptible` on a worker, and being a plain function is what lets a JVM test call
 * it with a carried value and a locale and read back exactly the strings the screen would
 * get. An [AbortedException] travels out of it untouched — every other arithmetic failure
 * becomes an empty result, but an abort is the user pressing another key, and reporting it
 * as an empty field would show a finished-looking wrong screen.
 */
internal fun convertForDisplay(
    source: ConverterInput,
    snapshot: ConverterUiState,
    angleMode: AngleMode,
    renderIn: Locale,
): Conversion {
    val amount: UnifiedReal = valueOf(source, angleMode) ?: return Conversion.NONE
    val fromUnit: UnitDef = if (snapshot.editingFrom) snapshot.fromUnit else snapshot.toUnit
    val toUnit: UnitDef = if (snapshot.editingFrom) snapshot.toUnit else snapshot.fromUnit
    // Rendered on its own, ahead of the conversion: a carried value the target unit cannot
    // take — the reciprocal of a fuel-economy zero — still has to come out in the locale the
    // rest of the screen is drawn in, or the language switch leaves it behind exactly as
    // before.
    val activeText: String? = source.exact?.let { exact ->
        unlessArithmeticFails { format(exact, renderIn) }
    }
    return unlessArithmeticFails {
        val result: UnifiedReal = UnitConverter.convert(amount, fromUnit, toUnit)
        Conversion(
            value = result,
            text = format(result, renderIn),
            siblings = siblingsOf(amount, fromUnit, toUnit, snapshot.dimension, renderIn),
            activeText = activeText,
        )
    } ?: Conversion(null, "", emptyList(), activeText)
}

/** The exact number the input stands for, or `null` when it does not parse. */
private fun valueOf(source: ConverterInput, mode: AngleMode): UnifiedReal? {
    source.exact?.let { return it }
    val parsed: EvalResult = ExprEvaluator.evaluate(source.expr, mode)
    return (parsed as? EvalResult.Success)?.value
}

/**
 * Runs [block], answering an arithmetic failure with `null` and a cancellation with itself.
 *
 * [AbortedException] is named ahead of every other [ArithmeticException] deliberately. Every
 * [CalculationException] is one, so a broad catch would turn "the user pressed another key"
 * into a finished, wrong-looking answer; it has to keep travelling. Of the rest, a
 * [CalculationException] is a reciprocal unit undefined at zero or a value too large to
 * approximate, and the bare [ArithmeticException] is what `BigInteger` raises when a value
 * outgrows its own supported range — in every such case an empty field is a better answer
 * than infinity or a crash.
 */
private inline fun <T : Any> unlessArithmeticFails(block: () -> T): T? =
    try {
        block()
    } catch (e: AbortedException) {
        throw e
    } catch (e: CalculationException) {
        null
    } catch (e: ArithmeticException) {
        null
    }

/**
 * The same amount expressed in a few other units of the dimension.
 *
 * Drawn from [UnitCatalog.popularOf] rather than declaration order: the catalogue lists
 * every dimension smallest-unit-first, so taking the first entries spent all four slots
 * on nanometres through centimetres while the user was converting miles. The
 * destination unit is skipped alongside the source, because repeating the answer that
 * is already on screen costs one of only four slots.
 */
private fun siblingsOf(
    amount: UnifiedReal,
    from: UnitDef,
    to: UnitDef,
    dimension: Dimension,
    renderIn: Locale,
): List<Pair<UnitDef, String>> =
    UnitCatalog.popularOf(dimension)
        .asSequence()
        .filter { it.id != from.id && it.id != to.id }
        .take(COMMON_COUNT)
        .mapNotNull { unit ->
            // As above: an abort is a cancellation, not a unit that cannot be shown.
            unlessArithmeticFails {
                unit to format(UnitConverter.convert(amount, from, unit), renderIn)
            }
        }
        .toList()

private fun format(value: UnifiedReal, renderIn: Locale): String =
    ResultFormatter.formatShort(value, CONVERTER_VALUE_BUDGET, renderIn)
