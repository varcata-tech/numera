package app.numera.calculator.feature.converter

import app.numera.calculator.R
import app.numera.calculator.units.UnitCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every unit in the catalogue must have a name and a symbol here.
 *
 * [UnitNames] is an exhaustive `when` written by hand — deliberately, because a lookup
 * through `resources.getIdentifier` is invisible to the resource shrinker and would strip
 * every unit name out of the release build. The cost of writing it by hand is that adding a
 * unit to `:units` and forgetting to add its two arms here compiles, ships, and shows the
 * user "Unknown unit" on the one screen whose whole job is naming quantities.
 *
 * Nothing in `:units` can catch that: the mapping lives in `:app` and the catalogue knows
 * nothing about it. Nothing on a device catches it either, because the fallback only appears
 * for the unit that was forgotten, in whichever category it was added to.
 *
 * These are plain JVM assertions — `R` fields are ints, so no device and no Robolectric.
 */
class UnitNamesTest {

    @Test
    fun `every catalogued unit has a display name`() {
        val missing: List<String> = UnitCatalog.all
            .filter { UnitNames.nameRes(it.id) == R.string.unit_unknown }
            .map { it.id }
        assertEquals("units with no name in UnitNames: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `every catalogued unit has a symbol`() {
        val missing: List<String> = UnitCatalog.all
            .filter { UnitNames.symbolRes(it.id) == R.string.sym_unknown }
            .map { it.id }
        assertEquals("units with no symbol in UnitNames: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `an id the catalogue does not define falls back rather than throwing`() {
        // The fallback still has to work: a saved-state bundle from an older build can name a
        // unit this build has dropped, and the converter draws that row before deciding what
        // to do about it. It must also be an obvious placeholder — the fallback was once
        // R.string.app_name, which drew the missing unit as the app's own name.
        assertEquals(R.string.unit_unknown, UnitNames.nameRes("flux_capacitor"))
        assertEquals(R.string.sym_unknown, UnitNames.symbolRes("flux_capacitor"))
    }

    @Test
    fun `no two units share a name or a symbol resource`() {
        // A copy-pasted arm pointing two units at one resource is the other half of the same
        // mistake, and it is worse than the fallback: the row is captioned plausibly, with the
        // wrong unit's name, and nothing looks broken. The imperial and US gallons are the
        // pair this would happen to.
        val names: List<Int> = UnitCatalog.all.map { UnitNames.nameRes(it.id) }
        val symbols: List<Int> = UnitCatalog.all.map { UnitNames.symbolRes(it.id) }
        assertEquals("two units share one name resource", names.size, names.toSet().size)
        assertEquals("two units share one symbol resource", symbols.size, symbols.toSet().size)
    }

    @Test
    fun `every category has a label`() {
        // dimensionRes is an exhaustive `when` over the enum, so the compiler already refuses
        // a missing arm; this asserts the other half, that none of them fell back to zero.
        for (dimension in UnitCatalog.dimensions) {
            assertNotEquals("$dimension has no label", 0, UnitNames.dimensionRes(dimension))
        }
        assertTrue(UnitCatalog.dimensions.isNotEmpty())
    }
}
