package app.numera.calculator.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * What must survive an activity being destroyed, and what must not be mistaken for nothing.
 *
 * The activity declares no `configChanges`, so a rotation, a system dark-mode toggle, a
 * per-app language change and a split-screen resize all destroy and rebuild it. Before this
 * was saved, every one of those threw the user back to the launch route: someone half way
 * through the loan calculator landed on the plain calculator, and someone who had entered
 * through the converter shortcut was dragged back into the converter mid-calculation.
 *
 * None of it is visible from a screenshot, and none of it can be reached at all from a debug
 * install, which is why it is pinned here instead.
 */
class CalculatorAppStateTest {

    @Test
    fun `the route the user is on survives being saved and restored`() {
        val state = CalculatorAppState(Route.Calculator)
        state.navigateTo(Route.Financial)

        val restored: CalculatorAppState = CalculatorAppState.restoreFrom(state.saveToken())

        // Not Route.Calculator: the launch intent describes where the user entered, and a
        // recreation must not confuse that with where they are.
        assertSame(Route.Financial, restored.route.value)
    }

    @Test
    fun `every route round-trips through its saved token`() {
        Route.all.forEach { route ->
            val state = CalculatorAppState(route)
            assertSame(route, CalculatorAppState.restoreFrom(state.saveToken()).route.value)
        }
    }

    @Test
    fun `a token naming a mode this build no longer has restores the calculator`() {
        // A saved-instance bundle outlives the upgrade that dropped a mode, and a released
        // calculator crashing on restore is worse than one that opens as a calculator.
        assertSame(Route.Calculator, CalculatorAppState.restoreFrom("hyperbolic").route.value)
        assertSame(Route.Calculator, CalculatorAppState.restoreFrom(null).route.value)
        assertSame(Route.Calculator, CalculatorAppState.restoreFrom("").route.value)
    }

    @Test
    fun `saved tokens are the ids pinned into launcher shortcuts, not enum ordinals`() {
        // Route.id is persisted data twice over: in the saved-instance bundle and in a
        // shortcut a user pinned to their home screen. Renaming one breaks both.
        assertEquals("calculator", CalculatorAppState(Route.Calculator).saveToken())
        assertEquals("financial", CalculatorAppState(Route.Financial).saveToken())
        Route.all.forEach { route ->
            assertSame(route, Route.fromId(route.id))
        }
        assertNull(Route.fromId("Financial"))
    }

    @Test
    fun `asking for a route the user has since backed out of moves there again`() {
        // The second tap of the same launcher shortcut. Modelled as a state value the two
        // requests are structurally equal and the second one looks like no request at all,
        // so the app used to come to the foreground still showing the calculator.
        val state = CalculatorAppState(Route.Converter)
        state.backToCalculator()
        assertSame(Route.Calculator, state.route.value)

        state.navigateTo(Route.Converter)

        assertSame(Route.Converter, state.route.value)
    }
}
