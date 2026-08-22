package app.numera.calculator.feature.programmer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.numera.calculator.R
import app.numera.calculator.ui.common.CalcButton
import app.numera.calculator.ui.common.KeyStyle
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = dimensionResource(R.dimen.calc_screen_padding)),
        ) {
            for (base in NumberBase.entries) {
                BaseRow(
                    base = base,
                    text = state.rendered(base),
                    active = base == state.base,
                    onClick = { viewModel.onSelectBase(base) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
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
                if (state.overflow) {
                    Text(
                        text = stringResource(R.string.prog_overflow),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            BitGrid(
                value = state.value,
                wordSize = state.wordSize,
                onToggleBit = viewModel::onToggleBit,
            )

            Box(modifier = Modifier.weight(1f)) {
                ProgrammerPad(state, viewModel, Modifier.fillMaxSize())
            }
        }
    }
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
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 42.dp, height = 20.dp),
        )
        Text(
            text = text,
            // Monospace so the digits line up between the four rows; a proportional font
            // makes two bit patterns of the same length look different lengths.
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).horizontalScroll(scrollState),
        )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val nibbles = (wordSize.bits - 1 downTo 0).chunked(4)
        for (nibble in nibbles) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (bit in nibble) {
                        val set = BitwiseEngine.truncate(value, wordSize) and (1L shl bit) != 0L
                        val setLabel = stringResource(R.string.desc_bit_set)
                        val clearLabel = stringResource(R.string.desc_bit_clear)
                        val bitLabel = stringResource(R.string.desc_bit, bit)
                        Text(
                            text = if (set) "1" else "0",
                            style = MaterialTheme.typography.labelLarge
                                .copy(fontFamily = FontFamily.Monospace),
                            color = if (set) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .clickable { onToggleBit(bit) }
                                .padding(horizontal = 3.dp, vertical = 2.dp)
                                .semantics {
                                    contentDescription = bitLabel
                                    stateDescription = if (set) setLabel else clearLabel
                                },
                        )
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

@Composable
private fun ProgrammerPad(
    state: ProgrammerUiState,
    viewModel: ProgrammerViewModel,
    modifier: Modifier,
) {
    val spacing = dimensionResource(R.dimen.calc_key_spacing)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        PadRow(spacing) {
            OpKey(R.string.op_and) { viewModel.onOperator(BinaryOp.AND) }
            OpKey(R.string.op_or) { viewModel.onOperator(BinaryOp.OR) }
            OpKey(R.string.op_xor) { viewModel.onOperator(BinaryOp.XOR) }
            OpKey(R.string.op_not) { viewModel.onNot() }
            OpKey(R.string.key_clear, KeyStyle.DESTRUCTIVE) { viewModel.onClear() }
        }
        PadRow(spacing) {
            OpKey(R.string.op_shl) { viewModel.onOperator(BinaryOp.SHL) }
            OpKey(R.string.op_shr) { viewModel.onOperator(BinaryOp.SHR) }
            OpKey(R.string.op_rol) { viewModel.onOperator(BinaryOp.ROL) }
            OpKey(R.string.op_ror) { viewModel.onOperator(BinaryOp.ROR) }
            OpKey(R.string.key_del, KeyStyle.FUNCTION) { viewModel.onDelete() }
        }
        // A–F stay visible but disabled outside hex. Hiding them would reflow the pad
        // under the user's thumb every time the base changed.
        val letterRows = listOf(listOf('A', 'B', 'C', 'D'), listOf('E', 'F'))
        PadRow(spacing) {
            for (c in letterRows[0]) DigitKey(c, state, viewModel)
            OpKey(R.string.op_div) { viewModel.onOperator(BinaryOp.DIVIDE) }
        }
        PadRow(spacing) {
            for (c in letterRows[1]) DigitKey(c, state, viewModel)
            DigitKey('7', state, viewModel)
            DigitKey('8', state, viewModel)
            OpKey(R.string.op_mul) { viewModel.onOperator(BinaryOp.MULTIPLY) }
        }
        PadRow(spacing) {
            DigitKey('4', state, viewModel)
            DigitKey('5', state, viewModel)
            DigitKey('6', state, viewModel)
            DigitKey('9', state, viewModel)
            OpKey(R.string.op_sub) { viewModel.onOperator(BinaryOp.SUBTRACT) }
        }
        PadRow(spacing) {
            DigitKey('1', state, viewModel)
            DigitKey('2', state, viewModel)
            DigitKey('3', state, viewModel)
            DigitKey('0', state, viewModel)
            OpKey(R.string.op_add) { viewModel.onOperator(BinaryOp.ADD) }
        }
        PadRow(spacing) {
            OpKey(R.string.key_eq, KeyStyle.ACCENT) { viewModel.onEquals() }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PadRow(
    spacing: androidx.compose.ui.unit.Dp,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.OpKey(
    labelRes: Int,
    style: KeyStyle = KeyStyle.OPERATOR,
    onClick: () -> Unit,
) {
    val label = stringResource(labelRes)
    CalcButton(
        label = label,
        contentDescription = label,
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
