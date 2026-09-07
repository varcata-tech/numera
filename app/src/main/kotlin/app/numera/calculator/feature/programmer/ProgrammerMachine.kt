package app.numera.calculator.feature.programmer

/**
 * Immediate-execution state machine over [BitwiseEngine], with no Android in it.
 *
 * Deliberately not the expression parser the main calculator uses: programmer arithmetic is
 * fixed-width and wrapping, and running it through an exact-rational engine would give
 * mathematically correct answers to the wrong question — 0xFF + 1 is 0x00 on an 8-bit
 * machine, and that is the answer this screen must give.
 *
 * Kept out of [ProgrammerViewModel] so the keystroke sequences that used to be wrong can be
 * driven from a JVM test. Every one of the bugs this type was extracted to fix — a second
 * operator applying the first one twice, a zero divisor reported as "Overflow" with the
 * divisor left on screen as though it were the answer, backspace wiping the whole register —
 * is invisible in a screenshot and obvious in three lines of test.
 */
internal data class ProgrammerMachine(
    val ui: ProgrammerUiState = ProgrammerUiState(),
    /** The left-hand operand of [pending]. */
    val accumulator: Long = 0L,
    val pending: BinaryOp? = null,
    /**
     * True between an operator key and the operand that follows it.
     *
     * Not the same question as `ui.entry.isEmpty()`, which is why it is a field of its own:
     * the entry buffer is also cleared by a bit tap, NOT and a base switch, and each of those
     * *does* supply an operand. Treating an empty buffer as "no operand yet" made
     * `5 + [tap a bit] × 3 =` skip the addition.
     */
    val awaitingOperand: Boolean = false,
) {

    fun selectBase(base: NumberBase): ProgrammerMachine =
        // Whatever is half-typed is committed before switching, or the digits would be
        // reinterpreted in the new base and silently mean something else.
        copy(ui = ui.copy(base = base, entry = ""))

    fun selectWordSize(size: WordSize): ProgrammerMachine = copy(
        ui = ui.copy(
            wordSize = size,
            value = BitwiseEngine.truncate(ui.value, size),
            entry = "",
            error = null,
        ),
    )

    /**
     * The signed/unsigned chip.
     *
     * The error goes with it. "Overflow" is a claim about a result not fitting the word *as
     * it was being read*, and 0x7F + 1 overflows a signed byte while the same 0x80 is an
     * ordinary unsigned 128 — so a flag left standing across the toggle describes a fault the
     * value on screen no longer has.
     */
    fun toggleSigned(): ProgrammerMachine =
        copy(ui = ui.copy(signed = !ui.signed, entry = "", error = null))

    fun digit(digit: Char): ProgrammerMachine {
        if (!BitwiseEngine.isDigitAllowed(digit, ui.base)) return this
        val candidate = ui.entry + digit.uppercaseChar()
        // A keystroke that would not fit the word is refused rather than accepted and
        // truncated: silently dropping the high digit is worse than not typing it.
        val parsed = BitwiseEngine.parse(candidate, ui.base, ui.wordSize) ?: return this
        return copy(
            ui = ui.copy(entry = candidate, value = parsed, error = null),
            awaitingOperand = false,
        )
    }

    /**
     * Backspace.
     *
     * With nothing in the entry buffer this shortens the *register*. It used to set the whole
     * word to zero, which is reachable at any moment — the buffer is emptied by every
     * operator, base switch, width switch, NOT and bit tap — so typing 100, tapping a bit and
     * pressing delete destroyed the value instead of correcting it.
     */
    fun delete(): ProgrammerMachine {
        if (ui.entry.isNotEmpty()) {
            val shortened = ui.entry.dropLast(1)
            val parsed = BitwiseEngine.parse(shortened, ui.base, ui.wordSize) ?: 0L
            return copy(
                ui = ui.copy(entry = shortened, value = parsed, error = null),
                awaitingOperand = false,
            )
        }
        val shortened = BitwiseEngine.dropLastDigit(ui.value, ui.wordSize, ui.signed, ui.base)
        return copy(
            ui = ui.copy(value = shortened, entry = editableDigits(shortened), error = null),
            awaitingOperand = false,
        )
    }

    fun clear(): ProgrammerMachine =
        ProgrammerMachine(ui = ui.copy(value = 0L, entry = "", error = null))

    fun toggleBit(bit: Int): ProgrammerMachine {
        val toggled = ui.value xor (1L shl bit)
        return copy(
            ui = ui.copy(
                value = BitwiseEngine.truncate(toggled, ui.wordSize),
                entry = "",
                error = null,
            ),
            awaitingOperand = false,
        )
    }

    fun not(): ProgrammerMachine = copy(
        ui = ui.copy(value = BitwiseEngine.not(ui.value, ui.wordSize), entry = "", error = null),
        awaitingOperand = false,
    )

    /**
     * The sign key.
     *
     * The only way to enter a negative operand: the decimal keypad admits digits alone, so
     * without this a screen that defaults to *signed* and renders signed decimals could not
     * be given a negative number at all except by tapping the sign bit.
     */
    fun negate(): ProgrammerMachine = copy(
        ui = ui.copy(
            value = BitwiseEngine.twosComplement(ui.value, ui.wordSize),
            entry = "",
            error = null,
        ),
        awaitingOperand = false,
    )

    /**
     * An operator key.
     *
     * Two operators in a row replace each other. Applying the first one again is what made
     * `5 + × 3 =` answer 30: with no operand typed since the `+`, the displayed value was
     * still the left-hand 5 and the pending addition ran as 5 + 5.
     */
    fun operator(op: BinaryOp): ProgrammerMachine {
        if (ui.error == ProgError.DIVIDE_BY_ZERO) return this
        if (awaitingOperand) return copy(pending = op)
        val applied = applyPending()
        // A refused division keeps its operands so the divisor can be retyped; taking a new
        // operator here would throw the division away and answer the wrong question.
        if (applied.ui.error == ProgError.DIVIDE_BY_ZERO) return applied
        return applied.copy(
            ui = applied.ui.copy(entry = ""),
            accumulator = applied.ui.value,
            pending = op,
            awaitingOperand = true,
        )
    }

    /**
     * The equals key.
     *
     * Unlike [operator] this leaves `x op =` alone: repeating the displayed operand is what
     * every immediate-execution calculator does there.
     */
    fun evaluate(): ProgrammerMachine {
        if (ui.error == ProgError.DIVIDE_BY_ZERO) return this
        return applyPending().copy(awaitingOperand = false)
    }

    /**
     * Runs the pending operation, if there is one.
     *
     * A zero divisor computes nothing at all. The operation and its left-hand operand stay
     * pending and the readout goes back to showing that operand: leaving the divisor on
     * screen presented the number the user had just typed as though it were the answer, and
     * discarding the operation meant retyping the divisor and pressing equals did nothing.
     * The error is latched — [operator] and [evaluate] refuse until a digit, delete or clear
     * moves the value on — so the failure cannot be walked past into a plausible answer.
     */
    private fun applyPending(): ProgrammerMachine {
        val op = pending ?: return this
        val size = ui.wordSize
        val signed = ui.signed
        val rhs = ui.value

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
            BinaryOp.MOD -> BitwiseEngine.remainder(accumulator, rhs, size, signed)
        }

        if (result == null) {
            return copy(
                ui = ui.copy(value = accumulator, entry = "", error = ProgError.DIVIDE_BY_ZERO),
                awaitingOperand = false,
            )
        }
        return copy(
            ui = ui.copy(
                value = result.value,
                entry = "",
                // Overflow describes the value now on screen, so it is set and cleared with
                // it rather than left standing over a later, perfectly representable answer.
                error = if (result.overflow) ProgError.OVERFLOW else null,
            ),
            accumulator = result.value,
            pending = null,
        )
    }

    /**
     * [value] as digits that can be typed on to, or "" when it cannot be.
     *
     * A negative signed decimal renders with a minus sign, which no key can produce and
     * [BitwiseEngine.parse] will not read back, so there is nothing to append to.
     */
    private fun editableDigits(value: Long): String {
        val text = BitwiseEngine.format(value, ui.wordSize, ui.signed, ui.base)
        return if (text.all { it in ui.base.digits }) text else ""
    }

    private fun Long.noOverflow(): BitwiseEngine.Operation =
        BitwiseEngine.Operation(this, overflow = false)
}

/**
 * Rebuilds the machine from the values [ProgrammerViewModel] saved.
 *
 * Kept pure, and separate from the view model, because everything it gets wrong is silent.
 * The entry buffer is the case that proves it: it was not saved at all, so a screen reclaimed
 * mid-word came back looking identical — [ProgrammerUiState.rendered] falls through to the
 * committed value — and the next digit typed replaced the word instead of extending it,
 * turning 0x100 into 0x0 rather than 0x1000.
 *
 * Enums come back by name, never by ordinal, so reordering [WordSize], [NumberBase],
 * [BinaryOp] or [ProgError] in a later release cannot silently turn a saved 32-bit word into
 * a 64-bit one. Anything that fails to match falls back to the default rather than throwing —
 * a saved state written by an older build must not stop the screen from opening.
 */
internal fun restoredMachine(
    value: Long?,
    sizeName: String?,
    baseName: String?,
    signed: Boolean?,
    accumulator: Long?,
    pendingName: String?,
    entry: String?,
    awaitingOperand: Boolean?,
    errorName: String?,
): ProgrammerMachine {
    val size: WordSize = sizeName
        ?.let { name -> enumValues<WordSize>().firstOrNull { it.name == name } }
        ?: WordSize.BITS_32
    val base: NumberBase = baseName
        ?.let { name -> enumValues<NumberBase>().firstOrNull { it.name == name } }
        ?: NumberBase.HEX
    val typed: String = entry.orEmpty()
    // A buffer that no longer fits the restored base or width is dropped rather than shown:
    // a saved 8-bit "FF" alongside a base since switched to decimal is not a number the user
    // can go on typing.
    val typedValue: Long? =
        if (typed.isEmpty()) null else BitwiseEngine.parse(typed, base, size)
    return ProgrammerMachine(
        ui = ProgrammerUiState(
            // Truncated on the way in: a saved 64-bit word restored alongside a saved 8-bit
            // width would otherwise show digits the emulated register cannot hold.
            value = typedValue ?: BitwiseEngine.truncate(value ?: 0L, size),
            wordSize = size,
            signed = signed ?: true,
            base = base,
            error = errorName
                ?.let { name -> enumValues<ProgError>().firstOrNull { it.name == name } },
            entry = if (typedValue == null) "" else typed,
        ),
        accumulator = BitwiseEngine.truncate(accumulator ?: 0L, size),
        pending = pendingName
            ?.let { name -> enumValues<BinaryOp>().firstOrNull { it.name == name } },
        awaitingOperand = awaitingOperand ?: false,
    )
}
