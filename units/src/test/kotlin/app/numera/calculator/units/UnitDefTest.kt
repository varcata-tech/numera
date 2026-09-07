package app.numera.calculator.units

import app.numera.calculator.math.BoundedRational
import app.numera.calculator.math.UnifiedReal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The one mistake the dimension model permits that no other test in this module can see.
 *
 * [UnitConverter]'s two inverse branches evaluate `factor / value` and never read
 * [UnitDef.offset], so a unit declared with both `inverse = true` and a non-zero offset is
 * converted as though the offset were not there. The omission is *symmetric*, which is what
 * makes it invisible: [RoundTripTest] asserts only `a → b → a`, so a conversion that drops
 * the same term in both directions round-trips exactly while every number it produces is
 * wrong by that term. [UnitCatalogTest]'s structural checks read `offset` solely to count
 * base units and `factor` solely for its sign, so they cannot see it either.
 *
 * The guard therefore has to live in the constructor, where the category fails to load at
 * all rather than shipping with a whole dimension quietly off by an additive constant.
 */
class UnitDefTest {

    private fun v(text: String): UnifiedReal = UnifiedReal.of(BoundedRational.parse(text))

    @Test
    fun `an inverse unit carrying an offset is refused at construction`() {
        try {
            UnitDef(
                id = "litre_per_100km_at_reference",
                dimension = Dimension.FUEL_ECONOMY,
                factor = v("100"),
                offset = v("5"),
                inverse = true,
            )
            fail("an affine reciprocal unit was accepted; UnitConverter would ignore its offset")
        } catch (e: IllegalArgumentException) {
            val message: String = e.message.orEmpty()
            assertTrue("unhelpful message: $message", message.contains("inverse"))
            assertTrue("message does not name the unit: $message", message.contains("litre_per"))
        }
    }

    @Test
    fun `a reciprocal unit with no offset is still allowed`() {
        // The shape the catalogue actually uses. A guard that also rejected this would take
        // the whole fuel-economy dimension down with it.
        val unit = UnitDef(
            id = "litre_per_100km",
            dimension = Dimension.FUEL_ECONOMY,
            factor = v("100"),
            inverse = true,
        )
        assertTrue(unit.offset.definitelyZero())
    }

    @Test
    fun `an affine unit that is not reciprocal is still allowed`() {
        // Temperature is the reason `offset` exists at all; the guard must not touch it.
        val celsius = UnitDef(
            id = "celsius",
            dimension = Dimension.TEMPERATURE,
            factor = UnifiedReal.ONE,
            offset = v("273.15"),
        )
        assertEquals(0, UnitConverter.toBase(v("0"), celsius).compareTo(v("273.15")))
    }

    @Test
    fun `the shipped catalogue satisfies the guard`() {
        // Loading the object at all would have thrown, but asserting it explicitly says what
        // the guard is for to anyone adding a unit rather than leaving it to a stack trace.
        for (unit in UnitCatalog.all) {
            assertTrue(
                "${unit.id} is both inverse and affine",
                !unit.inverse || unit.offset.definitelyZero(),
            )
        }
    }
}
