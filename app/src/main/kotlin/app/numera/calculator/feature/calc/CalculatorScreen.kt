package app.numera.calculator.feature.calc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.delay
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

    // The one locale read the view model cannot make for itself. It is retained across the
    // configuration change an in-app language switch causes, so left to `Locale.getDefault()`
    // it would keep rendering answers in the previous locale's digits under a keypad that had
    // already recomposed into the new one.
    LaunchedEffect(viewModel, locale) { viewModel.onLocaleChanged(locale) }

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

    /** Animates the drawer to wherever the state machine says it belongs. */
    fun land(target: DrawerState) {
        drawerOpen = target.isOpen
        scope.launch { offset.animateTo(target.offset) }
    }

    fun here(): DrawerState = DrawerState(offset = offset.value, maxOffset = drawerHeight)

    fun settle(velocity: Float) = land(here().settle(velocity))

    // Through the state machine rather than by hand. Written out inline, the two clamps
    // agreed today and nothing would have noticed them diverging: DrawerStateTest builds
    // every one of its settle cases on drag(), so a change to either copy alone would leave
    // a green suite describing behaviour the app does not have.
    fun closeDrawer() = land(here().closed())

    fun toggleDrawer() = land(if (drawerOpen) here().closed() else here().opened())

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
    // Hoisted out of the pads, and held as one remembered object rather than as seven
    // lambdas. `CalculatorViewModel` is an unstable type, so a pad taking it could never be
    // skipped: every keystroke re-emitted forty CalcButtons and re-ran each label's
    // measurement loop, on the frame that has to answer the key press.
    val actions: PadActions = remember(viewModel) {
        PadActions(
            onKey = viewModel::onKey,
            onClear = viewModel::onClear,
            onSmartParen = viewModel::onSmartParen,
            onDelete = viewModel::onDelete,
            onEquals = viewModel::onEquals,
            onToggleInverse = viewModel::onToggleInverse,
            onToggleAngleMode = viewModel::onToggleAngleMode,
        )
    }

    // The drawer is an overlay pulled down over the pad, and back is how an overlay is
    // dismissed. Without this the gesture leaves the app with the drawer still open, and the
    // only other ways out are an upward drag, choosing a row, or clearing the list.
    BackHandler(enabled = drawerVisible) { closeDrawer() }

    Surface(modifier = Modifier.fillMaxSize()) {
        BoxWithWideBreakpoint { wide ->
            // The overflow floats above the layout rather than taking a row in it. As a
            // row it cost the display band 48dp of height, and in landscape — where both
            // pads are already on screen — that is height the display does not have to give.
            Box(modifier = Modifier.fillMaxSize()) {
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
                                    val dragged = here().drag(delta)
                                    scope.launch { offset.snapTo(dragged.offset) }
                                },
                                onDragStopped = { velocity -> settle(velocity) },
                            ),
                        onRequestMoreDigits = viewModel::onRequestMoreDigits,
                        onCopy = onCopy,
                        onPaste = onPaste,
                        drawerOpen = drawerOpen,
                        onToggleDrawer = { toggleDrawer() },
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1.4f)
                            // The drawer is a child of this Box and is drawn at a negative
                            // translationY while it is being pulled down. Nothing else in the
                            // tree clips — graphicsLayer does not by default, the Column does
                            // not, and Display's own clipToBounds governs Display's children
                            // rather than a later sibling — so without this the drawer's
                            // opaque surface paints over the display for the whole of every
                            // drag, which is exactly what sliding it into the pad area
                            // instead of over the display was meant to avoid.
                            .clipToBounds()
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
                                AdvancedPad(
                                    inverse = state.inverse,
                                    angleMode = state.angleMode,
                                    actions = actions,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                                NumericPad(actions, Modifier.weight(1f).fillMaxHeight())
                            }
                        } else {
                            val pagerState = rememberPagerState(pageCount = { 2 })
                            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                                if (page == 0) {
                                    NumericPad(actions, Modifier.fillMaxSize())
                                } else {
                                    AdvancedPad(
                                        inverse = state.inverse,
                                        angleMode = state.angleMode,
                                        actions = actions,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }

                        if (drawerVisible) {
                            HistoryDrawer(
                                entries = entries,
                                onSelect = { entry ->
                                    viewModel.onInsertHistory(entry.expression)
                                    closeDrawer()
                                },
                                onCopy = { entry ->
                                    scope.launch { copyHistoryEntry(context, viewModel, entry) }
                                },
                                onClear = {
                                    scope.launch { history.clear() }
                                    closeDrawer()
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

                // Both bits of chrome live here rather than in the display band, and both
                // are aligned to a *logical* edge so the whole top row mirrors in an RTL
                // locale: the angle chip follows the reading direction and the overflow sits
                // opposite it, instead of being stranded on the right where it reads as part
                // of the expression.
                //
                // The chip used to sit inside the band. Two things were wrong with that. It
                // cost the band a row of height, which is the height landscape does not have
                // to give — and because the band is bottom-aligned, the chip was the topmost
                // item of a column that grows upward, so it drifted vertically as the
                // expression got longer. Anchored here it is still, and the band is nothing
                // but the number.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(dimensionResource(R.dimen.calc_screen_padding)),
                ) {
                    if (state.angleMode == AngleMode.RADIANS) {
                        RadiansBadge(
                            onToggle = viewModel::onToggleAngleMode,
                            modifier = Modifier.align(Alignment.TopStart),
                        )
                    }
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        OverflowMenu(onOpenMode)
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

    // Both lines are sized in sp, so a 2.0 system font scale doubles them while the band
    // holding them does not grow at all. In landscape, where both pads are already on screen
    // and the band is half its portrait height, the pair then overflows — and since the pads
    // are the later sibling, their keys paint straight over the bottom of the answer. Shrink
    // the two lines together by whatever it takes to fit instead of letting them be covered.
    BoxWithConstraints(
        // The backstop for a band too short even at MinimumDisplayFit. Cut off at the band
        // edge is survivable; drawn across the keypad is not.
        modifier = modifier.clipToBounds(),
    ) {
        val typography = MaterialTheme.typography
        val fit: Float = with(LocalDensity.current) {
            val wanted =
                (typography.displaySmall.fontSize * formulaScale * 1.15f).toPx() +
                    (typography.displayMedium.fontSize * resultScale * 1.12f).toPx()
            // The chip is measured rather than estimated: it is a TextButton, so its height
            // is its own padding plus a label that grows with the font scale too.
            // No chrome to subtract any more: the angle chip moved out to the top overlay,
            // so the band is the two text lines and the computing indicator, nothing else.
            val available = constraints.maxHeight - ComputingIndicatorHeight.toPx()
            if (wanted > 0f && available > 0f) {
                (available / wanted).coerceIn(MinimumDisplayFit, 1f)
            } else {
                1f
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.End,
        ) {
            // Both the formula and the result are pinned to LTR. No physical keypad mirrors
            // its digits in an RTL locale, and neither does Google Calculator; letting bidi
            // reorder a mixed expression would move the operators out from between operands.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                FormulaLine(
                    text = state.formula.ifEmpty { EmptyFormula },
                    scale = formulaScale * fit,
                    onPaste = onPaste,
                    drawerLabel = drawerLabel,
                    onToggleDrawer = onToggleDrawer,
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
                    generation = state.resultGeneration,
                    scale = resultScale * fit,
                    isError = state.mode == DisplayMode.ERROR,
                    announce = showingResult,
                    hasMoreDigits = state.hasMoreDigits,
                    onRequestMoreDigits = onRequestMoreDigits,
                    onCopy = onCopy,
                )
            }
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
    generation: Int,
    scale: Float,
    isError: Boolean,
    announce: Boolean,
    hasMoreDigits: Boolean,
    onRequestMoreDigits: () -> Unit,
    onCopy: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val moreDigitsLabel = stringResource(R.string.desc_more_digits)
    val copyLabel = stringResource(R.string.action_copy)
    var showCopy by remember { mutableStateOf(false) }

    // One ScrollState serves every result this line ever shows, and a ScrollState clamps its
    // value down when the content shrinks — so a new, short answer arriving under a position
    // scrolled three hundred digits into the previous one lands parked at its right-hand end,
    // with its leading digits off screen. Keyed on the generation rather than on the text,
    // because an expansion appends to the same answer and must keep the position the user's
    // finger reached to ask for it.
    LaunchedEffect(generation) { scrollState.scrollTo(0) }

    if (hasMoreDigits) {
        // Keyed on the text as well, so each rendering gets exactly one chance to grow
        // itself to fit; without that the fit branch below re-fires on every measurement
        // between the request and the longer answer arriving.
        LaunchedEffect(scrollState, hasMoreDigits, text) {
            var askedToFill = false
            snapshotFlow { Triple(scrollState.value, scrollState.maxValue, scrollState.viewportSize) }
                .collect { (value, max, viewport) ->
                    // viewportSize is 0 until the line has actually been measured, and
                    // snapshotFlow emits once before that. Reading max == 0 in that first
                    // emission means "not laid out yet", not "nothing to scroll" — acting on
                    // it expanded every result on every screen, including the portrait ones
                    // that could already scroll perfectly well.
                    if (viewport == 0) return@collect
                    if (max == 0) {
                        // maxValue is also 0 for a frame or two straight after the text
                        // changes, before the new content has been measured — so a bare
                        // max == 0 fires on every result everywhere. Let it settle and
                        // look again; only a line that still cannot scroll a moment later
                        // genuinely has no more room.
                        // The answer does not fill the line, so there is nothing to scroll
                        // and the branch below can never fire — no drag can produce a
                        // scroll offset in a viewport with no overflow. That is not a
                        // corner case: it is every landscape phone, tablet and unfolded
                        // foldable, where a twenty-character answer sits in a viewport
                        // three times its width. Those users could never reach past the
                        // first seventeen digits of one seventh while the trailing ellipsis
                        // went on promising more. Ask on the display's behalf instead, once
                        // per rendering; each expansion doubles, so this stops as soon as
                        // the answer is wider than the line and scrolling takes over.
                        if (!askedToFill) {
                            delay(SETTLE_MS)
                            if (scrollState.maxValue == 0 && scrollState.viewportSize > 0) {
                                askedToFill = true
                                onRequestMoreDigits()
                            }
                        }
                    } else if (value > 0 && value >= max - 8) {
                        // Ask well before the end so the next block of digits has landed by
                        // the time the finger gets there; waiting until the true edge shows
                        // a stop. `value > 0` is what keeps a line that merely overflows its
                        // box by a few pixels from requesting an expansion nobody scrolled
                        // for — including the clamped position a shrinking result leaves.
                        onRequestMoreDigits()
                    }
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
                    val actions = ArrayList<CustomAccessibilityAction>(2)
                    // Copy is a long press and nothing else, and a switch-access user has no
                    // long press at all — so without this the one place the exact answer can
                    // be got out of the app is unreachable for them. HistoryRow already
                    // states the rule and offers the same action on its own copy gesture.
                    if (text.isNotEmpty() && !isError) {
                        actions += CustomAccessibilityAction(copyLabel) {
                            onCopy()
                            true
                        }
                    }
                    if (hasMoreDigits) {
                        actions += CustomAccessibilityAction(moreDigitsLabel) {
                            onRequestMoreDigits()
                            true
                        }
                    }
                    if (actions.isNotEmpty()) customActions = actions
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

/**
 * The expression line: what the user is typing, and the only place a paste can be made.
 *
 * It carries no `contentDescription`. One used to name the field — the literal word
 * "formula" — on the very node that holds the expression, and TalkBack reads a
 * contentDescription *instead of* the text, so the announcement of the user's own sum was
 * replaced by the name of the box it sits in. The result line has never had one, which is why
 * it reads correctly.
 */
@Composable
private fun FormulaLine(
    text: String,
    scale: Float,
    onPaste: () -> Unit,
    drawerLabel: String,
    onToggleDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val pasteLabel = stringResource(R.string.action_paste)
    // Read through rememberUpdatedState so the gesture detector below can be keyed on Unit.
    // Keyed on the callback instead, any recomposition that produced a new lambda — a
    // preview result landing, for instance — would restart the pointer-input node and throw
    // away a long press already in progress.
    val longPress by rememberUpdatedState(onPaste)
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
            // Both actions live here rather than on the Display column, which had the drawer
            // action on a node with no text, no description and no role: Compose makes a node
            // screen-reader focusable when it carries content, so an action alone on an empty
            // container is plausibly unreachable. This line always has content — the
            // placeholder zero at the very least — so it is the node that can be reached.
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(pasteLabel) {
                        longPress()
                        true
                    },
                    CustomAccessibilityAction(drawerLabel) {
                        onToggleDrawer()
                        true
                    },
                )
            },
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
    Box(modifier = Modifier.fillMaxWidth().height(ComputingIndicatorHeight)) {
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

/**
 * The mark that trigonometry is being done in radians, shown only while it is.
 *
 * Degrees is the default and the unit nearly everything here is written in, so a permanent
 * chip spent its life restating it — on a page whose keys it does not govern, since the trig
 * keys are a swipe away on the advanced pad, and one stray tap from changing the unit of
 * every calculation to come. Radians is the state that earns a mark on the display: the same
 * keystrokes give a different answer and no error at all, so with the badge gone nothing on
 * screen would say which unit produced the number above it.
 *
 * Tapping it returns to degrees; the advanced pad's own DEG/RAD key and the Settings entry
 * are the other two ways in and out, and both are unchanged.
 */
@Composable
private fun RadiansBadge(
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hoisted: stringResource cannot be called inside the semantics lambda, which is why
    // this was an English string constant that no locale ever translated — invisible to the
    // HardcodedText check, because that inspects XML attributes and not Kotlin.
    val label = stringResource(R.string.desc_switch_deg)
    TextButton(
        onClick = onToggle,
        modifier = modifier.semantics { contentDescription = label },
    ) {
        Text(
            text = stringResource(R.string.mode_rad),
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

/**
 * What a key press does, as data the pads can be skipped on.
 *
 * The pads used to take the view model itself. A view model is an unstable type as far as the
 * Compose compiler can tell, so a composable holding one can never be skipped: every
 * keystroke re-emitted both pads in full — some forty [CalcButton]s, each re-running its
 * label's shrink-to-fit measurement — on the frame that had to answer the key. Held here as
 * one remembered, immutable object, the pads recompose only when what they draw changes.
 */
@Immutable
private class PadActions(
    val onKey: (KeyId) -> Unit,
    val onClear: () -> Unit,
    val onSmartParen: () -> Unit,
    val onDelete: () -> Unit,
    val onEquals: () -> Unit,
    val onToggleInverse: () -> Unit,
    val onToggleAngleMode: () -> Unit,
)

@Composable
private fun NumericPad(
    actions: PadActions,
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
                    KeyStyle.DESTRUCTIVE) { actions.onClear() }
                Key(stringResource(R.string.key_paren), stringResource(R.string.desc_paren),
                    KeyStyle.FUNCTION) { actions.onSmartParen() }
                Key(stringResource(R.string.op_pct), stringResource(R.string.desc_op_pct),
                    KeyStyle.FUNCTION) { actions.onKey(KeyId.PERCENT) }
                Key(stringResource(R.string.op_div), stringResource(R.string.desc_op_div),
                    KeyStyle.OPERATOR) { actions.onKey(KeyId.DIVIDE) }
            }
            PadRow {
                DigitKey(digit(7), KeyId.D7, actions)
                DigitKey(digit(8), KeyId.D8, actions)
                DigitKey(digit(9), KeyId.D9, actions)
                Key(stringResource(R.string.op_mul), stringResource(R.string.desc_op_mul),
                    KeyStyle.OPERATOR) { actions.onKey(KeyId.MULTIPLY) }
            }
            PadRow {
                DigitKey(digit(4), KeyId.D4, actions)
                DigitKey(digit(5), KeyId.D5, actions)
                DigitKey(digit(6), KeyId.D6, actions)
                Key(stringResource(R.string.op_sub), stringResource(R.string.desc_op_sub),
                    KeyStyle.OPERATOR) { actions.onKey(KeyId.SUBTRACT) }
            }
            PadRow {
                DigitKey(digit(1), KeyId.D1, actions)
                DigitKey(digit(2), KeyId.D2, actions)
                DigitKey(digit(3), KeyId.D3, actions)
                Key(stringResource(R.string.op_add), stringResource(R.string.desc_op_add),
                    KeyStyle.OPERATOR) { actions.onKey(KeyId.ADD) }
            }
            PadRow {
                DigitKey(digit(0), KeyId.D0, actions)
                Key(separator.toString(), stringResource(R.string.desc_dec_point),
                    KeyStyle.DIGIT) { actions.onKey(KeyId.POINT) }
                // Long-press clears everything, exactly as Google Calculator does.
                Key(
                    label = stringResource(R.string.key_del),
                    description = stringResource(R.string.desc_del),
                    style = KeyStyle.FUNCTION,
                    onLongClick = { actions.onClear() },
                ) { actions.onDelete() }
                Key(stringResource(R.string.key_eq), stringResource(R.string.desc_eq),
                    KeyStyle.ACCENT) { actions.onEquals() }
            }
        }
    }
}

@Composable
private fun AdvancedPad(
    inverse: Boolean,
    angleMode: AngleMode,
    actions: PadActions,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.calc_key_spacing)),
    ) {
        PadRow {
            Key(
                stringResource(R.string.key_inv),
                stringResource(if (inverse) R.string.desc_inv_on else R.string.desc_inv_off),
                KeyStyle.FUNCTION,
            ) { actions.onToggleInverse() }
            Key(
                stringResource(if (angleMode == AngleMode.DEGREES) R.string.mode_deg else R.string.mode_rad),
                stringResource(
                    if (angleMode == AngleMode.DEGREES) R.string.desc_switch_rad
                    else R.string.desc_switch_deg,
                ),
                KeyStyle.FUNCTION,
            ) { actions.onToggleAngleMode() }
            // INV swaps the six trig and log labels in place rather than revealing a third
            // pad, which is what keeps the layout stable under the user's thumb.
            Key(
                stringResource(if (inverse) R.string.fun_arcsin else R.string.fun_sin),
                stringResource(if (inverse) R.string.desc_fun_arcsin else R.string.desc_fun_sin),
                KeyStyle.FUNCTION,
            ) { actions.onKey(if (inverse) KeyId.ASIN else KeyId.SIN) }
            Key(
                stringResource(if (inverse) R.string.fun_arccos else R.string.fun_cos),
                stringResource(if (inverse) R.string.desc_fun_arccos else R.string.desc_fun_cos),
                KeyStyle.FUNCTION,
            ) { actions.onKey(if (inverse) KeyId.ACOS else KeyId.COS) }
            Key(
                stringResource(if (inverse) R.string.fun_arctan else R.string.fun_tan),
                stringResource(if (inverse) R.string.desc_fun_arctan else R.string.desc_fun_tan),
                KeyStyle.FUNCTION,
            ) { actions.onKey(if (inverse) KeyId.ATAN else KeyId.TAN) }
        }
        PadRow {
            Key(stringResource(R.string.const_pi), stringResource(R.string.desc_const_pi),
                KeyStyle.FUNCTION) { actions.onKey(KeyId.PI) }
            Key(stringResource(R.string.const_e), stringResource(R.string.desc_const_e),
                KeyStyle.FUNCTION) { actions.onKey(KeyId.E) }
            Key(stringResource(R.string.op_pow), stringResource(R.string.desc_op_pow),
                KeyStyle.FUNCTION) { actions.onKey(KeyId.POWER) }
            Key(
                stringResource(if (inverse) R.string.op_sqr else R.string.op_sqrt),
                stringResource(if (inverse) R.string.desc_op_sqr else R.string.desc_op_sqrt),
                KeyStyle.FUNCTION,
            ) { actions.onKey(if (inverse) KeyId.SQUARE else KeyId.SQRT) }
            Key(stringResource(R.string.op_fact), stringResource(R.string.desc_op_fact),
                KeyStyle.FUNCTION) { actions.onKey(KeyId.FACTORIAL) }
        }
        PadRow {
            Key(
                stringResource(if (inverse) R.string.fun_exp else R.string.fun_ln),
                stringResource(if (inverse) R.string.desc_fun_exp else R.string.desc_fun_ln),
                KeyStyle.FUNCTION,
            ) { actions.onKey(if (inverse) KeyId.EXPE else KeyId.LN) }
            Key(
                stringResource(if (inverse) R.string.fun_10pow else R.string.fun_log),
                stringResource(if (inverse) R.string.desc_fun_10pow else R.string.desc_fun_log),
                KeyStyle.FUNCTION,
            ) { actions.onKey(if (inverse) KeyId.EXP10 else KeyId.LOG) }
            Key(stringResource(R.string.key_lparen), stringResource(R.string.desc_paren),
                KeyStyle.FUNCTION) { actions.onKey(KeyId.LEFT_PAREN) }
            Key(stringResource(R.string.key_rparen), stringResource(R.string.desc_paren),
                KeyStyle.FUNCTION) { actions.onKey(KeyId.RIGHT_PAREN) }
            Key(stringResource(R.string.op_pct), stringResource(R.string.desc_op_pct),
                KeyStyle.FUNCTION) { actions.onKey(KeyId.PERCENT) }
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
    key: KeyId,
    actions: PadActions,
) {
    Key(label, stringResource(R.string.desc_digit, label), KeyStyle.DIGIT) { actions.onKey(key) }
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
 * Puts the readable value on the clipboard for other applications, with Numera's own encoded
 * expression alongside it where only Numera looks. The first implementation copied
 * `state.result` — the *localised, twenty-character truncated* display string — which lost
 * precision twice over and, in a locale with non-Latin digits, could not even be pasted back.
 */
private suspend fun copyResult(context: Context, viewModel: CalculatorViewModel) {
    val payload = viewModel.clipboardPayload() ?: return
    putOnClipboard(context, payload.text, payload.encodedExpression)
}

/**
 * Copies a history row, carrying its exact expression the same way.
 *
 * The row's own `result` is a display string — grouped, localised, cut to twenty characters
 * and ellipsised — so the value handed to other applications is re-derived from the stored
 * calculation instead; see `CalculatorViewModel.historyPayload`.
 */
private suspend fun copyHistoryEntry(
    context: Context,
    viewModel: CalculatorViewModel,
    entry: HistoryEntry,
) {
    val payload = viewModel.historyPayload(entry)
    putOnClipboard(context, payload.text, payload.encodedExpression)
}

private fun putOnClipboard(context: Context, text: String, encodedExpression: String) {
    if (text.isEmpty()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(CLIP_LABEL, text)
    // In the description's extras, not as a second item. A clip's *items* are all pasted:
    // the plain-text receive path every stock EditText uses loops over every item, coerces
    // each to text and joins them with a newline, so a second item put the whole Base64 token
    // stream into the user's message the moment they pasted an answer into Messages or Gmail.
    // Nothing but Numera reads the extras.
    clip.description.extras = PersistableBundle().apply {
        putString(CLIP_EXTRA_EXPR, encodedExpression)
    }
    clipboard.setPrimaryClip(clip)
}

/**
 * Reads the clipboard, preferring Numera's own exact payload over the rendered text.
 *
 * The label is the marker. When it matches, the description's extras hold the original token
 * stream, so copy-then-paste round-trips a value with no loss at all; anything else falls back
 * to tokenising whatever text is there, which is what makes pasting `12+34` from a notes app
 * work — a clip this app wrote before the payload moved into the extras included.
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

    if (clip.description.label == CLIP_LABEL) {
        val encoded: String? = clip.description.extras?.getString(CLIP_EXTRA_EXPR)
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

/**
 * What the formula line reads before anything is typed.
 *
 * A desk calculator holding nothing shows a zero, not a blank slot, and that zero is what
 * makes the refusal of a leading operator legible: press `×` on a fresh line and the value
 * on screen — zero — visibly has no operator hanging off it, rather than the display simply
 * failing to react to a key.
 *
 * ASCII, unlike the keypad's own labels, because it stands in for the formula line's text
 * and that line writes its digits in ASCII in every locale — see `CalculatorExpr.display`.
 * Localising only the placeholder would make the digit change shape in Arabic at the moment
 * the user replaced it with the same digit off the pad.
 */
private const val EmptyFormula: String = "0"

/**
 * How far the display may shrink to fit its band before it stops trying.
 *
 * Below this the answer is smaller than the key labels under it, at which point clipping a
 * line the user can still scroll is the better failure.
 */
private const val MinimumDisplayFit = 0.45f

/** Height the computing bar occupies whether or not it is showing. */
private val ComputingIndicatorHeight = 4.dp

/** Distinctive enough that another app's clipboard entry cannot be mistaken for ours. */
private const val CLIP_LABEL = "app.numera.calculator/expression"

/** Where the exact token stream rides: read by this app, pasted by nothing. */
private const val CLIP_EXTRA_EXPR = "app.numera.calculator.EXPRESSION"

/**
 * How long to wait before believing that a result line has nothing left to scroll.
 *
 * `ScrollState.maxValue` is 0 both for a line that fits its viewport and for one whose new
 * text has not been measured yet, and the two are indistinguishable in the frame the text
 * changes. Long enough for layout, short enough that a wide display fills itself before the
 * user looks for the digits that are missing.
 */
private const val SETTLE_MS: Long = 250L
