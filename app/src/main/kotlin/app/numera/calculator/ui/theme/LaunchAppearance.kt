package app.numera.calculator.ui.theme

/**
 * How the window must be painted for the frames that exist before Compose does.
 *
 * The starting window is drawn from `res/values/themes.xml` or `res/values-night/themes.xml`,
 * and the platform picks between those two buckets by the *system* night setting alone — it
 * has no way to read the app's own [ThemeMode], which lives in SharedPreferences and is not
 * loaded until the process starts. A user who forces Light on a dark-mode phone therefore
 * gets a near-black window with light status-bar icons, and then a full-screen step to
 * near-white once the first Compose frame lands.
 *
 * Kept as its own Android-free type so the mapping can be unit tested: it is three lines of
 * logic that nothing else on the launch path would notice going wrong, on a screen the user
 * only ever sees for a few frames.
 */
enum class LaunchAppearance {

    /** The light palette's window background. */
    LIGHT,

    /** The dark palette's window background. */
    DARK,

    /** True black, for [ThemeMode.BLACK]. */
    BLACK;

    /**
     * True when the system bars must draw their icons dark, because the window behind them
     * is light. Wrong in either direction leaves the clock and battery invisible.
     */
    val lightSystemBars: Boolean get() = this == LIGHT
}

/**
 * Resolves what the window should look like before the first Compose frame.
 *
 * @param themeMode the user's stored preference.
 * @param systemDark true when the system is in night mode, which is the only thing the
 *   resource buckets themselves can see.
 * @return the appearance to paint the window and the system bars with.
 */
fun launchAppearanceOf(themeMode: ThemeMode, systemDark: Boolean): LaunchAppearance =
    when (themeMode) {
        ThemeMode.SYSTEM -> if (systemDark) LaunchAppearance.DARK else LaunchAppearance.LIGHT
        ThemeMode.LIGHT -> LaunchAppearance.LIGHT
        ThemeMode.DARK -> LaunchAppearance.DARK
        // Not DARK: the OLED preference is a true-black background, and starting from
        // #111416 would put a visible step on the launch of the one theme whose whole
        // purpose is that those pixels are off.
        ThemeMode.BLACK -> LaunchAppearance.BLACK
    }
