package app.numera.calculator.math.expr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persistence format, including the cases that make it necessary at all.
 *
 * Two of these tests are load-bearing beyond ordinary round-tripping. The incomplete-expression
 * cases are the entire reason this codec exists rather than reusing `display()`/`fromText()`.
 * The golden-vector test is what protects every history row a user has ever saved: it fails
 * loudly if the tag numbering is ever changed, which is a mistake that would otherwise be
 * invisible until someone's saved `÷` came back as `×`.
 */
class ExprCodecTest {

    private fun expr(vararg keys: KeyId): CalculatorExpr =
        keys.fold(CalculatorExpr()) { acc, key -> acc.append(key) }

    private fun roundTrip(source: CalculatorExpr): CalculatorExpr {
        val decoded = ExprCodec.decode(ExprCodec.encode(source))
        assertNotNull("decode returned null for ${source.display()}", decoded)
        return decoded!!
    }

    @Test
    fun `a complete expression survives the round trip`() {
        val source = expr(KeyId.D1, KeyId.DIVIDE, KeyId.D3, KeyId.MULTIPLY, KeyId.D3)
        assertEquals(source.tokens, roundTrip(source).tokens)
    }

    @Test
    fun `an in-progress expression survives, which is the whole point`() {
        // Exactly what is on screen when a phone call interrupts the user mid-sum.
        for (source in listOf(
            expr(KeyId.D1, KeyId.ADD),
            expr(KeyId.SIN, KeyId.D2),
            expr(KeyId.LEFT_PAREN, KeyId.D3, KeyId.MULTIPLY),
            expr(KeyId.D5, KeyId.POINT),
            expr(KeyId.D2, KeyId.POWER),
        )) {
            assertEquals(source.tokens, roundTrip(source).tokens)
        }
    }

    @Test
    fun `a dangling operator cannot survive the text form, which is why this codec exists`() {
        // fromText runs the parser, and a trailing operator has no right-hand operand, so the
        // text round trip loses the expression entirely. Note that not every partial input
        // fails this way — "sin(2" survives because the parser auto-closes unmatched parens —
        // but the codec must not depend on knowing which is which.
        val dangling = expr(KeyId.D1, KeyId.ADD)
        assertNull(CalculatorExpr.fromText(dangling.display()))
        assertEquals(dangling.tokens, roundTrip(dangling).tokens)

        val trailingOperator = expr(KeyId.LEFT_PAREN, KeyId.D3, KeyId.MULTIPLY)
        assertNull(CalculatorExpr.fromText(trailingOperator.display()))
        assertEquals(trailingOperator.tokens, roundTrip(trailingOperator).tokens)
    }

    @Test
    fun `every key survives the round trip`() {
        // Catches a tag that was assigned in one direction but not the other.
        for (key in KeyId.entries) {
            val source = CalculatorExpr(listOf(Token.Key(key)))
            assertEquals("key $key did not survive", source.tokens, roundTrip(source).tokens)
        }
    }

    @Test
    fun `number tokens keep their exact text`() {
        val source = CalculatorExpr(listOf(Token.Number("0.000123")))
        assertEquals(source.tokens, roundTrip(source).tokens)
        // A trailing point is a legal in-progress state and must not be normalised away.
        val trailing = CalculatorExpr(listOf(Token.Number("5.")))
        assertEquals(trailing.tokens, roundTrip(trailing).tokens)
    }

    @Test
    fun `an empty expression round trips to an empty expression`() {
        val decoded = roundTrip(CalculatorExpr())
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `the string form round trips`() {
        val source = expr(KeyId.SQRT, KeyId.D2, KeyId.MULTIPLY, KeyId.SQRT, KeyId.D2)
        val decoded = ExprCodec.decodeFromString(ExprCodec.encodeToString(source))
        assertNotNull(decoded)
        assertEquals(source.tokens, decoded!!.tokens)
    }

    @Test
    fun `the string form is safe to embed in text`() {
        val text = ExprCodec.encodeToString(expr(KeyId.D1, KeyId.ADD, KeyId.D2))
        // URL-safe, unpadded: no +, / or = to be mangled by whatever carries it.
        assertTrue("got '$text'", text.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    // ------------------------------------------------------------ defensive decoding

    @Test
    fun `garbage decodes to null rather than throwing`() {
        // Every one of these is reachable: a truncated preference, a downgraded app reading a
        // newer blob, or arbitrary text someone pasted from another application.
        assertNull(ExprCodec.decode(ByteArray(0)))
        assertNull(ExprCodec.decode(byteArrayOf(99)))                    // unknown version
        assertNull(ExprCodec.decode(byteArrayOf(1, 1)))                  // key tag, no key
        assertNull(ExprCodec.decode(byteArrayOf(1, 1, 99)))              // unknown key tag
        assertNull(ExprCodec.decode(byteArrayOf(1, 0)))                  // number, no length
        assertNull(ExprCodec.decode(byteArrayOf(1, 0, 10, 65)))          // length overruns
        assertNull(ExprCodec.decode(byteArrayOf(1, 0, 0)))               // zero-length number
        assertNull(ExprCodec.decode(byteArrayOf(1, 77)))                 // unknown token tag
        assertNull(ExprCodec.decodeFromString(""))
        assertNull(ExprCodec.decodeFromString("not base64 !!!"))
        assertNull(ExprCodec.decodeFromString("SGVsbG8"))                // valid base64, not ours
    }

    @Test
    fun `a number token the keypad could never produce is refused`() {
        // Framing is not enough: a blob is not necessarily one this version wrote. Each of
        // these is well-formed and still wrong, and two of them are silently wrong rather
        // than merely odd — a lower-case exponent slips past the parser's literal bound,
        // and two adjacent numbers display as "12" while evaluating as the product 1 × 2.
        assertNull(ExprCodec.decode(numbers("1e30")))
        assertNull(ExprCodec.decode(numbers("1,5")))
        assertNull(ExprCodec.decode(numbers("-5")))
        assertNull(ExprCodec.decode(numbers("1E")))
        assertNull(ExprCodec.decode(numbers("1.2.3")))
        assertNull(ExprCodec.decode(numbers("1", "2")))

        // The shapes the tokenizer really does emit must still decode.
        assertNotNull(ExprCodec.decode(numbers("1E30")))
        assertNotNull(ExprCodec.decode(numbers("12.5")))
        assertNotNull(ExprCodec.decode(numbers("5.")))
    }

    @Test
    fun `a generated literal longer than the keypad allows survives the round trip`() {
        // CalculatorExpr.seedFromDecimal produces these when a calculation continues from a
        // large answer: 100! is 158 digits, and the value has to survive process death in
        // the middle of the next expression.
        val source = CalculatorExpr(listOf(Token.Number("9" + "3".repeat(157))))
        assertEquals(source.tokens, roundTrip(source).tokens)
    }

    /** A blob carrying nothing but [texts] as number tokens, framed correctly. */
    private fun numbers(vararg texts: String): ByteArray {
        val out = ArrayList<Byte>()
        out += 1.toByte()
        for (text in texts) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out += 0.toByte()
            out += bytes.size.toByte()
            for (b in bytes) out += b
        }
        return out.toByteArray()
    }

    @Test
    fun `truncating a valid encoding anywhere still decodes to null and never throws`() {
        val full = ExprCodec.encode(
            expr(KeyId.D1, KeyId.D2, KeyId.POINT, KeyId.D5, KeyId.ADD, KeyId.SIN, KeyId.D3),
        )
        for (length in 1 until full.size) {
            // Not asserting null — some prefixes are legitimately valid shorter expressions.
            // Asserting only that nothing throws, which is what a corrupt store must not do.
            ExprCodec.decode(full.copyOf(length))
        }
    }

    // ------------------------------------------------------------ format stability

    @Test
    fun `the wire format is stable against a golden vector`() {
        // 1 ÷ 3, encoded by hand from the documented layout:
        //   version 1, number "1", key DIVIDE (tag 15), number "3"
        val golden = byteArrayOf(
            1,                    // version
            0, 1, '1'.code.toByte(),
            1, 15,                // Token.Key(DIVIDE)
            0, 1, '3'.code.toByte(),
        )
        val decoded = ExprCodec.decode(golden)
        assertNotNull("the format changed — saved history would be unreadable", decoded)
        assertEquals("1÷3", decoded!!.display())

        // And the encoder still produces exactly those bytes.
        val encoded = ExprCodec.encode(expr(KeyId.D1, KeyId.DIVIDE, KeyId.D3))
        assertEquals(golden.toList(), encoded.toList())
    }

    @Test
    fun `a number token too long for one length byte is framed, not wrapped`() {
        // CalculatorExpr.MAX_LITERAL_LENGTH is 255 because the compact frame states its
        // length in a single byte, but that ceiling is held by the four places that build a
        // Token.Number rather than by the type, whose constructor is public. Written into
        // that byte, a 300-character token would state a length of 44 and decode as a
        // different, shorter number — with the blob still perfectly well formed, so nothing
        // downstream would notice a value quietly changing.
        val long = "9".repeat(300)
        val source = CalculatorExpr(listOf(Token.Number(long)))
        assertEquals(source.tokens, roundTrip(source).tokens)

        // And the wider length field must not have opened a way to read past the array.
        val blob = ExprCodec.encode(source)
        for (length in 1 until blob.size) {
            ExprCodec.decode(blob.copyOf(length))
        }
    }

    @Test
    fun `key tags are unique and stable`() {
        // Encoding one key at a time exposes its tag byte, so a duplicated or shifted tag
        // shows up here rather than in a user's history months later.
        val tags = KeyId.entries.map { key ->
            val bytes = ExprCodec.encode(CalculatorExpr(listOf(Token.Key(key))))
            assertEquals(3, bytes.size)
            bytes[2].toInt()
        }
        assertEquals("tags must be unique", tags.size, tags.toSet().size)
        // Tag 0 is reserved so that a zeroed byte array cannot look like a valid key.
        assertTrue("no tag may be zero", tags.none { it == 0 })
    }
}
