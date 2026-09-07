package app.numera.calculator.feature.programmer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The binary operations the programmer keypad offers. */
enum class BinaryOp { AND, OR, XOR, SHL, SHR, ROL, ROR, ADD, SUBTRACT, MULTIPLY, DIVIDE, MOD }

/**
 * What went wrong with the value on screen.
 *
 * Two cases, not one flag, because they are different faults and the difference is the whole
 * message: an overflow is a real answer that left the word's range, while a zero divisor has
 * no answer at all. Reporting the second as "Overflow" — which is what a single boolean
 * forced — names a fault the user did not commit, in every one of the twelve languages.
 */
enum class ProgError { OVERFLOW, DIVIDE_BY_ZERO }

/** Everything the programmer screen draws. */
data class ProgrammerUiState(
    val value: Long = 0L,
    val wordSize: WordSize = WordSize.BITS_32,
    val signed: Boolean = true,
    val base: NumberBase = NumberBase.HEX,
    val error: ProgError? = null,
    /** Digits typed so far in the active base; empty means "showing [value]". */
    val entry: String = "",
) {
    /** The value as it should appear in [base]'s row. */
    fun rendered(base: NumberBase): String {
        // The binary row is always the whole word, nibble-grouped, even while digits are
        // still being typed. Echoing the raw entry there dropped the grouping until the
        // entry was committed, so the row flipped between two layouts under the user's
        // finger — and ungrouped bits cannot be counted by eye, which is the only reason
        // the row is shown at all. [value] already holds what [entry] parses to, so nothing
        // typed is lost by rendering from it.
        if (base == NumberBase.BIN) {
            return BitwiseEngine.formatBinaryGrouped(value, wordSize)
        }
        if (entry.isNotEmpty() && base == this.base) return entry
        return BitwiseEngine.format(value, wordSize, signed, base)
    }
}

/**
 * Holds the programmer screen's [ProgrammerMachine] and saves it.
 *
 * The state machine itself is pure and lives next door, so the keystroke sequences that
 * actually go wrong can be tested on the JVM. All this type adds is the Compose-facing flow
 * and the [SavedStateHandle] round trip.
 *
 * Everything the machine holds goes through saved state, the half-typed entry buffer
 * included. Without it the screen is the one surface that loses work to process death: a
 * ViewModel survives rotation on its own, so the loss only shows up after the system has
 * reclaimed a backgrounded app, which is precisely when it is hardest to notice.
 */
class ProgrammerViewModel(private val savedState: SavedStateHandle) : ViewModel() {

    private var machine: ProgrammerMachine = restoredMachine(
        value = savedState.get<Long>(KEY_VALUE),
        sizeName = savedState.get<String>(KEY_SIZE),
        baseName = savedState.get<String>(KEY_BASE),
        signed = savedState.get<Boolean>(KEY_SIGNED),
        accumulator = savedState.get<Long>(KEY_ACCUMULATOR),
        pendingName = savedState.get<String>(KEY_PENDING),
        entry = savedState.get<String>(KEY_ENTRY),
        awaitingOperand = savedState.get<Boolean>(KEY_AWAITING),
        errorName = savedState.get<String>(KEY_ERROR),
    )

    private val _state = MutableStateFlow(machine.ui)
    val state: StateFlow<ProgrammerUiState> = _state.asStateFlow()

    fun onSelectBase(base: NumberBase) = step { it.selectBase(base) }

    fun onSelectWordSize(size: WordSize) = step { it.selectWordSize(size) }

    fun onToggleSigned() = step { it.toggleSigned() }

    fun onDigit(digit: Char) = step { it.digit(digit) }

    fun onDelete() = step { it.delete() }

    fun onClear() = step { it.clear() }

    fun onToggleBit(bit: Int) = step { it.toggleBit(bit) }

    fun onNot() = step { it.not() }

    fun onNegate() = step { it.negate() }

    fun onOperator(op: BinaryOp) = step { it.operator(op) }

    fun onEquals() = step { it.evaluate() }

    private fun step(transition: (ProgrammerMachine) -> ProgrammerMachine) {
        machine = transition(machine)
        _state.value = machine.ui
        persist()
    }

    /** Saves the word itself, never its rendering, so no base or width choice is baked in. */
    private fun persist() {
        savedState[KEY_VALUE] = machine.ui.value
        savedState[KEY_SIZE] = machine.ui.wordSize.name
        savedState[KEY_BASE] = machine.ui.base.name
        savedState[KEY_SIGNED] = machine.ui.signed
        savedState[KEY_ENTRY] = machine.ui.entry
        savedState[KEY_ERROR] = machine.ui.error?.name
        savedState[KEY_ACCUMULATOR] = machine.accumulator
        savedState[KEY_PENDING] = machine.pending?.name
        savedState[KEY_AWAITING] = machine.awaitingOperand
    }

    private companion object {
        const val KEY_VALUE = "programmer_value"
        const val KEY_SIZE = "programmer_word_size"
        const val KEY_BASE = "programmer_base"
        const val KEY_SIGNED = "programmer_signed"
        const val KEY_ACCUMULATOR = "programmer_accumulator"
        const val KEY_PENDING = "programmer_pending"
        const val KEY_ENTRY = "programmer_entry"
        const val KEY_AWAITING = "programmer_awaiting_operand"
        const val KEY_ERROR = "programmer_error"
    }
}

/** Builds the view model without a DI framework, matching the rest of the app. */
fun programmerViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { ProgrammerViewModel(createSavedStateHandle()) }
}
