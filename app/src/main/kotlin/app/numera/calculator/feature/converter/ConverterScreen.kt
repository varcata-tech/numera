package app.numera.calculator.feature.converter

import androidx.core.text.BidiFormatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.numera.calculator.R
import app.numera.calculator.math.expr.KeyId
import app.numera.calculator.settings.LocalSettingsStore
import app.numera.calculator.feature.calc.localiseFormula
import app.numera.calculator.ui.common.CalcButton
import app.numera.calculator.ui.common.KeyStyle
import app.numera.calculator.ui.common.KeypadColumn
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

    // rememberSaveable, not remember: a rotation — or the activity recreation that a per-app
    // language switch causes — would otherwise close the picker out from under the user.
    var picking: Boolean? by rememberSaveable { mutableStateOf<Boolean?>(null) }

    // The view model outlives that same recreation, so the locale it renders answers in has to
    // be pushed to it from here, where LocalConfiguration is observable. Read from
    // Locale.getDefault() inside the view model it went stale, and the keypad's Arabic-Indic
    // digits ended up sitting under an answer still written in the previous locale's digits.
    val locale: Locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(viewModel, locale) { viewModel.onLocaleChanged(locale) }

    ModeScaffold(title = stringResource(R.string.title_converter), onBack = onBack) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = dimensionResource(R.dimen.calc_screen_padding)),
        ) {
            // The same measured breakpoint the calculator and programmer use, for the same
            // reason: at targetSdk 36 a window can be any shape, and "landscape" is not the
            // question. Stacked, the pad was the one weighted child under the chips, both
            // value rows, the swap button and the common conversions, whose fixed heights
            // sum to more than a landscape phone has — so the pad measured 23dp tall on a
            // Pixel 8 and the converter had no keypad at all in landscape. Side by side it
            // is measured against the full height, where its four rows fit with room over.
            if (maxWidth >= WIDE_BREAKPOINT) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(
                        dimensionResource(R.dimen.calc_key_spacing),
                    ),
                ) {
                    // Scrolls, because beside the pad it has the window's full height and no
                    // more: a landscape phone gives it about 300dp for chips, two value rows,
                    // the swap button and the common conversions, and a plain Column hands
                    // the last child whatever is left — which squeezed the common conversions
                    // to nothing rather than letting the user reach them.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ConverterReadout(state, viewModel, onPick = { picking = it })
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        ConverterPad(
                            onKey = viewModel::onKey,
                            onDelete = viewModel::onDelete,
                            onClear = viewModel::onClear,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    ConverterReadout(state, viewModel, onPick = { picking = it })
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
    val unitName: String = stringResource(UnitNames.nameRes(unit.id))
    // Re-pin the active field to its caret end whenever the text grows, exactly as the
    // calculator's own display does. Left at zero the scroll shows the *start* of the string,
    // so every digit typed past the field's width was entered blind — the user could not see
    // what they were typing, and could not even retype a figure the app had just shown them.
    // Keyed on maxValue too: on the frame the text changes, maxValue still holds the previous
    // width and scrolling to it lands short of the new end.
    LaunchedEffect(active, value, scrollState.maxValue) {
        // Only the field being typed into. The computed field is a finished number, and its
        // most significant digits — the start — are the ones worth showing.
        if (active) scrollState.scrollTo(scrollState.maxValue)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onActivate)
            // Merged so TalkBack reads the row as one control — "From, 12.5, Centimetre" —
            // rather than stopping on the label, the value and the unit name in turn. The
            // unit button below stays separately focusable because it merges in its own
            // right, which is what keeps "change the unit" reachable.
            .semantics(mergeDescendants = true) { }
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
            val changeUnit: String = stringResource(R.string.converter_change_unit, unitName)
            Row(
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onPickUnit)
                    // The only visual cue that this opens a picker is the "▾" glyph, which
                    // spoken is nothing at all — and the symbol beside it is read letter by
                    // letter ("c m"). Both are replaced by the unit's spoken name and what
                    // tapping does.
                    .semantics(mergeDescendants = true) { contentDescription = changeUnit },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = unitSymbol(unit.id),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = "  ▾", style = MaterialTheme.typography.titleMedium)
            }
        }
        // Pinned to LTR for the same reason the calculator's display is: this field accepts
        // operators, so it can hold `2+3`, and bidi reordering in an RTL locale moves the
        // operator out from between its operands and shows the user `3+2`.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
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
        }
        Text(
            text = unitName,
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
    // Laid out even with nothing to show, and hidden rather than omitted.
    //
    // The strip appears the moment there is a value to convert — which is the *first*
    // keystroke. Letting it appear from nothing pushed the whole keypad down with it: the
    // 7/8/9 row moved 74dp, a full row's height, between the first key and the second, so a
    // quick second tap landed on the row above the one the user was aiming at. Reserving the
    // space costs an empty band on a screen the user has not typed into yet, which is the
    // cheaper of the two.
    //
    // The placeholder mirrors a real column rather than setting a dp height, so the space
    // reserved still matches at a 2.0 font scale, where a fixed height would not.
    val empty = entries.isEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .then(if (empty) Modifier.alpha(0f).clearAndSetSemantics { } else Modifier),
    ) {
        Text(
            text = stringResource(R.string.converter_common),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (empty) {
                Column {
                    Text(text = " ", style = MaterialTheme.typography.bodyMedium)
                    Text(text = " ", style = MaterialTheme.typography.labelSmall)
                    Text(text = " ", style = MaterialTheme.typography.labelSmall)
                }
            }
            for ((unit, text) in entries) {
                // Merged: three separate TalkBack stops per column turned a four-unit strip
                // into twelve, and the number arrived before the unit that gives it meaning.
                Column(modifier = Modifier.semantics(mergeDescendants = true) { }) {
                    Text(text = text, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = unitSymbol(unit.id),
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
    // rememberSaveable: a rotation with the sheet open otherwise throws away a partly typed
    // search along with the list it had narrowed to.
    var query by rememberSaveable { mutableStateOf("") }
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
                        .clickable(role = Role.Button) { onPick(unit) }
                        // One stop per row, not one for the name and another for the symbol,
                        // in a list that is sixty rows long in the length category.
                        .semantics(mergeDescendants = true) { }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(UnitNames.nameRes(unit.id)),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = unitSymbol(unit.id),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The width at which the pad moves beside the readout instead of under it. */
private val WIDE_BREAKPOINT = 600.dp

/**
 * Everything above the keypad: the category chips, the two value rows with the swap button
 * between them, and the common conversions. One composable so the stacked and side-by-side
 * layouts cannot drift apart.
 *
 * @param onPick opens the unit picker; `true` for the from-unit, `false` for the to-unit.
 */
@Composable
private fun ConverterReadout(
    state: ConverterUiState,
    viewModel: ConverterViewModel,
    onPick: (Boolean) -> Unit,
) {
    // The typed side holds the locale-free text the parser reads — `1.5` under a German
    // keypad whose point key says `,` — and the computed side is already in the locale's
    // digits, so both are respelt the same way the calculator's formula line is. A no-op
    // on text that is already localised.
    val locale = LocalConfiguration.current.locales[0]
    val symbols = remember(locale) { DecimalFormatSymbols.getInstance(locale) }
    fun shown(text: String): String = localiseFormula(text, symbols.zeroDigit, symbols.decimalSeparator)

    CategoryChips(
        selected = state.dimension,
        onSelect = viewModel::onSelectDimension,
    )

    ValueRow(
        label = stringResource(R.string.converter_from),
        value = shown(state.fromText),
        unit = state.fromUnit,
        active = state.editingFrom,
        onActivate = { viewModel.onFocus(from = true) },
        onPickUnit = { onPick(true) },
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
        value = shown(state.toText),
        unit = state.toUnit,
        active = !state.editingFrom,
        onActivate = { viewModel.onFocus(from = false) },
        onPickUnit = { onPick(false) },
    )

    // Composed even when empty; see CommonConversions.
    CommonConversions(state.common)
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

    KeypadColumn(rows = 4, modifier = modifier) { row ->
        val rows = listOf(
            listOf(7, 8, 9),
            listOf(4, 5, 6),
            listOf(1, 2, 3),
        )
        // Label and spoken form are separate resources on purpose: the label is the glyph,
        // and a content description of "÷" is read out as the letter it happens to resemble.
        val trailing = listOf(
            Triple(KeyId.DIVIDE, R.string.op_div, R.string.desc_op_div),
            Triple(KeyId.MULTIPLY, R.string.op_mul, R.string.desc_op_mul),
            Triple(KeyId.SUBTRACT, R.string.op_sub, R.string.desc_op_sub),
        )
        rows.forEachIndexed { index, digits ->
            Row(
                modifier = row,
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
                val (key, labelRes, descRes) = trailing[index]
                CalcButton(
                    label = stringResource(labelRes),
                    contentDescription = stringResource(descRes),
                    onClick = { onKey(key) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    style = KeyStyle.OPERATOR,
                )
            }
        }
        Row(
            modifier = row,
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
                contentDescription = stringResource(R.string.desc_op_add),
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

/**
 * A unit's symbol, isolated from the paragraph it is drawn in.
 *
 * The degree sign is bidi class ET — a European Terminator — which takes its direction from
 * whatever surrounds it. With no adjacent European number and an RTL paragraph, "°C" was laid
 * out as "C°" in Arabic: the stored string was right and the rendering was not. The same
 * hazard applies to "µg", "Ω" and every other symbol built from neutrals.
 *
 * Isolated rather than pinned to LTR, because a symbol is not always an LTR run — the
 * formatter looks at the string and wraps it in whichever direction it actually reads.
 *
 * Not applied to the search haystack, which is matched against what the user types: the
 * isolate characters are invisible but they are still characters, and they would sit between
 * the query and the symbol it is meant to find.
 */
@Composable
private fun unitSymbol(id: String): String {
    val locale = LocalConfiguration.current.locales[0]
    val bidi = remember(locale) { BidiFormatter.getInstance(locale) }
    return bidi.unicodeWrap(stringResource(UnitNames.symbolRes(id)))
}
