package app.numera.calculator.feature.graphing

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.numera.calculator.math.AngleMode
import app.numera.calculator.math.expr.CalculatorExpr
import app.numera.calculator.math.expr.ExprEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/** One plotted function: its text, its compiled closure, and its current samples. */
data class Plot(
    val expressionText: String,
    val evaluate: (Double) -> Double,
    val samples: Samples? = null,
)

/**
 * Where the trace crosshair currently sits, and on which curve.
 *
 * [subject] is not decoration. The crosshair is always put on the first plot, and deleting
 * that plot while another remains changes which function "the first plot" means without
 * touching the point: the readout then reported a coordinate the new first curve does not
 * pass through, and the canvas drew a filled dot in empty space that survived every pan,
 * zoom and resample until the user found the "Clear trace" button.
 */
data class TracePoint(val x: Double, val y: Double, val subject: String)

/**
 * The roots readout: the values, and the expression they were computed from.
 *
 * The two travel together for the same reason [TracePoint] carries its subject. The values
 * are produced by a background pass that lands well after the plot list has changed, so
 * pairing them with whatever `plots.first()` happens to be at composition time captioned one
 * function's roots with another function's name — and a gesture started right after a delete
 * keeps restarting the debounce, so the mismatch stays on screen for as long as the finger
 * is down.
 */
data class RootsReadout(val subject: String, val values: List<Double>)

/** Everything the graphing screen draws. */
data class GraphingUiState(
    val viewport: Viewport = Viewport(),
    val plots: List<Plot> = emptyList(),
    val trace: TracePoint? = null,
    val roots: RootsReadout? = null,
    val lastAddFailed: Boolean = false,
    val canvasColumns: Int = 0,
    val canvasRows: Int = 0,
)

/**
 * Whether a readout computed for [subject] still describes the curve the screen would draw.
 *
 * Both the trace and the roots are computed for the first plot only, and both outlive the
 * list they were computed from. Kept out of the view model as a plain function so the
 * invalidation can be tested on the JVM, the way the converter's restore logic is.
 */
internal fun describesFirstPlot(subject: String?, plots: List<Plot>): Boolean =
    subject != null && plots.firstOrNull()?.expressionText == subject

/** Drops a trace or a roots list that no longer belongs to the first plot. */
internal fun GraphingUiState.withoutStaleReadouts(): GraphingUiState = copy(
    trace = trace?.takeIf { describesFirstPlot(it.subject, plots) },
    roots = roots?.takeIf { describesFirstPlot(it.subject, plots) },
)

/**
 * The crosshair for [plot] at [x].
 *
 * A column where the function has no value produces a point with a NaN `y`, not no point.
 * Writing `null` there deleted whatever crosshair was already placed: tapping left of the
 * axis on `ln(x)` blanked the readout and took the "Clear trace" button with it, exactly as
 * if that button had been pressed, and nothing said the function is undefined there. A NaN
 * `y` keeps the column, the canvas marks it, and the readout says so in words.
 */
internal fun traceFor(plot: Plot, x: Double): TracePoint {
    val y: Double = try {
        plot.evaluate(x)
    } catch (e: ArithmeticException) {
        Double.NaN
    }
    return TracePoint(x, if (y.isFinite()) y else Double.NaN, plot.expressionText)
}

/**
 * How long a gesture-driven resample waits, given how long one has already been pending.
 *
 * A plain restart-debounce never fires during a drag: pointer moves arrive every frame and
 * each one restarted the wait, so the samples stayed exactly as they were until the finger
 * lifted, and everything a long pan revealed past the sampled margin was blank for the whole
 * gesture. The wait is therefore bounded. It shrinks as the gesture goes on, so a pass runs
 * at least every [GraphingViewModel.RESAMPLE_MAX_WAIT_MS] while the finger keeps moving,
 * and the ordinary debounce still coalesces the tail of the gesture into one pass once it
 * settles.
 */
internal fun resampleDelayMillis(pendingForMillis: Long): Long {
    val remaining: Long = GraphingViewModel.RESAMPLE_MAX_WAIT_MS - pendingForMillis
    return min(GraphingViewModel.RESAMPLE_DEBOUNCE_MS, remaining).coerceAtLeast(0L)
}

/**
 * Owns the viewport, the plotted functions, and the resampling schedule.
 *
 * The resampling policy is the whole performance story. During a gesture the samples are
 * left alone and the existing path is simply redrawn against the new viewport; a fresh
 * sample pass is scheduled once, after the gesture goes quiet. Resampling on every frame of
 * a pinch is what makes a graph stutter, and it is invisible in a screenshot.
 *
 * What survives process death is the expression *text* and the window, not the plot. A [Plot]
 * carries a compiled closure, which cannot be written to a Bundle at all; the text can, and
 * recompiling it on the way back costs one parse and reproduces the plot exactly. Samples are
 * not saved either — they are rebuilt as soon as the canvas reports its size. Nor is the
 * crosshair: it is a reading the user took, not a setting, and coming back to a graph with a
 * dot on it and no memory of putting it there is worse than coming back to a clean graph.
 */
class GraphingViewModel(private val savedState: SavedStateHandle) : ViewModel() {

    private var resampleJob: Job? = null

    /** The pass in [resampleJob] once it is past its delay and computing, else null. */
    private var inFlightJob: Job? = null

    /** When the gesture now in progress first asked for a pass, or null outside a gesture. */
    private var gesturePendingSinceMillis: Long? = null

    private val _state = MutableStateFlow(GraphingUiState())
    val state: StateFlow<GraphingUiState> = _state.asStateFlow()

    init {
        val texts: List<String> = savedState.get<ArrayList<String>>(KEY_EXPRESSIONS).orEmpty()
        val restored: List<Plot> = texts.mapNotNull { text ->
            compile(text)?.let { Plot(text, it) }
        }
        _state.value = GraphingUiState(
            viewport = restoreViewport(savedState.get<DoubleArray>(KEY_VIEWPORT)),
            plots = restored,
        )
    }

    /** Adds a function, returning false when the expression does not parse. */
    fun onAddFunction(text: String): Boolean {
        if (_state.value.plots.size >= MAX_PLOTS) return false
        val compiled = compile(text)
        if (compiled == null) {
            _state.update { it.copy(lastAddFailed = true) }
            return false
        }
        _state.update {
            it.copy(plots = it.plots + Plot(text, compiled), lastAddFailed = false)
                .withoutStaleReadouts()
        }
        scheduleResample(immediate = true)
        return true
    }

    /**
     * Clears the "cannot plot that" message.
     *
     * Called while the user edits the draft: leaving the message up as they type the
     * correction says the expression is still rejected when nothing has judged it yet.
     */
    fun onExpressionEdited() {
        if (!_state.value.lastAddFailed) return
        _state.update { it.copy(lastAddFailed = false) }
    }

    /**
     * Deletes one plotted function.
     *
     * The readouts are invalidated in the same update as the list, not left for the resample
     * to correct. The resample only cleared them when the list had become *empty*, and it
     * cannot be relied on to arrive in any case: a pan or pinch begun straight after the
     * delete cancels the immediate pass and replaces it with a debounce that restarts on
     * every pointer event.
     */
    fun onRemoveFunction(index: Int) {
        _state.update {
            it.copy(plots = it.plots.filterIndexed { i, _ -> i != index }).withoutStaleReadouts()
        }
        scheduleResample(immediate = true)
    }

    fun onCanvasResized(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        val current = _state.value
        if (columns == current.canvasColumns && rows == current.canvasRows) return
        _state.update { it.copy(canvasColumns = columns, canvasRows = rows) }
        scheduleResample(immediate = true)
    }

    fun onPan(dx: Float, dy: Float, width: Float, height: Float) {
        _state.update { it.copy(viewport = it.viewport.pan(dx, dy, width, height)) }
        scheduleResample(immediate = false)
    }

    fun onZoom(factor: Float, centroid: Offset, width: Float, height: Float) {
        _state.update { current ->
            val aboutX = current.viewport.screenToWorldX(centroid.x, width)
            val aboutY = current.viewport.screenToWorldY(centroid.y, height)
            current.copy(
                viewport = current.viewport.zoom(
                    factor.toDouble(), factor.toDouble(), aboutX, aboutY,
                ),
            )
        }
        scheduleResample(immediate = false)
    }

    fun onResetView() {
        _state.update { it.copy(viewport = Viewport(), trace = null) }
        scheduleResample(immediate = true)
    }

    /**
     * Equalises world units per pixel on the two axes.
     *
     * The canvas dimensions have to be the real ones. Passing 1 by 1 made this a no-op that
     * could never be noticed from the outside: the default window is already square in world
     * units and every gesture keeps it square, so squaring against a 1:1 canvas returns the
     * window unchanged while the plot stays visibly stretched on a tall screen.
     */
    fun onSquareAxes() {
        val current = _state.value
        if (current.canvasColumns <= 0 || current.canvasRows <= 0) return
        _state.update {
            it.copy(
                viewport = it.viewport.squared(
                    current.canvasColumns.toFloat(),
                    current.canvasRows.toFloat(),
                ),
            )
        }
        scheduleResample(immediate = true)
    }

    /**
     * Puts the crosshair on the first plot at the tapped column.
     *
     * Only the column is used. The crosshair's y is the curve's value there, not the height
     * the finger landed at, so a tap anywhere in a column snaps to the plot rather than
     * reporting a point the function never passes through — which is why the canvas height
     * is not a parameter. A column with no value still places the crosshair; see [traceFor].
     */
    fun onTrace(px: Float, width: Float) {
        val current = _state.value
        val plot = current.plots.firstOrNull() ?: return
        val x = current.viewport.screenToWorldX(px, width)
        val trace: TracePoint = traceFor(plot, x)
        _state.update { it.copy(trace = trace) }
    }

    /** Takes the crosshair off the graph; without it the readout can only ever be moved. */
    fun onClearTrace() {
        if (_state.value.trace == null) return
        _state.update { it.copy(trace = null) }
    }

    /**
     * Recomputes samples and roots off the main thread.
     *
     * The wait is what separates a gesture from its cost: [immediate] is for a discrete
     * change the user is waiting on, while a pan or pinch coalesces into a pass after the
     * finger settles — and, bounded by [resampleDelayMillis], into one every quarter second
     * while it keeps moving.
     *
     * A pass that is already computing is left to finish when only the window has moved.
     * Its samples are in world coordinates and draw correctly under any window, and
     * cancelling it on every pointer event is what kept a drag from ever seeing a fresh
     * sample; it reschedules itself if the window moved on while it ran. A change to the
     * plot list or the canvas must still cancel it, because the pass would otherwise publish
     * a list that no longer matches what is plotted.
     */
    private fun scheduleResample(immediate: Boolean) {
        val current: Job? = resampleJob
        if (current != null && current.isActive) {
            if (!immediate && current === inFlightJob) return
            current.cancel()
        }
        val delayMillis: Long = if (immediate) 0L else gestureDelayMillis()
        resampleJob = launchPass(delayMillis)
    }

    /** The wait for a pan or pinch, measured from the first pointer event of the gesture. */
    private fun gestureDelayMillis(): Long {
        val now: Long = System.nanoTime() / NANOS_PER_MILLI
        val since: Long = gesturePendingSinceMillis ?: now
        gesturePendingSinceMillis = since
        return resampleDelayMillis(now - since)
    }

    private fun launchPass(delayMillis: Long): Job = viewModelScope.launch {
        if (delayMillis > 0L) delay(delayMillis)
        inFlightJob = coroutineContext[Job]
        gesturePendingSinceMillis = null
        // Saved here rather than at the call, which a pan or a pinch reaches on every
        // pointer event: an ArrayList and a DoubleArray allocated and published into the
        // saved-state flows at 60 to 120 Hz, for a window that only needs recording once
        // the gesture has settled — which is exactly where the wait already puts us.
        persist()
        val snapshot = _state.value
        val columns = snapshot.canvasColumns
        if (snapshot.plots.isEmpty()) {
            // Returning here without touching the state left the roots and the traced
            // point of the deleted function on screen, describing a curve that no longer
            // exists and that the user has no way to remove.
            _state.update { it.copy(roots = null, trace = null) }
            return@launch
        }
        if (columns <= 1) return@launch

        val resampled = withContext(Dispatchers.Default) {
            snapshot.plots.map { plot ->
                plot.copy(
                    samples = GraphSampler.sample(
                        plot.evaluate, snapshot.viewport, columns, SAMPLE_MARGIN_WINDOWS,
                    ),
                )
            }
        }
        val first: Plot? = resampled.firstOrNull()
        val firstSamples: Samples? = first?.samples
        val evaluate: ((Double) -> Double)? = first?.evaluate
        val subject: String = first?.expressionText.orEmpty()
        val roots: RootsReadout? = if (firstSamples == null || evaluate == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                // The visible columns only: the margin is for the canvas, and the readout
                // says "in view".
                val inView: Samples = GraphSampler.visible(firstSamples, snapshot.viewport)
                RootsReadout(subject, RootFinder.roots(evaluate, inView))
            }
        }
        // Stale-checked once more on the way in. The job is cancelled whenever the plot
        // list changes, but cancellation is only observed at a suspension point, and the
        // readout must never be captioned with a function it was not computed from.
        _state.update { it.copy(plots = resampled, roots = roots).withoutStaleReadouts() }
        // The window moved while this pass ran, so what it published is already behind:
        // a pass that was let run through a gesture owes the gesture's end its own pass.
        if (_state.value.viewport != snapshot.viewport) {
            resampleJob = launchPass(RESAMPLE_DEBOUNCE_MS)
        }
    }

    /**
     * Compiles to the double-precision closure the plotter samples, or null if it won't parse.
     *
     * Always radians, and deliberately not the app-wide angle mode the calculator and the
     * converter both read from `SettingsStore`. A plot of sin x in degrees has a period of
     * 360 and is a flat line across the reset window, and the x axis of a graph is a length,
     * not an angle setting. What is not acceptable is doing that silently, so the screen says
     * so: `graph_angle_unit` is shown under the expression field and spoken as part of the
     * canvas description. Change one and the other is wrong.
     */
    private fun compile(text: String): ((Double) -> Double)? {
        val expr: CalculatorExpr = CalculatorExpr.fromText(text) ?: return null
        return ExprEvaluator.compileToDouble(expr, AngleMode.RADIANS)
    }

    /** Writes the parts of the screen that can outlive the process into the saved state. */
    private fun persist() {
        val current = _state.value
        savedState[KEY_EXPRESSIONS] = ArrayList(current.plots.map { it.expressionText })
        savedState[KEY_VIEWPORT] = doubleArrayOf(
            current.viewport.minX,
            current.viewport.maxX,
            current.viewport.minY,
            current.viewport.maxY,
        )
    }

    /**
     * Rebuilds the saved window, falling back to the default if it is not usable.
     *
     * A restored bundle is not trusted to be well formed: a degenerate or non-finite window
     * would divide by zero in every world-to-screen conversion on the first frame.
     */
    private fun restoreViewport(bounds: DoubleArray?): Viewport {
        if (bounds == null || bounds.size != 4) return Viewport()
        if (!bounds.all { it.isFinite() }) return Viewport()
        if (bounds[1] <= bounds[0] || bounds[3] <= bounds[2]) return Viewport()
        return Viewport(bounds[0], bounds[1], bounds[2], bounds[3])
    }

    companion object {
        /** Four is where the colours stop being distinguishable and the frame budget bites. */
        const val MAX_PLOTS = 4
        internal const val RESAMPLE_DEBOUNCE_MS = 80L

        /** The longest a moving finger can hold the samples still — see [resampleDelayMillis]. */
        internal const val RESAMPLE_MAX_WAIT_MS = 250L
        private const val NANOS_PER_MILLI = 1_000_000L

        /**
         * Whole window widths sampled beyond each edge, so that a pan reveals curve that has
         * already been sampled — see [GraphSampler.sample]. One each side is three windows
         * of columns, about three thousand evaluations, and covers a drag of a full screen
         * or a pinch out to a third of the scale before anything unsampled shows.
         */
        private const val SAMPLE_MARGIN_WINDOWS = 1
        private const val KEY_EXPRESSIONS = "graph_expressions"
        private const val KEY_VIEWPORT = "graph_viewport"
    }
}

/** Builds the view model without a DI framework, matching the rest of the app. */
fun graphingViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { GraphingViewModel(createSavedStateHandle()) }
}
