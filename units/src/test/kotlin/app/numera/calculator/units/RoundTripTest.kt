package app.numera.calculator.units

import app.numera.calculator.math.BoundedRational
import app.numera.calculator.math.UnifiedReal
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Converting there and back must return the identical value, for every pair of units.
 *
 * This is the property that exact rationals buy and floating point cannot: an inch → cm →
 * inch round trip in doubles drifts in the last bits, and after a few hops through a
 * converter the user watches their number decay. Asserting it across the entire catalogue
 * is also the cheapest possible check that no factor was mistyped, because a wrong factor
 * that still round-trips is a much rarer mistake than one that does not.
 */
class RoundTripTest {

    private val samples = listOf("1", "7", "0.5", "-3.25", "1000", "0.001")

    @Test
    fun `every unit pair round trips exactly`() {
        var pairs = 0
        for (dimension in UnitCatalog.dimensions) {
            val units = UnitCatalog.unitsOf(dimension)
            for (from in units) {
                for (to in units) {
                    for (sample in samples) {
                        val start = UnifiedReal.of(BoundedRational.parse(sample))
                        // A reciprocal unit is undefined at zero, and our samples avoid it.
                        val there = UnitConverter.convert(start, from, to)
                        val back = UnitConverter.convert(there, to, from)
                        assertTrue(
                            "${from.id} → ${to.id} → ${from.id} on $sample gave " +
                                back.toNiceString(),
                            back.isComparable(start) && back.compareTo(start) == 0,
                        )
                        pairs++
                    }
                }
            }
        }
        // Guards against the loop silently covering nothing if the catalogue empties.
        assertTrue("only $pairs round trips ran", pairs > 5000)
    }

    @Test
    fun `chaining several conversions does not accumulate drift`() {
        // The failure this catches is subtle: each hop is individually within tolerance,
        // and the error only becomes visible after a handful of them.
        val start = UnifiedReal.of(BoundedRational.parse("1"))
        val chain = listOf("inch", "centimetre", "foot", "metre", "yard", "millimetre", "inch")
        var value = start
        for (index in 0 until chain.size - 1) {
            val from = requireNotNull(UnitCatalog.byId(chain[index]))
            val to = requireNotNull(UnitCatalog.byId(chain[index + 1]))
            value = UnitConverter.convert(value, from, to)
        }
        assertTrue(
            "after ${chain.size - 1} hops the value became ${value.toNiceString()}",
            value.isComparable(start) && value.compareTo(start) == 0,
        )
    }
}
