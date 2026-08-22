package app.numera.calculator.feature.converter

import app.numera.calculator.units.Dimension
import app.numera.calculator.units.UnitCatalog
import app.numera.calculator.units.UnitDef

/** The category and the two units a restored converter opens on. */
internal data class ConverterSelection(
    val dimension: Dimension,
    val fromUnit: UnitDef,
    val toUnit: UnitDef,
)

/**
 * Rebuilds the converter's category and unit pair from the ids in the saved-state bundle.
 *
 * Kept out of the view model because the failure it prevents is invisible on screen:
 * [app.numera.calculator.units.UnitConverter.convert] *requires* both units to share a
 * dimension, and throws [IllegalArgumentException] when they do not. Nothing in the
 * conversion job catches that — it catches `CalculationException` and `ArithmeticException`,
 * and this is neither — so a mismatched pair would take the process down on the first
 * keystroke after a restore. A bundle written before a unit was moved between categories, or
 * one carrying an id this build no longer defines, is how such a pair arrives.
 *
 * Nothing here throws or reads an enum by ordinal: a saved state the current build cannot
 * make sense of falls back to the category default, because a screen that refuses to open is
 * a worse answer than a screen that opens on metres.
 */
internal fun restoredSelection(
    dimensionName: String?,
    fromId: String?,
    toId: String?,
): ConverterSelection {
    val dimension: Dimension = dimensionName
        ?.let { name -> enumValues<Dimension>().firstOrNull { it.name == name } }
        ?: Dimension.LENGTH
    return ConverterSelection(
        dimension = dimension,
        fromUnit = unitIn(dimension, fromId) ?: UnitCatalog.defaultFrom(dimension),
        toUnit = unitIn(dimension, toId) ?: UnitCatalog.defaultTo(dimension),
    )
}

/** The unit [id] names, but only while it still belongs to [dimension]. */
private fun unitIn(dimension: Dimension, id: String?): UnitDef? =
    id?.let { UnitCatalog.byId(it) }?.takeIf { it.dimension == dimension }
