package app.numera.calculator.settings

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The one [SettingsStore] for the process.
 *
 * A composition local rather than a parameter because every feature screen's signature is
 * fixed by the cross-module contract and none of them take a store. It has to be a single
 * shared instance: each [SettingsStore] owns its own `MutableStateFlow`s, so a second one
 * built inside the settings screen would accept a theme change that the theme wrapper
 * above it never hears about.
 *
 * `staticCompositionLocalOf` because the store reference itself never changes — only the
 * flows inside it do — and a dynamic local would invalidate every reader on each write.
 */
val LocalSettingsStore: ProvidableCompositionLocal<SettingsStore> = staticCompositionLocalOf {
    error("LocalSettingsStore was read outside CalculatorTheme; provide it in MainActivity.")
}
