package app.numera.calculator.feature.programmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The programmer keypad as a sequence of keystrokes, which is the only way these faults show.
 *
 * Every case here produced a plausible number on screen with nothing marking it wrong. A
 * second operator applied the first one twice, so `5 + × 3 =` answered 30. A zero divisor was
 * announced as "Overflow", left the divisor sitting in the readout as though it were the
 * answer, and threw the division away so that correcting the divisor did nothing. Delete with
 * no half-typed digits wiped the whole register instead of shortening it. None of that is
 * visible in [BitwiseEngine], which is correct arithmetic on the wrong operands.
 */
class ProgrammerMachineTest {

    /** Decimal, because a test that has to be read in hex is a test nobody checks. */
    private fun decimal(): ProgrammerMachine = ProgrammerMachine().selectBase(NumberBase.DEC)

    private fun ProgrammerMachine.type(digits: String): ProgrammerMachine =
        digits.fold(this) { machine, digit -> machine.digit(digit) }

    @Test
    fun `a second operator replaces the first instead of applying it to one operand twice`() {
        val result = decimal()
            .type("5")
            .operator(BinaryOp.ADD)
            .operator(BinaryOp.MULTIPLY)
            .type("3")
            .evaluate()
        // 15, the way every immediate-execution calculator answers it. The old machine ran
        // the pending addition with the displayed 5 as both operands and multiplied 10 by 3.
        assertEquals(15L, result.ui.value)
        assertNull(result.ui.error)
    }

    /**
     * The flag is not "the entry buffer is empty".
     *
     * A bit tap clears the buffer and *does* supply an operand, so the pending operation must
     * still run: 5 + (bit 1 set, making 7) × 3 is 36.
     */
    @Test
    fun `an operand entered by tapping a bit still counts as an operand`() {
        val result = decimal()
            .type("5")
            .operator(BinaryOp.ADD)
            .toggleBit(1)
            .operator(BinaryOp.MULTIPLY)
            .type("3")
            .evaluate()
        assertEquals(36L, result.ui.value)
    }

    @Test
    fun `chaining a new operation onto a result uses the result`() {
        val result = decimal()
            .type("5").operator(BinaryOp.ADD).type("3").evaluate()
            .operator(BinaryOp.MULTIPLY).type("2").evaluate()
        assertEquals(16L, result.ui.value)
    }

    @Test
    fun `equals with no operand typed repeats the displayed one`() {
        assertEquals(10L, decimal().type("5").operator(BinaryOp.ADD).evaluate().ui.value)
    }

    @Test
    fun `dividing by zero says so, and does not present the divisor as the answer`() {
        val failed = decimal().type("10").operator(BinaryOp.DIVIDE).type("0").evaluate()
        assertEquals(ProgError.DIVIDE_BY_ZERO, failed.ui.error)
        // Not 0. The divisor was the last thing typed, and leaving it in the readout is what
        // made a refused division look like a completed one that answered zero.
        assertEquals(10L, failed.ui.value)
    }

    @Test
    fun `a refused division keeps its operands so the divisor can be corrected`() {
        val corrected = decimal()
            .type("10").operator(BinaryOp.DIVIDE).type("0").evaluate()
            .type("5").evaluate()
        assertEquals(2L, corrected.ui.value)
        assertNull(corrected.ui.error)
    }

    /**
     * The error is latched.
     *
     * Before, the next keystrokes simply carried on: the digit cleared the flag, the
     * accumulator had been reset to the discarded divisor, and `+ 5 =` answered 5 with
     * nothing left on screen to say a division had failed.
     */
    @Test
    fun `an operator pressed after a divide by zero does not clear it or invent an answer`() {
        val stillFailed = decimal()
            .type("10").operator(BinaryOp.DIVIDE).type("0").evaluate()
            .operator(BinaryOp.ADD)
        assertEquals(ProgError.DIVIDE_BY_ZERO, stillFailed.ui.error)
        assertEquals(10L, stillFailed.ui.value)
        // The division is still the pending operation, not the addition that was refused.
        val resumed = stillFailed.type("5").evaluate()
        assertEquals(2L, resumed.ui.value)
    }

    @Test
    fun `modulo divides by zero the same way division does`() {
        val ok = decimal().type("10").operator(BinaryOp.MOD).type("3").evaluate()
        assertEquals(1L, ok.ui.value)
        val failed = decimal().type("10").operator(BinaryOp.MOD).type("0").evaluate()
        assertEquals(ProgError.DIVIDE_BY_ZERO, failed.ui.error)
    }

    @Test
    fun `an overflow is reported as an overflow and not as a divide by zero`() {
        val wrapped = ProgrammerMachine()
            .selectWordSize(WordSize.BITS_8)
            .toggleSigned()
            .type("FF")
            .operator(BinaryOp.ADD)
            .type("1")
            .evaluate()
        assertEquals(0L, wrapped.ui.value)
        assertEquals(ProgError.OVERFLOW, wrapped.ui.error)
    }

    @Test
    fun `the sign key is the only way to reach a negative operand from the keypad`() {
        val negated = ProgrammerMachine()
            .selectWordSize(WordSize.BITS_8)
            .selectBase(NumberBase.DEC)
            .type("5")
            .negate()
        assertEquals("-5", negated.ui.rendered(NumberBase.DEC))
        // A real operand, not only a rendering: −5 + 2 is −3.
        val sum = negated.operator(BinaryOp.ADD).type("2").evaluate()
        assertEquals("-3", sum.ui.rendered(NumberBase.DEC))
    }

    /**
     * Delete with an empty entry buffer.
     *
     * The buffer is emptied by every operator, base switch, width switch, NOT and bit tap, so
     * this is reachable at any moment — and it used to set the whole word to zero. Typing
     * 0x100, tapping bit 4 and pressing delete destroyed the value instead of correcting it.
     */
    @Test
    fun `delete shortens the register rather than wiping it`() {
        val shortened = ProgrammerMachine().type("100").toggleBit(4).delete()
        assertEquals(0x11L, shortened.ui.value)
        // And what is left can be typed on to, rather than being replaced by the next digit.
        assertEquals(0x115L, shortened.digit('5').ui.value)
    }

    @Test
    fun `clear forgets the pending operation as well as the value`() {
        val cleared = decimal().type("5").operator(BinaryOp.ADD).clear()
        assertEquals(0L, cleared.ui.value)
        assertNull(cleared.pending)
        assertEquals(0L, cleared.accumulator)
        // 7, not 12: there is no addition left to complete.
        assertEquals(7L, cleared.type("7").evaluate().ui.value)
    }
}
