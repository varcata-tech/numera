package app.numera.calculator.feature.programmer

/**
 * The machine word the programmer mode is emulating.
 *
 * Everything here is computed in a Kotlin [Long], which is always 64-bit and always signed.
 * That is the wrong shape for every width except one, so each operation masks and
 * sign-extends explicitly rather than trusting the host type — the whole point of the mode
 * is to show what an 8-bit machine would do, not what the JVM does.
 */
enum class WordSize(val bits: Int) {
    BITS_8(8), BITS_16(16), BITS_32(32), BITS_64(64);

    /** All ones in this width. */
    val mask: Long get() = if (bits == 64) -1L else (1L shl bits) - 1L

    /** The sign bit's position. */
    val signBit: Long get() = 1L shl (bits - 1)
}

/** The four bases shown simultaneously in the readout. */
enum class NumberBase(val radix: Int, val digits: String) {
    HEX(16, "0123456789ABCDEF"),
    DEC(10, "0123456789"),
    OCT(8, "01234567"),
    BIN(2, "01"),
}

/**
 * Integer arithmetic that behaves like fixed-width hardware.
 *
 * The interesting cases are the ones where the JVM and the emulated machine disagree:
 * shifting right, where arithmetic and logical shifts differ for negative values; and
 * overflow, which a 64-bit Long simply absorbs and an 8-bit register does not.
 */
object BitwiseEngine {

    /** Truncates [value] to [size], leaving the raw bit pattern in the low bits. */
    fun truncate(value: Long, size: WordSize): Long = value and size.mask

    /**
     * Interprets the stored bit pattern as a number.
     *
     * Sign extension is done by hand: masking alone leaves 0xFF as 255 in a Long, and an
     * 8-bit signed register holding 0xFF means −1.
     */
    fun interpret(raw: Long, size: WordSize, signed: Boolean): Long {
        val masked = truncate(raw, size)
        // At 64 bits the Long already *is* the machine word, in both interpretations: the
        // bit pattern is the same and only the rendering differs, so there is nothing to
        // extend. Below 64, a set sign bit has to be pushed out to the top of the Long or
        // 0xFF reads as 255 where an 8-bit signed register means -1.
        if (size.bits == 64 || !signed) return masked
        return if (masked and size.signBit != 0L) masked or size.mask.inv() else masked
    }

    fun and(a: Long, b: Long, size: WordSize): Long = truncate(a and b, size)

    fun or(a: Long, b: Long, size: WordSize): Long = truncate(a or b, size)

    fun xor(a: Long, b: Long, size: WordSize): Long = truncate(a xor b, size)

    fun not(a: Long, size: WordSize): Long = truncate(a.inv(), size)

    fun nand(a: Long, b: Long, size: WordSize): Long = truncate((a and b).inv(), size)

    fun nor(a: Long, b: Long, size: WordSize): Long = truncate((a or b).inv(), size)

    /**
     * Shifts left, clearing the word for any count outside `0 until size.bits`.
     *
     * The count is a [Long] because it arrives as a whole machine word. Narrowing it with
     * `toInt()` first keeps only the low 32 bits, so a count of 2^32 becomes 0 and slips past
     * the guard; Kotlin's `shl` then masks the count to six bits and performs some arbitrary
     * small shift instead of shifting the value out — a silently wrong answer rather than a
     * flagged one. A negative count is treated the same way: as an unsigned word it is larger
     * than any width, and as a signed one it is meaningless.
     */
    fun shiftLeft(a: Long, by: Long, size: WordSize): Long =
        if (by < 0L || by >= size.bits) 0L else truncate(a shl by.toInt(), size)

    /**
     * Logical right shift: zeros enter from the left regardless of the sign bit.
     *
     * Must operate on the *masked* value. Using the Long's own `ushr` on a sign-extended
     * negative number pulls in the extension bits and gives an answer that is wrong by
     * a factor of 2^(64−width).
     */
    fun shiftRightLogical(a: Long, by: Long, size: WordSize): Long =
        if (by < 0L || by >= size.bits) 0L else truncate(truncate(a, size) ushr by.toInt(), size)

    /** Arithmetic right shift: the sign bit is replicated, so −8 shr 1 is −4, not a huge number. */
    fun shiftRightArithmetic(a: Long, by: Long, size: WordSize): Long {
        val signed = interpret(a, size, signed = true)
        if (by < 0L || by >= size.bits) return if (signed < 0) size.mask else 0L
        return truncate(signed shr by.toInt(), size)
    }

    /**
     * Rotates left by [by] positions.
     *
     * Unlike the shifts, an over-large count is meaningful here: a rotation is periodic in the
     * word width, so the count is reduced modulo it rather than treated as rotating the word
     * away.
     */
    fun rotateLeft(a: Long, by: Long, size: WordSize): Long {
        val n = ((((by % size.bits) + size.bits) % size.bits)).toInt()
        if (n == 0) return truncate(a, size)
        val v = truncate(a, size)
        return truncate((v shl n) or (v ushr (size.bits - n)), size)
    }

    fun rotateRight(a: Long, by: Long, size: WordSize): Long =
        rotateLeft(a, size.bits - (((by % size.bits) + size.bits) % size.bits), size)

    /** Reverses the byte order, as a network-to-host swap would. */
    fun byteSwap(a: Long, size: WordSize): Long {
        var value = truncate(a, size)
        var result = 0L
        repeat(size.bits / 8) {
            result = (result shl 8) or (value and 0xFFL)
            value = value ushr 8
        }
        return truncate(result, size)
    }

    fun twosComplement(a: Long, size: WordSize): Long = truncate(-truncate(a, size), size)

    fun onesComplement(a: Long, size: WordSize): Long = not(a, size)

    /** Adds, reporting whether the result left the representable range. */
    fun add(a: Long, b: Long, size: WordSize, signed: Boolean): Operation =
        operate(a, b, size, signed, { x, y -> x + y }) { x, y, sum ->
            if (signed) {
                // Two operands of the same sign producing the opposite sign is the
                // textbook signed-overflow test, and unlike Math.addExact it costs nothing.
                (x xor sum) and (y xor sum) < 0
            } else {
                java.lang.Long.compareUnsigned(sum, x) < 0
            }
        }

    fun subtract(a: Long, b: Long, size: WordSize, signed: Boolean): Operation =
        operate(a, b, size, signed, { x, y -> x - y }) { x, y, difference ->
            if (signed) {
                (x xor y) and (x xor difference) < 0
            } else {
                java.lang.Long.compareUnsigned(x, y) < 0
            }
        }

    fun multiply(a: Long, b: Long, size: WordSize, signed: Boolean): Operation =
        operate(a, b, size, signed, { x, y -> x * y }) { x, y, product ->
            when {
                y == 0L -> false
                // The re-read test cannot see this one: MIN_VALUE * -1 wraps back to
                // MIN_VALUE, and MIN_VALUE / -1 wraps back to MIN_VALUE too, so the quotient
                // agrees with x while the true product 2^63 does not fit at all. The operands
                // have to be named in this order — the mirrored pair is already caught,
                // because MIN_VALUE / MIN_VALUE is 1, which is not -1.
                signed -> product / y != x || (x == Long.MIN_VALUE && y == -1L)
                else -> java.lang.Long.divideUnsigned(product, y) != x
            }
        }

    /**
     * Integer division, truncating toward zero. Division by zero is refused, not wrapped.
     *
     * Two things the JVM's own `/` gets wrong for an emulated word:
     *
     * 1. At 64 bits [interpret] hands back the raw pattern, so an unsigned value above
     *    2^63−1 arrives as a negative Long and signed division answers a different question
     *    entirely — 0xFFFFFFFFFFFFFFFF ÷ 2 comes out as 0 rather than 0x7FFFFFFFFFFFFFFF.
     * 2. The one signed division that overflows, (most negative value) ÷ −1, silently returns
     *    the most negative value again at every width. Unflagged, that is indistinguishable
     *    from a correct answer.
     */
    fun divide(a: Long, b: Long, size: WordSize, signed: Boolean): Operation? {
        val divisor = interpret(b, size, signed)
        if (divisor == 0L) return null
        val dividend = interpret(a, size, signed)
        val quotient = if (isWideUnsigned(size, signed)) {
            java.lang.Long.divideUnsigned(dividend, divisor)
        } else {
            dividend / divisor
        }
        val overflow = signed && dividend == -size.signBit && divisor == -1L
        return Operation(truncate(quotient, size), overflow)
    }

    fun remainder(a: Long, b: Long, size: WordSize, signed: Boolean): Operation? {
        val divisor = interpret(b, size, signed)
        if (divisor == 0L) return null
        val dividend = interpret(a, size, signed)
        val rest = if (isWideUnsigned(size, signed)) {
            java.lang.Long.remainderUnsigned(dividend, divisor)
        } else {
            dividend % divisor
        }
        return Operation(truncate(rest, size), overflow = false)
    }

    /**
     * Whether [interpret] returned a raw pattern that Kotlin's own operators would misread.
     *
     * Only at 64 bits: below it, an unsigned word is masked into a non-negative Long and the
     * signed and unsigned operations agree.
     */
    private fun isWideUnsigned(size: WordSize, signed: Boolean): Boolean =
        size.bits == 64 && !signed

    /**
     * Runs [op] at the emulated width and decides whether the answer fitted.
     *
     * Two different tests are needed, because the two cases fail differently. Below 64 bits
     * the Long has room to hold the true answer, so overflow is simply "re-reading the
     * truncated bits does not give back what we computed" — which is what makes 8-bit
     * 0xFF + 1 report a carry rather than quietly becoming 256. At exactly 64 bits there is
     * no room: the JVM has already wrapped by the time we could look, the true answer is
     * gone, and the only way to see the carry is [wideOverflow]'s bit test on the operands.
     */
    private inline fun operate(
        a: Long,
        b: Long,
        size: WordSize,
        signed: Boolean,
        op: (Long, Long) -> Long,
        wideOverflow: (Long, Long, Long) -> Boolean,
    ): Operation {
        val x = interpret(a, size, signed)
        val y = interpret(b, size, signed)
        val wide = op(x, y)
        if (size.bits == 64) return Operation(wide, wideOverflow(x, y, wide))
        val truncated = truncate(wide, size)
        return Operation(truncated, interpret(truncated, size, signed) != wide)
    }

    /** The result of an arithmetic step, plus whether it left the word's range. */
    data class Operation(val value: Long, val overflow: Boolean)

    /** Renders the stored pattern in [base], respecting signedness in decimal only. */
    fun format(raw: Long, size: WordSize, signed: Boolean, base: NumberBase): String {
        val masked = truncate(raw, size)
        return when (base) {
            // Only decimal shows a sign. Hex, octal and binary show the bit pattern, which
            // is the entire reason a programmer is looking at them.
            //
            // The unsigned branch cannot go through interpret + toString: at 64 bits
            // interpret returns the raw pattern and Long.toString renders it signed, so the
            // whole upper half of an unsigned word would print as a negative number while the
            // hex row alongside it showed FFFFFFFFFFFFFFFF. Below 64 bits the masked value is
            // already non-negative and the two agree.
            NumberBase.DEC -> if (signed) {
                interpret(masked, size, signed = true).toString()
            } else {
                java.lang.Long.toUnsignedString(masked)
            }
            else -> java.lang.Long.toUnsignedString(masked, base.radix).uppercase()
        }
    }

    /** Binary with the nibble grouping that makes a bit pattern readable. */
    fun formatBinaryGrouped(raw: Long, size: WordSize): String =
        (size.bits - 1 downTo 0)
            .joinToString("") { bit -> if (truncate(raw, size) and (1L shl bit) != 0L) "1" else "0" }
            .chunked(4)
            .joinToString(" ")

    /** Parses [text] in [base], or null if it does not fit the word or contains bad digits. */
    fun parse(text: String, base: NumberBase, size: WordSize): Long? {
        val cleaned = text.replace(" ", "").uppercase()
        if (cleaned.isEmpty()) return 0L
        if (cleaned.any { it !in base.digits }) return null
        return try {
            val parsed = java.lang.Long.parseUnsignedLong(cleaned, base.radix)
            if (size.bits < 64 && parsed > size.mask) null else truncate(parsed, size)
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Whether [digit] can legally be typed in [base]; drives disabling keys rather than hiding them. */
    fun isDigitAllowed(digit: Char, base: NumberBase): Boolean = digit.uppercaseChar() in base.digits
}
