package app.numera.calculator.feature.calc

import app.numera.calculator.math.AngleMode

/** Which of the three things the display is currently showing. */
enum class DisplayMode {
    /** The user is typing. Formula on top, a small live preview underneath. */
    INPUT,

    /** Equals has been pressed. The result is the large line and can be scrolled. */
    RESULT,

    /** Evaluation failed. The error replaces the result. */
    ERROR,
}

/**
 * Everything the calculator screen draws, and nothing else.
 *
 * Deliberately all strings rather than [app.numera.calculator.math.UnifiedReal]s: formatting
 * needs a locale and a character budget that only the composable knows, and doing it here
 * once per state change keeps it off the frame path.
 */
data class CalculatorUiState(
    val formula: String = "",
    /** Live result while typing. Empty when suppressed — see the rules in the view model. */
    val preview: String = "",
    val result: String = "",
    /** Set only in [DisplayMode.ERROR]; a string resource id. */
    val errorRes: Int? = null,
    val mode: DisplayMode = DisplayMode.INPUT,
    val angleMode: AngleMode = AngleMode.DEGREES,
    /** Whether the advanced pad is showing inverse functions. */
    val inverse: Boolean = false,
    /**
     * True when the result has more digits than are on screen.
     *
     * Drives both the "read more digits" accessibility action and whether scrolling the
     * result should ask the evaluator for more.
     */
    val hasMoreDigits: Boolean = false,
    /** True while a long evaluation is still running, so the UI can show progress. */
    val computing: Boolean = false,
)
