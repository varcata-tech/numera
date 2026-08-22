package app.numera.calculator.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.numera.calculator.R

/**
 * Reads a size from `dimens.xml` and returns it as an `sp` value.
 *
 * The dimension is declared in `dp` and re-labelled as `sp` here rather than declared in
 * `sp` directly: [dimensionResource] resolves an `sp` dimension through the *scaled*
 * density and then divides by the plain density to make a [androidx.compose.ui.unit.Dp],
 * so the font scale is already baked in. Converting that back to `sp` applies the user's
 * font scale a second time, and at a 2.0 accessibility scale the result display comes out
 * four times its intended size and overflows the screen.
 */
@Composable
private fun spDimen(id: Int): TextUnit = dimensionResource(id).value.sp

/**
 * Trims the extra leading Material puts above and below a line.
 *
 * Without this a 60sp result sits visibly off-centre in its row, because the default line
 * height reserves ascender space for glyphs that digits never use.
 */
private val TightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

/**
 * The calculator's type scale.
 *
 * This is a `@Composable` function rather than a top-level `val` so the sizes come from
 * `dimens.xml`: a locale whose digits are wider than Latin ones (or a `values-sw600dp`
 * bucket) can override `calc_text_*` and change the whole scale without touching Kotlin.
 *
 * Roles are repurposed for a calculator, where the numbers are the interface:
 *  - `displayLarge`  the committed result
 *  - `displayMedium` the live result preview — the default of [ScrollableValueText]
 *  - `displaySmall`  the formula being typed
 *  - `headlineLarge` a digit or operator key label
 *  - `headlineMedium` a secondary/function key label
 * Everything else keeps the Material 3 baseline, which is correct for the settings and
 * converter screens.
 *
 * @return a [Typography] whose display and headline roles are sized for a calculator.
 */
@Composable
fun calculatorTypography(): Typography {
    val base = Typography()

    val displayLarge: TextUnit = spDimen(R.dimen.calc_text_display_large)
    val displayMedium: TextUnit = spDimen(R.dimen.calc_text_display_medium)
    val displaySmall: TextUnit = spDimen(R.dimen.calc_text_display_small)
    val headlineLarge: TextUnit = spDimen(R.dimen.calc_text_headline_large)
    val headlineMedium: TextUnit = spDimen(R.dimen.calc_text_headline_medium)

    return base.copy(
        displayLarge = base.displayLarge.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Light,
            fontSize = displayLarge,
            lineHeight = displayLarge * 1.12f,
            // Negative tracking on a light 60sp numeral; the Material default of -0.25sp is
            // tuned for 57sp headlines and leaves digit groups looking loose.
            letterSpacing = (-0.5).sp,
            lineHeightStyle = TightLineHeight,
        ),
        displayMedium = base.displayMedium.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Light,
            fontSize = displayMedium,
            lineHeight = displayMedium * 1.12f,
            letterSpacing = (-0.25).sp,
            lineHeightStyle = TightLineHeight,
        ),
        displaySmall = base.displaySmall.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = displaySmall,
            lineHeight = displaySmall * 1.15f,
            letterSpacing = 0.sp,
            lineHeightStyle = TightLineHeight,
        ),
        headlineLarge = base.headlineLarge.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = headlineLarge,
            lineHeight = headlineLarge * 1.15f,
            letterSpacing = 0.sp,
            lineHeightStyle = TightLineHeight,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = headlineMedium,
            lineHeight = headlineMedium * 1.15f,
            letterSpacing = 0.sp,
            lineHeightStyle = TightLineHeight,
        ),
    )
}
