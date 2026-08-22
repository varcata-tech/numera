package app.numera.calculator.units

import app.numera.calculator.math.UnifiedReal

/** The physical quantities the converter knows about. */
enum class Dimension {
    LENGTH, MASS, AREA, VOLUME, SPEED, TEMPERATURE, TIME, ANGLE,
    ENERGY, POWER, FORCE, PRESSURE, FREQUENCY,
    DIGITAL_STORAGE, DATA_RATE, FUEL_ECONOMY,
}

/**
 * One unit, defined exactly against its dimension's base unit.
 *
 * [factor] is a [UnifiedReal] rather than a `Double` deliberately. An inch is *exactly*
 * 25.4 mm and a mile is *exactly* 1.609344 km; in floating point the first is fine and the
 * second is 1.6093440000000001, and an inch → cm → inch round trip drifts. Keeping the
 * factor exact makes every round trip lossless, which is a property most calculator apps
 * quietly fail. It also means an angle conversion carries π symbolically, so 180° → radians
 * can be scrolled out to a thousand digits.
 *
 * @property id stable key used for preferences and history; never localised, never shown.
 * @property offset affine term, needed only by temperature: 0 °C is not 0 K.
 * @property inverse set for reciprocal units such as L/100 km, where more is less.
 */
data class UnitDef(
    val id: String,
    val dimension: Dimension,
    val factor: UnifiedReal,
    val offset: UnifiedReal = UnifiedReal.ZERO,
    val inverse: Boolean = false,
)

/**
 * Converts between two units of the same dimension, via their shared base unit.
 *
 * Every conversion is exact, so [convert] composed with itself in the other direction is
 * the identity — that is what [app.numera.calculator.units.UnitCatalog] can be tested on.
 */
object UnitConverter {

    /** Converts [value] from [from] to [to]. Both must share a [Dimension]. */
    fun convert(value: UnifiedReal, from: UnitDef, to: UnitDef): UnifiedReal {
        require(from.dimension == to.dimension) {
            "cannot convert ${from.id} to ${to.id}: different dimensions"
        }
        return fromBase(toBase(value, from), to)
    }

    /** Expresses [value] in the dimension's base unit. */
    fun toBase(value: UnifiedReal, unit: UnitDef): UnifiedReal =
        if (unit.inverse) {
            // L/100km is not a scaled km/L, it is its own reciprocal. Without this branch
            // the whole fuel-economy category is silently, plausibly wrong.
            unit.factor / value
        } else {
            value * unit.factor + unit.offset
        }

    /** Expresses a base-unit [base] in [unit]. */
    fun fromBase(base: UnifiedReal, unit: UnitDef): UnifiedReal =
        if (unit.inverse) {
            unit.factor / base
        } else {
            (base - unit.offset) / unit.factor
        }
}
