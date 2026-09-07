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
    /**
     * Bumped whenever [result] is replaced by a *different* answer rather than extended.
     *
     * The result line keeps one scroll position for every result it ever shows, and a
     * `ScrollState` clamps its value down when the content shrinks — so scrolling one seventh
     * out to three hundred digits and then pressing equals again parks the new, short answer
     * at its right-hand end with the leading digits off screen. Scrolling back to the start
     * on a *new* answer is what fixes that, and it has to be a new answer rather than merely
     * new text: an expansion appends digits to the same value and must leave the position
     * where the user's finger put it.
     */
    val resultGeneration: Int = 0,
)
