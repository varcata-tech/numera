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

/** One plotted function: its text, its compiled closure, and its current samples. */
data class Plot(
    val expressionText: String,
    val evaluate: (Double) -> Double,
    val samples: Samples? = null,
)

/** Where the trace crosshair currently sits. */
data class TracePoint(val x: Double, val y: Double)

/** Everything the graphing screen draws. */
data class GraphingUiState(
    val viewport: Viewport = Viewport(),
    val plots: List<Plot> = emptyList(),
    val trace: TracePoint? = null,
    val roots: List<Double> = emptyList(),
    val lastAddFailed: Boolean = false,
    val canvasColumns: Int = 0,
    val canvasRows: Int = 0,
)

/**
 * Owns the viewport, the plotted functions, and the resampling schedule.
 *
 * The resampling policy is the whole performance story. During a gesture the samples are
 * left alone and the existing path is simply redrawn against the new viewport; a fresh
 * sample pass is scheduled once, after the gesture goes quiet. Resampling on every frame of
 * a pinch is what makes a graph stutter, and it is invisible in a screenshot.
 *
 * What survives process death is the expression *text*, not the plot. A [Plot] carries a
 * compiled closure, which cannot be written to a Bundle at all; the text can, and recompiling
 * it on the way back costs one parse and reproduces the plot exactly. Samples are not saved
 * either — they are rebuilt as soon as the canvas reports its size.
 */
class GraphingViewModel(private val savedState: SavedStateHandle) : ViewModel() {

    private var resampleJob: Job? = null

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

    fun onRemoveFunction(index: Int) {
        _state.update {
            it.copy(plots = it.plots.filterIndexed { i, _ -> i != index })
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
     * is not a parameter.
     */
    fun onTrace(px: Float, width: Float) {
        val current = _state.value
        val plot = current.plots.firstOrNull() ?: return
        val x = current.viewport.screenToWorldX(px, width)
        val y = try {
            plot.evaluate(x)
        } catch (e: ArithmeticException) {
            Double.NaN
        }
        _state.update {
            it.copy(trace = if (y.isFinite()) TracePoint(x, y) else null)
        }
    }

    /** Takes the crosshair off the graph; without it the readout can only ever be moved. */
    fun onClearTrace() {
        if (_state.value.trace == null) return
        _state.update { it.copy(trace = null) }
    }

    /**
     * Recomputes samples and roots off the main thread.
     *
     * The debounce is what separates a gesture from its cost: [immediate] is for a discrete
     * change the user is waiting on, while a pan or pinch coalesces into one pass after the
     * finger settles.
     */
    private fun scheduleResample(immediate: Boolean) {
        persist()
        resampleJob?.cancel()
        resampleJob = viewModelScope.launch {
            if (!immediate) delay(RESAMPLE_DEBOUNCE_MS)
            val snapshot = _state.value
            val columns = snapshot.canvasColumns
            if (snapshot.plots.isEmpty()) {
                // Returning here without touching the state left the roots and the traced
                // point of the deleted function on screen, describing a curve that no longer
                // exists and that the user has no way to remove.
                _state.update { it.copy(roots = emptyList(), trace = null) }
                return@launch
            }
            if (columns <= 1) return@launch

            val resampled = withContext(Dispatchers.Default) {
                snapshot.plots.map { plot ->
                    plot.copy(
                        samples = GraphSampler.sample(plot.evaluate, snapshot.viewport, columns),
                    )
                }
            }
            val roots = withContext(Dispatchers.Default) {
                resampled.firstOrNull()?.let { plot ->
                    plot.samples?.let { RootFinder.roots(plot.evaluate, it) }
                }.orEmpty()
            }
            _state.update { it.copy(plots = resampled, roots = roots) }
        }
    }

    /** Compiles to the double-precision closure the plotter samples, or null if it won't parse. */
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
        private const val RESAMPLE_DEBOUNCE_MS = 80L
        private const val KEY_EXPRESSIONS = "graph_expressions"
        private const val KEY_VIEWPORT = "graph_viewport"
    }
}

/** Builds the view model without a DI framework, matching the rest of the app. */
fun graphingViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { GraphingViewModel(createSavedStateHandle()) }
}
