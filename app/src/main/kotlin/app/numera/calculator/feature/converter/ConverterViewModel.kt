package app.numera.calculator.feature.converter

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.numera.calculator.math.AbortedException
import app.numera.calculator.math.AngleMode
import app.numera.calculator.math.CalculationException
import app.numera.calculator.math.UnifiedReal
import app.numera.calculator.math.expr.EvalResult
import app.numera.calculator.math.expr.ExprCodec
import app.numera.calculator.math.expr.ExprEvaluator
import app.numera.calculator.math.expr.KeyId
import app.numera.calculator.math.format.ResultFormatter
import app.numera.calculator.settings.SettingsStore
import app.numera.calculator.units.Dimension
import app.numera.calculator.units.UnitCatalog
import app.numera.calculator.units.UnitConverter
import app.numera.calculator.units.UnitDef
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Everything the converter screen draws. */
data class ConverterUiState(
    val dimension: Dimension = Dimension.LENGTH,
    val fromUnit: UnitDef = UnitCatalog.defaultFrom(Dimension.LENGTH),
    val toUnit: UnitDef = UnitCatalog.defaultTo(Dimension.LENGTH),
    val fromText: String = "",
    val toText: String = "",
    /** Which field the keypad types into; the other one is the computed one. */
    val editingFrom: Boolean = true,
    /** The same amount in a few sibling units, shown as a strip under the result. */
    val common: List<Pair<UnitDef, String>> = emptyList(),
) {

    /**
     * True when [other] describes the very same conversion this state does.
     *
     * A background conversion is valid only for the category, direction and unit pair it was
     * started from. Applying one after any of those changed writes a length into a field
     * labelled °C, or overwrites the number the user has just typed with the answer to a
     * question they are no longer asking.
     */
    fun describesSameConversion(other: ConverterUiState): Boolean =
        dimension == other.dimension &&
            editingFrom == other.editingFrom &&
            fromUnit == other.fromUnit &&
            toUnit == other.toUnit
}

/**
 * Holds the converter's two units and the value being entered into the active field.
 *
 * The typed value goes through the calculator's own parser rather than `String.toDouble`,
 * which is what lets a user type `2+3` in the from-field and convert five — and it means
 * the value that reaches [UnitConverter] is an exact [UnifiedReal], not a rounded double.
 *
 * The category, the two units, the focus and the typed tokens all go through
 * [SavedStateHandle]. A ViewModel survives rotation on its own, so without it the loss shows
 * up only after the system has reclaimed a backgrounded app — the screen comes back on
 * Length, metre → foot, having thrown away the unit pair the user had chosen.
 */
class ConverterViewModel(
    private val settings: SettingsStore,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private var input: ConverterInput = ConverterInput()

    /**
     * The exact value currently shown in the *computed* field.
     *
     * Kept so that a swap or a change of focus can move that value into the input as the
     * number it is. The alternative — re-reading the formatted string off the screen — is
     * lossy in every locale, and is what [ConverterInput] documents at length.
     */
    private var computed: UnifiedReal? = null

    private var conversionJob: Job? = null

    private val _state = MutableStateFlow(ConverterUiState())
    val state: StateFlow<ConverterUiState> = _state.asStateFlow()

    init {
        restore()
    }

    /**
     * Rebuilds the screen after process death.
     *
     * The input comes back as its token stream, never as the text that was on screen. A
     * half-typed expression such as `2+` is not something `CalculatorExpr.fromText` can parse
     * back, and a formatted number is not something any locale can parse back exactly — both
     * are the reasons [ExprCodec] exists. The passive field is not saved at all: recomputing
     * it costs one conversion and gives back the exact value, where a saved rendering would
     * give back a rounding of it.
     *
     * An input carrying a value no keypad literal can spell — the π a swapped angle holds —
     * has no token form and so is not restored. The category, the units and the focus still
     * are, which is the part the user would otherwise have to set up again by hand.
     */
    private fun restore() {
        val selection: ConverterSelection = restoredSelection(
            savedState.get<String>(KEY_DIMENSION),
            savedState.get<String>(KEY_FROM_UNIT),
            savedState.get<String>(KEY_TO_UNIT),
        )
        savedState.get<String>(KEY_EXPR)
            ?.let(ExprCodec::decodeFromString)
            ?.let { input = ConverterInput(it) }
        val editingFrom: Boolean = savedState.get<Boolean>(KEY_EDITING_FROM) ?: true
        val typed: String = input.displayOrNull().orEmpty()
        _state.value = ConverterUiState(
            dimension = selection.dimension,
            fromUnit = selection.fromUnit,
            toUnit = selection.toUnit,
            fromText = if (editingFrom) typed else "",
            toText = if (editingFrom) "" else typed,
            editingFrom = editingFrom,
        )
        if (!input.isEmpty()) recompute()
    }

    /**
     * Writes what the screen cannot be rebuilt without.
     *
     * Only the input side is saved. The computed side is derived, and saving its *rendering*
     * would be saving a number the app can no longer read back without rounding it.
     */
    private fun persist() {
        val current: ConverterUiState = _state.value
        savedState[KEY_DIMENSION] = current.dimension.name
        savedState[KEY_FROM_UNIT] = current.fromUnit.id
        savedState[KEY_TO_UNIT] = current.toUnit.id
        savedState[KEY_EDITING_FROM] = current.editingFrom
        // A carried exact value has no token form; see the note on restore().
        val encoded: String? =
            if (input.exact == null) ExprCodec.encodeToString(input.expr) else null
        savedState[KEY_EXPR] = encoded
    }

    fun onSelectDimension(dimension: Dimension) {
        // Cancelling matters as much as clearing here: a length conversion still in flight
        // would otherwise land after the category changed and print metres, and a metre
        // sibling strip, underneath a °C label.
        conversionJob?.cancel()
        conversionJob = null
        input = ConverterInput()
        computed = null
        _state.update {
            it.copy(
                dimension = dimension,
                fromUnit = UnitCatalog.defaultFrom(dimension),
                toUnit = UnitCatalog.defaultTo(dimension),
                fromText = "",
                toText = "",
                editingFrom = true,
                common = emptyList(),
            )
        }
        persist()
    }

    /**
     * Changes one of the two units.
     *
     * The computed side goes with it. Its number was produced for the unit that has just been
     * replaced, so leaving it on screen draws metres under a "foot" label until the new
     * conversion lands — and, worse, a swap taken inside that window would adopt it as the
     * new input, making the answer to the previous question permanent.
     */
    fun onSelectUnit(unit: UnitDef, from: Boolean) {
        computed = null
        _state.update {
            val picked = if (from) it.copy(fromUnit = unit) else it.copy(toUnit = unit)
            if (picked.editingFrom) {
                picked.copy(toText = "", common = emptyList())
            } else {
                picked.copy(fromText = "", common = emptyList())
            }
        }
        recompute()
        persist()
    }

    /**
     * Swaps the two units *and* the two values.
     *
     * Moving the computed value into the input is what makes the button feel like a swap
     * rather than a reset; leaving the input where it was would silently change the answer.
     * The value moved across is the exact one, never the string it was rendered into.
     */
    fun onSwap() {
        conversionJob?.cancel()
        val previous: ConverterUiState = _state.value
        // Whether a value crosses between the fields at all. It does not when the user was
        // typing into the from-field and nothing has been computed from it: the to-field is
        // blank because its conversion is undefined or has not landed yet.
        val moved: Boolean = !previous.editingFrom || computed != null
        // Whatever the to-field is showing becomes the new input. When the user was typing
        // into the to-field, the input already *is* that value and must be kept verbatim;
        // otherwise it is the computed one, adopted exactly — and when nothing has been
        // computed the input is kept too, which is what [ConverterInput.adopting] documents.
        if (previous.editingFrom) input = input.adopting(computed)
        // Consumed. Until the recompute below lands there is no computed field, and a second
        // swap that re-adopted this value would install the answer to the previous question.
        computed = null
        val carried: String = if (moved) previous.toText else previous.fromText
        val typed: String? = input.displayOrNull()
        _state.update {
            val swapped = it.copy(
                fromUnit = it.toUnit,
                toUnit = it.fromUnit,
                // A carried value has no typed rendering, so the from-field has to keep the
                // text that value is already drawn with: the to-field's when it crossed over,
                // its own when the input stayed where it was.
                fromText = typed ?: carried,
                // The old input becomes the new result, which is exactly what it is: if
                // x A is y B then y B is x A. Unless nothing crossed over, in which case
                // there is no result yet and the typed digits are not one.
                toText = if (moved) it.fromText else "",
                editingFrom = true,
            )
            // The sibling strip survives a swap that moved a value across: that is the same
            // physical amount in a different unit, so every sibling reads the same. An input
            // that stayed put is not — the same digits now mean a different quantity, and the
            // strip would show the previous unit's numbers under labels that no longer match.
            if (moved) swapped else swapped.copy(common = emptyList())
        }
        recompute()
        persist()
    }

    fun onFocus(from: Boolean) {
        val previous: ConverterUiState = _state.value
        if (previous.editingFrom == from) return
        conversionJob?.cancel()
        val moved: Boolean = computed != null
        // The field being focused was the computed one, so its value becomes the input.
        // Starting from an empty expression instead left the *other* field showing a result
        // computed from a number that was no longer anywhere on screen — and the next unit
        // change then blanked the typed side while keeping that stale answer.
        input = input.adopting(computed)
        // Consumed, as in onSwap: the field it came from is now the one being typed into.
        computed = null
        val typed: String? = input.displayOrNull()
        // Where the input's text is: already in the field being focused when it came from
        // there, still in the field being left when the input stayed put — in which case it
        // has to travel with the focus, or the active field reads as empty while the passive
        // one shows an answer derived from it.
        val shown: String? = when {
            typed != null -> typed
            moved -> null
            else -> if (from) previous.toText else previous.fromText
        }
        _state.update {
            // As in onSwap: a moved value is the same amount in another unit and its siblings
            // are unchanged; an input that stayed put now belongs to the other unit.
            val focused = it.copy(
                editingFrom = from,
                common = if (moved) it.common else emptyList(),
            )
            // A value that crossed over leaves the correct converse behind — if x A is y B
            // then y B is x A — so that text stays. An input that merely travelled with the
            // focus leaves nothing: its digits in both fields read as a converter that does
            // not convert, until the new conversion lands and replaces one of them.
            when {
                shown == null -> focused
                from -> focused.copy(fromText = shown, toText = if (moved) it.toText else "")
                else -> focused.copy(toText = shown, fromText = if (moved) it.fromText else "")
            }
        }
        recompute()
        persist()
    }

    fun onKey(key: KeyId) {
        val next = input.append(key)
        // A key the input refuses is not an edit. `×` on a carried value asks to use it and
        // is answered by keeping it, so there is nothing to recompute and nothing to persist.
        if (next == input) return
        input = next
        onInputChanged()
    }

    fun onDelete() {
        input = input.delete()
        onInputChanged()
    }

    fun onClear() {
        input = input.cleared()
        onInputChanged()
    }

    private fun onInputChanged() {
        // Null means "keep the text the field already shows", which is [ConverterInput]'s
        // own contract and not an empty field: a carried value exists on screen only as its
        // rendered string, and there is nothing here to re-render it from. Reading that null
        // as empty text blanked a converted value the moment any key left it in place.
        val typed: String? = input.displayOrNull()
        if (typed != null) {
            _state.update {
                if (it.editingFrom) it.copy(fromText = typed) else it.copy(toText = typed)
            }
        }
        recompute()
        persist()
    }

    private fun recompute() {
        conversionJob?.cancel()
        val snapshot = _state.value
        val source = input
        if (source.isEmpty()) {
            // Both fields, not only the passive one. With nothing to convert there is no
            // number to show anywhere, and blanking one side left the other holding an
            // answer that its own unit label no longer matched.
            computed = null
            _state.update { it.copy(fromText = "", toText = "", common = emptyList()) }
            return
        }
        val angleMode: AngleMode = settings.angleMode.value

        conversionJob = viewModelScope.launch {
            val converted: Conversion = try {
                withContext(Dispatchers.Default) {
                    runInterruptible<Conversion?> {
                        val amount: UnifiedReal = valueOf(source, angleMode)
                            ?: return@runInterruptible null
                        val fromUnit =
                            if (snapshot.editingFrom) snapshot.fromUnit else snapshot.toUnit
                        val toUnit =
                            if (snapshot.editingFrom) snapshot.toUnit else snapshot.fromUnit
                        try {
                            val result = UnitConverter.convert(amount, fromUnit, toUnit)
                            Conversion(
                                value = result,
                                text = format(result),
                                siblings = siblingsOf(
                                    amount,
                                    fromUnit,
                                    toUnit,
                                    snapshot.dimension,
                                ),
                            )
                        } catch (e: AbortedException) {
                            // Named ahead of every other ArithmeticException deliberately.
                            // Every CalculationException is one, so a broad catch here
                            // would turn "the user pressed another key" into a finished,
                            // wrong-looking answer. It has to keep travelling.
                            throw e
                        } catch (e: CalculationException) {
                            // A reciprocal unit is undefined at zero, and a value too large
                            // to approximate has no digits to print. In both cases an empty
                            // field is a better answer than infinity or a crash.
                            null
                        } catch (e: ArithmeticException) {
                            // BigInteger raises a bare one when a value outgrows its own
                            // supported range: still no answer, still not a crash.
                            null
                        }
                    }
                } ?: Conversion(null, "", emptyList())
            } catch (e: AbortedException) {
                // This job's result is stale by definition and a newer recompute() is
                // already queued, so the whole computation is dropped rather than shown.
                return@launch
            }

            // Discard a result the screen has moved on from instead of writing it into
            // whichever field happens to be active by the time it lands.
            if (!_state.value.describesSameConversion(snapshot)) return@launch
            computed = converted.value
            _state.update {
                if (snapshot.editingFrom) {
                    it.copy(toText = converted.text, common = converted.siblings)
                } else {
                    it.copy(fromText = converted.text, common = converted.siblings)
                }
            }
        }
    }

    /** One finished conversion: the exact result, its rendering, and the sibling strip. */
    private class Conversion(
        val value: UnifiedReal?,
        val text: String,
        val siblings: List<Pair<UnitDef, String>>,
    )

    /** The exact number the input stands for, or `null` when it does not parse. */
    private fun valueOf(source: ConverterInput, mode: AngleMode): UnifiedReal? {
        source.exact?.let { return it }
        val parsed = ExprEvaluator.evaluate(source.expr, mode)
        return (parsed as? EvalResult.Success)?.value
    }

    /**
     * The same amount expressed in a few other units of the dimension.
     *
     * Drawn from [UnitCatalog.popularOf] rather than declaration order: the catalogue lists
     * every dimension smallest-unit-first, so taking the first entries spent all four slots
     * on nanometres through centimetres while the user was converting miles. The
     * destination unit is skipped alongside the source, because repeating the answer that
     * is already on screen costs one of only four slots.
     */
    private fun siblingsOf(
        amount: UnifiedReal,
        from: UnitDef,
        to: UnitDef,
        dimension: Dimension,
    ): List<Pair<UnitDef, String>> =
        UnitCatalog.popularOf(dimension)
            .asSequence()
            .filter { it.id != from.id && it.id != to.id }
            .take(COMMON_COUNT)
            .mapNotNull { unit ->
                try {
                    unit to format(UnitConverter.convert(amount, from, unit))
                } catch (e: AbortedException) {
                    // As above: an abort is a cancellation, not a unit that cannot be shown.
                    throw e
                } catch (e: CalculationException) {
                    null
                } catch (e: ArithmeticException) {
                    null
                }
            }
            .toList()

    private fun format(value: UnifiedReal): String =
        ResultFormatter.formatShort(value, VALUE_BUDGET, Locale.getDefault())

    private companion object {
        const val VALUE_BUDGET = 18
        const val COMMON_COUNT = 4

        const val KEY_DIMENSION = "converter_dimension"
        const val KEY_FROM_UNIT = "converter_from_unit"
        const val KEY_TO_UNIT = "converter_to_unit"
        const val KEY_EDITING_FROM = "converter_editing_from"
        const val KEY_EXPR = "converter_expr"
    }
}

/** Builds the view model without a DI framework, matching the rest of the app. */
fun converterViewModelFactory(settings: SettingsStore): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { ConverterViewModel(settings, createSavedStateHandle()) }
    }
