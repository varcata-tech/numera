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
import kotlinx.coroutines.flow.update

/** The binary operations the programmer keypad offers. */
enum class BinaryOp { AND, OR, XOR, SHL, SHR, ROL, ROR, ADD, SUBTRACT, MULTIPLY, DIVIDE }

/** Everything the programmer screen draws. */
data class ProgrammerUiState(
    val value: Long = 0L,
    val wordSize: WordSize = WordSize.BITS_32,
    val signed: Boolean = true,
    val base: NumberBase = NumberBase.HEX,
    val overflow: Boolean = false,
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
 * Immediate-execution state machine over [BitwiseEngine].
 *
 * Deliberately not the expression parser the main calculator uses: programmer arithmetic is
 * fixed-width and wrapping, and running it through an exact-rational engine would give
 * mathematically correct answers to the wrong question — 0xFF + 1 is 0x00 on an 8-bit
 * machine, and that is the answer this screen must give.
 *
 * The word, its width, its signedness and the pending operation all go through
 * [SavedStateHandle]. Without it the screen is the one surface that loses everything to
 * process death: a ViewModel survives rotation on its own, so the loss only shows up after
 * the system has reclaimed a backgrounded app, which is precisely when it is hardest to
 * notice and most annoying.
 */
class ProgrammerViewModel(private val savedState: SavedStateHandle) : ViewModel() {

    private var accumulator: Long = 0L
    private var pending: BinaryOp? = null

    private val _state = MutableStateFlow(ProgrammerUiState())
    val state: StateFlow<ProgrammerUiState> = _state.asStateFlow()

    init {
        restore()
    }

    fun onSelectBase(base: NumberBase) {
        // Commit whatever is half-typed before switching, or the digits would be
        // reinterpreted in the new base and silently mean something else.
        commitEntry()
        _state.update { it.copy(base = base) }
        persist()
    }

    fun onSelectWordSize(size: WordSize) {
        commitEntry()
        _state.update {
            it.copy(wordSize = size, value = BitwiseEngine.truncate(it.value, size), overflow = false)
        }
        persist()
    }

    fun onToggleSigned() {
        commitEntry()
        _state.update { it.copy(signed = !it.signed) }
        persist()
    }

    fun onDigit(digit: Char) {
        val current = _state.value
        if (!BitwiseEngine.isDigitAllowed(digit, current.base)) return
        val candidate = current.entry + digit.uppercaseChar()
        // Refuse a keystroke that would not fit the word rather than accepting it and
        // truncating: silently dropping the high digit is worse than not typing it.
        val parsed = BitwiseEngine.parse(candidate, current.base, current.wordSize) ?: return
        _state.update { it.copy(entry = candidate, value = parsed, overflow = false) }
        persist()
    }

    fun onDelete() {
        val current = _state.value
        if (current.entry.isEmpty()) {
            _state.update { it.copy(value = 0L, overflow = false) }
            persist()
            return
        }
        val shortened = current.entry.dropLast(1)
        val parsed = BitwiseEngine.parse(shortened, current.base, current.wordSize) ?: 0L
        _state.update { it.copy(entry = shortened, value = parsed) }
        persist()
    }

    fun onClear() {
        accumulator = 0L
        pending = null
        _state.update { it.copy(value = 0L, entry = "", overflow = false) }
        persist()
    }

    fun onToggleBit(bit: Int) {
        commitEntry()
        _state.update {
            val toggled = it.value xor (1L shl bit)
            it.copy(value = BitwiseEngine.truncate(toggled, it.wordSize), overflow = false)
        }
        persist()
    }

    fun onNot() {
        commitEntry()
        _state.update {
            it.copy(value = BitwiseEngine.not(it.value, it.wordSize), overflow = false)
        }
        persist()
    }

    fun onOperator(op: BinaryOp) {
        commitEntry()
        applyPending()
        accumulator = _state.value.value
        pending = op
        _state.update { it.copy(entry = "") }
        persist()
    }

    fun onEquals() {
        commitEntry()
        applyPending()
        pending = null
        persist()
    }

    private fun commitEntry() {
        _state.update { it.copy(entry = "") }
    }

    /**
     * Rebuilds the screen after process death.
     *
     * Enums come back by name, never by ordinal, so reordering [WordSize] or [NumberBase] in a
     * later release cannot silently turn a saved 32-bit word into a 64-bit one. Anything that
     * fails to match falls back to the default rather than throwing — a saved state written by
     * an older build must not stop the screen from opening.
     */
    private fun restore() {
        val size = savedState.get<String>(KEY_SIZE)
            ?.let { name -> enumValues<WordSize>().firstOrNull { it.name == name } }
            ?: WordSize.BITS_32
        val base = savedState.get<String>(KEY_BASE)
            ?.let { name -> enumValues<NumberBase>().firstOrNull { it.name == name } }
            ?: NumberBase.HEX
        accumulator = savedState.get<Long>(KEY_ACCUMULATOR) ?: 0L
        pending = savedState.get<String>(KEY_PENDING)
            ?.let { name -> enumValues<BinaryOp>().firstOrNull { it.name == name } }
        _state.value = ProgrammerUiState(
            // Truncated on the way in: a saved 64-bit word restored alongside a saved 8-bit
            // width would otherwise show digits the emulated register cannot hold.
            value = BitwiseEngine.truncate(savedState.get<Long>(KEY_VALUE) ?: 0L, size),
            wordSize = size,
            signed = savedState.get<Boolean>(KEY_SIGNED) ?: true,
            base = base,
        )
    }

    /** Saves the word itself, never its rendering, so no base or width choice is baked in. */
    private fun persist() {
        val current = _state.value
        savedState[KEY_VALUE] = current.value
        savedState[KEY_SIZE] = current.wordSize.name
        savedState[KEY_BASE] = current.base.name
        savedState[KEY_SIGNED] = current.signed
        savedState[KEY_ACCUMULATOR] = accumulator
        savedState[KEY_PENDING] = pending?.name
    }

    private fun applyPending() {
        val op = pending ?: return
        val current = _state.value
        val size = current.wordSize
        val signed = current.signed
        val rhs = current.value

        val result: BitwiseEngine.Operation? = when (op) {
            BinaryOp.AND -> BitwiseEngine.and(accumulator, rhs, size).noOverflow()
            BinaryOp.OR -> BitwiseEngine.or(accumulator, rhs, size).noOverflow()
            BinaryOp.XOR -> BitwiseEngine.xor(accumulator, rhs, size).noOverflow()
            // The count goes across as the full machine word. Narrowing it here is what let a
            // count of 2^32 arrive as zero and leave the value untouched.
            BinaryOp.SHL -> BitwiseEngine.shiftLeft(accumulator, rhs, size).noOverflow()
            // Arithmetic when signed, logical when not: the two differ on a negative value,
            // and picking one for both is the classic programmer-calculator bug.
            BinaryOp.SHR -> if (signed) {
                BitwiseEngine.shiftRightArithmetic(accumulator, rhs, size).noOverflow()
            } else {
                BitwiseEngine.shiftRightLogical(accumulator, rhs, size).noOverflow()
            }
            BinaryOp.ROL -> BitwiseEngine.rotateLeft(accumulator, rhs, size).noOverflow()
            BinaryOp.ROR -> BitwiseEngine.rotateRight(accumulator, rhs, size).noOverflow()
            BinaryOp.ADD -> BitwiseEngine.add(accumulator, rhs, size, signed)
            BinaryOp.SUBTRACT -> BitwiseEngine.subtract(accumulator, rhs, size, signed)
            BinaryOp.MULTIPLY -> BitwiseEngine.multiply(accumulator, rhs, size, signed)
            BinaryOp.DIVIDE -> BitwiseEngine.divide(accumulator, rhs, size, signed)
        }

        if (result == null) {
            // Division by zero. Leave the operands alone so the user can correct the
            // divisor rather than having to retype both.
            _state.update { it.copy(overflow = true) }
            return
        }
        accumulator = result.value
        _state.update { it.copy(value = result.value, overflow = result.overflow, entry = "") }
    }

    private fun Long.noOverflow(): BitwiseEngine.Operation =
        BitwiseEngine.Operation(this, overflow = false)

    private companion object {
        const val KEY_VALUE = "programmer_value"
        const val KEY_SIZE = "programmer_word_size"
        const val KEY_BASE = "programmer_base"
        const val KEY_SIGNED = "programmer_signed"
        const val KEY_ACCUMULATOR = "programmer_accumulator"
        const val KEY_PENDING = "programmer_pending"
    }
}

/** Builds the view model without a DI framework, matching the rest of the app. */
fun programmerViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer { ProgrammerViewModel(createSavedStateHandle()) }
}
