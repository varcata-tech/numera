package app.numera.calculator.feature.converter

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.numera.calculator.R
import app.numera.calculator.math.expr.KeyId
import app.numera.calculator.settings.LocalSettingsStore
import app.numera.calculator.ui.common.CalcButton
import app.numera.calculator.ui.common.KeyStyle
import app.numera.calculator.ui.common.ModeScaffold
import app.numera.calculator.units.Dimension
import app.numera.calculator.units.UnitCatalog
import app.numera.calculator.units.UnitDef
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The unit converter.
 *
 * Shaped like Apple's Convert mode but wearing the calculator's clothes — the same
 * [CalcButton] keypad sits underneath, so switching modes does not feel like switching apps.
 *
 * Every conversion runs through [app.numera.calculator.units.UnitConverter] on exact
 * rationals, which is why an inch → centimetre → inch round trip returns the number the user
 * typed rather than something a few bits away from it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(onBack: () -> Unit) {
    val settings = LocalSettingsStore.current
    val viewModel: ConverterViewModel = viewModel(factory = converterViewModelFactory(settings))
    val state by viewModel.state.collectAsStateWithLifecycle()

    var picking: Boolean? by remember { mutableStateOf(null) }

    ModeScaffold(title = stringResource(R.string.title_converter), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = dimensionResource(R.dimen.calc_screen_padding)),
        ) {
            CategoryChips(
                selected = state.dimension,
                onSelect = viewModel::onSelectDimension,
            )

            ValueRow(
                label = stringResource(R.string.converter_from),
                value = state.fromText,
                unit = state.fromUnit,
                active = state.editingFrom,
                onActivate = { viewModel.onFocus(from = true) },
                onPickUnit = { picking = true },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = viewModel::onSwap) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = stringResource(R.string.converter_swap),
                    )
                }
            }

            ValueRow(
                label = stringResource(R.string.converter_to),
                value = state.toText,
                unit = state.toUnit,
                active = !state.editingFrom,
                onActivate = { viewModel.onFocus(from = false) },
                onPickUnit = { picking = false },
            )

            if (state.common.isNotEmpty()) {
                CommonConversions(state.common)
            }

            Box(modifier = Modifier.weight(1f)) {
                ConverterPad(
                    onKey = viewModel::onKey,
                    onDelete = viewModel::onDelete,
                    onClear = viewModel::onClear,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    picking?.let { isFrom ->
        UnitPickerSheet(
            units = UnitCatalog.unitsOf(state.dimension),
            onDismiss = { picking = null },
            onPick = { unit ->
                viewModel.onSelectUnit(unit, from = isFrom)
                picking = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryChips(selected: Dimension, onSelect: (Dimension) -> Unit) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (dimension in UnitCatalog.dimensions) {
            FilterChip(
                selected = dimension == selected,
                onClick = { onSelect(dimension) },
                label = { Text(stringResource(UnitNames.dimensionRes(dimension))) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun ValueRow(
    label: String,
    value: String,
    unit: UnitDef,
    active: Boolean,
    onActivate: () -> Unit,
    onPickUnit: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onActivate)
            .background(
                color = if (active) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                shape = RoundedCornerShape(dimensionResource(R.dimen.calc_key_corner)),
            )
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Box(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.clickable(onClick = onPickUnit),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(UnitNames.symbolRes(unit.id)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = "  ▾", style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
        )
        Text(
            text = stringResource(UnitNames.nameRes(unit.id)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The same value expressed in a few sibling units.
 *
 * Apple ships this and Google does not; it turns the converter from a calculator into an
 * answer, because the unit the user actually wanted is often the third one down.
 */
@Composable
private fun CommonConversions(entries: List<Pair<UnitDef, String>>) {
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.converter_common),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            for ((unit, text) in entries) {
                Column {
                    Text(text = text, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(UnitNames.symbolRes(unit.id)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The name, because several units of one dimension share a symbol: the
                    // US and imperial gallons are both "gal" and their "mpg" differ by a
                    // fifth, so a strip labelled by symbol alone shows two adjacent columns
                    // with the same caption and different numbers.
                    Text(
                        text = stringResource(UnitNames.nameRes(unit.id)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * Joins a unit's searchable fields.
 *
 * A character no keyboard produces, so a query can never match by straddling the boundary
 * between a unit's name and its symbol.
 */
private const val SEARCH_FIELD_SEPARATOR: String = "\u0000"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitPickerSheet(
    units: List<UnitDef>,
    onDismiss: () -> Unit,
    onPick: (UnitDef) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.converter_search)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        // Matched against what the row actually displays. Filtering on UnitDef.id searched
        // an internal English key that is never shown, so the box was dead in eleven of the
        // twelve shipped locales — "pouce" and "メートル" emptied the list — and even in
        // English missed "meter" and every symbol. The id stays in the haystack as a
        // fallback so an English spelling still finds its unit.
        val haystacks: List<Pair<UnitDef, String>> = units.map { unit ->
            unit to listOf(
                stringResource(UnitNames.nameRes(unit.id)),
                stringResource(UnitNames.symbolRes(unit.id)),
                unit.id.replace('_', ' '),
            ).joinToString(SEARCH_FIELD_SEPARATOR)
        }
        val needle = query.trim()
        val visible: List<UnitDef> = haystacks
            .filter { (_, haystack) ->
                needle.isEmpty() || haystack.contains(needle, ignoreCase = true)
            }
            .map { it.first }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(visible, key = { it.id }) { unit ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(unit) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(UnitNames.nameRes(unit.id)),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(UnitNames.symbolRes(unit.id)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A reduced keypad: the ten digits, a point, delete, and the four operators.
 *
 * The operators are here because the input runs through the same expression parser as the
 * calculator, so a user can type `2+3` in the from-field and convert five. There is no ± key
 * and none is needed: `ExprParser` reads a leading `−` as a negation, so pressing subtract
 * first is how a negative temperature is entered.
 */
@Composable
private fun ConverterPad(
    onKey: (KeyId) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    // Observable locale read: see the note in CalculatorScreen's NumericPad.
    val locale = LocalConfiguration.current.locales[0]
    val symbols = remember(locale) { DecimalFormatSymbols.getInstance(locale) }
    fun digit(n: Int): String = (symbols.zeroDigit + n).toString()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.calc_key_spacing)),
    ) {
        val rows = listOf(
            listOf(7, 8, 9),
            listOf(4, 5, 6),
            listOf(1, 2, 3),
        )
        val trailing = listOf(
            KeyId.DIVIDE to R.string.op_div,
            KeyId.MULTIPLY to R.string.op_mul,
            KeyId.SUBTRACT to R.string.op_sub,
        )
        rows.forEachIndexed { index, digits ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(
                    dimensionResource(R.dimen.calc_key_spacing),
                ),
            ) {
                for (d in digits) {
                    val label = digit(d)
                    CalcButton(
                        label = label,
                        contentDescription = stringResource(R.string.desc_digit, label),
                        onClick = { onKey(digitKey(d)) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                val (key, labelRes) = trailing[index]
                CalcButton(
                    label = stringResource(labelRes),
                    contentDescription = stringResource(labelRes),
                    onClick = { onKey(key) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    style = KeyStyle.OPERATOR,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(
                dimensionResource(R.dimen.calc_key_spacing),
            ),
        ) {
            CalcButton(
                label = digit(0),
                contentDescription = stringResource(R.string.desc_digit, digit(0)),
                onClick = { onKey(KeyId.D0) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            CalcButton(
                label = symbols.decimalSeparator.toString(),
                contentDescription = stringResource(R.string.desc_dec_point),
                onClick = { onKey(KeyId.POINT) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            CalcButton(
                label = stringResource(R.string.key_del),
                contentDescription = stringResource(R.string.desc_del),
                onClick = onDelete,
                onLongClick = onClear,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                style = KeyStyle.FUNCTION,
            )
            CalcButton(
                label = stringResource(R.string.op_add),
                contentDescription = stringResource(R.string.op_add),
                onClick = { onKey(KeyId.ADD) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                style = KeyStyle.OPERATOR,
            )
        }
    }
}

private fun digitKey(digit: Int): KeyId = when (digit) {
    0 -> KeyId.D0
    1 -> KeyId.D1
    2 -> KeyId.D2
    3 -> KeyId.D3
    4 -> KeyId.D4
    5 -> KeyId.D5
    6 -> KeyId.D6
    7 -> KeyId.D7
    8 -> KeyId.D8
    else -> KeyId.D9
}
