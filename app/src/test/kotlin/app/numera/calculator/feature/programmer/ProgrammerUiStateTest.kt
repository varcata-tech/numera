package app.numera.calculator.feature.programmer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the four base rows show, including while a value is still being typed.
 *
 * The binary row is the one with a rule of its own: it is read by counting bit positions, so
 * it has to stay nibble-grouped and full-width at every keystroke. A row that is grouped
 * only some of the time is worse than one that is never grouped, because the bit under the
 * user's finger moves as they type.
 */
class ProgrammerUiStateTest {

    @Test
    fun `the binary row stays nibble grouped while digits are being typed`() {
        val state = ProgrammerUiState(
            value = 0b1101L,
            wordSize = WordSize.BITS_8,
            base = NumberBase.BIN,
            entry = "1101",
        )
        assertEquals("0000 1101", state.rendered(NumberBase.BIN))
    }

    @Test
    fun `the binary row does not change format when the entry is committed`() {
        val typing = ProgrammerUiState(
            value = 0xA5L,
            wordSize = WordSize.BITS_8,
            base = NumberBase.BIN,
            entry = "10100101",
        )
        val committed = typing.copy(entry = "")
        assertEquals(committed.rendered(NumberBase.BIN), typing.rendered(NumberBase.BIN))
        assertEquals("1010 0101", typing.rendered(NumberBase.BIN))
    }

    /**
     * The other three rows keep echoing the raw entry, and must: a leading zero typed in hex
     * is a legitimate thing to see on screen while the word is being entered.
     */
    @Test
    fun `the active row in another base still echoes what was typed`() {
        val state = ProgrammerUiState(
            value = 0xFFL,
            wordSize = WordSize.BITS_16,
            base = NumberBase.HEX,
            entry = "00FF",
        )
        assertEquals("00FF", state.rendered(NumberBase.HEX))
        // An inactive row shows the value, never another base's digits.
        assertEquals("255", state.rendered(NumberBase.DEC))
    }
}
