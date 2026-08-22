package app.numera.calculator.data

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The one [HistoryStore] for the process.
 *
 * A composition local for the same reason as the settings store: every feature screen's
 * signature is fixed by the navigation contract and none of them take a store. It must be a
 * single shared instance, because the store owns the `StateFlow` the drawer observes — a
 * second instance built somewhere else would insert rows that the open list never hears about.
 */
val LocalHistoryStore: ProvidableCompositionLocal<HistoryStore> = staticCompositionLocalOf {
    error("LocalHistoryStore was read outside CalculatorTheme; provide it in MainActivity.")
}
