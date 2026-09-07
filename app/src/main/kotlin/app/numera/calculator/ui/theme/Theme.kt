package app.numera.calculator.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * How the user wants the app themed, independent of what the system is doing.
 *
 * [BLACK] is not the same as [DARK]: it forces a true #000000 background so an OLED panel
 * powers those pixels down entirely, which matters for an app that is mostly background.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK, BLACK }

private val LightColors: ColorScheme = CalculatorLightColors
private val DarkColors: ColorScheme = CalculatorDarkColors

/**
 * Collapses the roles that are drawn *behind* everything else to true black.
 *
 * Applied on top of whichever dark scheme is in play — including a dynamic one — so the
 * OLED preference keeps working when the user has also opted into wallpaper colours.
 *
 * Every container role that something is drawn *on* is deliberately left at the scheme's own
 * near-black. A key is a `Modifier.background` on a clipped box with no outline and no
 * elevation (see `CalcButton`), so a container tone equal to the screen behind it does not
 * read as a dimmer key — it disappears. `surfaceContainer` is the function-key tone, and
 * flattening it turned all fifteen keys of the scientific pad into floating labels with no
 * edge and no visible ripple bounds; `surfaceContainerLow` is the history drawer's panel and
 * the converter's inactive value card, which lost their edges the same way.
 * `surfaceContainerHigh` and above, the digit-key tone, were already excluded for this reason.
 *
 * What remains flattened is the background proper, which is the large majority of the pixels
 * on screen and the whole point of the preference.
 */
private fun ColorScheme.onOled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
)

/**
 * Applies the calculator's colour scheme and type scale to [content].
 *
 * @param themeMode the user's explicit choice, or [ThemeMode.SYSTEM] to follow the system.
 * @param dynamicColor true to derive the scheme from the wallpaper instead of the
 *   hand-tuned palette in `Color.kt`.
 * @param content the app, drawn inside the resulting [MaterialTheme].
 */
@Composable
fun CalculatorTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    // Off by default. Google Calculator's identity is its own palette; wallpaper colours
    // are something the user opts into rather than something the app assumes.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }

    val context = LocalContext.current
    // minSdk is 31, so the dynamic-colour APIs are always present; the flag is a user
    // preference rather than a version check.
    val scheme = when {
        dynamicColor && dark -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }.let { if (themeMode == ThemeMode.BLACK) it.onOled() else it }

    val view = LocalView.current
    if (!view.isInEditMode) {
        // Reapplied on every scheme change, not once in onCreate: switching to BLACK while
        // the app is open otherwise leaves the status-bar icons in the previous polarity.
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = scheme.background.luminance() > 0.5f
                isAppearanceLightNavigationBars = scheme.background.luminance() > 0.5f
            }
        }
    }

    val typography: Typography = calculatorTypography()
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
