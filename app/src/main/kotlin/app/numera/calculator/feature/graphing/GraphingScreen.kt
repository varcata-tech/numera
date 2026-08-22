package app.numera.calculator.feature.graphing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/**
 * The graphing calculator.
 *
 * Plotting runs entirely on the double-precision compiled closure from
 * [app.numera.calculator.math.expr.ExprEvaluator.compileToDouble], never on the exact
 * engine: a frame needs a sample per pixel column, and exact arithmetic is orders of
 * magnitude too slow for that. The exact engine stays authoritative for numbers the app
 * *prints*; this path only decides where pixels go.
 */
@Composable
fun GraphingScreen(onBack: () -> Unit) {
    val viewModel: GraphingViewModel = viewModel(factory = graphingViewModelFactory())
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Read through LocalConfiguration, not Locale.getDefault(). The latter is invisible to
    // composition, so an app-language change would leave every readout on this screen
    // formatted in the language the user just left.
    val locale: Locale = LocalConfiguration.current.locales[0]

    ModeScaffold(title = stringResource(R.string.title_graphing), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            GraphCanvas(
                state = state,
                locale = locale,
                onPan = viewModel::onPan,
                onZoom = viewModel::onZoom,
                onTrace = viewModel::onTrace,
                onResize = viewModel::onCanvasResized,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = viewModel::onResetView) {
                    Text(stringResource(R.string.graph_reset_view))
                }
                TextButton(onClick = viewModel::onSquareAxes) {
                    Text(stringResource(R.string.graph_square_axes))
                }
                if (state.trace != null) {
                    TextButton(onClick = viewModel::onClearTrace) {
                        Text(stringResource(R.string.graph_clear_trace))
                    }
                }
            }

            Readouts(state = state, locale = locale)

            FunctionList(state, viewModel)
        }
    }
}

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
private fun Readouts(state: GraphingUiState, locale: Locale) {
    val viewport = state.viewport
    val trace = state.trace
    val traceText: String = if (trace == null) {
        ""
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
    )

    val roots = state.roots
    val subject = state.plots.firstOrNull()?.expressionText
    val separator = stringResource(R.string.graph_list_separator)
    val rootsText: String = if (roots.isEmpty() || subject == null) {
        stringResource(R.string.graph_no_roots)
    } else {
        val shown = roots.take(MAX_LISTED_ROOTS)
        val list = shown.joinToString(separator) { it.pretty(locale, viewport.width) }
        if (roots.size > shown.size) {
            stringResource(R.string.graph_roots_of_truncated, shown.size, subject, list)
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
    state: GraphingUiState,
    locale: Locale,
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
    // reads off the axes. "Graph" alone tells a screen-reader user nothing at all.
    val description = stringResource(R.string.desc_graph_canvas) + ". " + stringResource(
        R.string.graph_summary,
        state.viewport.minX.pretty(locale, state.viewport.width),
        state.viewport.maxX.pretty(locale, state.viewport.width),
        state.viewport.minY.pretty(locale, state.viewport.height),
        state.viewport.maxY.pretty(locale, state.viewport.height),
    )

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Reported from the layout phase, never from inside the draw lambda. The size
                // feeds state that the layout depends on, and a draw-phase write to it is an
                // invalidate-draw-invalidate loop waiting for the moment the reported value
                // starts depending on the layout.
                .onSizeChanged { onResize(it.width, it.height) }
                .semantics { contentDescription = description }
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
            drawGrid(state, gridColor, axisColor)
            state.plots.forEachIndexed { index, plot ->
                drawPlot(plot, state, PLOT_COLORS[index % PLOT_COLORS.size])
            }
            state.trace?.let { drawTrace(it, state.viewport, traceColor) }
        }
    }
}

private fun DrawScope.drawGrid(state: GraphingUiState, grid: Color, axis: Color) {
    val viewport = state.viewport
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

private fun DrawScope.drawPlot(plot: Plot, state: GraphingUiState, color: Color) {
    val samples = plot.samples ?: return
    val viewport = state.viewport
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
        if (started && i > 0 && GraphSampler.breaksBetween(samples.ys[i - 1], y, viewport)) {
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

@Composable
private fun FunctionList(state: GraphingUiState, viewModel: GraphingViewModel) {
    // Saveable: a half-typed function is exactly what a rotation would otherwise throw away.
    var draft: String by rememberSaveable { mutableStateOf("") }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                // The rejection belonged to the text that was submitted, not to the one being
                // typed now; leaving it up tells the user their correction is wrong too.
                viewModel.onExpressionEdited()
            },
            label = { Text(stringResource(R.string.graph_expression)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                if (viewModel.onAddFunction(draft)) draft = ""
            },
            enabled = draft.isNotBlank() && state.plots.size < GraphingViewModel.MAX_PLOTS,
        ) {
            Text(stringResource(R.string.graph_add_function))
        }
    }
    if (state.lastAddFailed) {
        Text(
            text = stringResource(R.string.graph_invalid),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp)) {
        itemsIndexed(state.plots) { index, plot ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "y = ${plot.expressionText}",
                    color = PLOT_COLORS[index % PLOT_COLORS.size],
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.onRemoveFunction(index) }) {
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
    val formatted: String = String.format(locale, "%.${decimals}f", this)
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
