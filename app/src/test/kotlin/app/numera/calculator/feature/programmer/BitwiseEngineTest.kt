package app.numera.calculator.feature.programmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixed-width integer behaviour, where the JVM's own 64-bit signed Long is the wrong answer.
 *
 * Every case here is one where trusting Kotlin's arithmetic would produce something
 * plausible and wrong: an 8-bit add that quietly reaches 256, a right shift that pulls in
 * sign-extension bits, a negative value that prints as a twenty-digit number.
 */
class BitwiseEngineTest {

    @Test
    fun `an eight bit unsigned add wraps and reports the overflow`() {
        val result = BitwiseEngine.add(0xFF, 1, WordSize.BITS_8, signed = false)
        assertEquals(0L, result.value)
        assertTrue("0xFF + 1 must report a carry", result.overflow)
    }

    @Test
    fun `an eight bit signed minus one is the bit pattern 0xFF`() {
        val raw = BitwiseEngine.truncate(-1, WordSize.BITS_8)
        assertEquals(0xFFL, raw)
        assertEquals(-1L, BitwiseEngine.interpret(raw, WordSize.BITS_8, signed = true))
        assertEquals(255L, BitwiseEngine.interpret(raw, WordSize.BITS_8, signed = false))
        assertEquals("11111111", BitwiseEngine.format(raw, WordSize.BITS_8, false, NumberBase.BIN))
        assertEquals("-1", BitwiseEngine.format(raw, WordSize.BITS_8, true, NumberBase.DEC))
        assertEquals("255", BitwiseEngine.format(raw, WordSize.BITS_8, false, NumberBase.DEC))
    }

    @Test
    fun `signed overflow is detected at the signed boundary not the unsigned one`() {
        // 127 + 1 fits in eight unsigned bits but not in eight signed ones.
        val signed = BitwiseEngine.add(127, 1, WordSize.BITS_8, signed = true)
        assertEquals(-128L, BitwiseEngine.interpret(signed.value, WordSize.BITS_8, true))
        assertTrue(signed.overflow)

        val unsigned = BitwiseEngine.add(127, 1, WordSize.BITS_8, signed = false)
        assertEquals(128L, unsigned.value)
        assertFalse(unsigned.overflow)
    }

    @Test
    fun `logical and arithmetic right shifts differ on negative values`() {
        val minusEight = BitwiseEngine.truncate(-8, WordSize.BITS_8)   // 0xF8
        // Arithmetic keeps the sign: -8 >> 1 is -4.
        val arithmetic = BitwiseEngine.shiftRightArithmetic(minusEight, 1, WordSize.BITS_8)
        assertEquals(-4L, BitwiseEngine.interpret(arithmetic, WordSize.BITS_8, signed = true))
        // Logical does not: 0xF8 ushr 1 is 0x7C, which is 124.
        val logical = BitwiseEngine.shiftRightLogical(minusEight, 1, WordSize.BITS_8)
        assertEquals(0x7CL, logical)
    }

    @Test
    fun `a plain right shift of a positive value is the same either way`() {
        assertEquals(2L, BitwiseEngine.shiftRightLogical(5, 1, WordSize.BITS_8))
        assertEquals(2L, BitwiseEngine.shiftRightArithmetic(5, 1, WordSize.BITS_8))
    }

    @Test
    fun `shifting by the whole word clears it`() {
        assertEquals(0L, BitwiseEngine.shiftLeft(0xFF, 8, WordSize.BITS_8))
        assertEquals(0L, BitwiseEngine.shiftRightLogical(0xFF, 8, WordSize.BITS_8))
    }

    @Test
    fun `not of zero fills the word whatever its width`() {
        assertEquals(0xFFL, BitwiseEngine.not(0, WordSize.BITS_8))
        assertEquals(0xFFFFL, BitwiseEngine.not(0, WordSize.BITS_16))
        assertEquals(0xFFFFFFFFL, BitwiseEngine.not(0, WordSize.BITS_32))
        assertEquals(-1L, BitwiseEngine.not(0, WordSize.BITS_64))
    }

    @Test
    fun `rotation wraps bits around rather than dropping them`() {
        // 0b1000_0001 rotated left by one is 0b0000_0011.
        assertEquals(0b00000011L, BitwiseEngine.rotateLeft(0b10000001, 1, WordSize.BITS_8))
        assertEquals(0b11000000L, BitwiseEngine.rotateRight(0b10000001, 1, WordSize.BITS_8))
        // A full turn is the identity.
        assertEquals(0xABL, BitwiseEngine.rotateLeft(0xAB, 8, WordSize.BITS_8))
    }

    @Test
    fun `bitwise operators stay inside the word`() {
        assertEquals(0x0FL, BitwiseEngine.and(0xFF, 0x0F, WordSize.BITS_8))
        assertEquals(0xFFL, BitwiseEngine.or(0xF0, 0x0F, WordSize.BITS_8))
        assertEquals(0xFFL, BitwiseEngine.xor(0xF0, 0x0F, WordSize.BITS_8))
        assertEquals(0x0FL, BitwiseEngine.not(0xF0, WordSize.BITS_8))
    }

    /**
     * The sign key's arithmetic, including the one value that has no negative.
     *
     * −128 in eight bits negates to itself. That is what the register does, and reporting it
     * as an error instead would be describing the JVM rather than the emulated machine.
     */
    @Test
    fun `two's complement negates within the word`() {
        assertEquals(0xFFL, BitwiseEngine.twosComplement(1, WordSize.BITS_8))
        assertEquals(1L, BitwiseEngine.twosComplement(0xFF, WordSize.BITS_8))
        assertEquals(0L, BitwiseEngine.twosComplement(0, WordSize.BITS_8))
        assertEquals(0x80L, BitwiseEngine.twosComplement(0x80, WordSize.BITS_8))
    }

    @Test
    fun `division by zero is refused rather than wrapped`() {
        assertNull(BitwiseEngine.divide(10, 0, WordSize.BITS_8, signed = true))
        assertNull(BitwiseEngine.remainder(10, 0, WordSize.BITS_8, signed = true))
        assertEquals(3L, BitwiseEngine.divide(10, 3, WordSize.BITS_8, true)!!.value)
        assertEquals(1L, BitwiseEngine.remainder(10, 3, WordSize.BITS_8, true)!!.value)
    }

    @Test
    fun `every base round trips through parse and format`() {
        val value = 0xDEADL
        for (base in NumberBase.entries) {
            val text = BitwiseEngine.format(value, WordSize.BITS_16, signed = false, base = base)
            assertEquals(
                "round trip failed in $base via '$text'",
                value,
                BitwiseEngine.parse(text, base, WordSize.BITS_16),
            )
        }
    }

    @Test
    fun `parsing rejects digits the base does not have and values the word cannot hold`() {
        assertNull(BitwiseEngine.parse("2", NumberBase.BIN, WordSize.BITS_8))
        assertNull(BitwiseEngine.parse("9", NumberBase.OCT, WordSize.BITS_8))
        assertNull(BitwiseEngine.parse("G", NumberBase.HEX, WordSize.BITS_8))
        // 0x100 needs nine bits.
        assertNull(BitwiseEngine.parse("100", NumberBase.HEX, WordSize.BITS_8))
        assertEquals(0xFFL, BitwiseEngine.parse("FF", NumberBase.HEX, WordSize.BITS_8))
    }

    /**
     * The range check has to be an *unsigned* comparison.
     *
     * parseUnsignedLong hands back a raw bit pattern, so everything from 2^63 up arrives as a
     * negative Long. A signed `parsed > mask` is then false against a small positive mask,
     * the guard is skipped, and the value is truncated into the word instead of refused —
     * 0xFFFFFFFFFFFFFFFF would be accepted as a 32-bit 0xFFFFFFFF, which is a different
     * number presented as the one that was typed.
     */
    @Test
    fun `parsing refuses a value at or above two to the sixty three`() {
        assertNull(BitwiseEngine.parse("FFFFFFFFFFFFFFFF", NumberBase.HEX, WordSize.BITS_32))
        assertNull(BitwiseEngine.parse("8000000000000000", NumberBase.HEX, WordSize.BITS_32))
        assertNull(BitwiseEngine.parse("18446744073709551615", NumberBase.DEC, WordSize.BITS_8))
        // The whole unsigned range still fits a 64-bit word, and must not be caught by it.
        assertEquals(-1L, BitwiseEngine.parse("FFFFFFFFFFFFFFFF", NumberBase.HEX, WordSize.BITS_64))
        assertEquals(
            Long.MIN_VALUE,
            BitwiseEngine.parse("9223372036854775808", NumberBase.DEC, WordSize.BITS_64),
        )
    }

    /**
     * Backspace once the typed buffer has been committed.
     *
     * The register is what is left, and shortening it is the only reading of delete that
     * does not destroy a value the user was correcting. The signed decimal case is the one
     * worth pinning: the sign is not a digit, so it survives the keystroke that removes one.
     */
    @Test
    fun `dropping a digit shortens the value as it is written`() {
        assertEquals(0x10L, BitwiseEngine.dropLastDigit(0x100, WordSize.BITS_32, true, NumberBase.HEX))
        assertEquals(12L, BitwiseEngine.dropLastDigit(123, WordSize.BITS_32, true, NumberBase.DEC))
        assertEquals(0b101L, BitwiseEngine.dropLastDigit(0b1011, WordSize.BITS_8, false, NumberBase.BIN))
        // Octal 123 is 83, and dropping its last digit leaves octal 12, which is 10.
        assertEquals(10L, BitwiseEngine.dropLastDigit(83, WordSize.BITS_16, false, NumberBase.OCT))
        // -123 in an 8-bit signed word backspaces to -12, not to a huge unsigned number.
        val negative = BitwiseEngine.truncate(-123, WordSize.BITS_8)
        val shortened = BitwiseEngine.dropLastDigit(negative, WordSize.BITS_8, true, NumberBase.DEC)
        assertEquals(-12L, BitwiseEngine.interpret(shortened, WordSize.BITS_8, signed = true))
        // The last digit of a single-digit value leaves zero rather than wrapping.
        assertEquals(0L, BitwiseEngine.dropLastDigit(7, WordSize.BITS_8, false, NumberBase.DEC))
    }

    /**
     * The magnitude of the most negative 64-bit value cannot be held in a Long at all.
     *
     * Negating it wraps it back to itself, so a signed division of the negated value would
     * take the digits from the wrong number entirely. Read unsigned, the same wrapped pattern
     * is exactly 2^63, which is what the decimal row on screen says.
     */
    @Test
    fun `dropping a digit from the most negative sixty four bit value`() {
        val shortened =
            BitwiseEngine.dropLastDigit(Long.MIN_VALUE, WordSize.BITS_64, true, NumberBase.DEC)
        assertEquals(-922337203685477580L, shortened)
    }

    @Test
    fun `key availability follows the active base`() {
        assertTrue(BitwiseEngine.isDigitAllowed('F', NumberBase.HEX))
        assertFalse(BitwiseEngine.isDigitAllowed('F', NumberBase.DEC))
        assertFalse(BitwiseEngine.isDigitAllowed('8', NumberBase.OCT))
        assertFalse(BitwiseEngine.isDigitAllowed('2', NumberBase.BIN))
        assertTrue(BitwiseEngine.isDigitAllowed('1', NumberBase.BIN))
    }

    @Test
    fun `binary is grouped into nibbles for readability`() {
        assertEquals("1010 0101", BitwiseEngine.formatBinaryGrouped(0xA5, WordSize.BITS_8))
        assertEquals(
            "0001 0010 0011 0100",
            BitwiseEngine.formatBinaryGrouped(0x1234, WordSize.BITS_16),
        )
    }

    @Test
    fun `sixty four bit words behave like the host Long`() {
        val result = BitwiseEngine.add(Long.MAX_VALUE, 1, WordSize.BITS_64, signed = true)
        assertEquals(Long.MIN_VALUE, result.value)
        assertTrue(result.overflow)
    }

    /**
     * The upper half of a 64-bit unsigned word is the half a signed Long cannot express, and
     * it is exactly the half a programmer reaches for. Rendering it with `Long.toString` puts
     * a minus sign next to a hex row reading FFFFFFFFFFFFFFFF.
     */
    @Test
    fun `a sixty four bit unsigned word prints its whole decimal range`() {
        val allOnes = BitwiseEngine.not(0, WordSize.BITS_64)
        assertEquals(
            "18446744073709551615",
            BitwiseEngine.format(allOnes, WordSize.BITS_64, signed = false, base = NumberBase.DEC),
        )
        assertEquals(
            "-1",
            BitwiseEngine.format(allOnes, WordSize.BITS_64, signed = true, base = NumberBase.DEC),
        )
        // The sign bit alone: 2^63 unsigned, the most negative value signed.
        assertEquals(
            "9223372036854775808",
            BitwiseEngine.format(
                Long.MIN_VALUE, WordSize.BITS_64, signed = false, base = NumberBase.DEC,
            ),
        )
        // Narrower widths were never affected and must stay as they are.
        assertEquals(
            "255",
            BitwiseEngine.format(0xFF, WordSize.BITS_8, signed = false, base = NumberBase.DEC),
        )
    }

    /**
     * At 64 bits `interpret` hands back the raw pattern, so an unsigned value above 2^63−1
     * reaches the arithmetic as a negative Long. Signed division of it answers a completely
     * different question rather than being merely imprecise.
     */
    @Test
    fun `sixty four bit unsigned division divides the unsigned value`() {
        val allOnes = -1L
        val half = BitwiseEngine.divide(allOnes, 2, WordSize.BITS_64, signed = false)!!
        assertEquals(Long.MAX_VALUE, half.value)
        // Anything divided by a divisor larger than it is zero, not the dividend negated.
        assertEquals(0L, BitwiseEngine.divide(10, allOnes, WordSize.BITS_64, signed = false)!!.value)
        assertEquals(
            10L,
            BitwiseEngine.remainder(10, allOnes, WordSize.BITS_64, signed = false)!!.value,
        )
        // The signed reading of the same pattern is still the signed answer.
        assertEquals(0L, BitwiseEngine.divide(allOnes, 2, WordSize.BITS_64, signed = true)!!.value)
    }

    /**
     * The one signed product the "divide it back" test cannot see: MIN_VALUE × −1 wraps to
     * MIN_VALUE, and MIN_VALUE ÷ −1 wraps back to MIN_VALUE, so the check agrees with itself
     * while the true product 2^63 does not fit at all.
     */
    @Test
    fun `the most negative value times minus one is flagged as overflow`() {
        assertTrue(
            "MIN_VALUE * -1 does not fit a signed 64-bit word",
            BitwiseEngine.multiply(Long.MIN_VALUE, -1L, WordSize.BITS_64, signed = true).overflow,
        )
        // The mirrored operands were already caught and must stay caught.
        assertTrue(
            BitwiseEngine.multiply(-1L, Long.MIN_VALUE, WordSize.BITS_64, signed = true).overflow,
        )
        assertFalse(BitwiseEngine.multiply(3, 5, WordSize.BITS_64, signed = true).overflow)
    }

    /**
     * (most negative value) ÷ −1 is the only signed division that overflows, and the truncated
     * answer is the most negative value again — indistinguishable from a correct result unless
     * the flag is raised.
     */
    @Test
    fun `the most negative value divided by minus one is flagged as overflow`() {
        val eightBit = BitwiseEngine.divide(0x80, 0xFF, WordSize.BITS_8, signed = true)!!
        assertEquals(0x80L, eightBit.value)
        assertTrue("-128 / -1 is 128, which an 8-bit signed word cannot hold", eightBit.overflow)

        val wide = BitwiseEngine.divide(Long.MIN_VALUE, -1L, WordSize.BITS_64, signed = true)!!
        assertTrue(wide.overflow)

        // Ordinary divisions are still not flagged, and the same pattern read unsigned is a
        // perfectly representable 128 ÷ 255.
        assertFalse(BitwiseEngine.divide(10, 3, WordSize.BITS_8, signed = true)!!.overflow)
        assertFalse(BitwiseEngine.divide(0x80, 0xFF, WordSize.BITS_8, signed = false)!!.overflow)
    }

    /**
     * A shift count is a whole machine word. Narrowing it to an Int first lets 2^32 arrive as
     * zero, which slips past a "count is at least the width" guard and leaves the value
     * untouched — a no-op presented as a shift.
     */
    @Test
    fun `a shift count wider than an Int shifts the word out instead of wrapping`() {
        assertEquals(0L, BitwiseEngine.shiftLeft(1L, 1L shl 32, WordSize.BITS_64))
        assertEquals(0L, BitwiseEngine.shiftRightLogical(0xFFL, 1L shl 32, WordSize.BITS_64))
        // 0xFFFFFFC0 is typeable in a 32-bit word; as an Int it would be -64, and
        // -64 masked to six bits is zero.
        assertEquals(0L, BitwiseEngine.shiftLeft(5L, 0xFFFFFFC0L, WordSize.BITS_32))
        assertEquals(0L, BitwiseEngine.shiftRightLogical(5L, 0xFFFFFFC0L, WordSize.BITS_32))
        // An arithmetic shift-out still fills with the sign.
        assertEquals(
            0xFFL,
            BitwiseEngine.shiftRightArithmetic(0x80L, 0xFFFFFFC0L, WordSize.BITS_8),
        )
        // Rotation is periodic in the width, so an over-large count is reduced, not refused:
        // 2^32 is a whole number of turns of a 64-bit word.
        assertEquals(0xABL, BitwiseEngine.rotateLeft(0xAB, 1L shl 32, WordSize.BITS_64))
    }
}
