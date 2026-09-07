package app.numera.calculator.math.expr

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * A stable binary form for [CalculatorExpr].
 *
 * This exists because [CalculatorExpr.fromText] is not a persistence format. `fromText` runs
 * the parser and returns `null` on any exception, so an expression the user is *part-way
 * through typing* — `1+`, `sin(2`, `(3×` — cannot survive a round trip through
 * [CalculatorExpr.display]. Persisting the token stream directly sidesteps the parser
 * entirely, which is what lets a half-typed expression survive process death and what lets a
 * history row carry the exact calculation rather than a rendering of its answer.
 *
 * Three properties matter and each is a deliberate choice:
 *
 * 1. **Every [KeyId] has an explicit integer tag**, assigned in [tagOf]'s `when`. Using
 *    `ordinal` would mean that inserting a key into the middle of the enum silently
 *    reinterprets every history row ever written — `÷` quietly becoming `×`. A `when` that
 *    fails to compile when a constant is added is a much better failure than that.
 * 2. **Decoding never throws.** Input can be a truncated preference, a blob written by a
 *    newer version, or plain corruption. Every failure returns `null` so the caller can fall
 *    back to an empty expression rather than crashing at startup, which is the worst possible
 *    time to crash.
 * 3. **The format is versioned.** Byte zero is [VERSION]; an unrecognised version decodes to
 *    `null` instead of being misread as data.
 */
object ExprCodec {

    /** Bumped only when the layout changes; unknown versions decode to `null`. */
    private const val VERSION: Byte = 1

    private const val TAG_NUMBER: Byte = 0
    private const val TAG_KEY: Byte = 1

    /**
     * A number token whose length needs more than the single byte [TAG_NUMBER] carries.
     *
     * [CalculatorExpr.MAX_LITERAL_LENGTH] is 255 *because* of that byte, but the ceiling is
     * enforced by the four places that build a [Token.Number] and not by the type, whose
     * constructor is public. A 256-character token written under [TAG_NUMBER] would take a
     * length byte of zero and decode as a different, shorter number — silently, since the
     * blob stays well-formed. Spending a second tag on the case makes the framing correct
     * whatever a producer hands over, rather than correct by remote agreement.
     *
     * Appended rather than replacing [TAG_NUMBER], so every blob written before it still
     * decodes: [VERSION] is unchanged and short tokens are still framed exactly as they were.
     */
    private const val TAG_NUMBER_LONG: Byte = 2

    /** The largest length a [TAG_NUMBER] frame can state, and so the ceiling on that form. */
    private const val MAX_SHORT_LITERAL: Int = 0xFF

    /**
     * Refuses absurd token counts before allocating for them.
     *
     * A corrupt length prefix would otherwise ask for a multi-gigabyte array. No real
     * expression approaches this. Shared with the tokenizer so that a blob and a paste can
     * never disagree about what is too large to parse.
     */
    private const val MAX_TOKENS = CalculatorExpr.MAX_TOKENS

    /** Encodes [expr] to bytes. Never fails: any expression is representable. */
    fun encode(expr: CalculatorExpr): ByteArray {
        // A stream rather than an ArrayList<Byte>: persist() runs this on the main thread on
        // every keystroke, and the list form boxed each byte and then copied the whole thing
        // again in toByteArray().
        val out = ByteArrayOutputStream(expr.tokens.size * 2 + 1)
        out.write(VERSION.toInt())
        for (token in expr.tokens) {
            when (token) {
                is Token.Number -> {
                    // Every character CalculatorExpr.isNumberLiteral admits is ASCII, so the
                    // byte count is the character count; the long form is reached only by a
                    // token no current producer can build.
                    val bytes = token.text.toByteArray(Charsets.UTF_8)
                    if (bytes.size <= MAX_SHORT_LITERAL) {
                        out.write(TAG_NUMBER.toInt())
                        out.write(bytes.size)
                    } else {
                        out.write(TAG_NUMBER_LONG.toInt())
                        writeInt(out, bytes.size)
                    }
                    out.write(bytes, 0, bytes.size)
                }
                is Token.Key -> {
                    out.write(TAG_KEY.toInt())
                    out.write(tagOf(token.key).toInt())
                }
            }
        }
        return out.toByteArray()
    }

    /** Big-endian, so a length reads the same way on every device that opens the blob. */
    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    /** Decodes [bytes], or returns `null` if they are not a well-formed expression. */
    fun decode(bytes: ByteArray): CalculatorExpr? {
        if (bytes.isEmpty() || bytes[0] != VERSION) return null
        val tokens = ArrayList<Token>()
        var i = 1
        while (i < bytes.size) {
            if (tokens.size >= MAX_TOKENS) return null
            when (bytes[i]) {
                TAG_NUMBER, TAG_NUMBER_LONG -> {
                    val wide = bytes[i] == TAG_NUMBER_LONG
                    val header = if (wide) 5 else 2
                    if (i + header > bytes.size) return null
                    // Read unsigned: a 200-character token would otherwise arrive negative.
                    val length =
                        if (wide) readInt(bytes, i + 1) else bytes[i + 1].toInt() and 0xFF
                    val start = i + header
                    // A crafted four-byte length can be negative or vast, so it is compared
                    // against the room that is left rather than added to the offset — the
                    // sum itself would overflow and pass a bounds check written that way.
                    if (length < 0 || length > bytes.size - start) return null
                    val text = String(bytes, start, length, Charsets.UTF_8)
                    // Content, not just framing. A blob is not necessarily one this version
                    // wrote: a truncated preference, a crafted clipboard payload or a
                    // corrupt history row can carry a number token the keypad could never
                    // produce, and two of those shapes are silently wrong rather than
                    // merely odd. A lower-case `1e300000000` bypasses the parser's exponent
                    // bound, and two adjacent numbers display as "12" while evaluating as
                    // the implicit product 1 × 2.
                    if (!CalculatorExpr.isNumberLiteral(text)) return null
                    if (tokens.lastOrNull() is Token.Number) return null
                    tokens += Token.Number(text)
                    i = start + length
                }
                TAG_KEY -> {
                    if (i + 1 >= bytes.size) return null
                    val key = keyOf(bytes[i + 1]) ?: return null
                    tokens += Token.Key(key)
                    i += 2
                }
                else -> return null
            }
        }
        return CalculatorExpr(tokens)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /**
     * Encodes to a URL-safe, unpadded Base64 string.
     *
     * The string form is what goes into `SavedStateHandle` and onto the clipboard, both of
     * which carry text far more comfortably than they carry byte arrays.
     */
    fun encodeToString(expr: CalculatorExpr): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(encode(expr))

    /** Decodes [text] produced by [encodeToString], or `null` if it is not one. */
    fun decodeFromString(text: String): CalculatorExpr? {
        if (text.isEmpty()) return null
        val bytes = try {
            Base64.getUrlDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            // Arbitrary clipboard text lands here constantly; it is not exceptional.
            return null
        }
        return decode(bytes)
    }

    /**
     * The persisted tag for [key].
     *
     * Written as an exhaustive `when` with literal numbers rather than `ordinal` on purpose:
     * adding a constant to [KeyId] makes this stop compiling, which forces a deliberate
     * choice of a new tag instead of silently renumbering every tag after the insertion
     * point and corrupting saved history.
     *
     * **Never reuse or change a number here.** Only append.
     */
    private fun tagOf(key: KeyId): Byte = when (key) {
        KeyId.D0 -> 1
        KeyId.D1 -> 2
        KeyId.D2 -> 3
        KeyId.D3 -> 4
        KeyId.D4 -> 5
        KeyId.D5 -> 6
        KeyId.D6 -> 7
        KeyId.D7 -> 8
        KeyId.D8 -> 9
        KeyId.D9 -> 10
        KeyId.POINT -> 11
        KeyId.ADD -> 12
        KeyId.SUBTRACT -> 13
        KeyId.MULTIPLY -> 14
        KeyId.DIVIDE -> 15
        KeyId.POWER -> 16
        KeyId.LEFT_PAREN -> 17
        KeyId.RIGHT_PAREN -> 18
        KeyId.FACTORIAL -> 19
        KeyId.PERCENT -> 20
        KeyId.SQUARE -> 21
        KeyId.SQRT -> 22
        KeyId.SIN -> 23
        KeyId.COS -> 24
        KeyId.TAN -> 25
        KeyId.ASIN -> 26
        KeyId.ACOS -> 27
        KeyId.ATAN -> 28
        KeyId.LN -> 29
        KeyId.LOG -> 30
        KeyId.EXP10 -> 31
        KeyId.EXPE -> 32
        KeyId.PI -> 33
        KeyId.E -> 34
        KeyId.VARIABLE_X -> 35
    }

    /** Inverse of [tagOf]; `null` for a tag this version does not know. */
    private fun keyOf(tag: Byte): KeyId? = when (tag.toInt()) {
        1 -> KeyId.D0
        2 -> KeyId.D1
        3 -> KeyId.D2
        4 -> KeyId.D3
        5 -> KeyId.D4
        6 -> KeyId.D5
        7 -> KeyId.D6
        8 -> KeyId.D7
        9 -> KeyId.D8
        10 -> KeyId.D9
        11 -> KeyId.POINT
        12 -> KeyId.ADD
        13 -> KeyId.SUBTRACT
        14 -> KeyId.MULTIPLY
        15 -> KeyId.DIVIDE
        16 -> KeyId.POWER
        17 -> KeyId.LEFT_PAREN
        18 -> KeyId.RIGHT_PAREN
        19 -> KeyId.FACTORIAL
        20 -> KeyId.PERCENT
        21 -> KeyId.SQUARE
        22 -> KeyId.SQRT
        23 -> KeyId.SIN
        24 -> KeyId.COS
        25 -> KeyId.TAN
        26 -> KeyId.ASIN
        27 -> KeyId.ACOS
        28 -> KeyId.ATAN
        29 -> KeyId.LN
        30 -> KeyId.LOG
        31 -> KeyId.EXP10
        32 -> KeyId.EXPE
        33 -> KeyId.PI
        34 -> KeyId.E
        35 -> KeyId.VARIABLE_X
        else -> null
    }
}
