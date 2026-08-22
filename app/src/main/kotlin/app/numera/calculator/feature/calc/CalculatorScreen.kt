package app.numera.calculator.feature.calc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.core.Animatable
import app.numera.calculator.R
import app.numera.calculator.data.HistoryEntry
import app.numera.calculator.data.LocalHistoryStore
import app.numera.calculator.feature.history.DrawerState
import app.numera.calculator.feature.history.HistoryDrawer
import kotlinx.coroutines.launch
import app.numera.calculator.math.AngleMode
import app.numera.calculator.math.expr.CalculatorExpr
import app.numera.calculator.math.expr.ExprCodec
import app.numera.calculator.math.expr.KeyId
import app.numera.calculator.nav.Route
import app.numera.calculator.settings.LocalSettingsStore
import app.numera.calculator.ui.common.CalcButton
import app.numera.calculator.ui.common.KeyStyle
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The calculator — the app's root surface and the one that has to pass as a replica.
 *
 * Everything visible here is modelled on Google Calculator: the two-line display with a
 * live preview, the contextual parenthesis key, token-wise backspace, the advanced pad
 * reached by swiping in from the right, and an overflow menu that is the *only* place the
 * other modes appear. Adding a drawer or a bottom bar would be the single most obvious tell
 * that this is not the real thing, which is why the extra modes hide behind the ⋮ that
 * Google already ships.
 */
@Composable
fun CalculatorScreen(onOpenMode: (Route) -> Unit) {
    val settings = LocalSettingsStore.current
    val history = LocalHistoryStore.current
    val viewModel: CalculatorViewModel =
        viewModel(factory = calculatorViewModelFactory(settings, history))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entries by history.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Through LocalConfiguration, never Locale.getDefault(): the latter is invisible to
    // Compose, so after an in-app language change a paste would still be read against the
    // previous locale's decimal separator.
    val locale = LocalConfiguration.current.locales[0]
    val symbols = remember(locale) { DecimalFormatSymbols.getInstance(locale) }

    // The drawer is loaded lazily: the database is not touched until the screen exists.
    LaunchedEffect(history) { history.refresh() }

    // Offset is an Animatable rather than plain state so a drag can snap it frame-for-frame
    // while a release can animate it, without the two fighting over the same value.
    val offset = remember { Animatable(0f) }
    var drawerHeight by remember { mutableFloatStateOf(0f) }
    // What survives a configuration change is open-or-closed, not the pixel offset: the
    // offset was measured against a height the rotation has already invalidated. The
    // activity is recreated on every rotation while the view model — and so the expression —
    // is retained, so without rememberSaveable the drawer alone silently disappears.
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    // Deferred deliberately. Reading offset.value in this body would subscribe the whole
    // screen — the display, both pads, every key — to every frame of a drag or fling;
    // derivedStateOf narrows that to the one moment the drawer appears or disappears.
    val drawerVisible by remember { derivedStateOf { offset.value > 0f } }

    fun settle(velocity: Float) {
        val landed = DrawerState(offset = offset.value, maxOffset = drawerHeight).settle(velocity)
        drawerOpen = landed.isOpen
        scope.launch { offset.animateTo(landed.offset) }
    }

    // Memoised so they are not a fresh instance on every recomposition. onPaste is also the
    // key of the formula line's gesture detector, and re-keying that mid-gesture cancels the
    // long press the user is in the middle of making.
    val onCopy: () -> Unit = remember(context, viewModel, scope) {
        { scope.launch { copyResult(context, viewModel) } }
    }
    val onPaste: () -> Unit = remember(context, viewModel, scope, symbols) {
        {
            scope.launch {
                pasteFromClipboard(context, symbols)?.let(viewModel::onReplaceExpression)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        BoxWithWideBreakpoint { wide ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(dimensionResource(R.dimen.calc_screen_padding)),
            ) {
                Display(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        // Vertical only. The formula and result inside are horizontally
                        // scrollable, and because each gesture waits for slop on its own
                        // axis, the framework arbitrates between them: a sideways drag
                        // scrolls the formula and never opens the drawer, and vice versa.
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                scope.launch {
                                    offset.snapTo((offset.value + delta).coerceIn(0f, drawerHeight))
                                }
                            },
                            onDragStopped = { velocity -> settle(velocity) },
                        ),
                    onRequestMoreDigits = viewModel::onRequestMoreDigits,
                    onCopy = onCopy,
                    onPaste = onPaste,
                    onToggleAngleMode = viewModel::onToggleAngleMode,
                    onOpenMode = onOpenMode,
                    drawerOpen = drawerOpen,
                    onToggleDrawer = {
                        settle(velocity = if (drawerOpen) -Float.MAX_VALUE else Float.MAX_VALUE)
                    },
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.4f)
                        .onSizeChanged { size ->
                            val height = size.height.toFloat()
                            if (height == drawerHeight) return@onSizeChanged
                            // The offset belongs to the old height. resized() re-derives it
                            // from open-or-closed, which is both what a rotation carries
                            // across and what stops the first drag after a window resize
                            // from jumping by the difference between the two heights.
                            val resized = DrawerState(offset = offset.value, maxOffset = drawerHeight)
                                .resized(height, open = drawerOpen)
                            drawerHeight = resized.maxOffset
                            scope.launch { offset.snapTo(resized.offset) }
                        },
                ) {
                    if (wide) {
                        // A wide window shows both pads at once, with no reveal affordance.
                        // Driven off the measured width rather than Configuration.orientation,
                        // because at targetSdk 36 the system ignores orientation locks on
                        // large windows and the app can be handed any size at all.
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(
                                dimensionResource(R.dimen.calc_key_spacing),
                            ),
                        ) {
                            AdvancedPad(state, viewModel, Modifier.weight(1f).fillMaxHeight())
                            NumericPad(state, viewModel, Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        val pagerState = rememberPagerState(pageCount = { 2 })
                        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                            if (page == 0) {
                                NumericPad(state, viewModel, Modifier.fillMaxSize())
                            } else {
                                AdvancedPad(state, viewModel, Modifier.fillMaxSize())
                            }
                        }
                    }

                    if (drawerVisible) {
                        HistoryDrawer(
                            entries = entries,
                            onSelect = { entry ->
                                viewModel.onInsertHistory(entry.expression)
                                settle(velocity = -Float.MAX_VALUE)
                            },
                            onCopy = { entry -> copyHistoryEntry(context, entry) },
                            onClear = {
                                scope.launch { history.clear() }
                                settle(velocity = -Float.MAX_VALUE)
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                // Slides down into the pad area rather than over the display,
                                // so the formula stays readable while choosing an entry.
                                // The offset is read here rather than in the screen body so
                                // that a drag invalidates this one layer instead of
                                // recomposing the calculator once per frame.
                                .graphicsLayer {
                                    val progress = DrawerState(
                                        offset = offset.value,
                                        maxOffset = drawerHeight,
                                    ).progress
                                    translationY = -size.height * (1f - progress)
                                },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- display

@Composable
private fun Display(
    state: CalculatorUiState,
    modifier: Modifier,
    onRequestMoreDigits: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onToggleAngleMode: () -> Unit,
    onOpenMode: (Route) -> Unit,
    drawerOpen: Boolean,
    onToggleDrawer: () -> Unit,
) {
    val drawerLabel = stringResource(
        if (drawerOpen) R.string.desc_history_close else R.string.desc_history_open,
    )
    val showingResult = state.mode != DisplayMode.INPUT
    // The equals transition: the formula shrinks toward the small line while the result
    // grows into the slot it vacated. Reading the animation scale from the system means a
    // user who has turned animations off gets the end state immediately, which is both an
    // accessibility requirement and what keeps the final layout testable.
    val formulaScale by animateFloatAsState(
        targetValue = if (showingResult) 0.55f else 1f,
        label = "formulaScale",
    )
    val resultScale by animateFloatAsState(
        targetValue = if (showingResult) 1f else 0.55f,
        label = "resultScale",
    )

    Column(
        modifier = modifier
            // The drawer opens by dragging, which a switch- or screen-reader user cannot
            // perform. The same action has to be reachable without the gesture.
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(drawerLabel) {
                        onToggleDrawer()
                        true
                    },
                )
            },
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AngleModeChip(state.angleMode, onToggleAngleMode)
            Box(modifier = Modifier.weight(1f))
            OverflowMenu(onOpenMode)
        }

        // Both the formula and the result are pinned to LTR. No physical keypad mirrors
        // its digits in an RTL locale, and neither does Google Calculator; letting bidi
        // reorder a mixed expression would move the operators out from between operands.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            HorizontallyScrollingText(
                text = state.formula,
                scale = formulaScale,
                contentDescription = stringResource(R.string.desc_formula),
                onLongPress = onPaste,
                modifier = Modifier.fillMaxWidth(),
            )

            ComputingIndicator(visible = state.computing)

            val resultText = when (state.mode) {
                DisplayMode.ERROR -> state.errorRes?.let { stringResource(it) }.orEmpty()
                DisplayMode.RESULT -> state.result
                DisplayMode.INPUT -> state.preview
            }
            ResultLine(
                text = resultText,
                scale = resultScale,
                isError = state.mode == DisplayMode.ERROR,
                announce = showingResult,
                hasMoreDigits = state.hasMoreDigits,
                onRequestMoreDigits = onRequestMoreDigits,
                onCopy = onCopy,
            )
        }
    }
}

/**
 * The result line, which is also the thing the user scrolls to reveal more digits.
 *
 * Written inline rather than reusing ScrollableValueText because it has to *observe* its
 * own scroll position: reaching the right-hand end is the signal to ask the evaluator for
 * more precision, and that is the whole mechanism behind scrolling one seventh past a
 * thousand digits.
 */
@Composable
private fun ResultLine(
    text: String,
    scale: Float,
    isError: Boolean,
    announce: Boolean,
    hasMoreDigits: Boolean,
    onRequestMoreDigits: () -> Unit,
    onCopy: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val moreDigitsLabel = stringResource(R.string.desc_more_digits)
    var showCopy by remember { mutableStateOf(false) }

    if (hasMoreDigits) {
        LaunchedEffect(scrollState, hasMoreDigits) {
            snapshotFlow { scrollState.value to scrollState.maxValue }
                .collect { (value, max) ->
                    // Ask well before the end so the next block of digits has landed by the
                    // time the finger gets there; waiting until the true edge shows a stop.
                    if (max > 0 && value >= max - 8) onRequestMoreDigits()
                }
        }
    }

    Box {
        Text(
            text = text,
            style = MaterialTheme.typography.displayMedium.copy(
                fontSize = MaterialTheme.typography.displayMedium.fontSize * scale,
            ),
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .pointerInput(text) {
                    // Opens a menu rather than copying outright. A silent copy gives the
                    // user no confirmation that anything happened and no way to discover
                    // that the gesture exists at all.
                    detectTapGestures(onLongPress = { if (text.isNotEmpty()) showCopy = true })
                }
                .semantics {
                    // Announced after equals without the user having to move focus there —
                    // but only then. While typing this line carries the running preview, and
                    // a live region there interrupts with a number after every single key,
                    // burying the announcement of the key that was actually pressed.
                    if (announce) liveRegion = LiveRegionMode.Polite
                    if (hasMoreDigits) {
                        customActions = listOf(
                            CustomAccessibilityAction(moreDigitsLabel) {
                                onRequestMoreDigits()
                                true
                            },
                        )
                    }
                },
        )

        DropdownMenu(expanded = showCopy, onDismissRequest = { showCopy = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_copy)) },
                onClick = {
                    onCopy()
                    showCopy = false
                },
            )
        }
    }
}

@Composable
private fun HorizontallyScrollingText(
    text: String,
    scale: Float,
    contentDescription: String,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    // Read through rememberUpdatedState so the gesture detector below can be keyed on Unit.
    // Keyed on the callback instead, any recomposition that produced a new lambda — a
    // preview result landing, for instance — would restart the pointer-input node and throw
    // away a long press already in progress.
    val longPress by rememberUpdatedState(onLongPress)
    // Re-pin to the right whenever the text grows, so the caret end stays visible while
    // typing. Keyed on maxValue too: on the frame the text changes, maxValue still holds
    // the previous width and scrolling to it lands short of the new end.
    LaunchedEffect(text, scrollState.maxValue) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.displaySmall.copy(
            fontSize = MaterialTheme.typography.displaySmall.fontSize * scale,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.End,
        modifier = modifier
            .horizontalScroll(scrollState)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { longPress() })
            }
            .semantics { this.contentDescription = contentDescription },
    )
}

/**
 * The only sign that a long evaluation is running.
 *
 * Equals allows fifteen seconds, and until this existed the display was completely unchanged
 * for all of them while the keypad stayed live: indistinguishable from an app that has hung,
 * and one stray key press away from cancelling the calculation being waited on.
 */
@Composable
private fun ComputingIndicator(visible: Boolean) {
    val label = stringResource(R.string.desc_computing)
    // The space is held whether or not the bar is showing, so the result line does not jump
    // by its height at the moment the answer arrives.
    Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
        if (visible) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = label
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
    }
}

@Composable
private fun AngleModeChip(angleMode: AngleMode, onToggle: () -> Unit) {
    val isDegrees = angleMode == AngleMode.DEGREES
    // Hoisted: stringResource cannot be called inside the semantics lambda, which is why
    // this was two English string constants that no locale ever translated — invisible to
    // the HardcodedText check, because that inspects XML attributes and not Kotlin.
    val label = stringResource(
        if (isDegrees) R.string.desc_switch_rad else R.string.desc_switch_deg,
    )
    TextButton(
        onClick = onToggle,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Text(
            text = stringResource(if (isDegrees) R.string.mode_deg else R.string.mode_rad),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun OverflowMenu(onOpenMode: (Route) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.desc_more_options),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Modes live here, and only here. This is the affordance Google Calculator
            // already has, so extending it keeps the home surface identical to the original.
            MenuEntry(R.string.title_converter, Route.Converter, onOpenMode) { expanded = false }
            MenuEntry(R.string.title_programmer, Route.Programmer, onOpenMode) { expanded = false }
            MenuEntry(R.string.title_graphing, Route.Graphing, onOpenMode) { expanded = false }
            MenuEntry(R.string.title_datetime, Route.DateTime, onOpenMode) { expanded = false }
            MenuEntry(R.string.title_financial, Route.Financial, onOpenMode) { expanded = false }
            HorizontalDivider()
            MenuEntry(R.string.title_settings, Route.Settings, onOpenMode) { expanded = false }
        }
    }
}

@Composable
private fun MenuEntry(
    labelRes: Int,
    route: Route,
    onOpenMode: (Route) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        onClick = {
            onDismiss()
            onOpenMode(route)
        },
    )
}

// ---------------------------------------------------------------------------- pads

@Composable
private fun NumericPad(
    state: CalculatorUiState,
    viewModel: CalculatorViewModel,
    modifier: Modifier,
) {
    // Read through LocalConfiguration, not Locale.getDefault(). The latter is invisible to
    // Compose, so switching the app's language at runtime would leave the pad rendering the
    // previous locale's digits until something else happened to recompose it.
    val locale = LocalConfiguration.current.locales[0]
    val symbols = remember(locale) { DecimalFormatSymbols.getInstance(locale) }
    val zero = symbols.zeroDigit
    val separator = symbols.decimalSeparator

    // Digits are built from the locale's own symbols rather than from string resources,
    // because an Arabic locale must show ٧ and not 7, and no amount of translation of a
    // literal "7" would produce that.
    fun digit(n: Int): String = (zero + n).toString()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.calc_key_spacing)),
        ) {
            PadRow {
                Key(stringResource(R.string.key_clear), stringResource(R.string.desc_clear),
                    KeyStyle.DESTRUCTIVE) { viewModel.onClear() }
                Key(stringResource(R.string.key_paren), stringResource(R.string.desc_paren),
                    KeyStyle.FUNCTION) { viewModel.onSmartParen() }
                Key(stringResource(R.string.op_pct), stringResource(R.string.desc_op_pct),
                    KeyStyle.FUNCTION) { viewModel.onKey(KeyId.PERCENT) }
                Key(stringResource(R.string.op_div), stringResource(R.string.desc_op_div),
                    KeyStyle.OPERATOR) { viewModel.onKey(KeyId.DIVIDE) }
            }
            PadRow {
                DigitKey(digit(7), viewModel, KeyId.D7)
                DigitKey(digit(8), viewModel, KeyId.D8)
                DigitKey(digit(9), viewModel, KeyId.D9)
                Key(stringResource(R.string.op_mul), stringResource(R.string.desc_op_mul),
                    KeyStyle.OPERATOR) { viewModel.onKey(KeyId.MULTIPLY) }
            }
            PadRow {
                DigitKey(digit(4), viewModel, KeyId.D4)
                DigitKey(digit(5), viewModel, KeyId.D5)
                DigitKey(digit(6), viewModel, KeyId.D6)
                Key(stringResource(R.string.op_sub), stringResource(R.string.desc_op_sub),
                    KeyStyle.OPERATOR) { viewModel.onKey(KeyId.SUBTRACT) }
            }
            PadRow {
                DigitKey(digit(1), viewModel, KeyId.D1)
                DigitKey(digit(2), viewModel, KeyId.D2)
                DigitKey(digit(3), viewModel, KeyId.D3)
                Key(stringResource(R.string.op_add), stringResource(R.string.desc_op_add),
                    KeyStyle.OPERATOR) { viewModel.onKey(KeyId.ADD) }
            }
            PadRow {
                DigitKey(digit(0), viewModel, KeyId.D0)
                Key(separator.toString(), stringResource(R.string.desc_dec_point),
                    KeyStyle.DIGIT) { viewModel.onKey(KeyId.POINT) }
                // Long-press clears everything, exactly as Google Calculator does.
                Key(
                    label = stringResource(R.string.key_del),
                    description = stringResource(R.string.desc_del),
                    style = KeyStyle.FUNCTION,
                    onLongClick = { viewModel.onClear() },
                ) { viewModel.onDelete() }
                Key(stringResource(R.string.key_eq), stringResource(R.string.desc_eq),
                    KeyStyle.ACCENT) { viewModel.onEquals() }
            }
        }
    }
}

@Composable
private fun AdvancedPad(
    state: CalculatorUiState,
    viewModel: CalculatorViewModel,
    modifier: Modifier,
) {
    val inverse = state.inverse
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.calc_key_spacing)),
    ) {
        PadRow {
            Key(
                stringResource(R.string.key_inv),
                stringResource(if (inverse) R.string.desc_inv_on else R.string.desc_inv_off),
                KeyStyle.FUNCTION,
            ) { viewModel.onToggleInverse() }
            Key(
                stringResource(if (state.angleMode == AngleMode.DEGREES) R.string.mode_deg else R.string.mode_rad),
                stringResource(
                    if (state.angleMode == AngleMode.DEGREES) R.string.desc_switch_rad
                    else R.string.desc_switch_deg,
                ),
                KeyStyle.FUNCTION,
            ) { viewModel.onToggleAngleMode() }
            // INV swaps the six trig and log labels in place rather than revealing a third
            // pad, which is what keeps the layout stable under the user's thumb.
            Key(
                stringResource(if (inverse) R.string.fun_arcsin else R.string.fun_sin),
                stringResource(if (inverse) R.string.desc_fun_arcsin else R.string.desc_fun_sin),
                KeyStyle.FUNCTION,
            ) { viewModel.onKey(if (inverse) KeyId.ASIN else KeyId.SIN) }
            Key(
                stringResource(if (inverse) R.string.fun_arccos else R.string.fun_cos),
                stringResource(if (inverse) R.string.desc_fun_arccos else R.string.desc_fun_cos),
                KeyStyle.FUNCTION,
            ) { viewModel.onKey(if (inverse) KeyId.ACOS else KeyId.COS) }
            Key(
                stringResource(if (inverse) R.string.fun_arctan else R.string.fun_tan),
                stringResource(if (inverse) R.string.desc_fun_arctan else R.string.desc_fun_tan),
                KeyStyle.FUNCTION,
            ) { viewModel.onKey(if (inverse) KeyId.ATAN else KeyId.TAN) }
        }
        PadRow {
            Key(stringResource(R.string.const_pi), stringResource(R.string.desc_const_pi),
                KeyStyle.FUNCTION) { viewModel.onKey(KeyId.PI) }
            Key(stringResource(R.string.const_e), stringResource(R.string.desc_const_e),
                KeyStyle.FUNCTION) { viewModel.onKey(KeyId.E) }
            Key(stringResource(R.string.op_pow), stringResource(R.string.desc_op_pow),
                KeyStyle.FUNCTION) { viewModel.onKey(KeyId.POWER) }
            Key(
                stringResource(if (inverse) R.string.op_sqr else R.string.op_sqrt),
                stringResource(if (inverse) R.string.desc_op_sqr else R.string.desc_op_sqrt),
                KeyStyle.FUNCTION,
            ) { viewModel.onKey(if (inverse) KeyId.SQUARE else KeyId.SQRT) }
            Key(stringResource(R.string.op_fact), stringResource(R.string.desc_op_fact),
                KeyStyle.FUNCTION) { viewModel.onKey(KeyId.FACTORIAL) }
        }
        PadRow {
            Key(
                stringResource(if (inverse) R.string.fun_exp else R.string.fun_ln),
                stringResource(if (inverse) R.string.desc_fun_exp else R.string.desc_fun_ln),
                KeyStyle.FUNCTION,
            ) { viewModel.onKey(if (inverse) KeyId.EXPE else KeyId.LN) }
            Key(
                stringResource(if (inverse) R.string.fun_10pow else R.string.fun_log),
                stringResource(if (inverse) R.string.desc_fun_10pow else R.string.desc_fun_log),
                KeyStyle.FUNCTION,
            ) { viewModel.onKey(if (inverse) KeyId.EXP10 else KeyId.LOG) }
            Key(stringResource(R.string.key_lparen), stringResource(R.string.desc_paren),
                KeyStyle.FUNCTION) { viewModel.onKey(KeyId.LEFT_PAREN) }
            Key(stringResource(R.string.key_rparen), stringResource(R.string.desc_paren),
                KeyStyle.FUNCTION) { viewModel.onKey(KeyId.RIGHT_PAREN) }
            Key(stringResource(R.string.op_pct), stringResource(R.string.desc_op_pct),
                KeyStyle.FUNCTION) { viewModel.onKey(KeyId.PERCENT) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PadRow(
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.calc_key_spacing)),
        content = content,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Key(
    label: String,
    description: String,
    style: KeyStyle,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    CalcButton(
        label = label,
        contentDescription = description,
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
        style = style,
        onLongClick = onLongClick,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DigitKey(
    label: String,
    viewModel: CalculatorViewModel,
    key: KeyId,
) {
    Key(label, stringResource(R.string.desc_digit, label), KeyStyle.DIGIT) { viewModel.onKey(key) }
}

// ---------------------------------------------------------------------------- helpers

/**
 * Reports whether the window is wide enough to show both pads.
 *
 * Measured, not inferred from orientation: a freeform or split-screen window on Android 16
 * can be any shape at all, and asking the Configuration for "landscape" answers a different
 * question than the one the layout needs.
 */
@Composable
private fun BoxWithWideBreakpoint(content: @Composable (Boolean) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints {
        content(maxWidth >= 600.dp)
    }
}

/**
 * Copies the current result.
 *
 * Puts two items on the clipboard: the readable value for other applications, and Numera's
 * own encoded expression as a second item. The previous implementation copied
 * `state.result` — the *localised, twenty-character truncated* display string — which lost
 * precision twice over and, in a locale with non-Latin digits, could not even be pasted back.
 */
private suspend fun copyResult(context: Context, viewModel: CalculatorViewModel) {
    val payload = viewModel.clipboardPayload() ?: return
    putOnClipboard(context, payload.text, payload.encodedExpression)
}

/** Copies a history row, carrying its exact expression the same way. */
private fun copyHistoryEntry(context: Context, entry: HistoryEntry) {
    putOnClipboard(context, entry.result, ExprCodec.encodeToString(entry.expression))
}

private fun putOnClipboard(context: Context, text: String, encodedExpression: String) {
    if (text.isEmpty()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(CLIP_LABEL, text)
    // A second item, read only by us. Other apps see item 0 and are unaffected.
    clip.addItem(ClipData.Item(encodedExpression))
    clipboard.setPrimaryClip(clip)
}

/**
 * Reads the clipboard, preferring Numera's own exact payload over the rendered text.
 *
 * The label is the marker. When it matches, the second item holds the original token stream,
 * so copy-then-paste round-trips a value with no loss at all; anything else falls back to
 * tokenising whatever text is there, which is what makes pasting `12+34` from a notes app work.
 *
 * On [Dispatchers.IO] because reading a clip is not necessarily cheap: `coerceToText` on a
 * `content://` clip — what copying a photo or a file leaves behind — resolves the URI through
 * the `ContentResolver` and reads it, and this is invoked from a long press on the frame
 * thread.
 */
private suspend fun pasteFromClipboard(
    context: Context,
    symbols: DecimalFormatSymbols,
): CalculatorExpr? = withContext(Dispatchers.IO) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: return@withContext null

    if (clip.description.label == CLIP_LABEL && clip.itemCount > 1) {
        val encoded = clip.getItemAt(1).text?.toString()
        if (encoded != null) {
            val decoded = ExprCodec.decodeFromString(encoded)
            if (decoded != null) return@withContext decoded
        }
    }
    // getItemAt is a list index, not a lookup: on an empty clip it throws rather than
    // returning null, and the safe call gives no protection because the platform type is
    // non-null as far as Kotlin can tell.
    if (clip.itemCount == 0) return@withContext null
    val item = clip.getItemAt(0)
    // Plain text first, so the common case never touches a content provider at all.
    val text = item.text?.toString() ?: item.coerceToText(context)?.toString()
    if (text.isNullOrEmpty()) return@withContext null
    parsePastedText(text, symbols)
}

/** Distinctive enough that another app's clipboard entry cannot be mistaken for ours. */
private const val CLIP_LABEL = "app.numera.calculator/expression"
