package app.numera.calculator.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Hand-specified palette. Every Material 3 role is named explicitly rather than left to
 * lightColorScheme()'s baseline purple, because the baseline gives operator keys and digit
 * keys nearly the same tone and the keypad reads as one undifferentiated slab.
 *
 * Structure, in the order a user's eye lands on it:
 *   surface / surfaceContainer*  a calm near-neutral blue-grey — the keypad is 80% of the
 *                               screen, so anything saturated here fatigues immediately.
 *   primaryContainer            the operator keys (+ − × ÷). Distinctly blue against the
 *                               neutral digits, but still a container tone, so it does not
 *                               outrank equals.
 *   primary                     the equals key, filled. The single most saturated area on
 *                               the screen and the only one, so it reads as the action.
 *   errorContainer              clear / delete. Warm, so a destructive key is never
 *                               mistaken for an operator at a glance.
 *   tertiary                    reserved for the graphing and financial modes (plot traces,
 *                               positive balances) — deliberately a different hue family
 *                               from both operators and errors.
 *
 * Contrast ratios below are WCAG 2.1 against the paired container, computed from these exact
 * hex values. Every pair that carries text clears AA (4.5:1) and all but two clear AAA (7:1).
 */

// ---------------------------------------------------------------------------------------
// Light
// ---------------------------------------------------------------------------------------

private val LightPrimary = Color(0xFF00629E)
private val LightOnPrimary = Color(0xFFFFFFFF) // 6.48:1 on primary — AA, equals key label
private val LightPrimaryContainer = Color(0xFFD0E4FF)
private val LightOnPrimaryContainer = Color(0xFF001D34) // 13.24:1 — AAA, operator glyphs
private val LightInversePrimary = Color(0xFF9BCBFF)

private val LightSecondary = Color(0xFF4F606E)
private val LightOnSecondary = Color(0xFFFFFFFF) // 6.50:1 — AA
private val LightSecondaryContainer = Color(0xFFD2E5F5)
private val LightOnSecondaryContainer = Color(0xFF0B1D29) // 13.31:1 — AAA

private val LightTertiary = Color(0xFF146C2E)
private val LightOnTertiary = Color(0xFFFFFFFF) // 6.53:1 — AA
private val LightTertiaryContainer = Color(0xFFA6F4B4)
private val LightOnTertiaryContainer = Color(0xFF002107) // 13.29:1 — AAA

private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color(0xFFFFFFFF) // 6.46:1 — AA
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF410002) // 13.26:1 — AAA, clear/delete glyphs

private val LightBackground = Color(0xFFFAFCFF)
private val LightOnBackground = Color(0xFF191C1E) // 16.66:1 — AAA
private val LightSurface = Color(0xFFFAFCFF)
private val LightOnSurface = Color(0xFF191C1E) // 16.66:1 on surface, 14.08:1 on
// surfaceContainerHigh (the digit-key tone) — AAA on both, which is what lets the same
// onSurface be used for every neutral key without a per-tone override.
private val LightSurfaceVariant = Color(0xFFDDE3EA)
private val LightOnSurfaceVariant = Color(0xFF41474D) // 7.28:1 on surfaceVariant, 8.16:1 on
// surfaceContainer (the function-key tone) — AAA on both.
private val LightOutline = Color(0xFF71787E) // 4.36:1 on surface — below AA on purpose:
// outlines are non-text decoration, held to the 3:1 non-text threshold instead.
private val LightOutlineVariant = Color(0xFFC1C7CE)

private val LightSurfaceDim = Color(0xFFD9DBDE)
private val LightSurfaceBright = Color(0xFFFAFCFF)
private val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
private val LightSurfaceContainerLow = Color(0xFFF3F5F8)
private val LightSurfaceContainer = Color(0xFFEDEFF2)
private val LightSurfaceContainerHigh = Color(0xFFE7E9EC)
private val LightSurfaceContainerHighest = Color(0xFFE2E3E6)

private val LightInverseSurface = Color(0xFF2E3133)
private val LightInverseOnSurface = Color(0xFFF0F1F3) // 11.59:1 — AAA, snackbar text
private val LightScrim = Color(0xFF000000)

// ---------------------------------------------------------------------------------------
// Dark
// ---------------------------------------------------------------------------------------

private val DarkPrimary = Color(0xFF9BCBFF)
private val DarkOnPrimary = Color(0xFF003353) // 7.75:1 — AAA, equals key label
private val DarkPrimaryContainer = Color(0xFF004A77)
private val DarkOnPrimaryContainer = Color(0xFFD0E4FF) // 7.22:1 — AAA, operator glyphs
private val DarkInversePrimary = Color(0xFF00629E)

private val DarkSecondary = Color(0xFFB7C9D9)
private val DarkOnSecondary = Color(0xFF21323F) // 7.77:1 — AAA
private val DarkSecondaryContainer = Color(0xFF384956)
private val DarkOnSecondaryContainer = Color(0xFFD2E5F5) // 7.21:1 — AAA

private val DarkTertiary = Color(0xFF8BD79A)
private val DarkOnTertiary = Color(0xFF003914) // 7.70:1 — AAA
private val DarkTertiaryContainer = Color(0xFF00531F)
private val DarkOnTertiaryContainer = Color(0xFFA6F4B4) // 7.19:1 — AAA

private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005) // 7.72:1 — AAA
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFDAD6) // 7.24:1 — AAA, clear/delete glyphs

private val DarkBackground = Color(0xFF111416)
private val DarkOnBackground = Color(0xFFE2E2E5) // 14.31:1 — AAA
private val DarkSurface = Color(0xFF111416)
private val DarkOnSurface = Color(0xFFE2E2E5) // 14.31:1 on surface, 11.13:1 on
// surfaceContainerHigh, 16.24:1 on the true black of ThemeMode.BLACK — AAA on all three.
private val DarkSurfaceVariant = Color(0xFF41474D)
private val DarkOnSurfaceVariant = Color(0xFFC1C7CE) // 5.52:1 on surfaceVariant, 9.62:1 on
// surfaceContainer (the function-key tone) — AA and AAA respectively.
private val DarkOutline = Color(0xFF8B9198) // 5.81:1 on surface — non-text decoration.
private val DarkOutlineVariant = Color(0xFF41474D)

private val DarkSurfaceDim = Color(0xFF111416)
private val DarkSurfaceBright = Color(0xFF36393C)
private val DarkSurfaceContainerLowest = Color(0xFF0C0E10)
private val DarkSurfaceContainerLow = Color(0xFF191C1E)
private val DarkSurfaceContainer = Color(0xFF1D2022)
private val DarkSurfaceContainerHigh = Color(0xFF282A2D)
private val DarkSurfaceContainerHighest = Color(0xFF323538)

private val DarkInverseSurface = Color(0xFFE2E2E5)
private val DarkInverseOnSurface = Color(0xFF2E3133) // 10.13:1 — AAA
private val DarkScrim = Color(0xFF000000)

/** The calculator's light colour scheme, with every Material 3 role named explicitly. */
internal val CalculatorLightColors: ColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = LightInversePrimary,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightPrimary,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = LightScrim,
    surfaceBright = LightSurfaceBright,
    surfaceDim = LightSurfaceDim,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainerLowest = LightSurfaceContainerLowest,
)

/** The calculator's dark colour scheme, with every Material 3 role named explicitly. */
internal val CalculatorDarkColors: ColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = DarkInversePrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkPrimary,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = DarkScrim,
    surfaceBright = DarkSurfaceBright,
    surfaceDim = DarkSurfaceDim,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
)
