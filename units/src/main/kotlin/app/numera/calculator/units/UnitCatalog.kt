package app.numera.calculator.units

import app.numera.calculator.math.BoundedRational
import app.numera.calculator.math.UnifiedReal

/** Exact decimal literal, e.g. `v("0.45359237")` is the pound in kilograms, precisely. */
private fun v(text: String): UnifiedReal = UnifiedReal.of(BoundedRational.parse(text))

/** Exact fraction, for the many definitions that are ratios rather than decimals. */
private fun q(numerator: Long, denominator: Long = 1L): UnifiedReal =
    UnifiedReal.of(BoundedRational.of(numerator, denominator))

/**
 * Ten to the [power], for the metric ladder.
 *
 * Stays in [java.math.BigInteger] the whole way. Narrowing through `toLong()` keeps only the
 * low 64 bits, and 10^19 does not fit: it wraps to −8446744073709551616, which made the
 * electronvolt a *negative* energy 18% too large. Nothing downstream rejects a negative
 * factor — the conversion still round-trips — so the wrong sign would have reached the user.
 */
private fun e10(power: Int): UnifiedReal =
    if (power >= 0) {
        UnifiedReal.of(BoundedRational.of(java.math.BigInteger.TEN.pow(power)))
    } else {
        UnifiedReal.of(
            BoundedRational.of(java.math.BigInteger.ONE, java.math.BigInteger.TEN.pow(-power)),
        )
    }

/**
 * Two to the [power], for the binary storage ladder.
 *
 * Also in [java.math.BigInteger]: `1L shl 64` is silently `1`, not an overflow, so a ladder
 * extended one rung past the pebibyte would define a unit as its own base rather than fail.
 */
private fun e2(power: Int): UnifiedReal =
    UnifiedReal.of(BoundedRational.of(java.math.BigInteger.TWO.pow(power)))

/**
 * Every unit the converter offers, defined exactly.
 *
 * The categories are the union of what Google's converter and Apple's iOS 18 Calculator
 * offer, minus currency: the app has no network access and no INTERNET permission, so live
 * exchange rates are out of scope by construction rather than by omission.
 *
 * The base unit of each dimension is the SI one, and is the unit with a factor of exactly 1.
 * Where a definition is a *convention* rather than an exact SI derivation, it is commented
 * as such — a user comparing against another tool deserves to know which numbers are
 * definitions and which are agreements.
 */
object UnitCatalog {

    // Definitional constants that many units are derived from, so the derivations read the
    // way the standards write them rather than as pre-multiplied decimals.
    private val INCH_M = v("0.0254")                    // exact by international definition
    private val FOOT_M = INCH_M * q(12)                 // 0.3048 exactly
    private val YARD_M = FOOT_M * q(3)
    private val MILE_M = YARD_M * q(1760)               // 1609.344 exactly
    private val POUND_KG = v("0.45359237")              // exact by international definition
    private val POUND_FORCE_N = v("4.4482216152605")    // pound times standard gravity
    private val GRAVITY = v("9.80665")
    private val US_GALLON_M3 = INCH_M * INCH_M * INCH_M * q(231)   // 231 cubic inches exactly
    private val IMP_GALLON_M3 = v("0.00454609")
    private val BTU_J = v("1055.05585262")              // IT calorie basis
    private val CALORIE_J = v("4.184")                  // thermochemical

    val length: List<UnitDef> = dimension(Dimension.LENGTH) {
        unit("nanometre", e10(-9))
        unit("micrometre", e10(-6))
        unit("millimetre", e10(-3))
        unit("centimetre", e10(-2))
        unit("decimetre", e10(-1))
        unit("metre", UnifiedReal.ONE)
        unit("kilometre", e10(3))
        unit("thou", INCH_M / q(1000))
        unit("inch", INCH_M)
        unit("foot", FOOT_M)
        unit("yard", YARD_M)
        unit("mile", MILE_M)
        unit("nautical_mile", q(1852))
        unit("fathom", FOOT_M * q(6))
        unit("chain", FOOT_M * q(66))
        unit("furlong", FOOT_M * q(660))
        unit("league", MILE_M * q(3))
        unit("angstrom", e10(-10))
        unit("astronomical_unit", q(149_597_870_700L))
        unit("light_year", q(9_460_730_472_580_800L))
        // A parsec is defined as 648000/π astronomical units, and because π stays symbolic
        // that definition is carried exactly rather than rounded to a decimal.
        unit("parsec", q(648_000) / UnifiedReal.PI * q(149_597_870_700L))
    }

    val mass: List<UnitDef> = dimension(Dimension.MASS) {
        unit("microgram", e10(-9))
        unit("milligram", e10(-6))
        unit("gram", e10(-3))
        unit("kilogram", UnifiedReal.ONE)
        unit("tonne", q(1000))
        unit("grain", POUND_KG / q(7000))
        unit("carat", v("0.0002"))
        unit("ounce", POUND_KG / q(16))
        unit("pound", POUND_KG)
        unit("stone", POUND_KG * q(14))
        unit("ton_us", POUND_KG * q(2000))
        unit("ton_imperial", POUND_KG * q(2240))
        unit("slug", POUND_FORCE_N / FOOT_M)
    }

    val area: List<UnitDef> = dimension(Dimension.AREA) {
        unit("square_millimetre", e10(-6))
        unit("square_centimetre", e10(-4))
        unit("square_metre", UnifiedReal.ONE)
        unit("hectare", q(10_000))
        unit("square_kilometre", e10(6))
        unit("square_inch", INCH_M * INCH_M)
        unit("square_foot", FOOT_M * FOOT_M)
        unit("square_yard", YARD_M * YARD_M)
        unit("acre", YARD_M * YARD_M * q(4840))
        unit("square_mile", MILE_M * MILE_M)
    }

    val volume: List<UnitDef> = dimension(Dimension.VOLUME) {
        unit("millilitre", e10(-6))
        unit("cubic_centimetre", e10(-6))
        unit("litre", e10(-3))
        unit("cubic_metre", UnifiedReal.ONE)
        unit("cubic_inch", INCH_M * INCH_M * INCH_M)
        unit("cubic_foot", FOOT_M * FOOT_M * FOOT_M)
        unit("cubic_yard", YARD_M * YARD_M * YARD_M)
        unit("teaspoon_us", US_GALLON_M3 / q(768))
        unit("tablespoon_us", US_GALLON_M3 / q(256))
        unit("fluid_ounce_us", US_GALLON_M3 / q(128))
        unit("cup_us", US_GALLON_M3 / q(16))
        unit("pint_us", US_GALLON_M3 / q(8))
        unit("quart_us", US_GALLON_M3 / q(4))
        unit("gallon_us", US_GALLON_M3)
        unit("fluid_ounce_imperial", IMP_GALLON_M3 / q(160))
        unit("pint_imperial", IMP_GALLON_M3 / q(8))
        unit("quart_imperial", IMP_GALLON_M3 / q(4))
        unit("gallon_imperial", IMP_GALLON_M3)
        unit("barrel_oil", US_GALLON_M3 * q(42))
    }

    val speed: List<UnitDef> = dimension(Dimension.SPEED) {
        unit("metre_per_second", UnifiedReal.ONE)
        unit("kilometre_per_hour", q(1000, 3600))
        unit("mile_per_hour", MILE_M / q(3600))
        unit("foot_per_second", FOOT_M)
        unit("knot", q(1852, 3600))
    }

    /**
     * Temperature, the one affine dimension: `kelvin = value × factor + offset`.
     *
     * A purely multiplicative model gets 0 °C wrong by 273.15 and −40 °F wrong entirely,
     * which is why [UnitDef.offset] exists at all.
     */
    val temperature: List<UnitDef> = dimension(Dimension.TEMPERATURE) {
        unit("kelvin", UnifiedReal.ONE)
        unit("celsius", UnifiedReal.ONE, offset = v("273.15"))
        // K = (F + 459.67) × 5/9, so the offset is 459.67 × 5/9 = 45967/180 exactly.
        unit("fahrenheit", q(5, 9), offset = q(45_967, 180))
        unit("rankine", q(5, 9))
        unit("reaumur", q(5, 4), offset = v("273.15"))
    }

    val time: List<UnitDef> = dimension(Dimension.TIME) {
        unit("nanosecond", e10(-9))
        unit("microsecond", e10(-6))
        unit("millisecond", e10(-3))
        unit("second", UnifiedReal.ONE)
        unit("minute", q(60))
        unit("hour", q(3600))
        unit("day", q(86_400))
        unit("week", q(604_800))
        unit("fortnight", q(1_209_600))
        // Conventions, not definitions: the Gregorian mean month and year. Any other tool
        // may use 30 days or 365; these are the values Google's converter uses.
        unit("month", q(2_629_746))
        unit("year", q(31_556_952))
        unit("decade", q(315_569_520))
        unit("century", q(3_155_695_200L))
    }

    /** Angle. Every non-metric unit here carries π exactly, so results stay scrollable. */
    val angle: List<UnitDef> = dimension(Dimension.ANGLE) {
        unit("radian", UnifiedReal.ONE)
        unit("milliradian", e10(-3))
        unit("degree", UnifiedReal.PI / q(180))
        unit("gradian", UnifiedReal.PI / q(200))
        unit("arcminute", UnifiedReal.PI / q(10_800))
        unit("arcsecond", UnifiedReal.PI / q(648_000))
        unit("turn", UnifiedReal.PI * q(2))
    }

    val energy: List<UnitDef> = dimension(Dimension.ENERGY) {
        unit("joule", UnifiedReal.ONE)
        unit("kilojoule", q(1000))
        unit("calorie", CALORIE_J)
        unit("kilocalorie", CALORIE_J * q(1000))
        unit("watt_hour", q(3600))
        unit("kilowatt_hour", q(3_600_000))
        // The 2019 SI redefinition fixed the electronvolt exactly.
        unit("electronvolt", v("1.602176634") / e10(19))
        unit("btu", BTU_J)
        // US therm: 100000 BTU on the 59 °F basis, a convention rather than an SI value.
        unit("therm", v("105480400"))
        unit("foot_pound", POUND_FORCE_N * FOOT_M)
    }

    val power: List<UnitDef> = dimension(Dimension.POWER) {
        unit("watt", UnifiedReal.ONE)
        unit("kilowatt", q(1000))
        unit("megawatt", e10(6))
        unit("horsepower_mechanical", POUND_FORCE_N * FOOT_M * q(550))
        unit("horsepower_metric", v("735.49875"))
        unit("btu_per_hour", BTU_J / q(3600))
        unit("calorie_per_second", CALORIE_J)
    }

    val force: List<UnitDef> = dimension(Dimension.FORCE) {
        unit("newton", UnifiedReal.ONE)
        unit("kilonewton", q(1000))
        unit("dyne", e10(-5))
        unit("pound_force", POUND_FORCE_N)
        unit("kilogram_force", GRAVITY)
        unit("poundal", POUND_KG * FOOT_M)
        unit("ton_force", POUND_FORCE_N * q(2000))
    }

    val pressure: List<UnitDef> = dimension(Dimension.PRESSURE) {
        unit("pascal", UnifiedReal.ONE)
        unit("hectopascal", q(100))
        unit("kilopascal", q(1000))
        unit("megapascal", e10(6))
        unit("bar", e10(5))
        unit("millibar", q(100))
        unit("psi", POUND_FORCE_N / (INCH_M * INCH_M))
        unit("atmosphere", q(101_325))
        unit("torr", q(101_325, 760))
        unit("mmhg", v("133.322387415"))
        // Conventional inch of mercury, not an exact derivation.
        unit("inhg", v("3386.389"))
    }

    val frequency: List<UnitDef> = dimension(Dimension.FREQUENCY) {
        unit("hertz", UnifiedReal.ONE)
        unit("kilohertz", q(1000))
        unit("megahertz", e10(6))
        unit("gigahertz", e10(9))
        unit("terahertz", e10(12))
        unit("rpm", q(1, 60))
    }

    val digitalStorage: List<UnitDef> = dimension(Dimension.DIGITAL_STORAGE) {
        storageLadder(perSecond = false)
    }

    val dataRate: List<UnitDef> = dimension(Dimension.DATA_RATE) {
        storageLadder(perSecond = true)
    }

    /**
     * Fuel economy, where one unit is the reciprocal of the others.
     *
     * L/100 km measures consumption, not economy: a bigger number is a worse car. Modelling
     * it as an ordinary scale factor produces answers that look plausible and are wrong,
     * which is why [UnitDef.inverse] exists.
     */
    val fuelEconomy: List<UnitDef> = dimension(Dimension.FUEL_ECONOMY) {
        unit("kilometre_per_litre", UnifiedReal.ONE)
        unit("litre_per_100km", q(100), inverse = true)
        unit("mpg_us", (MILE_M / q(1000)) / (US_GALLON_M3 * q(1000)))
        unit("mpg_imperial", (MILE_M / q(1000)) / (IMP_GALLON_M3 * q(1000)))
    }

    /** Every dimension, in the order the category chips are shown. */
    val dimensions: List<Dimension> = listOf(
        Dimension.LENGTH, Dimension.MASS, Dimension.TEMPERATURE, Dimension.AREA,
        Dimension.VOLUME, Dimension.SPEED, Dimension.TIME, Dimension.ANGLE,
        Dimension.DIGITAL_STORAGE, Dimension.DATA_RATE, Dimension.ENERGY,
        Dimension.POWER, Dimension.FORCE, Dimension.PRESSURE, Dimension.FREQUENCY,
        Dimension.FUEL_ECONOMY,
    )

    private val byDimension: Map<Dimension, List<UnitDef>> = listOf(
        length, mass, area, volume, speed, temperature, time, angle,
        energy, power, force, pressure, frequency,
        digitalStorage, dataRate, fuelEconomy,
    ).flatten().groupBy { it.dimension }

    val all: List<UnitDef> = byDimension.values.flatten()

    private val byId: Map<String, UnitDef> = all.associateBy { it.id }

    fun unitsOf(dimension: Dimension): List<UnitDef> = byDimension[dimension].orEmpty()

    fun byId(id: String): UnitDef? = byId[id]

    /**
     * The pair each category opens on, as unit ids.
     *
     * Spelled out rather than taken from declaration order, because every dimension is
     * declared smallest-unit-first: order alone opened Length on nm → µm, Time on ns → µs
     * and — worst — Volume on mL → cm³, which are the same size, so the default Volume
     * screen echoed whatever was typed and read as a converter that does not work.
     */
    private val defaultPairs: Map<Dimension, Pair<String, String>> = mapOf(
        Dimension.LENGTH to ("metre" to "foot"),
        Dimension.MASS to ("kilogram" to "pound"),
        Dimension.TEMPERATURE to ("celsius" to "fahrenheit"),
        Dimension.AREA to ("square_metre" to "square_foot"),
        Dimension.VOLUME to ("litre" to "gallon_us"),
        Dimension.SPEED to ("kilometre_per_hour" to "mile_per_hour"),
        Dimension.TIME to ("hour" to "minute"),
        Dimension.ANGLE to ("degree" to "radian"),
        Dimension.DIGITAL_STORAGE to ("gigabyte" to "gibibyte"),
        Dimension.DATA_RATE to ("megabit_per_second" to "megabyte_per_second"),
        Dimension.ENERGY to ("kilojoule" to "kilocalorie"),
        Dimension.POWER to ("kilowatt" to "horsepower_mechanical"),
        Dimension.FORCE to ("newton" to "pound_force"),
        Dimension.PRESSURE to ("bar" to "psi"),
        Dimension.FREQUENCY to ("megahertz" to "gigahertz"),
        Dimension.FUEL_ECONOMY to ("litre_per_100km" to "mpg_us"),
    )

    /**
     * The units each category is most often converted into, most useful first.
     *
     * The converter's "common conversions" strip has room for a handful of entries. Filling
     * them in declaration order spent every slot on nanometres through centimetres while the
     * user was converting road distances, which is why the ranking is written down instead
     * of inferred.
     */
    private val popularIds: Map<Dimension, List<String>> = mapOf(
        Dimension.LENGTH to
            listOf("metre", "kilometre", "foot", "mile", "centimetre", "inch", "yard"),
        Dimension.MASS to listOf("kilogram", "gram", "pound", "ounce", "tonne", "stone"),
        Dimension.TEMPERATURE to listOf("celsius", "fahrenheit", "kelvin", "rankine"),
        Dimension.AREA to
            listOf("square_metre", "square_foot", "hectare", "acre", "square_kilometre"),
        Dimension.VOLUME to
            listOf("litre", "millilitre", "gallon_us", "cup_us", "fluid_ounce_us", "pint_us"),
        Dimension.SPEED to
            listOf("kilometre_per_hour", "mile_per_hour", "metre_per_second", "knot"),
        Dimension.TIME to listOf("second", "minute", "hour", "day", "week", "year"),
        Dimension.ANGLE to listOf("degree", "radian", "gradian", "turn", "arcminute"),
        Dimension.DIGITAL_STORAGE to
            listOf("megabyte", "gigabyte", "terabyte", "gibibyte", "mebibyte", "kilobyte"),
        Dimension.DATA_RATE to listOf(
            "megabit_per_second", "megabyte_per_second",
            "gigabit_per_second", "kilobit_per_second",
        ),
        Dimension.ENERGY to
            listOf("kilojoule", "joule", "kilocalorie", "kilowatt_hour", "watt_hour", "btu"),
        Dimension.POWER to
            listOf("kilowatt", "watt", "horsepower_mechanical", "horsepower_metric", "megawatt"),
        Dimension.FORCE to listOf("newton", "kilonewton", "pound_force", "kilogram_force"),
        Dimension.PRESSURE to
            listOf("bar", "kilopascal", "psi", "atmosphere", "pascal", "mmhg"),
        Dimension.FREQUENCY to listOf("hertz", "kilohertz", "megahertz", "gigahertz", "rpm"),
        Dimension.FUEL_ECONOMY to
            listOf("litre_per_100km", "kilometre_per_litre", "mpg_us", "mpg_imperial"),
    )

    /** The unit a category opens on. */
    fun defaultFrom(dimension: Dimension): UnitDef =
        defaultPairs[dimension]?.first?.let { byId(it) } ?: unitsOf(dimension).first()

    /** The unit a category opens converting into. */
    fun defaultTo(dimension: Dimension): UnitDef {
        defaultPairs[dimension]?.second?.let { byId(it) }?.let { return it }
        val units = unitsOf(dimension)
        return units.getOrElse(1) { units.first() }
    }

    /**
     * Every unit of [dimension], the ones worth offering first at the front.
     *
     * Returns the whole dimension, never a subset: a unit added to the catalogue and
     * forgotten in [popularIds] has to keep showing up, or it would silently vanish from
     * anything that draws its list from here.
     */
    fun popularOf(dimension: Dimension): List<UnitDef> {
        val units = unitsOf(dimension)
        val ranked = popularIds[dimension].orEmpty()
            .mapNotNull { id -> units.firstOrNull { it.id == id } }
        val rankedIds = ranked.map { it.id }.toSet()
        return ranked + units.filterNot { it.id in rankedIds }
    }

    // ------------------------------------------------------------------ builders

    private class Builder(val dimension: Dimension) {
        val units = mutableListOf<UnitDef>()

        fun unit(
            id: String,
            factor: UnifiedReal,
            offset: UnifiedReal = UnifiedReal.ZERO,
            inverse: Boolean = false,
        ) {
            units += UnitDef(id, dimension, factor, offset, inverse)
        }

        /**
         * The bits-and-bytes ladder, decimal and binary side by side.
         *
         * Both are needed because a disk manufacturer's terabyte and an operating system's
         * tebibyte differ by ten percent, and that gap is exactly what people open a
         * converter to settle.
         */
        fun storageLadder(perSecond: Boolean) {
            val suffix = if (perSecond) "_per_second" else ""
            unit("bit$suffix", UnifiedReal.ONE)
            unit("byte$suffix", q(8))
            val decimal = listOf("kilo" to 3, "mega" to 6, "giga" to 9, "tera" to 12, "peta" to 15)
            val binary = listOf("kibi" to 10, "mebi" to 20, "gibi" to 30, "tebi" to 40, "pebi" to 50)
            for ((name, power) in decimal) {
                unit("${name}bit$suffix", e10(power))
                unit("${name}byte$suffix", e10(power) * q(8))
            }
            for ((name, power) in binary) {
                unit("${name}bit$suffix", e2(power))
                unit("${name}byte$suffix", e2(power) * q(8))
            }
        }
    }

    private fun dimension(dimension: Dimension, build: Builder.() -> Unit): List<UnitDef> =
        Builder(dimension).apply(build).units
}
