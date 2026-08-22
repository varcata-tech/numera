package app.numera.calculator.feature.converter

import app.numera.calculator.units.Dimension
import app.numera.calculator.units.UnitCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the converter opens on after the system has killed and rebuilt the process.
 *
 * The converter used to be the only StateFlow screen in the app without a `SavedStateHandle`,
 * so a backgrounded app that the system reclaimed came back on Length, metre → foot, having
 * thrown away the category and the pair of units the user had chosen.
 *
 * Restoring introduces a failure of its own, and it is the reason this decision is a separate
 * function rather than four lines inside the view model: `UnitConverter.convert` *requires*
 * both units to share a dimension and throws [IllegalArgumentException] when they do not.
 * Nothing in the conversion job catches that, so a bundle whose ids no longer agree with its
 * category would take the process down instead of merely showing the wrong screen.
 */
class ConverterRestoreTest {

    @Test
    fun `a saved category and unit pair come back exactly`() {
        val restored = restoredSelection("PRESSURE", "psi", "atmosphere")
        assertEquals(Dimension.PRESSURE, restored.dimension)
        assertEquals("psi", restored.fromUnit.id)
        assertEquals("atmosphere", restored.toUnit.id)
    }

    @Test
    fun `a unit that no longer belongs to the saved category is refused`() {
        // A pair from two dimensions is what a bundle written before a unit moved category
        // looks like, and converting across it throws out of a coroutine that expects only
        // arithmetic failures. Falling back to the category's own defaults keeps the screen
        // openable and keeps every later conversion well defined.
        val restored = restoredSelection("PRESSURE", "metre", "celsius")
        assertEquals(Dimension.PRESSURE, restored.dimension)
        assertEquals(UnitCatalog.defaultFrom(Dimension.PRESSURE), restored.fromUnit)
        assertEquals(UnitCatalog.defaultTo(Dimension.PRESSURE), restored.toUnit)
    }

    @Test
    fun `a category name this build no longer defines opens on length`() {
        // Matched by name and never by ordinal, so inserting a category into Dimension in a
        // later release cannot silently reopen a saved Mass screen as an Area one. A name
        // that has gone away falls back, because a screen that refuses to open at all is the
        // worse of the two failures.
        val restored = restoredSelection("LUMINOUS_INTENSITY", "candela", "lumen")
        assertEquals(Dimension.LENGTH, restored.dimension)
        assertEquals(UnitCatalog.defaultFrom(Dimension.LENGTH), restored.fromUnit)
        assertEquals(UnitCatalog.defaultTo(Dimension.LENGTH), restored.toUnit)
    }

    @Test
    fun `an empty bundle opens on exactly what a fresh screen opens on`() {
        // The first launch after an install has nothing saved, so restoring must be
        // indistinguishable from not restoring at all.
        val restored = restoredSelection(null, null, null)
        val fresh = ConverterUiState()
        assertEquals(fresh.dimension, restored.dimension)
        assertEquals(fresh.fromUnit, restored.fromUnit)
        assertEquals(fresh.toUnit, restored.toUnit)
    }
}
