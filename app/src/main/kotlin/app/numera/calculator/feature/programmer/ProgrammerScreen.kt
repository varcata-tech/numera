package app.numera.calculator.feature.programmer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.numera.calculator.R
import app.numera.calculator.ui.common.CalcButton
import app.numera.calculator.ui.common.KeyStyle
import app.numera.calculator.ui.common.KeypadColumn
import app.numera.calculator.ui.common.ModeScaffold

/**
 * Programmer mode: one value, shown in four bases at once, over an emulated machine word.
 *
 * The four simultaneous rows and the tappable bit grid are the point of the screen. A
 * calculator that makes you convert between bases one at a time is answering a different,
 * less useful question than "what does this value look like from every angle".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgrammerScreen(onBack: () -> Unit) {
    val viewModel: ProgrammerViewModel = viewModel(factory = programmerViewModelFactory())
    val state by viewModel.state.collectAsStateWithLifecycle()

    ModeScaffold(title = stringResource(R.string.title_programmer), onBack = onBack) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = dimensionResource(R.dimen.calc_screen_padding)),
        ) {
            // Measured, not inferred from orientation, for the reason the calculator's own
            // breakpoint gives: at targetSdk 36 a freeform or split-screen window can be any
            // shape, and "landscape" answers a different question than the layout asks.
            //
            // Stacking is only safe when there is height to stack into. The four base rows,
            // the chip row and the bit grid all have hard minimums totalling about 312dp, and
            // the pad was the single weighted child underneath them. A landscape phone leaves
            // roughly 300dp of content height, so the pad's weight resolved to less than the
            // 48dp of inter-row spacing its own Column subtracts first — and every row then
            // collapsed to exactly 0px. Not a small keypad: no keypad at all, on a screen
            // that still showed the word and its bits. Side by side, the pad is measured
            // against the full height instead of the remainder.
            if (maxWidth >= WIDE_BREAKPOINT) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(
                        dimensionResource(R.dimen.calc_key_spacing),
                    ),
                ) {
                    // Scrolls: beside the pad the readout gets the window's full height and
                    // nothing more, and on a landscape phone the four rows, the chips and
                    // the bit grid add up to more than that. Without the scroll a plain
                    // Column squeezed the chips to 39dp and clipped the grid instead.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ProgrammerReadout(state, viewModel)
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        ProgrammerPad(state, viewModel, Modifier.fillMaxSize())
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    ProgrammerReadout(state, viewModel)
                    Box(modifier = Modifier.weight(1f)) {
                        ProgrammerPad(state, viewModel, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** The width at which the pad moves beside the readout instead of under it. */
private val WIDE_BREAKPOINT = 600.dp

/**
 * The word itself: the four base rows, the size and sign chips, any error, and the bits.
 *
 * Extracted so the tall and wide arrangements share one definition — two copies would drift,
 * and the wide one is the arrangement nobody looks at day to day.
 */
@Composable
private fun ColumnScope.ProgrammerReadout(
    state: ProgrammerUiState,
    viewModel: ProgrammerViewModel,
) {
    for (base in NumberBase.entries) {
        BaseRow(
            base = base,
            text = state.rendered(base),
            active = base == state.base,
            onClick = { viewModel.onSelectBase(base) },
        )
    }

    // Scrollable, like the compounding row in the financial screen. Five chips
    // labelled "Vorzeichenbehaftet" or "Со знаком" do not fit a phone at any font
    // scale, and a plain Row squeezes the last of them to nothing rather than
    // letting the user reach it.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (size in WordSize.entries) {
            FilterChip(
                selected = size == state.wordSize,
                onClick = { viewModel.onSelectWordSize(size) },
                label = { Text(size.bits.toString()) },
            )
        }
        FilterChip(
            selected = state.signed,
            onClick = { viewModel.onToggleSigned() },
            label = {
                Text(
                    stringResource(
                        if (state.signed) R.string.prog_signed else R.string.prog_unsigned,
                    ),
                )
            },
        )
    }
    // On its own full-width line, not squeezed in beside the chips: this is the
    // screen's only error indication, and in the locales with the longest chip
    // labels it was the child that got clipped off the end of the row.
    state.error?.let { error ->
        Text(
            text = stringResource(
                when (error) {
                    ProgError.OVERFLOW -> R.string.prog_overflow
                    // Shared with the main calculator rather than reworded here: it
                    // is the same fault, already translated into all twelve locales,
                    // and one app saying it two ways is how a wording drifts.
                    ProgError.DIVIDE_BY_ZERO -> R.string.error_divide_by_zero
                },
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    BitGrid(
        value = state.value,
        wordSize = state.wordSize,
        onToggleBit = viewModel::onToggleBit,
    )
}

@Composable
private fun BaseRow(base: NumberBase, text: String, active: Boolean, onClick: () -> Unit) {
    val label = stringResource(
        when (base) {
            NumberBase.HEX -> R.string.base_hex
            NumberBase.DEC -> R.string.base_dec
            NumberBase.OCT -> R.string.base_oct
            NumberBase.BIN -> R.string.base_bin
        },
    )
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                color = if (active) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = RoundedCornerShape(8.dp),
            )
            // heightIn rather than more vertical padding: 6dp either side of a labelMedium
            // left the row a 36dp target, twelve short of the minimum, and padding that grows
            // with the font scale would overshoot at 2.0 instead.
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // A minimum width, not a fixed size: the label is sp and the box was dp, so at
            // a 2.0 font scale "HEX" was clipped to "HE" and "BIN" ran under its value.
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 42.dp).padding(end = 8.dp),
        )
        // The label mirrors with the chrome, but the value must not. A binary word is
        // written in space-separated nibbles, and space is bidi-neutral: in an RTL
        // paragraph the groups reorder, so 0000 0000 0000 1111 — fifteen — is drawn as
        // 1111 0000 0000 0000, which reads as 61440. The row would be showing a different
        // number from the one it holds.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = text,
                // Monospace so the digits line up between the four rows; a proportional font
                // makes two bit patterns of the same length look different lengths.
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.End,
                // Anchored at the end, not the start. Inside horizontalScroll the text is
                // measured at its own width, so TextAlign.End has nothing to align within
                // once the string outgrows the row, and a scroll at offset 0 shows the
                // *left* end — the most significant digits. Those are the zeros. The grouped
                // 32-bit binary row is already wider than a phone, and 64-bit is twice
                // that, so a freshly typed 1F opened on "0000 0000 0000 …" with every set
                // bit off screen to the right. reverseScrolling makes offset 0 the *right*
                // end by construction, at every width, with no re-pinning effect that could
                // land a frame short while the text is still being measured.
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState, reverseScrolling = true),
            )
        }
    }
}

/**
 * Every bit of the word, tappable.
 *
 * Grouped in nibbles with an index under each group, because counting bit positions by eye
 * across a 64-bit row is exactly the error this screen exists to prevent.
 */
@Composable
private fun BitGrid(value: Long, wordSize: WordSize, onToggleBit: (Int) -> Unit) {
    val scrollState = rememberScrollState()
    // Pinned LTR, like the calculator's display and keypad. A machine word is written
    // most-significant bit first in every reference there is, and this Row emits bit 63
    // first; left to mirror in an Arabic locale it would lay that out on the right and
    // render the whole word back to front, with the nibble captions reversed along with it.
    // A word read LSB-first is not a cosmetic problem — it is a different number.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The grid opens on bit 0, not bit 63. It emits the most significant nibble
                // first and is wider than a phone from 32 bits up, so at offset 0 the
                // visible columns were the top of the word — all zero for any small value —
                // and the bits actually carrying the value were off screen to the right.
                // Anchoring the *end* through reverseScrolling, rather than scrolling there
                // in an effect keyed on the value, also leaves the user's own position
                // alone: a tap on a high bit they scrolled to must not yank the grid back.
                .horizontalScroll(scrollState, reverseScrolling = true)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The target is as wide as the word allows and no wider.
            //
            // Eight bits leave room for the full 48dp minimum across a phone; sixteen leave half
            // of it; thirty-two and sixty-four cannot have it at any width without pushing most
            // of the word off screen, and this grid exists to show the word. Fixing the cell at
            // 32dp regardless looked like the tidy answer and cost the 32-bit view eleven of its
            // visible bits, so the compromise is made per word size rather than once for all.
            val cellWidth: Dp = when (wordSize.bits) {
                8 -> 44.dp
                16 -> 22.dp
                // Not 0.dp — letting the cells fall to their natural 10dp does fit all 32 bits on
                // screen at once, which is tempting, but it makes the target smaller than the
                // 17dp that was worth reporting in the first place. 16dp holds the line.
                else -> 16.dp
            }
            val nibbles = (wordSize.bits - 1 downTo 0).chunked(4)
            for (nibble in nibbles) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        for (bit in nibble) {
                            val set = BitwiseEngine.truncate(value, wordSize) and (1L shl bit) != 0L
                            val setLabel = stringResource(R.string.desc_bit_set)
                            val clearLabel = stringResource(R.string.desc_bit_clear)
                            val bitLabel = stringResource(R.string.desc_bit, bit)
                            // An explicit Box, so the layout node, the touch target and the
                            // glyph are one rectangle.
                            //
                            // A monospace glyph with 3dp either side was a 17dp target with
                            // the next bit 17dp away, so a slightly off tap silently set the
                            // wrong bit. cellWidth above is what widens it, by as much as the
                            // word size can spare.
                            //
                            // Widening it by hanging sizeIn off a centred Text was not enough:
                            // the digit was drawn 12dp to the right of the bounds the node
                            // reported to the accessibility tree, so touch worked where the
                            // user could see it while TalkBack's focus rectangle and
                            // explore-by-touch sat over the neighbouring bit. Centring the
                            // glyph inside a sized Box makes the three agree by construction.
                            Box(
                                modifier = Modifier
                                    .sizeIn(minWidth = cellWidth, minHeight = 48.dp)
                                    .clickable { onToggleBit(bit) }
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = bitLabel
                                        stateDescription = if (set) setLabel else clearLabel
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (set) "1" else "0",
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelLarge
                                        .copy(fontFamily = FontFamily.Monospace),
                                    color = if (set) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        text = nibble.last().toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgrammerPad(
    state: ProgrammerUiState,
    viewModel: ProgrammerViewModel,
    modifier: Modifier,
) {
    val spacing = dimensionResource(R.dimen.calc_key_spacing)
    // Pinned LTR for the same reason the calculator pad is: no physical keypad mirrors,
    // and letting this one flip in Arabic put A B C D and the operator column on the
    // opposite side from the calculator's, so the two pads in one app disagreed about
    // where a digit lives.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        KeypadColumn(rows = 7, modifier = modifier) { row ->
            PadRow(row, spacing) {
                OpKey(R.string.op_and, descRes = R.string.desc_op_and) { viewModel.onOperator(BinaryOp.AND) }
                OpKey(R.string.op_or, descRes = R.string.desc_op_or) { viewModel.onOperator(BinaryOp.OR) }
                OpKey(R.string.op_xor, descRes = R.string.desc_op_xor) { viewModel.onOperator(BinaryOp.XOR) }
                OpKey(R.string.op_not, descRes = R.string.desc_op_not) { viewModel.onNot() }
                OpKey(R.string.key_clear, KeyStyle.DESTRUCTIVE, R.string.desc_clear) { viewModel.onClear() }
            }
            PadRow(row, spacing) {
                OpKey(R.string.op_shl, descRes = R.string.desc_op_shl) { viewModel.onOperator(BinaryOp.SHL) }
                OpKey(R.string.op_shr, descRes = R.string.desc_op_shr) { viewModel.onOperator(BinaryOp.SHR) }
                OpKey(R.string.op_rol, descRes = R.string.desc_op_rol) { viewModel.onOperator(BinaryOp.ROL) }
                OpKey(R.string.op_ror, descRes = R.string.desc_op_ror) { viewModel.onOperator(BinaryOp.ROR) }
                OpKey(R.string.key_del, KeyStyle.FUNCTION, R.string.desc_del) { viewModel.onDelete() }
            }
            // A–F stay visible but disabled outside hex. Hiding them would reflow the pad
            // under the user's thumb every time the base changed.
            val letterRows = listOf(listOf('A', 'B', 'C', 'D'), listOf('E', 'F'))
            PadRow(row, spacing) {
                for (c in letterRows[0]) DigitKey(c, state, viewModel)
                OpKey(R.string.op_div, descRes = R.string.desc_op_div) { viewModel.onOperator(BinaryOp.DIVIDE) }
            }
            PadRow(row, spacing) {
                for (c in letterRows[1]) DigitKey(c, state, viewModel)
                DigitKey('7', state, viewModel)
                DigitKey('8', state, viewModel)
                OpKey(R.string.op_mul, descRes = R.string.desc_op_mul) { viewModel.onOperator(BinaryOp.MULTIPLY) }
            }
            PadRow(row, spacing) {
                DigitKey('4', state, viewModel)
                DigitKey('5', state, viewModel)
                DigitKey('6', state, viewModel)
                DigitKey('9', state, viewModel)
                OpKey(R.string.op_sub, descRes = R.string.desc_op_sub) { viewModel.onOperator(BinaryOp.SUBTRACT) }
            }
            PadRow(row, spacing) {
                DigitKey('1', state, viewModel)
                DigitKey('2', state, viewModel)
                DigitKey('3', state, viewModel)
                DigitKey('0', state, viewModel)
                OpKey(R.string.op_add, descRes = R.string.desc_op_add) { viewModel.onOperator(BinaryOp.ADD) }
            }
            PadRow(row, spacing) {
                // ± is the only way to enter a negative operand — the decimal keypad has digits
                // and nothing else — on a screen that defaults to signed and prints signed
                // decimals. MOD sits beside it because integer remainder is half of what integer
                // division means to a programmer.
                OpKey(R.string.op_neg, descRes = R.string.desc_op_neg) { viewModel.onNegate() }
                OpKey(R.string.op_mod, descRes = R.string.desc_op_mod) { viewModel.onOperator(BinaryOp.MOD) }
                OpKey(R.string.key_eq, KeyStyle.ACCENT, R.string.desc_eq) { viewModel.onEquals() }
            }
        }
    }
}

/** One row of keys; [modifier] is [KeypadColumn]'s row sizing, weighted or pinned to 48dp. */
@Composable
private fun PadRow(
    modifier: Modifier,
    spacing: androidx.compose.ui.unit.Dp,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * One operator key.
 *
 * [descRes] defaults to [labelRes] because most of this pad is already words — AND, XOR,
 * RoL — and those read correctly as they stand. The arithmetic and editing keys are not:
 * describing a key as "÷" has TalkBack announce the letter it resembles, and "<<" and "⌫"
 * fare worse still. Those pass a spoken form explicitly.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.OpKey(
    labelRes: Int,
    style: KeyStyle = KeyStyle.OPERATOR,
    descRes: Int = labelRes,
    onClick: () -> Unit,
) {
    CalcButton(
        label = stringResource(labelRes),
        contentDescription = stringResource(descRes),
        onClick = onClick,
        modifier = Modifier.weight(1f).fillMaxHeight(),
        style = style,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DigitKey(
    digit: Char,
    state: ProgrammerUiState,
    viewModel: ProgrammerViewModel,
) {
    val allowed = BitwiseEngine.isDigitAllowed(digit, state.base)
    val unavailable = stringResource(R.string.desc_key_unavailable)
    val label = digit.toString()
    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
        CalcButton(
            label = label,
            contentDescription = if (allowed) label else "$label, $unavailable",
            onClick = { if (allowed) viewModel.onDigit(digit) },
            modifier = Modifier.fillMaxSize(),
            style = if (allowed) KeyStyle.DIGIT else KeyStyle.FUNCTION,
        )
    }
}
