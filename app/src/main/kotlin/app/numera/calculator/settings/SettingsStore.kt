package app.numera.calculator.settings

import android.content.Context
import android.content.SharedPreferences
import app.numera.calculator.math.AngleMode
import app.numera.calculator.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The angle unit as the user picks it, and as it is written to preferences.
 *
 * Separate from [AngleMode] because that enum belongs to the maths module, whose constant
 * names (`DEGREES`, `RADIANS`) are an internal detail; persisting them would tie the
 * on-disk format to a module that has no business knowing about SharedPreferences.
 */
enum class AngleModeSetting {
    /** Degrees. */
    DEG,

    /** Radians. */
    RAD;

    /** @return the maths-module angle mode this setting selects. */
    fun toAngleMode(): AngleMode = when (this) {
        DEG -> AngleMode.DEGREES
        RAD -> AngleMode.RADIANS
    }

    companion object {
        /**
         * @param mode a maths-module angle mode.
         * @return the persisted setting that selects [mode].
         */
        fun from(mode: AngleMode): AngleModeSetting = when (mode) {
            AngleMode.DEGREES -> DEG
            AngleMode.RADIANS -> RAD
        }
    }
}

/**
 * The app's user preferences, exposed as [StateFlow]s.
 *
 * Plain [SharedPreferences] rather than DataStore: there are four scalar values, they are
 * read synchronously during the very first composition to pick a theme, and a suspending
 * read there would mean a frame of the wrong colours on every cold start.
 *
 * That synchronous read is a deliberate, permanent StrictMode disk-read violation. The four
 * accessors below run in property initialisers, each blocks in `awaitLoadedLocked()` until
 * `calculator_settings.xml` has been parsed, and the store is constructed on the main thread
 * during `MainActivity.onCreate`. Anyone adding `detectDiskReads().penaltyDeath()` to a debug
 * build must whitelist this constructor rather than make the read asynchronous: deferring it
 * reintroduces exactly the launch flash that `res/values/themes.xml` and this class exist to
 * prevent, and trades a sub-millisecond block for a visible one.
 *
 * Construct one per process and hand it around; it is not a singleton only so that tests
 * can build one over a test context. `MainActivity` keeps that single instance in a
 * process-scoped holder — a second store built per activity creation owns its own
 * `MutableStateFlow`s, so a retained view model would go on reading the old one's values
 * while the composition wrote to the new one's.
 *
 * @param context any context; the application context is retained, never the passed one.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode: MutableStateFlow<ThemeMode> = MutableStateFlow(readThemeMode())
    private val _dynamicColor: MutableStateFlow<Boolean> =
        MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_COLOR, DEFAULT_DYNAMIC_COLOR))
    private val _hapticsEnabled: MutableStateFlow<Boolean> =
        MutableStateFlow(prefs.getBoolean(KEY_HAPTICS, DEFAULT_HAPTICS))
    private val _angleMode: MutableStateFlow<AngleMode> =
        MutableStateFlow(readAngleMode().toAngleMode())

    /** How the app is themed. */
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** True when the palette is derived from the wallpaper instead of the app's own. */
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    /** True when key presses should tick. */
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    /** The angle unit trigonometric functions are evaluated in. */
    val angleMode: StateFlow<AngleMode> = _angleMode.asStateFlow()

    /**
     * Stores the theme preference.
     *
     * @param value the new theme mode. A no-op when it already holds.
     */
    fun setThemeMode(value: ThemeMode) {
        if (_themeMode.value == value) return
        _themeMode.value = value
        prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
    }

    /**
     * Stores the dynamic-colour preference.
     *
     * @param value true to follow the wallpaper. A no-op when it already holds.
     */
    fun setDynamicColor(value: Boolean) {
        if (_dynamicColor.value == value) return
        _dynamicColor.value = value
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
    }

    /**
     * Stores the haptics preference.
     *
     * @param value true to tick on key press. A no-op when it already holds.
     */
    fun setHaptics(value: Boolean) {
        if (_hapticsEnabled.value == value) return
        _hapticsEnabled.value = value
        prefs.edit().putBoolean(KEY_HAPTICS, value).apply()
    }

    /**
     * Stores the default angle mode.
     *
     * @param value the angle unit to evaluate trigonometry in. A no-op when unchanged.
     */
    fun setAngleMode(value: AngleMode) {
        if (_angleMode.value == value) return
        _angleMode.value = value
        prefs.edit().putString(KEY_ANGLE_MODE, AngleModeSetting.from(value).name).apply()
    }

    // Matched by name rather than by enumValueOf/valueOf, both of which throw
    // IllegalArgumentException on an unknown constant. A phone downgraded to an earlier
    // build, or a preferences file restored from a version with a constant this build no
    // longer has, would otherwise crash inside the constructor — before any UI exists to
    // report it and with no way for the user to get past the crash except clearing data.
    private fun readThemeMode(): ThemeMode {
        val stored: String? = prefs.getString(KEY_THEME_MODE, null)
        return enumValues<ThemeMode>().firstOrNull { it.name == stored } ?: DEFAULT_THEME_MODE
    }

    private fun readAngleMode(): AngleModeSetting {
        val stored: String? = prefs.getString(KEY_ANGLE_MODE, null)
        return enumValues<AngleModeSetting>().firstOrNull { it.name == stored }
            ?: DEFAULT_ANGLE_MODE
    }

    private companion object {
        const val PREFS_NAME: String = "calculator_settings"

        const val KEY_THEME_MODE: String = "theme_mode"
        const val KEY_DYNAMIC_COLOR: String = "dynamic_color"
        const val KEY_HAPTICS: String = "haptics_enabled"
        const val KEY_ANGLE_MODE: String = "angle_mode"

        val DEFAULT_THEME_MODE: ThemeMode = ThemeMode.SYSTEM

        // Off by default: the app ships with a palette tuned for a keypad, and wallpaper
        // colours routinely put an operator key and a digit key at the same tone.
        const val DEFAULT_DYNAMIC_COLOR: Boolean = false
        const val DEFAULT_HAPTICS: Boolean = true

        // Degrees, matching every pocket calculator and Google Calculator's own default.
        val DEFAULT_ANGLE_MODE: AngleModeSetting = AngleModeSetting.DEG
    }
}
