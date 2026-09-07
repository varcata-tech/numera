package app.numera.calculator.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the window must look like before Compose has drawn anything.
 *
 * The starting window is painted from `res/values/themes.xml` or `res/values-night`, and the
 * platform chooses between those buckets by the system night setting alone — it cannot read
 * the app's own [ThemeMode]. Every case below is one where the two disagree, and the visible
 * result of getting it wrong is a full-screen colour step, plus a frame or two of status-bar
 * icons the same colour as the bar behind them, on every cold launch.
 *
 * None of it is reachable from a unit test of the activity and none of it survives a
 * screenshot taken a moment too late, so the mapping itself is pinned here.
 */
class LaunchAppearanceTest {

    @Test
    fun `forcing light on a dark-mode phone launches light, not into the night bucket`() {
        // The largest step there is: #111416 to #FAFCFF across the whole window.
        assertEquals(
            LaunchAppearance.LIGHT,
            launchAppearanceOf(ThemeMode.LIGHT, systemDark = true),
        )
    }

    @Test
    fun `forcing dark on a light-mode phone launches dark`() {
        assertEquals(
            LaunchAppearance.DARK,
            launchAppearanceOf(ThemeMode.DARK, systemDark = false),
        )
    }

    @Test
    fun `the OLED theme launches true black, never the ordinary dark background`() {
        // #111416 is not #000000, and the whole point of the preference is that those pixels
        // are off — a launch that starts one shade up is the one flash an OLED user notices.
        assertEquals(
            LaunchAppearance.BLACK,
            launchAppearanceOf(ThemeMode.BLACK, systemDark = true),
        )
        assertEquals(
            LaunchAppearance.BLACK,
            launchAppearanceOf(ThemeMode.BLACK, systemDark = false),
        )
    }

    @Test
    fun `following the system is the only mode the night setting decides`() {
        assertEquals(
            LaunchAppearance.DARK,
            launchAppearanceOf(ThemeMode.SYSTEM, systemDark = true),
        )
        assertEquals(
            LaunchAppearance.LIGHT,
            launchAppearanceOf(ThemeMode.SYSTEM, systemDark = false),
        )
        // Every other mode ignores it, in both directions.
        listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.BLACK).forEach { mode ->
            assertEquals(
                "$mode must not depend on the system night setting",
                launchAppearanceOf(mode, systemDark = true),
                launchAppearanceOf(mode, systemDark = false),
            )
        }
    }

    @Test
    fun `only the light appearance asks for dark system-bar icons`() {
        // Inverted, the clock and battery are drawn in the same colour as the bar behind
        // them and simply vanish for the first frames of the launch.
        assertTrue(LaunchAppearance.LIGHT.lightSystemBars)
        assertFalse(LaunchAppearance.DARK.lightSystemBars)
        assertFalse(LaunchAppearance.BLACK.lightSystemBars)
    }
}
