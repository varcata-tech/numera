package app.numera.calculator.units

import app.numera.calculator.math.BoundedRational
import app.numera.calculator.math.UnifiedReal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversion factors, checked against their defining standards.
 *
 * These are assertions about *definitions*, not about arithmetic: an inch is 25.4 mm because
 * the 1959 international yard agreement says so, and if this file ever disagrees then the
 * catalogue has a typo rather than the engine having a bug. Every value asserted here is one
 * a user could check against a reference and hold us to.
 */
class UnitCatalogTest {

    private fun v(text: String) = UnifiedReal.of(BoundedRational.parse(text))
    private fun unit(id: String): UnitDef = requireNotNull(UnitCatalog.byId(id)) { "missing $id" }

    private fun convert(amount: String, from: String, to: String): UnifiedReal =
        UnitConverter.convert(v(amount), unit(from), unit(to))

    private fun assertExactly(expected: String, actual: UnifiedReal, label: String) {
        val wanted = v(expected)
        assertTrue(
            "$label: expected exactly $expected but got ${actual.toNiceString()}",
            actual.isRational && actual.asRational() == wanted.asRational(),
        )
    }

    // ------------------------------------------------------------ exact definitions

    @Test
    fun `an inch is exactly twenty five point four millimetres`() {
        assertExactly("25.4", convert("1", "inch", "millimetre"), "1 in → mm")
    }

    @Test
    fun `a mile is exactly one point six zero nine three four four kilometres`() {
        // In double precision this is 1.6093440000000001, which is the whole reason the
        // catalogue is built on exact rationals.
        assertExactly("1.609344", convert("1", "mile", "kilometre"), "1 mi → km")
    }

    @Test
    fun `a pound is exactly zero point four five three five nine two three seven kilograms`() {
        assertExactly("0.45359237", convert("1", "pound", "kilogram"), "1 lb → kg")
    }

    @Test
    fun `a nautical mile is exactly 1852 metres`() {
        assertExactly("1852", convert("1", "nautical_mile", "metre"), "1 NM → m")
    }

    @Test
    fun `a standard atmosphere is exactly 101325 pascals`() {
        assertExactly("101325", convert("1", "atmosphere", "pascal"), "1 atm → Pa")
    }

    @Test
    fun `a thermochemical calorie is exactly 4 point 184 joules`() {
        assertExactly("4.184", convert("1", "calorie", "joule"), "1 cal → J")
    }

    @Test
    fun `a BTU is exactly 1055 point 05585262 joules`() {
        assertExactly("1055.05585262", convert("1", "btu", "joule"), "1 BTU → J")
    }

    @Test
    fun `an electronvolt is exactly the 2019 SI value in joules`() {
        // The one power of ten in the catalogue that does not fit in a Long. While the
        // metric ladder narrowed through BigInteger.toLong(), 10^19 wrapped to
        // -8446744073709551616 and this factor came out negative and 18% too large, so the
        // converter answered "1 eV = -1.8967978904283E-19 J".
        assertExactly(
            "0.0000000000000000001602176634",
            convert("1", "electronvolt", "joule"),
            "1 eV → J",
        )
        // The direction a user would actually notice: a joule is about 6.24 × 10^18 eV,
        // and above all it is a positive number of them.
        val perJoule = convert("1", "joule", "electronvolt")
        assertTrue(
            "1 J → eV gave ${perJoule.toNiceString()}",
            perJoule > v("6.241e18") && perJoule < v("6.242e18"),
        )
    }

    @Test
    fun `metric horsepower is exactly 735 point 49875 watts`() {
        assertExactly("735.49875", convert("1", "horsepower_metric", "watt"), "1 PS → W")
    }

    @Test
    fun `a millimetre of mercury is exactly 133 point 322387415 pascals`() {
        assertExactly("133.322387415", convert("1", "mmhg", "pascal"), "1 mmHg → Pa")
    }

    @Test
    fun `pound force is exactly 4 point 4482216152605 newtons`() {
        assertExactly("4.4482216152605", convert("1", "pound_force", "newton"), "1 lbf → N")
    }

    @Test
    fun `a US gallon is exactly 231 cubic inches`() {
        assertExactly("231", convert("1", "gallon_us", "cubic_inch"), "1 gal → in³")
        assertExactly("3.785411784", convert("1", "gallon_us", "litre"), "1 gal → L")
    }

    // ------------------------------------------------------------ affine temperature

    @Test
    fun `water boils at 212 fahrenheit`() {
        assertExactly("212", convert("100", "celsius", "fahrenheit"), "100°C → °F")
    }

    @Test
    fun `minus forty is the same in celsius and fahrenheit`() {
        // The one temperature where the two scales cross; a purely multiplicative
        // conversion cannot produce it at all.
        assertExactly("-40", convert("-40", "celsius", "fahrenheit"), "-40°C → °F")
    }

    @Test
    fun `absolute zero is minus 273 point 15 celsius`() {
        assertExactly("-273.15", convert("0", "kelvin", "celsius"), "0 K → °C")
        assertExactly("273.15", convert("0", "celsius", "kelvin"), "0°C → K")
    }

    @Test
    fun `rankine and fahrenheit share a degree size`() {
        assertExactly("491.67", convert("32", "fahrenheit", "rankine"), "32°F → °R")
    }

    // ------------------------------------------------------------ inverse dimension

    @Test
    fun `litres per hundred kilometres is a reciprocal not a scale factor`() {
        // 10 km/L is 10 L/100km. A scale-factor model would give something else entirely.
        assertExactly("10", convert("10", "kilometre_per_litre", "litre_per_100km"), "10 km/L")
        assertExactly("5", convert("20", "kilometre_per_litre", "litre_per_100km"), "20 km/L")
    }

    @Test
    fun `US and imperial miles per gallon differ by the gallon`() {
        val usToImperial = convert("30", "mpg_us", "mpg_imperial")
        // An imperial gallon is larger, so the same car scores higher in imperial mpg.
        assertTrue(usToImperial > v("36") && usToImperial < v("36.1"))
    }

    // ------------------------------------------------------------ symbolic pi

    @Test
    fun `a half turn in radians is exactly pi`() {
        val radians = convert("180", "degree", "radian")
        // Not 3.141592653589793 — actually pi, so it can be scrolled to a thousand digits.
        assertEquals(UnifiedReal.PI.factor, radians.factor)
        assertEquals(BoundedRational.ONE, radians.ratFactor)
    }

    @Test
    fun `a full turn is two pi radians`() {
        val radians = convert("1", "turn", "radian")
        assertEquals(BoundedRational.of(2L), radians.ratFactor)
    }

    @Test
    fun `there are 3600 arcseconds in a degree`() {
        assertExactly("3600", convert("1", "degree", "arcsecond"), "1° → arcsec")
    }

    // ------------------------------------------------------------ storage ladder

    @Test
    fun `a kibibyte and a kilobyte are not the same thing`() {
        assertExactly("1024", convert("1", "kibibyte", "byte"), "1 KiB → B")
        assertExactly("1000", convert("1", "kilobyte", "byte"), "1 kB → B")
        // The ten-percent gap people open a converter to settle.
        assertExactly("8", convert("1", "byte", "bit"), "1 B → bit")
    }

    @Test
    fun `a terabyte disk is smaller than a tebibyte of memory`() {
        val ratio = convert("1", "terabyte", "tebibyte")
        assertTrue("expected under 1, got ${ratio.toNiceString()}", ratio < UnifiedReal.ONE)
        assertTrue(ratio > v("0.909") && ratio < v("0.910"))
    }

    // ------------------------------------------------------------ structural

    @Test
    fun `every dimension has at least two units and exactly one base unit`() {
        for (dimension in UnitCatalog.dimensions) {
            val units = UnitCatalog.unitsOf(dimension)
            assertTrue("$dimension has ${units.size} units", units.size >= 2)
            val bases = units.count {
                !it.inverse && it.offset.definitelyZero() && it.factor == UnifiedReal.ONE
            }
            assertEquals("$dimension should have exactly one base unit", 1, bases)
        }
    }

    @Test
    fun `every conversion factor is strictly positive`() {
        // A round trip is blind to a uniformly wrong factor — including a wrong *sign* —
        // so RoundTripTest cannot catch an overflowed constant. This can, for the whole
        // table at once, which is the point of asserting it structurally rather than
        // pinning one more decimal.
        for (unit in UnitCatalog.all) {
            assertTrue(
                "${unit.id} has factor ${unit.factor.toNiceString()}",
                unit.factor.signum() > 0,
            )
        }
    }

    @Test
    fun `every category opens on two different units of different sizes`() {
        val one = UnifiedReal.ONE
        for (dimension in UnitCatalog.dimensions) {
            val from = UnitCatalog.defaultFrom(dimension)
            val to = UnitCatalog.defaultTo(dimension)
            assertEquals("$dimension opens on a foreign unit", dimension, from.dimension)
            assertEquals("$dimension converts into a foreign unit", dimension, to.dimension)
            assertNotEquals("$dimension opens on one unit twice", from.id, to.id)
            // Volume used to open on millilitre → cubic centimetre, which are the same
            // size: typing 5 showed 5 in both fields, which reads as a broken screen.
            assertTrue(
                "$dimension opens on two names for the same size",
                UnitConverter.convert(one, from, to) != one,
            )
        }
    }

    @Test
    fun `ranking a dimension reorders it without losing or repeating a unit`() {
        for (dimension in UnitCatalog.dimensions) {
            val all = UnitCatalog.unitsOf(dimension)
            val popular = UnitCatalog.popularOf(dimension)
            assertEquals("$dimension changed size when ranked", all.size, popular.size)
            assertEquals("$dimension lost or repeated a unit", all.toSet(), popular.toSet())
        }
    }

    @Test
    fun `a road distance is offered road-sized units first`() {
        // The common-conversions strip has four slots. Declaration order is smallest-first,
        // so it spent all four on nanometres through centimetres — nothing a driver
        // converting miles to kilometres could use.
        val top = UnitCatalog.popularOf(Dimension.LENGTH).take(4).map { it.id }
        assertTrue("length leads with $top", top.contains("kilometre"))
        assertTrue("length leads with $top", top.contains("mile"))
        assertFalse("length still leads with $top", top.contains("nanometre"))
    }

    @Test
    fun `unit ids are unique across the whole catalogue`() {
        val ids = UnitCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the catalogue covers all sixteen advertised categories`() {
        assertEquals(16, UnitCatalog.dimensions.size)
        assertEquals(Dimension.entries.size, UnitCatalog.dimensions.size)
        for (dimension in Dimension.entries) {
            assertNotNull(UnitCatalog.unitsOf(dimension).firstOrNull())
        }
    }
}
