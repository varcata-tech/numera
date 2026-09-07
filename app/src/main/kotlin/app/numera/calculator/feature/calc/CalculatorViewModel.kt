package app.numera.calculator.feature.calc

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.numera.calculator.R
import app.numera.calculator.data.HistoryEntry
import app.numera.calculator.data.HistoryStore
import app.numera.calculator.math.AbortedException
import app.numera.calculator.math.AngleMode
import app.numera.calculator.math.PrecisionOverflowException
import app.numera.calculator.math.TooMuchMemoryException
import app.numera.calculator.math.UnifiedReal
import app.numera.calculator.math.expr.CalculatorExpr
import app.numera.calculator.math.expr.EvalError
import app.numera.calculator.math.expr.EvalResult
import app.numera.calculator.math.expr.ExprCodec
import app.numera.calculator.math.expr.ExprEvaluator
import app.numera.calculator.math.expr.KeyId
import app.numera.calculator.math.expr.Token
import app.numera.calculator.math.format.ResultFormatter
import app.numera.calculator.settings.SettingsStore
import java.math.BigInteger
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the calculator's expression, its evaluation, and the digits currently on screen.
 *
 * Four invariants matter more than anything else here:
 *
 * 1. **Nothing evaluates on the main thread**, not even the one-keystroke preview. Exact
 *    arithmetic has no bounded running time, so any call could be the one that blocks a
 *    frame. Work is dispatched with [runInterruptible] specifically, because that is what
 *    interrupts the worker thread on cancellation — and thread interruption is exactly what
 *    the engine's abort checks look for. With a plain `withContext`, cancelling a runaway
 *    computation would return immediately while the thread kept a core busy indefinitely.
 *    *Formatting counts as evaluation.* A [UnifiedReal] is a lazy tree: evaluating it only
 *    builds that tree, and the first digit request is what runs the series. So every call
 *    into [ResultFormatter] is dispatched, bounded by a timeout of its own, and wrapped in a
 *    catch — the engine raises `AbortedException` and friends from *there*, not from the
 *    evaluator, and an `ArithmeticException` escaping a `viewModelScope.launch` is not a
 *    cancellation the supervisor absorbs but a call to the default uncaught handler.
 *
 * 2. **Asking for more digits grows geometrically.** Requesting a fixed increment makes
 *    result scrolling quadratic, and it visibly dies somewhere around three hundred digits.
 *
 * 3. **What survives process death is the token stream, not the display.** The system can
 *    kill the process at any moment while the user is mid-expression. Saving the rendered
 *    formula would be useless, because a half-typed expression like `1+` cannot be parsed
 *    back; saving the tokens through [ExprCodec] restores exactly what was on screen.
 *
 * 4. **A background outcome may only land on the state it was started for.** Evaluations run
 *    for up to [EXPLICIT_TIMEOUT_MS], and the keypad stays live throughout. Cancellation
 *    alone is not enough — a job cancelled a microsecond after its coroutine resumed would
 *    still write — so every outcome carries the [epoch] it was started in and is dropped when
 *    the epoch has moved on. Without that, the answer to an abandoned calculation replaces
 *    the expression the user is typing, and a history row is written for a sum nobody asked
 *    to finish.
 */
class CalculatorViewModel(
    private val settings: SettingsStore,
    private val history: HistoryStore,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private var expr: CalculatorExpr = CalculatorExpr()

    /**
     * The expression that produced the displayed result, or `null` while typing.
     *
     * Kept so the result can be *recomputed* after process death rather than stored as text,
     * and so that continuing from an answer that cannot be written down as a short decimal
     * carries the calculation forward instead of a rounding of it. Re-evaluating is cheap and
     * keeps the value exact; a saved decimal would not be.
     */
    private var resultSource: CalculatorExpr? = null

    /** The exact value behind [CalculatorUiState.result], kept so digits can be refined. */
    private var lastValue: UnifiedReal? = null

    /** How many decimal places [digitsText] covers. Zero when nothing has been requested. */
    private var digitsShown: Int = 0
    private var digitsText: String = ""

    /**
     * The locale every number on screen is rendered in.
     *
     * Kept current by [onLocaleChanged], which the composable calls because it — unlike this
     * class — can observe `LocalConfiguration`. The view model is retained across the
     * configuration change an in-app language switch causes, so a locale captured once and
     * left alone would keep rendering the previous locale's digits under a keypad that had
     * already recomposed into the new one: two numbering systems on one screen.
     *
     * The initial value is only a seed for the frame in which the view model is constructed,
     * which is before the composable can supply the real one. It is not left to stand: every
     * reader captures this on the main thread, [onLocaleChanged] fires from the screen's
     * first composition, and it re-renders both lines when the two disagree.
     */
    private var locale: Locale = Locale.getDefault()

    private var previewJob: Job? = null
    private var evaluateJob: Job? = null
    private var digitsJob: Job? = null

    /**
     * Bumped by every edit and every new evaluation; see invariant 4.
     *
     * Read and written only on the main thread, which is where every key handler and every
     * coroutine in this class resumes, so it needs no synchronisation.
     */
    private var epoch: Long = 0L

    private val _state = MutableStateFlow(
        CalculatorUiState(angleMode = settings.angleMode.value),
    )
    val state: StateFlow<CalculatorUiState> = _state.asStateFlow()

    init {
        restore()
        observeAngleMode()
    }

    /**
     * Rebuilds the screen after process death.
     *
     * The result is re-evaluated from its source expression rather than restored from text,
     * so scrolling for more digits still works and the value is still exact. Anything that
     * fails to decode is treated as absent — a corrupt saved state must not stop the app
     * from opening.
     *
     * The angle unit is read from settings rather than from the bundle. Both survive process
     * death, but only settings also survives the *user changing it on another screen*, and
     * two sources of truth for one switch is how the chip and the preference end up
     * disagreeing about what `sin(30` means.
     *
     * The re-evaluation is bounded and reported exactly as [onEquals] bounds and reports its
     * own. A saved calculation that took twelve seconds the first time takes twelve seconds
     * again, and it is restarted on *every* process restore; unbounded, it holds a worker
     * thread with nothing on screen to say why, and a saved expression that now fails — the
     * angle unit having changed on the settings screen in between, say — leaves the formula
     * line with no result and no explanation at all.
     */
    private fun restore() {
        savedState.get<String>(KEY_EXPR)
            ?.let(ExprCodec::decodeFromString)
            ?.let { expr = it }

        val angle = settings.angleMode.value
        val inverse = savedState.get<Boolean>(KEY_INVERSE) ?: false
        val source: CalculatorExpr? = savedState.get<String>(KEY_RESULT_EXPR)
            ?.let(ExprCodec::decodeFromString)
            ?.takeIf { !it.isEmpty() }
        // How far the user had scrolled the answer. Restoring the value without it brought
        // back a twenty-character rendering of a result the user had already expanded to
        // several hundred digits, and the only way back was to scroll the whole way again.
        val expanded: Int = savedState.get<Int>(KEY_DIGITS) ?: 0

        _state.value = CalculatorUiState(
            formula = expr.display(),
            angleMode = angle,
            inverse = inverse,
            // The restore path runs the same arbitrarily long calculation the equals key
            // does, so it owes the user the same progress indicator. Without it the screen
            // simply sits there, indistinguishable from an app that has hung.
            computing = source != null,
        )

        if (source != null) {
            val stamp = ++epoch
            // Tracked as the evaluation job so that a user who starts typing the instant the
            // app comes back cancels it, exactly as they would cancel a live one.
            evaluateJob = viewModelScope.launch {
                val outcome = withContext(Dispatchers.Default) {
                    withTimeoutOrNull(EXPLICIT_TIMEOUT_MS) {
                        runInterruptible { ExprEvaluator.evaluate(source, angle) }
                    }
                }
                when (outcome) {
                    null -> showError(R.string.error_timeout, stamp)
                    is EvalResult.Failure -> showError(outcome.error.messageRes(), stamp)
                    is EvalResult.Success -> {
                        showResult(source, outcome.value, angle, stamp, record = false)
                        // Re-expanded only once the short rendering is on screen, so the
                        // answer appears at the same moment it would have without the
                        // expansion rather than after it.
                        if (stamp == epoch && expanded > 0) requestDigits(expanded)
                    }
                }
            }
        } else if (!expr.isEmpty()) {
            schedulePreview()
        }
    }

    /**
     * Mirrors the angle unit chosen in Settings.
     *
     * This view model is scoped to the activity, so it outlives the trip to the settings
     * screen and back. Reading [SettingsStore.angleMode] once at construction meant the
     * preference appeared to do nothing at all: the chip kept its old label and every
     * trigonometric evaluation kept the old unit until the process was killed and restarted.
     */
    private fun observeAngleMode() {
        viewModelScope.launch {
            settings.angleMode.collect { mode -> applyAngleMode(mode) }
        }
    }

    /** Writes everything needed to reconstruct the screen into the saved-state bundle. */
    private fun persist() {
        savedState[KEY_EXPR] = ExprCodec.encodeToString(expr)
        savedState[KEY_RESULT_EXPR] = resultSource?.let(ExprCodec::encodeToString)
        savedState[KEY_INVERSE] = _state.value.inverse
        // The count, not the digits. Re-deriving them costs one evaluation the restore path
        // is already paying for, while the text itself can run to thousands of characters
        // through a Binder transaction whose size limit kills the process.
        savedState[KEY_DIGITS] = digitsShown
    }

    /**
     * Adopts the locale the screen is being drawn in.
     *
     * Called from the composable, which is where `LocalConfiguration` can be observed. The
     * number on screen is re-rendered rather than left alone: it was produced in the previous
     * locale's digits, and after an in-app language change it would sit above a keypad
     * writing its digits in the new one.
     *
     * Both lines are refreshed, not just the answer. [reformatResult] returns immediately
     * unless the screen is in [DisplayMode.RESULT], so routing the preview through it alone
     * left the *preview* stale for the one case that reaches this with a preview showing:
     * [restore] calls [schedulePreview] synchronously while the view model is being
     * constructed, which is before the composable has ever run and therefore before this has
     * been told what locale to use. A half-typed expression brought back after process death
     * then kept the seed locale's digits under a keypad drawing the new one's, with no
     * further edit able to fix it because the preview is only ever re-rendered by an edit.
     */
    fun onLocaleChanged(newLocale: Locale) {
        if (locale == newLocale) return
        locale = newLocale
        if (_state.value.mode == DisplayMode.RESULT) reformatResult() else schedulePreview()
    }

    // ------------------------------------------------------------------ key handling

    fun onKey(key: KeyId) {
        // The decision itself lives in KeyPress, where it can be tested without a device.
        // A null is a press that must leave the display alone, not an empty expression.
        val next = KeyPress.apply(_state.value.mode, expr, key) { seedFromResult() } ?: return
        expr = next
        afterEdit()
    }

    fun onSmartParen() {
        if (_state.value.mode == DisplayMode.RESULT) expr = CalculatorExpr()
        expr = expr.appendSmartParen()
        afterEdit()
    }

    fun onDelete() {
        if (_state.value.mode != DisplayMode.INPUT) {
            // Backspacing out of a result returns to the expression that produced it,
            // which is far less surprising than clearing everything.
            _state.update { it.copy(mode = DisplayMode.INPUT, result = "", errorRes = null) }
            afterEdit()
            return
        }
        expr = expr.deleteLastToken()
        afterEdit()
    }

    fun onClear() {
        epoch++
        previewJob?.cancel()
        evaluateJob?.cancel()
        digitsJob?.cancel()
        expr = CalculatorExpr()
        lastValue = null
        digitsShown = 0
        digitsText = ""
        resultSource = null
        _state.update {
            CalculatorUiState(angleMode = it.angleMode, inverse = it.inverse)
        }
        persist()
    }

    fun onToggleInverse() {
        _state.update { it.copy(inverse = !it.inverse) }
        persist()
    }

    fun onToggleAngleMode() {
        val next = when (_state.value.angleMode) {
            AngleMode.DEGREES -> AngleMode.RADIANS
            AngleMode.RADIANS -> AngleMode.DEGREES
        }
        // Written through to settings so the setting screen and the chip cannot disagree.
        // The collector in [observeAngleMode] sees this as a no-op, because the state has
        // already caught up; applying it here as well is what keeps the chip from lagging a
        // frame behind the press.
        settings.setAngleMode(next)
        applyAngleMode(next)
    }

    /**
     * Inserts a stored calculation at the caret.
     *
     * Wrapped in parentheses, which is not cosmetic: appending the tokens of `1+2` after a
     * `×` would build `3×1+2` and evaluate to 5 rather than 9. Inserting the *expression*
     * rather than its decimal is what keeps the result exact — tapping `1÷3` and multiplying
     * by three still gives exactly one.
     */
    fun onInsertHistory(source: CalculatorExpr) {
        if (source.isEmpty()) return
        // Built against a local base rather than by assigning `expr` first. The size test
        // below can refuse the insert, and clearing the field before deciding left the
        // expression empty behind a formula line still showing the previous answer.
        val base = if (_state.value.mode == DisplayMode.INPUT) expr else CalculatorExpr()
        val tokens = ArrayList<Token>(base.tokens.size + source.tokens.size + 2)
        tokens += base.tokens
        tokens += Token.Key(KeyId.LEFT_PAREN)
        tokens += source.tokens
        tokens += Token.Key(KeyId.RIGHT_PAREN)
        val combined = CalculatorExpr(tokens)
        if (!fitsExpressionLimits(combined)) return
        expr = combined
        afterEdit()
    }

    /**
     * Replaces the whole expression, used by paste and by history.
     *
     * Anything past [fitsExpressionLimits] is refused outright rather than truncated.
     * `ExprCodec.encode` has no ceiling but its decoder does, so an over-long expression
     * would be accepted, displayed and persisted — and then come back as an empty screen
     * after process death, with no hint that anything was ever there.
     */
    fun onReplaceExpression(replacement: CalculatorExpr) {
        if (!fitsExpressionLimits(replacement)) return
        expr = replacement
        afterEdit()
    }

    // ------------------------------------------------------------------ evaluation

    fun onEquals() {
        if (expr.isEmpty()) return
        val snapshot = expr
        val mode = _state.value.angleMode
        val stamp = ++epoch

        evaluateJob?.cancel()
        previewJob?.cancel()
        digitsJob?.cancel()
        _state.update { it.copy(computing = true) }

        evaluateJob = viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                withTimeoutOrNull(EXPLICIT_TIMEOUT_MS) {
                    runInterruptible { ExprEvaluator.evaluate(snapshot, mode) }
                }
            }
            when (outcome) {
                null -> showError(R.string.error_timeout, stamp)
                is EvalResult.Failure -> showError(outcome.error.messageRes(), stamp)
                is EvalResult.Success -> showResult(snapshot, outcome.value, mode, stamp)
            }
        }
    }

    /**
     * Publishes a finished evaluation, if it is still the one being waited for.
     *
     * Producing the digits is bounded and guarded, because this — not the evaluation — is
     * where a lazy value does its work. `e^(10^6)` evaluates in microseconds and then spends
     * unbounded time on its first digit, so without the timeout the screen shows a spinner
     * with no way out, and without the catch the `AbortedException` raised the moment the
     * user presses another key escapes the launch and kills the process.
     *
     * @param angleMode the unit [value] was computed in, recorded with the history row. The
     *   *current* setting is not a substitute: it can already have changed by the time this
     *   runs, and a row labelled with the wrong unit is worse than one with none.
     * @param stamp the [epoch] this evaluation was started in. An outcome from an earlier
     *   epoch belongs to an expression the user has since edited away and is discarded.
     */
    private suspend fun showResult(
        source: CalculatorExpr,
        value: UnifiedReal,
        angleMode: AngleMode,
        stamp: Long,
        record: Boolean = true,
    ) {
        if (stamp != epoch) return
        // Captured on the main thread, where it is written, and read from the worker below —
        // a plain field read across threads has no visibility guarantee at all.
        val target: Locale = locale
        // The failure's own error string, or null when it has none of its own; see
        // [formattingErrorRes].
        var failure: Int? = null
        val rendered: ShortResult? = try {
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(FORMAT_TIMEOUT_MS) {
                    runInterruptible {
                        ShortResult(
                            text = ResultFormatter.formatShort(value, SHORT_BUDGET, target),
                            // Asked here rather than from the `_state.update` lambda below.
                            // It walks the value's exact decimal — a power-of-ten BigInteger
                            // of up to ten thousand digits — and `update` re-runs its lambda
                            // on CAS contention, so on the main thread this was unbounded
                            // duplicated work on the very frame that publishes the answer.
                            exact = ResultFormatter.isExactlyDisplayable(value, SHORT_BUDGET),
                        )
                    }
                }
            }
        } catch (e: ArithmeticException) {
            // Every engine failure is one of these, `AbortedException` included. It is not a
            // CancellationException, so viewModelScope's SupervisorJob would not absorb it:
            // uncaught, it reaches the default handler and takes the process with it.
            failure = formattingErrorRes(e)
            null
        }
        if (stamp != epoch) return
        if (rendered == null) {
            // Nothing was aborted by the user — that moves the epoch and returns above — so
            // an abort here means this job's own deadline fired, which is what the fallback
            // reports. A failure with a string of its own reports that instead.
            showError(failure ?: R.string.error_timeout, stamp)
            return
        }
        lastValue = value
        resultSource = source
        digitsShown = 0
        digitsText = ""
        _state.update {
            it.copy(
                formula = source.display(),
                preview = "",
                result = rendered.text,
                errorRes = null,
                mode = DisplayMode.RESULT,
                hasMoreDigits = !rendered.exact,
                computing = false,
                // A wholly new answer, not an extension of the one on screen; see
                // [CalculatorUiState.resultGeneration].
                resultGeneration = it.resultGeneration + 1,
            )
        }
        persist()
        if (record) {
            // Recorded after the answer is on screen, and in a job of its own. The insert
            // opens a database, may trim the table and re-reads it, none of which belongs
            // between the key press and the number; and as a sibling rather than a child it
            // still completes if the user's next keystroke cancels this evaluation.
            viewModelScope.launch { history.insert(source, rendered.text, angleMode) }
        }
    }

    /**
     * One finished short rendering.
     *
     * @property text what the result line shows.
     * @property exact true when [text] is the whole value, so there are no further digits to
     *   scroll to. Carried alongside the text because deciding it costs the same exact
     *   decimal the text was built from, and deciding it twice — once here, once on the main
     *   thread — is what made publishing an answer a frame-length operation.
     */
    private class ShortResult(val text: String, val exact: Boolean)

    /** @param stamp as in [showResult]: a failure from a superseded evaluation is dropped. */
    private fun showError(messageRes: Int, stamp: Long) {
        if (stamp != epoch) return
        lastValue = null
        resultSource = null
        digitsShown = 0
        digitsText = ""
        _state.update {
            it.copy(
                preview = "",
                result = "",
                errorRes = messageRes,
                mode = DisplayMode.ERROR,
                hasMoreDigits = false,
                computing = false,
                resultGeneration = it.resultGeneration + 1,
            )
        }
        persist()
    }

    /**
     * What the clipboard should carry for the current result.
     *
     * Two representations, because they serve different readers. [ClipboardPayload.text] is
     * for every other app and is the *full* value — the exact decimal when the number
     * terminates, otherwise far more digits than the display's twenty-character budget shows.
     * [ClipboardPayload.encodedExpression] is for Numera itself, and is what makes copying
     * the result of `1÷3` and pasting it back give exactly one when multiplied by three
     * rather than 0.99999999999999999999.
     *
     * `suspend`, because producing those digits runs the same unbounded approximation as any
     * other evaluation: a menu tap must not be able to drive it on the frame thread.
     */
    suspend fun clipboardPayload(): ClipboardPayload? {
        val value = lastValue ?: return null
        val source = resultSource ?: return null
        val shown = digitsShown
        val text: String? = withContext(Dispatchers.Default) {
            withTimeoutOrNull(FORMAT_TIMEOUT_MS) {
                runInterruptible {
                    try {
                        val request = digitRequest(value.digitsRequired(), maxOf(shown, COPY_DIGITS))
                        // formatPlain, not formatWithDigits: every other entry point in the
                        // formatter groups and localises, and a grouped `1,745.13` — or an
                        // Arabic-Indic `١٧٤٥` — is not a number any tokenizer will take back,
                        // this app's own paste handler included. The exact decimal is used
                        // whenever the value has one, so precision is unaffected.
                        ResultFormatter.formatPlain(value, request.digits)
                    } catch (e: ArithmeticException) {
                        // Aborted, out of precision, or out of BigInteger's range. There is
                        // nothing honest to put on the clipboard, and a menu tap is not
                        // allowed to end the process.
                        null
                    }
                }
            }
        }
        if (text.isNullOrEmpty()) return null
        return ClipboardPayload(text, ExprCodec.encodeToString(source))
    }

    /**
     * What the clipboard should carry for a stored calculation.
     *
     * The row's cached answer is a *display* string: localised digits, grouping separators
     * and, when the value does not terminate, a trailing ellipsis inside a twenty-character
     * budget. Copying that is the precise loss [clipboardPayload] exists to avoid — copying
     * the history row for `1÷3` handed other applications `0.33333333333333333333…`, and in
     * an Arabic locale handed them digits no tokenizer will read back, this app's own paste
     * handler included. So the calculation is evaluated again, in the unit it was originally
     * evaluated in, and copied as a plain decimal.
     *
     * The cached string is the fallback, because a row whose re-evaluation times out is
     * still better copied imperfectly than not at all.
     */
    suspend fun historyPayload(entry: HistoryEntry): ClipboardPayload {
        val encoded = ExprCodec.encodeToString(entry.expression)
        val text: String? = withContext(Dispatchers.Default) {
            withTimeoutOrNull(FORMAT_TIMEOUT_MS) {
                runInterruptible {
                    try {
                        val outcome = ExprEvaluator.evaluate(entry.expression, entry.angleMode)
                        val value = (outcome as? EvalResult.Success)?.value
                        value?.let { ResultFormatter.formatPlain(it, COPY_DIGITS) }
                    } catch (e: ArithmeticException) {
                        // Aborted, out of precision, or out of BigInteger's range. A menu tap
                        // is not allowed to end the process; the cached rendering stands in.
                        null
                    }
                }
            }
        }
        val copied: String = if (text.isNullOrEmpty()) entry.result else text
        return ClipboardPayload(copied, encoded)
    }

    /**
     * Extends the displayed result, called as the user scrolls it sideways.
     *
     * The growth is geometric on purpose: reaching two thousand digits costs on the order
     * of fourteen evaluations rather than two thousand, and the already-computed prefix is
     * refined rather than recalculated because the engine caches its best approximation.
     */
    fun onRequestMoreDigits() {
        requestDigits(nextDigitTarget(digitsShown))
    }

    /**
     * Expands the displayed result to [target] decimal places.
     *
     * Separate from [onRequestMoreDigits] because the restore path asks for a specific count
     * — the one the user had scrolled to before the process died — rather than for the next
     * step of the doubling.
     */
    private fun requestDigits(target: Int) {
        val value = lastValue ?: return
        if (!_state.value.hasMoreDigits) return
        if (target <= digitsShown) return
        val stamp = epoch
        // Captured here, on the main thread, and read from the worker below; see [showResult].
        val renderLocale: Locale = locale

        digitsJob?.cancel()
        digitsJob = viewModelScope.launch {
            val request = withContext(Dispatchers.Default) {
                runInterruptible { digitRequest(value.digitsRequired(), target) }
            }
            val text: String? = withContext(Dispatchers.Default) {
                withTimeoutOrNull(FORMAT_TIMEOUT_MS) {
                    runInterruptible {
                        // The total overload, because an expansion that cannot be produced
                        // must leave the digits already on screen exactly as they are. The
                        // throwing one let the AbortedException raised by the user's *next*
                        // key press — the ordinary way to interrupt a long scroll — escape
                        // this launch as an uncaught exception.
                        ResultFormatter.formatWithDigitsOrNull(value, request.digits, renderLocale)
                    }
                }
            }
            // No honest expansion, or the deadline passed. Silent by design: the result line
            // still holds the digits it had, which remain correct as far as they go.
            if (text == null) return@launch
            // Guarded twice over. The epoch catches a new answer or an edit; the identity
            // check catches anything that changed the value without moving the epoch. Either
            // way, painting these digits now would put a previous value's expansion under
            // the current formula, where nothing on screen would reveal the mismatch.
            if (stamp != epoch || lastValue !== value) return@launch
            digitsShown = target
            // An exact value is printed with no ellipsis and a truncated one always says so;
            // without this, fifty digits of one seventh are indistinguishable from an answer
            // that simply stops there.
            digitsText = if (request.truncated) text + ELLIPSIS else text
            // No generation bump: this is the same answer with more of it showing, and the
            // result line must keep the scroll position the user reached to ask for it.
            _state.update { it.copy(result = digitsText, hasMoreDigits = request.truncated) }
            persist()
        }
    }

    /**
     * Re-renders the answer on screen after the display locale has changed.
     *
     * Nothing about the value changes, so the epoch is deliberately left alone: this is not a
     * new calculation and must not cancel one. The expansion the user had scrolled to is
     * reproduced at its current length rather than reset, because dropping back to twenty
     * characters would look like the language switch had lost their digits.
     */
    private fun reformatResult() {
        val value = lastValue ?: return
        if (_state.value.mode != DisplayMode.RESULT) return
        val shown = digitsShown
        val renderLocale: Locale = locale
        val stamp = epoch

        digitsJob?.cancel()
        digitsJob = viewModelScope.launch {
            val text: String? = withContext(Dispatchers.Default) {
                withTimeoutOrNull(FORMAT_TIMEOUT_MS) {
                    runInterruptible {
                        if (shown <= 0) {
                            ResultFormatter.formatShortOrNull(value, SHORT_BUDGET, renderLocale)
                        } else {
                            val request = digitRequest(value.digitsRequired(), shown)
                            ResultFormatter.formatWithDigitsOrNull(value, request.digits, renderLocale)
                                ?.let { if (request.truncated) it + ELLIPSIS else it }
                        }
                    }
                }
            }
            // The previous rendering stays: it is the same number, in digits the user could
            // read a moment ago. Blanking the line would be the worse failure.
            if (text == null) return@launch
            if (stamp != epoch || lastValue !== value) return@launch
            if (shown > 0) digitsText = text
            _state.update { it.copy(result = text) }
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Applies an angle unit that came from either the chip or the settings screen.
     *
     * Returning to [DisplayMode.INPUT] is deliberate: the answer on screen was computed in
     * the other unit, so it is no longer the answer to the question above it.
     */
    private fun applyAngleMode(mode: AngleMode) {
        if (_state.value.angleMode == mode) return
        _state.update { it.copy(angleMode = mode) }
        afterEdit()
    }

    private fun afterEdit() {
        // A running evaluation or digit request belongs to the expression that has just been
        // edited away. Cancelling stops most of them; the epoch bump is what stops the one
        // that had already resumed and was about to write its answer over the new formula.
        epoch++
        evaluateJob?.cancel()
        digitsJob?.cancel()

        val formula = expr.display()
        _state.update {
            it.copy(
                formula = formula,
                mode = DisplayMode.INPUT,
                result = "",
                errorRes = null,
                hasMoreDigits = false,
                computing = false,
            )
        }
        lastValue = null
        digitsShown = 0
        digitsText = ""
        resultSource = null
        persist()
        schedulePreview()
    }

    /**
     * Recomputes the small live result under the formula.
     *
     * Cancelling the previous job first is what keeps a fast typist from queueing up a
     * dozen abandoned evaluations, each holding a thread.
     */
    private fun schedulePreview() {
        previewJob?.cancel()

        val snapshot = expr
        if (!snapshot.worthPreviewing()) {
            _state.update { it.copy(preview = "") }
            return
        }
        val mode = _state.value.angleMode
        val stamp = epoch
        // Captured here, on the main thread, and read from the worker below; see [showResult].
        val target: Locale = locale

        previewJob = viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                withTimeoutOrNull(PREVIEW_TIMEOUT_MS) {
                    runInterruptible { ExprEvaluator.evaluate(snapshot, mode) }
                }
            }
            // A preview that failed or timed out simply shows nothing. Surfacing an error
            // mid-typing would flash "Bad expression" after every operator key.
            val text = (outcome as? EvalResult.Success)?.let { success ->
                withContext(Dispatchers.Default) {
                    // Bounded and total. Evaluating within PREVIEW_TIMEOUT_MS says nothing
                    // about how long the first digits take, and until this used the OrNull
                    // overload every keystroke that interrupted a preview mid-series threw
                    // an AbortedException straight out of this launch.
                    withTimeoutOrNull(PREVIEW_TIMEOUT_MS) {
                        runInterruptible {
                            ResultFormatter.formatShortOrNull(
                                success.value,
                                PREVIEW_BUDGET,
                                target,
                            )
                        }
                    }
                }
            }.orEmpty()
            if (stamp != epoch) return@launch
            _state.update { it.copy(preview = text) }
        }
    }

    /**
     * The expression an operator key continues from, after equals.
     *
     * Never a *rendering* of the answer. A rendered result carries grouping separators the
     * tokeniser splits a number in half at, stops at sixty-four characters, and loses the
     * parentheses a negative value needs before `^`; each of those turns a chained operator
     * into a silently wrong number rather than an error anybody would notice. So the value is
     * carried forward as a plain decimal only when it can be written down exactly and
     * briefly, and otherwise as the parenthesised calculation that produced it — which is
     * also what keeps `1÷3` × `3` exactly one.
     */
    private fun seedFromResult(): CalculatorExpr {
        val value = lastValue ?: return CalculatorExpr()
        exactSeedText(value)?.let { decimal ->
            // Assembled by CalculatorExpr, not here. It is the one place that knows a
            // generated literal still has to satisfy `isNumberLiteral` — a lower-case `e`
            // exponent slips past the parser's own bound — and that a negative seed needs
            // parentheses, because unary minus binds above `^` and the postfix keys, so a
            // bare −5 followed by `x²` answers −25. A second copy of those rules in this
            // file could only drift out of step with the one the tokenizer enforces.
            CalculatorExpr.seedFromDecimal(decimal)?.let { return it }
        }
        val source = resultSource ?: return CalculatorExpr()
        val tokens = ArrayList<Token>(source.tokens.size + 2)
        tokens += Token.Key(KeyId.LEFT_PAREN)
        tokens += source.tokens
        tokens += Token.Key(KeyId.RIGHT_PAREN)
        return CalculatorExpr(tokens)
    }

    /**
     * The result as a short exact decimal, or `null` when there is no such thing.
     *
     * The bit-length test comes first because a factorial result can run to tens of thousands
     * of digits, and converting one to a string costs milliseconds on the very frame a key
     * press has to be answered on — only to be thrown away for being too long to type.
     */
    private fun exactSeedText(value: UnifiedReal): String? {
        val integer: BigInteger? = value.asBigInteger()
        if (integer != null) {
            // Parentheses on bitLength: Kotlin resolves the bare name to a package-private
            // field on BigInteger, which is not the value this needs.
            if (integer.bitLength() > MAX_SEED_BITS) return null
            val text = integer.toString()
            return if (text.length <= MAX_SEED_LENGTH) text else null
        }
        val places = value.digitsRequired() ?: return null
        if (places > MAX_SEED_LENGTH) return null
        val exact = value.exactDecimalOrNull() ?: return null
        return if (exact.length <= MAX_SEED_LENGTH) exact else null
    }

    private companion object {
        const val PREVIEW_TIMEOUT_MS = 1_000L
        const val EXPLICIT_TIMEOUT_MS = 15_000L

        /**
         * Deadline for turning a finished value into digits.
         *
         * Separate from [EXPLICIT_TIMEOUT_MS] because it bounds separate work. Evaluation
         * builds a lazy tree and can finish in microseconds; the first digit request is what
         * runs the series, so a value that evaluated instantly can still take arbitrarily
         * long to print. Unbounded, the progress indicator never goes away and the only exit
         * is another key press.
         */
        const val FORMAT_TIMEOUT_MS = 10_000L

        const val SHORT_BUDGET = 20
        const val PREVIEW_BUDGET = 16
        const val COPY_DIGITS = 60

        /** Marks a result the user can scroll further; matches the formatter's own glyph. */
        const val ELLIPSIS = '…'

        /**
         * The longest decimal worth carrying forward as a number token.
         *
         * A cost and readability bound, not a correctness one: `CalculatorExpr.seedFromDecimal`
         * enforces what a token may actually hold, and anything refused here still continues
         * exactly, as the parenthesised expression that produced it. The number matches the
         * tokeniser's own `MAX_NUMBER_LENGTH`, so a seeded literal is never longer than one
         * the keypad could have typed — `100!` continues as `(100!)×` rather than as a
         * hundred and fifty-eight digits of formula line.
         */
        const val MAX_SEED_LENGTH = 64

        /** 64 decimal digits, with a little slack; the length test does the exact work. */
        const val MAX_SEED_BITS = 224

        const val KEY_EXPR = "expr"
        const val KEY_RESULT_EXPR = "resultExpr"
        const val KEY_INVERSE = "inverse"
        const val KEY_DIGITS = "digitsShown"
    }
}

/**
 * The two things a copy puts on the clipboard.
 *
 * @property text the human-readable value, for other applications.
 * @property encodedExpression the exact calculation, for pasting back into Numera.
 */
data class ClipboardPayload(val text: String, val encodedExpression: String)

/**
 * Whether a live preview would tell the user anything.
 *
 * Showing "5" underneath a formula that already reads "5" is noise, and Google Calculator
 * suppresses exactly this case.
 */
private fun CalculatorExpr.worthPreviewing(): Boolean {
    if (isEmpty()) return false
    if (tokens.size == 1) return false
    return true
}

/**
 * The error string a *formatting* failure should report, or `null` when it has none of its
 * own.
 *
 * Formatting is where a value's deferred work actually runs, so the failures the evaluator is
 * documented to raise arrive here instead — and each has to reach the display as the same
 * sentence the evaluator would have used, or the same calculation reports two different
 * things depending on which half of it happened to give up first.
 *
 * `null` is reserved for [AbortedException], which says only that the worker thread was
 * interrupted. That has two causes and the exception cannot tell them apart: the user pressed
 * another key, which moves the epoch and makes the whole outcome stale, or the caller's own
 * deadline fired, which is the caller's to report. Mapping it onto a message here would flash
 * "Cancelled" over an answer the user is still typing towards.
 */
internal fun formattingErrorRes(failure: ArithmeticException): Int? = when (failure) {
    is AbortedException -> null
    is TooMuchMemoryException -> R.string.error_too_much_memory
    // "I cannot decide" is not "the answer is wrong", and ExprEvaluator already reports this
    // same failure from the evaluation half of a calculation as a bad expression.
    is PrecisionOverflowException -> R.string.error_syntax
    // BigInteger raises a bare ArithmeticException when a value outgrows its own supported
    // range, which is an overflow; divide-by-zero and not-a-number cannot arise from
    // formatting, since the only division the formatter does is by a power of ten.
    else -> R.string.error_too_much_memory
}

/** Maps an evaluation failure onto the one string the display shows for it. */
private fun EvalError.messageRes(): Int = when (this) {
    EvalError.SYNTAX -> R.string.error_syntax
    EvalError.DIVIDE_BY_ZERO -> R.string.error_divide_by_zero
    EvalError.NOT_A_NUMBER -> R.string.error_not_a_number
    EvalError.TOO_MUCH_MEMORY -> R.string.error_too_much_memory
    EvalError.OVERFLOW -> R.string.error_too_much_memory
    EvalError.ABORTED -> R.string.error_aborted
}

/** Builds the view model without a DI framework, matching the rest of the app. */
fun calculatorViewModelFactory(
    settings: SettingsStore,
    history: HistoryStore,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { CalculatorViewModel(settings, history, createSavedStateHandle()) }
}
