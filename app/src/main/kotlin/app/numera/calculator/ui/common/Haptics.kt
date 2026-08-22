package app.numera.calculator.ui.common

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the user has key haptics switched on.
 *
 * Provided once at the top of the app from `SettingsStore`. It is a composition local
 * rather than a [CalcButton] parameter because [CalcButton]'s signature is fixed by the
 * cross-module contract, and threading the flag through every keypad by hand would mean
 * one keypad eventually forgets it and buzzes for a user who turned haptics off.
 */
val LocalHapticsEnabled: ProvidableCompositionLocal<Boolean> = compositionLocalOf { true }

/**
 * Fires the standard key-press tick on [this] view.
 *
 * [HapticFeedbackConstants.KEYBOARD_TAP] is used rather than Compose's
 * `LocalHapticFeedback`, which only offers long-press and text-handle constants; a key
 * press given the long-press buzz feels like a mis-hit.
 *
 * No `FLAG_IGNORE_GLOBAL_SETTING` is passed, so the platform silently drops the call when
 * the user has turned touch feedback off system-wide. Passing that flag would let an
 * in-app toggle override a system accessibility choice.
 */
internal fun View.performKeyTap() {
    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

/**
 * Fires the long-press tick on [this] view.
 *
 * Distinct from [performKeyTap] on purpose: a long press that feels identical to a tap
 * gives the user no way to tell that the secondary action, not the primary one, fired.
 */
internal fun View.performLongPressTick() {
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}
