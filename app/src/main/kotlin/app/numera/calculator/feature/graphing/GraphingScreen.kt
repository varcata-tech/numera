package app.numera.calculator.feature.graphing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.text.BidiFormatter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.numera.calculator.R
import app.numera.calculator.ui.common.ModeScaffold
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The graphing calculator.
 *
 * Plotting runs entirely on the double-precision compiled closure from
 * [app.numera.calculator.math.expr.ExprEvaluator.compileToDouble], never on the exact
 * engine: a frame needs a sample per pixel column, and exact arithmetic is orders of
 * magnitude too slow for that. The exact engine stays authoritative for numbers the app
 * *prints*; this path only decides where pixels go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GraphingScreen(onBack: () -> Unit) {
    val viewModel: GraphingViewModel = viewModel(factory = graphingViewModelFactory())
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Read through LocalConfiguration, not Locale.getDefault(). The latter is invisible to
    // composition, so an app-language change would leave every readout on this screen
    // formatted in the language the user just left.
    val locale: Locale = LocalConfiguration.current.locales[0]

    // Remembered against the view model rather than written inline at the call. Every pointer
    // event of a pan writes the viewport, so this composable re-runs at pointer-event rate,
    // and a bound reference to an unstable type is a fresh object each time it is evaluated —
    // which would make the canvas and the whole function list below it non-skippable however
    // carefully their other parameters are hoisted.
    val onPan: (Float, Float, Float, Float) -> Unit = remember(viewModel) { viewModel::onPan }
    val onZoom: (Float, Offset, Float, Float) -> Unit = remember(viewModel) { viewModel::onZoom }
    val onTrace: (Float, Float) -> Unit = remember(viewModel) { viewModel::onTrace }
    val onResize: (Int, Int) -> Unit = remember(viewModel) { viewModel::onCanvasResized }
    val onAdd: (String) -> Boolean = remember(viewModel) { viewModel::onAddFunction }
    val onEdited: () -> Unit = remember(viewModel) { viewModel::onExpressionEdited }
    val onRemove: (Int) -> Unit = remember(viewModel) { viewModel::onRemoveFunction }
    val onResetView: () -> Unit = remember(viewModel) { viewModel::onResetView }
    val onSquareAxes: () -> Unit = remember(viewModel) { viewModel::onSquareAxes }
    val onClearTrace: () -> Unit = remember(viewModel) { viewModel::onClearTrace }

    // The window's bounds, printed under the canvas and spoken as part of its description.
    // The gridlines carry no numbers, so with the bounds only in the description a sighted
    // user had no way to tell, after a couple of pinches, whether the visible span was twenty
    // units or a millionth of one — or whether "Square the axes" had done anything at all.
    val summary: String = stringResource(
        R.string.graph_summary,
        state.viewport.minX.pretty(locale, state.viewport.width),
        state.viewport.maxX.pretty(locale, state.viewport.width),
        state.viewport.minY.pretty(locale, state.viewport.height),
        state.viewport.maxY.pretty(locale, state.viewport.height),
    )

    ModeScaffold(title = stringResource(R.string.title_graphing), onBack = onBack) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            // Measured, not inferred from orientation, for the reason the programmer screen
            // gives: a freeform or split-screen window can be any shape. See
            // [controlsHeightCap] for what the cap prevents.
            val controlsCap: Dp = controlsHeightCap(maxHeight)
            Column(modifier = Modifier.fillMaxSize()) {
                GraphCanvas(
                    viewport = state.viewport,
                    plots = state.plots,
                    trace = state.trace,
                    columns = state.canvasColumns,
                    summary = summary,
                    onPan = onPan,
                    onZoom = onZoom,
                    onTrace = onTrace,
                    onResize = onResize,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = controlsCap)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // One line, always: a child under the canvas whose height varied with
                    // the state the canvas reports would be a layout feedback loop.
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    // A FlowRow, not a Row. A Row measures each child against the width its
                    // earlier siblings left, so a label that does not fit is not clipped: it
                    // is handed a width near zero and breaks one glyph per line. In de the
                    // first two labels alone exceed a 360dp phone, and the third —
                    // "Fadenkreuz entfernen" — came out as a twenty-line column of single
                    // letters that took the whole canvas height with it. The third button is
                    // composed whether or not there is a trace to clear, so that placing one
                    // does not re-measure the row and shift the canvas above it.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = onResetView) {
                            Text(stringResource(R.string.graph_reset_view))
                        }
                        TextButton(onClick = onSquareAxes) {
                            Text(stringResource(R.string.graph_square_axes))
                        }
                        TextButton(onClick = onClearTrace, enabled = state.trace != null) {
                            Text(stringResource(R.string.graph_clear_trace))
                        }
                    }

                    Readouts(
                        viewport = state.viewport,
                        trace = state.trace,
                        roots = state.roots,
                        locale = locale,
                    )

                    FunctionList(
                        plots = state.plots,
                        lastAddFailed = state.lastAddFailed,
                        onAdd = onAdd,
                        onEdited = onEdited,
                        onRemove = onRemove,
                    )
                }
            }
        }
    }
}

/**
 * How tall the controls under the canvas may be, for a content area [available] tall.
 *
 * The canvas is the Column's only weighted child, so it is handed whatever the controls
 * leave, and the controls — the button row, the readouts, the expression field and a
 * function list of up to 120dp — come to about 300dp with three functions plotted. A phone
 * in landscape has about that much content height altogether, so the plot was measured to a
 * strip a few dp tall with two functions and to nothing with three; with the keyboard up the
 * Column overflowed, and the very field being typed into was laid out below the visible area.
 *
 * The controls are therefore capped so that the canvas keeps [MIN_CANVAS_HEIGHT], and they
 * scroll inside the cap. The cap never falls under [MIN_CONTROLS_HEIGHT] while there is that
 * much height at all: below the point where both fit, the canvas gives way first — one dp at
 * a time rather than at a cliff — because the field is the one thing the user can act on
 * when the keyboard has taken the height, and when even that does not fit the controls get
 * all of it.
 */
internal fun controlsHeightCap(available: Dp): Dp {
    val leavingCanvas: Dp = available - MIN_CANVAS_HEIGHT
    val floor: Dp = if (available < MIN_CONTROLS_HEIGHT) available else MIN_CONTROLS_HEIGHT
    return if (leavingCanvas > floor) leavingCanvas else floor
}

/** The plot area the controls may not take from the canvas while there is room for both. */
private val MIN_CANVAS_HEIGHT: Dp = 160.dp

/** Enough for the expression field and its supporting line to be usable inside the cap. */
private val MIN_CONTROLS_HEIGHT: Dp = 120.dp

/**
 * The trace and roots lines.
 *
 * Both are fixed in height on purpose. They are non-weighted children of the same Column as
 * the weighted canvas, so Compose measures them first and hands the canvas whatever is left:
 * an uncapped roots line — `sin(100x)` produces hundreds of them — measured the canvas *and*
 * the function list below it to zero height, taking away the only control that could delete
 * the offending function. The trace line is rendered even when empty for the same reason in
 * reverse: appearing for the first time would otherwise squash the plot.
 */
@Composable
private fun Readouts(
    viewport: Viewport,
    trace: TracePoint?,
    roots: RootsReadout?,
    locale: Locale,
) {
    val traceText: String = if (trace == null) {
        ""
    } else if (trace.y.isNaN()) {
        // The column has no value — ln of a negative, 1/x on zero. Said in words: with the
        // line blank and the dot gone, a tap there was indistinguishable from "Clear trace".
        stringResource(R.string.graph_trace_undefined, trace.x.pretty(locale, viewport.width))
    } else {
        val x = trace.x.pretty(locale, viewport.width)
        val y = trace.y.pretty(locale, viewport.height)
        // Reporting a coordinate with no crosshair anywhere on the canvas reads as a bug; say
        // that the point is off the window rather than leaving the user hunting for it.
        if (viewport.contains(trace)) {
            stringResource(R.string.graph_trace_value, x, y)
        } else {
            stringResource(R.string.graph_trace_offscreen, x, y)
        }
    }
    Text(
        text = traceText,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // The crosshair is placed by a gesture on a canvas that has no text of its own, so
        // without this a screen-reader user moves the trace and is told nothing at all.
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )

    val separator = stringResource(R.string.graph_list_separator)
    // The expression is Latin notation dropped into prose that may run right to left. Without
    // an isolate around it the bidi algorithm reorders the readout's "x^2-2" against the
    // Arabic surrounding it, and the line names a function nobody typed.
    val bidi: BidiFormatter = remember(locale) { BidiFormatter.getInstance(locale) }
    val values: List<Double> = roots?.values.orEmpty()
    val rootsText: String = if (roots == null || values.isEmpty()) {
        stringResource(R.string.graph_no_roots)
    } else {
        val shown = values.take(MAX_LISTED_ROOTS)
        val list = shown.joinToString(separator) { it.pretty(locale, viewport.width) }
        val subject: String = bidi.unicodeWrap(roots.subject)
        if (values.size > shown.size) {
            pluralStringResource(
                R.plurals.graph_roots_of_truncated,
                shown.size,
                shown.size, subject, list,
            )
        } else {
            stringResource(R.string.graph_roots_of, subject, list)
        }
    }
    // Fixed at two lines in both directions. The canvas now reports its height back into the
    // state, so anything below it whose height depends on that state is a feedback loop; a
    // readout that is always the same height cannot start one.
    Text(
        text = rootsText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        minLines = 2,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun GraphCanvas(
    viewport: Viewport,
    plots: List<Plot>,
    trace: TracePoint?,
    columns: Int,
    summary: String,
    onPan: (Float, Float, Float, Float) -> Unit,
    onZoom: (Float, Offset, Float, Float) -> Unit,
    onTrace: (Float, Float) -> Unit,
    onResize: (Int, Int) -> Unit,
    modifier: Modifier,
) {
    val axisColor = MaterialTheme.colorScheme.outline
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val traceColor = MaterialTheme.colorScheme.onSurface
    // A graph is inherently visual, so the description carries the numbers a sighted user
    // reads off the axes. "Graph" alone tells a screen-reader user nothing at all. The
    // sentences are joined through a resource rather than a literal ". ": ja and zh end a
    // sentence with U+3002 and fr puts a space before some marks, and a hardcoded join is
    // invisible to the MissingTranslation check that would otherwise catch it.
    val sentence = stringResource(R.string.graph_sentence_separator)
    val description = listOf(
        stringResource(R.string.desc_graph_canvas),
        summary,
        stringResource(R.string.graph_angle_unit),
    ).joinToString(sentence)
    val traceCentre = stringResource(R.string.graph_trace_centre)

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // A Compose Canvas does not clip its own drawing. The sampler works in graph
                // coordinates and happily produces y values far outside the viewport — a
                // parabola at the reset view leaves the top of the plot area almost at once —
                // so the curve was drawn straight over the readout, the buttons and the
                // function list below it, in ink the same colour as the plot. It read as a
                // rendering fault rather than as a line that simply continues off screen.
                .clipToBounds()
                // Reported from the layout phase, never from inside the draw lambda. The size
                // feeds state that the layout depends on, and a draw-phase write to it is an
                // invalidate-draw-invalidate loop waiting for the moment the reported value
                // starts depending on the layout.
                .onSizeChanged { onResize(it.width, it.height) }
                .semantics {
                    contentDescription = description
                    // The trace is otherwise reachable only by tapping a pixel column, which
                    // is no path at all for a screen-reader user: the whole feature, and the
                    // readout under the canvas, would be unreachable without touch. The
                    // centre column is the one point on the canvas that can be named.
                    onClick(label = traceCentre) {
                        if (columns > 0) onTrace(columns / 2f, columns.toFloat())
                        columns > 0
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        if (zoomChange != 1f) {
                            onZoom(zoomChange, centroid, size.width.toFloat(), size.height.toFloat())
                        }
                        if (panChange != Offset.Zero) {
                            onPan(
                                panChange.x, panChange.y,
                                size.width.toFloat(), size.height.toFloat(),
                            )
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onTrace(offset.x, size.width.toFloat())
                    }
                },
        ) {
            drawGrid(viewport, gridColor, axisColor)
            plots.forEachIndexed { index, plot ->
                drawPlot(plot, viewport, PLOT_COLORS[index % PLOT_COLORS.size])
            }
            trace?.let { drawTrace(it, viewport, traceColor) }
        }
    }
}

private fun DrawScope.drawGrid(viewport: Viewport, grid: Color, axis: Color) {
    for (x in AxisTicks.ticks(viewport.minX, viewport.maxX)) {
        val px = viewport.worldToScreenX(x, size.width)
        drawLine(grid, Offset(px, 0f), Offset(px, size.height), strokeWidth = 1f)
    }
    for (y in AxisTicks.ticks(viewport.minY, viewport.maxY)) {
        val py = viewport.worldToScreenY(y, size.height)
        drawLine(grid, Offset(0f, py), Offset(size.width, py), strokeWidth = 1f)
    }
    // The axes themselves, drawn heavier, and only when zero is actually in view.
    if (viewport.minY <= 0.0 && viewport.maxY >= 0.0) {
        val py = viewport.worldToScreenY(0.0, size.height)
        drawLine(axis, Offset(0f, py), Offset(size.width, py), strokeWidth = 2.5f)
    }
    if (viewport.minX <= 0.0 && viewport.maxX >= 0.0) {
        val px = viewport.worldToScreenX(0.0, size.width)
        drawLine(axis, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.5f)
    }
}

private fun DrawScope.drawPlot(plot: Plot, viewport: Viewport, color: Color) {
    val samples = plot.samples ?: return
    val path = Path()
    var started = false
    for (i in samples.xs.indices) {
        val y = samples.ys[i]
        // Only a sample with no value is skipped. A break describes the *segment* arriving at
        // this sample, not the sample itself, so on a break the point still opens the next
        // subpath — dropping it as well cost the curve a whole pixel column at every
        // asymptote and erased any sample that stood alone between two breaks.
        if (y.isNaN()) {
            started = false
            continue
        }
        // A segment the sampler showed to be a steep run of the function is joined however
        // large its jump; see GraphSampler.sample. Under a window that has since been
        // pinched narrower the jump test is re-asked against the new height, which is what
        // keeps 1/x broken at its pole until the resample lands.
        if (started && i > 0 && !samples.continuous[i] &&
            GraphSampler.breaksBetween(samples.ys[i - 1], y, viewport)
        ) {
            started = false
        }
        val px = viewport.worldToScreenX(samples.xs[i], size.width)
        val py = onCanvasY(viewport.worldToScreenY(y, size.height), size.height)
        if (!started) {
            path.moveTo(px, py)
            // A zero-length segment so that an isolated sample still marks its position; a
            // bare moveTo strokes nothing at all.
            path.lineTo(px, py)
            started = true
        } else {
            path.lineTo(px, py)
        }
    }
    drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round))
}

/** The crosshair, drawn only where it can actually be seen. */
private fun DrawScope.drawTrace(trace: TracePoint, viewport: Viewport, color: Color) {
    if (trace.y.isNaN()) {
        // No value at this column: the vertical line marks where the tap landed, and the
        // readout says why there is no dot on it.
        if (trace.x < viewport.minX || trace.x > viewport.maxX) return
        val px = viewport.worldToScreenX(trace.x, size.width)
        drawLine(color, Offset(px, 0f), Offset(px, size.height), strokeWidth = 1f)
        return
    }
    if (!viewport.contains(trace)) return
    val px = viewport.worldToScreenX(trace.x, size.width)
    val py = viewport.worldToScreenY(trace.y, size.height)
    drawLine(color, Offset(px, 0f), Offset(px, size.height), strokeWidth = 1f)
    drawLine(color, Offset(0f, py), Offset(size.width, py), strokeWidth = 1f)
    drawCircle(color, radius = 6f, center = Offset(px, py))
}

/**
 * Keeps a wildly off-screen sample at a finite pixel coordinate.
 *
 * A sample of 1e300 converts to more than a Float can hold, and a single non-finite point can
 * cost the whole stroked path rather than just its own segment. A sample now opens the next
 * subpath even when the segment reaching it was broken, so such a point does get drawn.
 * Ten screens away is far outside the clip, so nothing visible moves.
 */
private fun onCanvasY(py: Float, height: Float): Float =
    if (py.isNaN()) height / 2f else py.coerceIn(-10f * height, 11f * height)

/** Whether the traced point lies inside the window, so a crosshair for it would be visible. */
private fun Viewport.contains(trace: TracePoint): Boolean =
    trace.x >= minX && trace.x <= maxX && trace.y >= minY && trace.y <= maxY

/**
 * The expression field and the list of what is plotted.
 *
 * Takes the two pieces of state it draws rather than the whole [GraphingUiState], and plain
 * lambdas rather than the view model. Handed the state object, the text field and this whole
 * list were re-composed on every pointer event of a pan — nothing here depends on the
 * viewport, and the view model is an unstable type that no amount of hoisting can make
 * skippable.
 */
@Composable
private fun FunctionList(
    plots: List<Plot>,
    lastAddFailed: Boolean,
    onAdd: (String) -> Boolean,
    onEdited: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    // Saveable: a half-typed function is exactly what a rotation would otherwise throw away.
    var draft: String by rememberSaveable { mutableStateOf("") }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Pinned to LTR exactly as the calculator's display is. The field takes an expression
        // with operators in it, and under an RTL layout direction the bidi algorithm reorders
        // "2+3x" as it is drawn, so what the user reads back is not what they typed.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    // The rejection belonged to the text that was submitted, not to the one
                    // being typed now; leaving it up tells the user their correction is wrong
                    // too.
                    onEdited()
                },
                label = { Text(stringResource(R.string.graph_expression)) },
                // Every plot is drawn against radians whatever the app-wide angle mode says,
                // because a graph's x axis is a length. Saying so under the field is the
                // whole of what makes that defensible: sin(30) is 0.5 on the calculator and
                // -0.988 here, and nothing else on this screen would explain the difference.
                supportingText = { Text(stringResource(R.string.graph_angle_unit)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = { if (onAdd(draft)) draft = "" },
            enabled = draft.isNotBlank() && plots.size < GraphingViewModel.MAX_PLOTS,
        ) {
            Text(stringResource(R.string.graph_add_function))
        }
    }
    if (lastAddFailed) {
        Text(
            text = stringResource(R.string.graph_invalid),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp)) {
        itemsIndexed(plots) { index, plot ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The label is notation, not prose — the same reason the keypad's glyphs are
                // translatable="false" — and it is pinned LTR for the same reason the field
                // above it is.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = stringResource(R.string.graph_function_label, plot.expressionText),
                        color = PLOT_COLORS[index % PLOT_COLORS.size],
                        modifier = Modifier.weight(1f),
                    )
                }
                IconButton(onClick = { onRemove(index) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.graph_delete),
                    )
                }
            }
        }
    }
}

/** Distinct plot colours, kept outside the theme so all four stay distinguishable. */
private val PLOT_COLORS = listOf(
    Color(0xFF2E7BE5),
    Color(0xFFE5533D),
    Color(0xFF2FA84F),
    Color(0xFFB44BD6),
)

/** How many roots the readout spells out before it says it is showing only the first few. */
private const val MAX_LISTED_ROOTS = 10

/** Decimal places at the default zoom, and the floor for how many are ever asked for. */
private const val BASE_DECIMALS = 4

/** Past this a `Double` is inventing digits rather than reporting them. */
private const val SIGNIFICANT_DIGITS = 15

/**
 * Trims a coordinate to something readable at the current zoom.
 *
 * The count of decimals follows [span] instead of being fixed. A fixed four places stops
 * telling the truth once the window is narrower than about 1e-3 — and [Viewport.MIN_SPAN]
 * allows 1e-9 — because every column of the screen then prints the same x, two distinct roots
 * print as one number, and a value under 5e-5 prints as a flat "0".
 *
 * At least one decimal is always requested, which is also what makes the trailing-zero trim
 * safe: it stops at the decimal separator, so a round number like 1e10 keeps its zeros.
 *
 * `internal` so the trimming can be tested against a locale that does not use ASCII digits;
 * there is no other way to reach it without rendering the screen.
 */
internal fun Double.pretty(locale: Locale, span: Double): String {
    val decimals = decimalsFor(span, this)
    // A value that rounds to zero must lose its sign first. Java's Formatter keeps it, so
    // anything in (-5e-5, 0) prints as "-0.0000" at the default zoom and the trim below turns
    // that into "-0" — and bisection routinely converges on a root of the order of 1e-63
    // rather than exactly zero, so the roots line of y = x read "Roots of x: -0" on a large
    // fraction of device widths. On an app that sells exactness that reads as an arithmetic
    // bug, and there is no sign to keep: the value being printed is zero.
    val value: Double = if (abs(this) < 0.5 * 10.0.pow(-decimals)) 0.0 else this
    val formatted: String = String.format(locale, "%.${decimals}f", value)
    // Trimmed against the locale's own symbols rather than against ASCII. `%f` formats
    // through the locale's DecimalFormatSymbols, so in ar the digits are Arabic-Indic
    // (U+0660 upward) and the separator is U+066B; an ASCII '0' or '.' matches nothing there,
    // and every readout on this screen kept its padding — "x = ٢٫٠٠٠٠" at the default zoom,
    // and fourteen trailing zeros at the deepest one.
    val symbols: DecimalFormatSymbols = DecimalFormatSymbols.getInstance(locale)
    val separator: Char = symbols.decimalSeparator
    // With no separator in the text the zeros are magnitude rather than padding: trimming
    // them would turn ten thousand million into one.
    if (formatted.indexOf(separator) < 0) return formatted
    return formatted.trimEnd(symbols.zeroDigit).trimEnd(separator)
}

private fun decimalsFor(span: Double, value: Double): Int {
    if (!span.isFinite() || span <= 0.0) return BASE_DECIMALS
    val requested = ceil(-log10(span)).toInt() + BASE_DECIMALS + 1
    val exponent = if (value == 0.0 || !value.isFinite()) {
        0
    } else {
        floor(log10(abs(value))).toInt()
    }
    val useful = (SIGNIFICANT_DIGITS - exponent).coerceIn(1, SIGNIFICANT_DIGITS)
    return requested.coerceIn(1, useful)
}
