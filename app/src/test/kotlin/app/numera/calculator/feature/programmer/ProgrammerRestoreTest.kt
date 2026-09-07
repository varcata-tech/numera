package app.numera.calculator.feature.programmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the programmer screen comes back as after the system has reclaimed it.
 *
 * The failure this covers is silent by construction. The half-typed entry buffer was never
 * saved, and the screen looked *identical* without it — [ProgrammerUiState.rendered] falls
 * through to the committed value — so the only symptom was the next keystroke: typing 100 in
 * hex, being reclaimed, coming back and pressing 0 turned 0x100 into 0x0 instead of 0x1000,
 * and delete cleared the register instead of shortening it.
 */
class ProgrammerRestoreTest {

    private fun restored(
        value: Long? = 0L,
        sizeName: String? = WordSize.BITS_32.name,
        baseName: String? = NumberBase.HEX.name,
        signed: Boolean? = true,
        accumulator: Long? = 0L,
        pendingName: String? = null,
        entry: String? = null,
        awaitingOperand: Boolean? = false,
        errorName: String? = null,
    ): ProgrammerMachine = restoredMachine(
        value = value,
        sizeName = sizeName,
        baseName = baseName,
        signed = signed,
        accumulator = accumulator,
        pendingName = pendingName,
        entry = entry,
        awaitingOperand = awaitingOperand,
        errorName = errorName,
    )

    @Test
    fun `a half-typed word survives and the next digit extends it`() {
        val machine = restored(value = 0x100L, entry = "100")
        assertEquals("100", machine.ui.entry)
        assertEquals(0x1000L, machine.digit('0').ui.value)
    }

    @Test
    fun `a restored half-typed word can be backspaced a digit at a time`() {
        val machine = restored(value = 0x100L, entry = "100")
        assertEquals(0x10L, machine.delete().ui.value)
    }

    @Test
    fun `a pending operation survives, and the operand typed after it completes it`() {
        val machine = restored(
            value = 5L,
            baseName = NumberBase.DEC.name,
            accumulator = 5L,
            pendingName = BinaryOp.ADD.name,
            awaitingOperand = true,
        )
        assertEquals(8L, machine.digit('3').evaluate().ui.value)
        // And an operator arriving first still replaces the pending one rather than
        // applying it: the saved flag is what carries that across process death.
        assertEquals(15L, machine.operator(BinaryOp.MULTIPLY).digit('3').evaluate().ui.value)
    }

    @Test
    fun `a latched divide by zero survives rather than unlatching itself`() {
        val machine = restored(
            value = 10L,
            baseName = NumberBase.DEC.name,
            accumulator = 10L,
            pendingName = BinaryOp.DIVIDE.name,
            errorName = ProgError.DIVIDE_BY_ZERO.name,
        )
        assertEquals(ProgError.DIVIDE_BY_ZERO, machine.ui.error)
        assertEquals(2L, machine.digit('5').evaluate().ui.value)
    }

    /**
     * A buffer that the restored base or width cannot hold is dropped, not shown.
     *
     * Both halves are read from the same bundle, but a bundle written by an older build — or
     * one whose base was saved after the digits were — can pair them, and an entry the
     * keypad could not have produced is not something the user can go on typing.
     */
    @Test
    fun `an entry that no longer fits the base or the word is discarded`() {
        val rebased = restored(value = 0xFFL, baseName = NumberBase.DEC.name, entry = "FF")
        assertEquals("", rebased.ui.entry)
        assertEquals(0xFFL, rebased.ui.value)

        val narrowed = restored(value = 0x1FFL, sizeName = WordSize.BITS_8.name, entry = "1FF")
        assertEquals("", narrowed.ui.entry)
        // Truncated to the restored width, so the readout cannot show digits the register
        // could not hold.
        assertEquals(0xFFL, narrowed.ui.value)
    }

    /** A bundle from a build that named its constants differently must still open. */
    @Test
    fun `unknown enum names fall back to the defaults instead of throwing`() {
        val machine = restored(
            sizeName = "BITS_128",
            baseName = "BASE_36",
            pendingName = "NAND",
            errorName = "MELTDOWN",
        )
        assertEquals(WordSize.BITS_32, machine.ui.wordSize)
        assertEquals(NumberBase.HEX, machine.ui.base)
        assertNull(machine.pending)
        assertNull(machine.ui.error)
    }

    /** An empty bundle is the first run, and must give the same screen a first run gives. */
    @Test
    fun `an empty bundle restores the default state`() {
        val machine = restoredMachine(null, null, null, null, null, null, null, null, null)
        assertEquals(ProgrammerMachine(), machine)
    }
}
