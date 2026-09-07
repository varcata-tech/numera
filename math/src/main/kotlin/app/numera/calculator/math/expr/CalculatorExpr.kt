package app.numera.calculator.math.expr

/**
 * Every keypad key that can contribute a token to an expression.
 *
 * Digits are keys rather than characters because the keypad, not a text field, is the only
 * input device: there is no cursor, no selection and no arbitrary text, so an expression is
 * always something that was *pressed*. Modelling it that way is what makes one backspace
 * remove one whole `sin(` instead of leaving a stranded `sin`.
 */
enum class KeyId {
    D0, D1, D2, D3, D4, D5, D6, D7, D8, D9, POINT,
    ADD, SUBTRACT, MULTIPLY, DIVIDE, POWER,
    LEFT_PAREN, RIGHT_PAREN,
    FACTORIAL, PERCENT, SQUARE,
    SQRT, SIN, COS, TAN, ASIN, ACOS, ATAN, LN, LOG, EXP10, EXPE,
    PI, E, VARIABLE_X
}

/** One element of an expression: a number being accumulated, or a single pressed key. */
sealed interface Token {

    /**
     * A run of digits with at most one point and an optional `E` exponent.
     *
     * Digits merge into one token instead of standing alone so that `12` is a single value
     * rather than an implicit `1 × 2`, and so that backspace peels one digit at a time.
     */
    data class Number(val text: String) : Token

    /** Any non-digit key: an operator, a paren, a function or a constant. */
    data class Key(val key: KeyId) : Token
}

/** The function keys, each of which carries its own opening paren. */
internal val FUNCTION_KEYS: Set<KeyId> = setOf(
    KeyId.SIN, KeyId.COS, KeyId.TAN,
    KeyId.ASIN, KeyId.ACOS, KeyId.ATAN,
    KeyId.LN, KeyId.LOG, KeyId.EXP10, KeyId.EXPE,
)

/** Keys after which a closing paren, rather than an opening one, is what the user means. */
private val VALUE_ENDING_KEYS: Set<KeyId> = setOf(
    KeyId.RIGHT_PAREN, KeyId.FACTORIAL, KeyId.PERCENT, KeyId.SQUARE,
    KeyId.PI, KeyId.E, KeyId.VARIABLE_X,
)

/**
 * The keys that join two values, each of which needs a value on its left.
 *
 * `−` is here as the *binary* minus. It is also the only one of them that can open a value
 * rather than join two, which is the one exception [CalculatorExpr.appendOperator] makes.
 */
private val BINARY_KEYS: Set<KeyId> = setOf(
    KeyId.ADD, KeyId.SUBTRACT, KeyId.MULTIPLY, KeyId.DIVIDE, KeyId.POWER,
)

/** Operators after which a `−` is the sign of the right operand rather than a subtraction. */
private val NEGATABLE_KEYS: Set<KeyId> = setOf(KeyId.MULTIPLY, KeyId.DIVIDE, KeyId.POWER)

/** Keys that modify the value to their left, and mean nothing without one. */
private val POSTFIX_KEYS: Set<KeyId> = setOf(KeyId.FACTORIAL, KeyId.PERCENT, KeyId.SQUARE)

internal val KeyId.isFunction: Boolean get() = this in FUNCTION_KEYS

/**
 * True when this key opens a group.
 *
 * Function keys count: their glyph ends in `(`, so treating them as anything else would
 * make [CalculatorExpr.unclosedParens] under-count and leave `sin(2` unbalanced forever.
 */
internal val KeyId.opensGroup: Boolean get() = this == KeyId.LEFT_PAREN || isFunction

/** True when a value ends here, so the next paren key must close rather than open. */
internal val KeyId.endsValue: Boolean get() = this in VALUE_ENDING_KEYS

/** True when this key takes an operand on each side. */
internal val KeyId.isBinaryOperator: Boolean get() = this in BINARY_KEYS

/** True when this key modifies the value it follows. */
internal val KeyId.isPostfix: Boolean get() = this in POSTFIX_KEYS

/**
 * True when this key joins or modifies a value that has to be there already.
 *
 * Public because a caller holding a value that is *not* a token sequence — the converter's
 * adopted result — has to tell a key that would consume that value from one that starts a
 * new one, before deciding whether to throw the value away for it.
 */
val KeyId.needsLeftOperand: Boolean get() = isBinaryOperator || isPostfix

/** The digit this key types, or `null` when it is not a digit key. */
internal val KeyId.digit: Char?
    get() = when (this) {
        KeyId.D0 -> '0'
        KeyId.D1 -> '1'
        KeyId.D2 -> '2'
        KeyId.D3 -> '3'
        KeyId.D4 -> '4'
        KeyId.D5 -> '5'
        KeyId.D6 -> '6'
        KeyId.D7 -> '7'
        KeyId.D8 -> '8'
        KeyId.D9 -> '9'
        else -> null
    }

/**
 * How this key reads in the formula line.
 *
 * Typographic glyphs, not ASCII: `×` rather than `*` and `−` (U+2212) rather than a hyphen,
 * because the formula sits next to the result in the same font and a hyphen there is
 * visibly shorter than the minus in the answer.
 */
internal val KeyId.glyph: String
    get() = when (this) {
        KeyId.D0, KeyId.D1, KeyId.D2, KeyId.D3, KeyId.D4,
        KeyId.D5, KeyId.D6, KeyId.D7, KeyId.D8, KeyId.D9,
        -> digit!!.toString()
        KeyId.POINT -> "."
        KeyId.ADD -> "+"
        KeyId.SUBTRACT -> "−"
        KeyId.MULTIPLY -> "×"
        KeyId.DIVIDE -> "÷"
        KeyId.POWER -> "^"
        KeyId.LEFT_PAREN -> "("
        KeyId.RIGHT_PAREN -> ")"
        KeyId.FACTORIAL -> "!"
        KeyId.PERCENT -> "%"
        KeyId.SQUARE -> "²"
        KeyId.SQRT -> "√"
        KeyId.SIN -> "sin("
        KeyId.COS -> "cos("
        KeyId.TAN -> "tan("
        KeyId.ASIN -> "asin("
        KeyId.ACOS -> "acos("
        KeyId.ATAN -> "atan("
        KeyId.LN -> "ln("
        KeyId.LOG -> "log("
        KeyId.EXP10 -> "10^("
        KeyId.EXPE -> "e^("
        KeyId.PI -> "π"
        KeyId.E -> "e"
        KeyId.VARIABLE_X -> "x"
    }

/**
 * An expression as the sequence of tokens that produced it.
 *
 * Immutable: every edit returns a new instance, so a view model can keep the previous one
 * for undo and can hand the same object to a background evaluation without the user's next
 * keystroke mutating it mid-computation.
 */
data class CalculatorExpr(val tokens: List<Token> = emptyList()) {

    /** True when nothing has been typed. */
    fun isEmpty(): Boolean = tokens.isEmpty()

    /**
     * Appends one key, dropping a keystroke that could not be part of an expression.
     *
     * Digits and the point fold into the trailing [Token.Number]; everything else becomes
     * its own token — but only when there is something for it to attach to. A key that needs
     * a value on its left and does not have one is dropped, and an operator pressed after
     * another operator rewrites it rather than stacking on it, so the token list never holds
     * a shape the parser would have to reject.
     */
    fun append(key: KeyId): CalculatorExpr {
        key.digit?.let { return appendToNumber(it) }
        if (key == KeyId.POINT) return appendPoint()
        // A `)` needs both a group to close and a value to close over: `(1+` followed by it
        // is `(1+)`, a syntax error rather than the nested group the user was reaching for.
        if (key == KeyId.RIGHT_PAREN && (unclosedParens() == 0 || !endsWithValue())) return this
        // `!`, `%` and `²` modify the value to their left. With no such value they are not
        // input at all: `²` as the first keystroke used to be stored and then answered with
        // a syntax error on every keystroke after it.
        if (key.isPostfix && !endsWithValue()) return this
        if (key.isBinaryOperator) return appendOperator(key)
        return CalculatorExpr(tokens + Token.Key(key))
    }

    /**
     * True when [append] of [key] would change anything.
     *
     * [append] drops a keystroke that cannot become a token — a `)` with nothing to close, a
     * second point in one number, a digit past [MAX_NUMBER_LENGTH] — by handing back the
     * same expression. A caller that clears state *around* the call cannot see that it
     * happened: pressing `)` on the scientific pad while a result is showing resets the
     * expression, appends nothing, and leaves a blank formula line, a blank result line and
     * no way back to the answer except the history drawer. Asking first lets a keystroke
     * that changes nothing be treated as the no-op it is, rather than as an edit.
     *
     * Answered by running [append] rather than by restating its rules, so the two can never
     * disagree about which keys are dropped.
     */
    fun accepts(key: KeyId): Boolean = append(key) !== this

    /**
     * Appends whichever paren the context calls for.
     *
     * Google Calculator ships one paren key, not two. It closes only when there is
     * something to close *and* a value has just ended; otherwise it opens. Without the
     * "value has just ended" half, `(1+` followed by the key would emit `)` and produce a
     * syntax error out of what was plainly a request for a nested group.
     */
    fun appendSmartParen(): CalculatorExpr {
        val last = tokens.lastOrNull()
        val closes = unclosedParens() > 0 && when (last) {
            is Token.Number -> true
            is Token.Key -> last.key.endsValue
            null -> false
        }
        return append(if (closes) KeyId.RIGHT_PAREN else KeyId.LEFT_PAREN)
    }

    /**
     * Removes exactly one token, or one digit from the trailing number.
     *
     * A function key is a single token whose glyph is `sin(`, so this deletes the whole
     * function in one press — deleting only the paren would leave a bare `sin` that can
     * never be completed by any further keystroke.
     *
     * Three things leave with the token rather than after it. The first two would otherwise
     * cost a press that changes nothing a reader could see; the third leaves behind a token
     * nothing else in the system accepts:
     *
     * 1. The zero a leading `−` brought with it. [appendOperator] writes `0−` for one key,
     *    so one key has to take it away; leaving the `0` behind means a press that redraws
     *    the display exactly as it already was, since an empty line draws a `0` too.
     * 2. A sign left standing in front of nothing. The keypad cannot build that shape, but
     *    pasting `-2` and deleting the `2` can, and every operator key would then be refused
     *    against a leading `−` the user has no way to explain.
     * 3. The `E` of an exponent, and its sign with it. Peeling `6.02E+23` one character at a
     *    time passes through `6.02E+` and `6.02E`, neither of which is a number
     *    [isNumberLiteral] accepts — so [ExprCodec.decode] rejects the *whole* blob and the
     *    expression the user pasted vanishes at the next process death, with nothing on
     *    screen to say it was ever in an unsaveable state. The exponent marker leaves with
     *    the digit that introduced it, exactly as `sin(` leaves as one token.
     */
    fun deleteLastToken(): CalculatorExpr {
        val last = tokens.lastOrNull() ?: return this
        if (last is Token.Number) {
            var shortened = last.text.dropLast(1)
            while (shortened.isNotEmpty() && !isNumberLiteral(shortened)) {
                shortened = shortened.dropLast(1)
            }
            if (shortened.isNotEmpty()) {
                return CalculatorExpr(tokens.dropLast(1) + Token.Number(shortened))
            }
            // Nothing survives as a literal — a one-character token, or a pasted `.5` whose
            // point cannot stand alone — so the token goes in full, below.
        }
        var remaining = tokens.size - 1
        if (last is Token.Key && last.key == KeyId.SUBTRACT && isImplicitZeroAt(remaining - 1)) {
            remaining--
        }
        var start = remaining
        while (start > 0) {
            val token = tokens[start - 1]
            if (token !is Token.Key || !token.key.isBinaryOperator) break
            start--
        }
        if (start < remaining && !endsWithValue(start)) remaining = start
        return CalculatorExpr(tokens.subList(0, remaining).toList())
    }

    /** Discards everything. */
    fun clear(): CalculatorExpr = CalculatorExpr()

    /**
     * True when the first [count] tokens end in something an operator can take as its left
     * operand.
     *
     * A number, a constant, a closed group or a postfix key ends a value; `(`, `sin(`, `√`
     * and an operator do not, and neither does an empty expression.
     */
    private fun endsWithValue(count: Int = tokens.size): Boolean =
        when (val last = tokens.getOrNull(count - 1)) {
            is Token.Number -> true
            is Token.Key -> last.key.endsValue
            null -> false
        }

    /**
     * Appends a binary operator, rewriting the run of operators already at the end.
     *
     * Two rules, both of which every desk calculator has:
     *
     * 1. An operator with no value on its left is not input. `×` as the first keystroke, or
     *    straight after `(`, only ever built an expression the evaluator answered with a
     *    syntax error — and it answered it on *every* keystroke after that, so the user had
     *    to work out which invisible early press had poisoned the line. This holds for the
     *    `−` too; see below.
     * 2. One operator stands between two values. Pressing `+` after `×` means the user
     *    changed their mind, so the new key replaces the run rather than extending it and
     *    `5×+3` can never be typed.
     *
     * The exception is a `−` after `×`, `÷` or `^`, where it is the sign of the right operand
     * rather than a second operator: `5×−3` and `2^−1` are what the user meant, and replacing
     * there would put a negative operand out of the keypad's reach entirely.
     *
     * A leading `−` is not a second exception so much as the display's own `0` becoming
     * real. With nothing to its left the sign belongs to a value that has not been typed
     * yet, so the zero already on screen is written down and the sign attaches to it:
     * `−5` is entered in two keystrokes and reaches the parser as `0−5`, which is the same
     * number with something under the sign the whole way rather than a sign hanging alone.
     *
     * Not after `√`, which takes a *factor* rather than a whole expression: `√0−5` parses as
     * `(√0)−5`, so a zero inserted there would quietly answer a different question. A
     * negative radicand is written `√(0−5)`.
     */
    private fun appendOperator(key: KeyId): CalculatorExpr {
        var start = tokens.size
        while (start > 0) {
            val token = tokens[start - 1]
            if (token !is Token.Key || !token.key.isBinaryOperator) break
            start--
        }
        if (!endsWithValue(start)) {
            if (key != KeyId.SUBTRACT || !opensImplicitZero(start)) return this
            return CalculatorExpr(
                tokens.subList(0, start) +
                    listOf(Token.Number(ZERO), Token.Key(KeyId.SUBTRACT)),
            )
        }
        val leading = (tokens.getOrNull(start) as? Token.Key)?.key
        val replacement = when {
            key != KeyId.SUBTRACT -> listOf(key)
            leading in NEGATABLE_KEYS -> listOf(leading!!, KeyId.SUBTRACT)
            else -> listOf(KeyId.SUBTRACT)
        }
        val next = tokens.subList(0, start) + replacement.map { Token.Key(it) }
        // Identity is the contract [accepts] reads: pressing `−` on `5−`, or again on `5×−`,
        // rewrites the run to what it already was, and a caller that clears state around the
        // call has to be able to tell that apart from an edit.
        if (next == tokens) return this
        return CalculatorExpr(next)
    }

    /** How many opened groups — parens and function calls alike — are still open. */
    fun unclosedParens(): Int {
        var open = 0
        for (token in tokens) {
            if (token !is Token.Key) continue
            when {
                token.key.opensGroup -> open++
                token.key == KeyId.RIGHT_PAREN -> if (open > 0) open--
            }
        }
        return open
    }

    /** The formula line, in display glyphs. Digits stay ASCII; the result line localises. */
    fun display(): String = buildString {
        for (token in tokens) {
            when (token) {
                is Token.Number -> append(token.text)
                is Token.Key -> append(token.key.glyph)
            }
        }
    }

    override fun toString(): String = display()

    companion object {

        /** The value the display already shows, written down when a leading `−` needs it. */
        private const val ZERO: String = "0"

        /** A typed number longer than this is refused; it is a paste accident, not input. */
        internal const val MAX_NUMBER_LENGTH: Int = 64

        /**
         * The longest literal any [Token.Number] may carry, however it was produced.
         *
         * 255 because that is the length [ExprCodec]'s compact number frame can state. The
         * codec no longer *depends* on the ceiling — it frames a longer token correctly
         * rather than wrapping the length — but a value that needs more significant digits
         * than this is still refused rather than seeded, because a literal that wide is a
         * number nobody can read back off the formula line either. Larger than
         * [MAX_NUMBER_LENGTH] because [seedFromDecimal] generates
         * literals the keypad never could — the 158 digits of `100!`, for one.
         */
        internal const val MAX_LITERAL_LENGTH: Int = 255

        /**
         * Refuses absurd token counts before parsing them.
         *
         * The parser recurses, and pasted text has no natural length limit; a clipboard
         * full of radicals is otherwise bounded only by the stack.
         */
        internal const val MAX_TOKENS: Int = 10_000

        /**
         * Rebuilds an expression from text, for paste.
         *
         * Returns `null` unless the whole string tokenises *and* parses. Accepting a
         * fragment that only tokenises would put the calculator into a state the keypad
         * can never reach, where every subsequent evaluation fails with no way back except
         * clearing.
         */
        fun fromText(text: String): CalculatorExpr? {
            val tokens = tokenize(text) ?: return null
            if (tokens.isEmpty()) return null
            val expr = CalculatorExpr(tokens)
            return try {
                ExprParser(tokens).parse()
                expr
            } catch (e: Exception) {
                null
            } catch (e: StackOverflowError) {
                // Deep nesting in pasted text overflows the stack rather than throwing an
                // Exception, and this runs on the main thread on paste: an Error escaping
                // here kills the process instead of reporting unparseable text.
                null
            }
        }

        /**
         * Builds an expression that stands for an already-computed value: the token form of
         * a decimal literal the app generated rather than the user typed.
         *
         * `CalculatorViewModel` continues a calculation by carrying the previous answer's
         * whole token stream forward instead, and only falls back to a literal for a short
         * exact value — for which it has its own copy of rules 2 and 3 below. So this is not
         * on that path today; it remains the one place the three rules are stated in full,
         * and the one entry point a non-keypad caller can seed from.
         *
         * Deliberately not [fromText], which is for text a *person* produced. Three things
         * differ, and each one is a wrong answer if it is missed:
         *
         * 1. [MAX_NUMBER_LENGTH] does not apply. It exists to refuse paste accidents; a
         *    generated literal is not one, and rejecting `100!`'s 158 digits under it drops
         *    the value on the floor and answers the next keystroke with whatever is left.
         * 2. A negative value is parenthesised. Seeded as a bare `−5`, the `²` the user
         *    presses next binds tighter than the minus in `parseFactor`, so `−5` squared
         *    comes out as −25.
         * 3. Failure is `null`, never an empty expression. An empty expression turns a lost
         *    value into a plausible wrong answer with nothing on screen to reveal it.
         *
         * [text] must be a plain ASCII decimal — optional `-`, digits, an optional point,
         * an optional upper-case `E` exponent — which is what
         * `ResultFormatter.formatPlain` produces. Anything else is a caller bug and
         * returns `null`.
         */
        fun seedFromDecimal(text: String): CalculatorExpr? {
            val negative = text.startsWith("-")
            val body = if (negative) text.substring(1) else text
            if (!isNumberLiteral(body)) return null
            val literal = if (body.length <= MAX_LITERAL_LENGTH) body else compact(body)
            // Even compacted, a value can need more significant digits than a token holds.
            // No seed can represent it, and saying so beats persisting a wrapped length.
            if (literal.length > MAX_LITERAL_LENGTH) return null
            val number = Token.Number(literal)
            if (!negative) return CalculatorExpr(listOf(number))
            return CalculatorExpr(
                listOf(
                    Token.Key(KeyId.LEFT_PAREN),
                    Token.Key(KeyId.SUBTRACT),
                    number,
                    Token.Key(KeyId.RIGHT_PAREN),
                ),
            )
        }

        /**
         * True when [text] is exactly the shape [scanNumber] produces.
         *
         * Unsigned, at most one point, and an exponent introduced only by an *upper-case*
         * `E`. The case matters: [ExprParser] bounds an exponent it finds, and
         * `BoundedRational.parse` accepts either case, so a lower-case `1e300000000`
         * arriving from somewhere other than the tokenizer would slip past the bound.
         */
        internal fun isNumberLiteral(text: String): Boolean {
            var index = 0
            var digits = 0
            var seenPoint = false
            while (index < text.length) {
                val c = text[index]
                when {
                    c in '0'..'9' -> digits++
                    c == '.' && !seenPoint -> seenPoint = true
                    else -> break
                }
                index++
            }
            if (digits == 0) return false
            if (index == text.length) return true
            if (text[index] != 'E') return false
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
            if (index == text.length) return false
            while (index < text.length) {
                if (text[index] !in '0'..'9') return false
                index++
            }
            return true
        }

        /**
         * The same value written as `mantissaEexponent`, for a literal too long to carry.
         *
         * Only zeros that carry no information move into the exponent, so this is exact:
         * `1` followed by three hundred zeros and `1E300` are the same number, but only the
         * second survives [MAX_LITERAL_LENGTH]. The result can still be too long — a value
         * with three hundred *significant* digits has nowhere to put them — which the
         * caller checks.
         */
        private fun compact(body: String): String {
            if (body.contains('E')) return body
            val point = body.indexOf('.')
            val digits = if (point < 0) body else body.removeRange(point, point + 1)
            var exponent = if (point < 0) 0 else -(body.length - point - 1)
            val first = digits.indexOfFirst { it != '0' }
            if (first < 0) return "0"
            var end = digits.length
            while (end > first + 1 && digits[end - 1] == '0') {
                end--
                exponent++
            }
            val mantissa = digits.substring(first, end)
            return if (exponent == 0) mantissa else "${mantissa}E$exponent"
        }

        /** Text that may sit between tokens without meaning anything. */
        private const val IGNORED = " \t\n\r\u00A0\u202F\u2009,_"

        /**
         * Prefixes recognised in pasted text, longest first within each ambiguous group.
         *
         * `e^(` has to precede `e`, and the inverse trig names have to precede the forward
         * ones, or `asin(30)` tokenises as `a × sin(30)` and then fails on the stray `a`.
         */
        private val PREFIXES: List<Pair<String, KeyId>> = listOf(
            "asin(" to KeyId.ASIN,
            "acos(" to KeyId.ACOS,
            "atan(" to KeyId.ATAN,
            "arcsin(" to KeyId.ASIN,
            "arccos(" to KeyId.ACOS,
            "arctan(" to KeyId.ATAN,
            "sin(" to KeyId.SIN,
            "cos(" to KeyId.COS,
            "tan(" to KeyId.TAN,
            "ln(" to KeyId.LN,
            "log(" to KeyId.LOG,
            "10^(" to KeyId.EXP10,
            "e^(" to KeyId.EXPE,
            "pi" to KeyId.PI,
            "π" to KeyId.PI,
            "√" to KeyId.SQRT,
            "×" to KeyId.MULTIPLY,
            "*" to KeyId.MULTIPLY,
            "·" to KeyId.MULTIPLY,
            "÷" to KeyId.DIVIDE,
            "/" to KeyId.DIVIDE,
            "−" to KeyId.SUBTRACT,
            "-" to KeyId.SUBTRACT,
            "–" to KeyId.SUBTRACT,
            "—" to KeyId.SUBTRACT,
            "+" to KeyId.ADD,
            "^" to KeyId.POWER,
            "(" to KeyId.LEFT_PAREN,
            ")" to KeyId.RIGHT_PAREN,
            "!" to KeyId.FACTORIAL,
            "%" to KeyId.PERCENT,
            "²" to KeyId.SQUARE,
            "e" to KeyId.E,
            "x" to KeyId.VARIABLE_X,
            "X" to KeyId.VARIABLE_X,
        )

        private fun tokenize(text: String): List<Token>? {
            val tokens = mutableListOf<Token>()
            var index = 0
            while (index < text.length) {
                if (tokens.size >= MAX_TOKENS) return null
                val c = text[index]
                if (c in IGNORED) {
                    index++
                    continue
                }
                // `sqrt(` is the only spelling whose glyph form is two tokens: the radical
                // takes a factor, so the paren has to survive as a group of its own.
                if (text.startsWith("sqrt(", index)) {
                    tokens += Token.Key(KeyId.SQRT)
                    tokens += Token.Key(KeyId.LEFT_PAREN)
                    index += 5
                    continue
                }
                val prefix = PREFIXES.firstOrNull { text.startsWith(it.first, index) }
                if (prefix != null) {
                    tokens += Token.Key(prefix.second)
                    index += prefix.first.length
                    continue
                }
                if (c in '0'..'9' || c == '.') {
                    // Two numbers can never be adjacent — no keypad sequence produces that
                    // shape — so reaching here means a character in IGNORED separated them,
                    // and every character in IGNORED is a digit-grouping separator in some
                    // locale: "1,234" in en, "1 234" in fr, "1,5" meaning three halves in de.
                    // The parser would read the pieces as an implicit multiplication and
                    // answer 1 × 234, so refuse the text instead of answering the wrong
                    // question with no way for the user to tell.
                    if (tokens.lastOrNull() is Token.Number) return null
                    val end = scanNumber(text, index) ?: return null
                    tokens += Token.Number(text.substring(index, end))
                    index = end
                    continue
                }
                return null
            }
            return tokens
        }

        /**
         * Finds the end of a numeric literal starting at [start], or `null` if it is
         * malformed or absurdly long.
         *
         * Only an upper-case `E` introduces an exponent. Lower-case `e` is Euler's number,
         * and guessing between the two turns `2e` — a perfectly ordinary `2 × e` — into a
         * truncated exponent and a syntax error.
         */
        private fun scanNumber(text: String, start: Int): Int? {
            var index = start
            var seenPoint = false
            var seenDigit = false
            while (index < text.length) {
                val c = text[index]
                when {
                    c in '0'..'9' -> seenDigit = true
                    c == '.' && !seenPoint -> seenPoint = true
                    else -> break
                }
                index++
            }
            if (!seenDigit) return null
            if (index < text.length && text[index] == 'E') {
                var probe = index + 1
                if (probe < text.length && (text[probe] == '+' || text[probe] == '-')) probe++
                if (probe < text.length && text[probe] in '0'..'9') {
                    while (probe < text.length && text[probe] in '0'..'9') probe++
                    index = probe
                }
            }
            return if (index - start > MAX_NUMBER_LENGTH) null else index
        }
    }

    private fun appendToNumber(digit: Char): CalculatorExpr {
        val last = tokens.lastOrNull()
        if (last is Token.Number) {
            if (last.text.length >= MAX_NUMBER_LENGTH) return this
            return CalculatorExpr(tokens.dropLast(1) + Token.Number(last.text + digit))
        }
        return CalculatorExpr(tokens + Token.Number(digit.toString()))
    }

    /**
     * True when a `−` at [start] would be the sign of the first value in a group.
     *
     * The start of the expression, or straight after `(` or a function's own paren — the
     * three places the parser reads what follows as a whole expression, and so the three
     * places an inserted `0` stands in front of everything the sign is meant to cover.
     */
    private fun opensImplicitZero(start: Int): Boolean {
        val previous = tokens.getOrNull(start - 1) ?: return true
        return previous is Token.Key && previous.key.opensGroup
    }

    /**
     * True when the token at [index] is a zero that a leading `−` could only have written.
     *
     * The same three positions [opensImplicitZero] writes it in — the start of the
     * expression, or straight after `(` or a function's paren. A zero the user typed there
     * is indistinguishable from it and is removed on the same press, which costs nothing:
     * an expression holding only that zero draws exactly what an empty one draws.
     */
    private fun isImplicitZeroAt(index: Int): Boolean {
        val token = tokens.getOrNull(index)
        if (token !is Token.Number || token.text != ZERO) return false
        val previous = tokens.getOrNull(index - 1) ?: return true
        return previous is Token.Key && previous.key.opensGroup
    }

    /**
     * Appends a decimal point.
     *
     * A point with no number in front of it starts `0.` rather than a bare `.`, so that the
     * formula line never shows a leading dot and one backspace still leaves a valid number.
     */
    private fun appendPoint(): CalculatorExpr {
        val last = tokens.lastOrNull()
        if (last is Token.Number) {
            if (last.text.contains('.') || last.text.contains('E')) return this
            if (last.text.length >= MAX_NUMBER_LENGTH) return this
            return CalculatorExpr(tokens.dropLast(1) + Token.Number(last.text + '.'))
        }
        return CalculatorExpr(tokens + Token.Number("0."))
    }
}
