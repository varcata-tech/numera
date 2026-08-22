package app.numera.calculator.math.expr

import app.numera.calculator.math.BoundedRational

/** The token stream is not an expression. Reported to the user as a bad expression. */
internal class SyntaxException : Exception("syntax")

/** A numeric literal names a magnitude the engine refuses to materialise. */
internal class LiteralOverflowException : Exception("literal out of range")

/**
 * The parsed shape of an expression.
 *
 * Deliberately a tree rather than a stream of exact values: it is walked twice, once in
 * exact arithmetic for the answer and once in `Double` for the graph, and building it once
 * is what keeps those two from drifting apart into two subtly different languages.
 */
internal sealed interface Node {

    /** A literal, carrying both the exact value and a pre-converted `Double`. */
    data class Literal(val rational: BoundedRational, val approx: Double) : Node

    /** The constant π. */
    data object Pi : Node

    /** The constant e. */
    data object EulerE : Node

    /** The graphing variable. */
    data object Variable : Node

    /** Unary minus, which binds looser than `^`. */
    data class Negate(val child: Node) : Node

    /**
     * Addition or subtraction.
     *
     * [relative] records that the right operand ended in `%`, which is what turns
     * `100 + 10%` into 110 instead of 100.1. It is a property of the *operator* rather
     * than of the percent node because only an additive context makes a percentage
     * relative to anything.
     */
    data class Additive(
        val left: Node,
        val right: Node,
        val subtract: Boolean,
        val relative: Boolean,
    ) : Node

    /** Multiplication, explicit or implicit. */
    data class Multiply(val left: Node, val right: Node) : Node

    /** Division. */
    data class Divide(val left: Node, val right: Node) : Node

    /** Exponentiation, right associative. */
    data class Power(val base: Node, val exponent: Node) : Node

    /** Postfix `!`. */
    data class Factorial(val child: Node) : Node

    /** Postfix `%`, meaning "divided by one hundred" on its own. */
    data class Percent(val child: Node) : Node

    /** Postfix `²`. */
    data class Square(val child: Node) : Node

    /** Prefix `√`, which takes a whole factor. */
    data class Root(val child: Node) : Node

    /** A named function applied to a parenthesised argument. */
    data class Call(val function: KeyId, val argument: Node) : Node
}

/**
 * Recursive descent over the token list.
 *
 * The grammar, loosest binding first:
 * ```
 * expr    := term (("+" | "−") term)*
 * term    := factor (("×" | "÷" | implicit) factor)*
 * factor  := "−"* power
 * power   := postfix ("^" factor)?          right associative
 * postfix := atom ("!" | "%" | "²")*
 * atom    := Number | constant | "(" expr ")" | fn expr ")" | "√" factor | x
 * ```
 * Unary minus sits *above* `^` on purpose, so `−2^2` is −4 and not 4 — every desk
 * calculator and every spreadsheet disagree about this, and Google Calculator is in the
 * −4 camp.
 */
internal class ExprParser(private val tokens: List<Token>) {

    private var position = 0
    private var depth = 0

    /** Parses the whole stream, or throws [SyntaxException]. */
    fun parse(): Node {
        if (tokens.isEmpty()) throw SyntaxException()
        val node = parseExpr()
        if (position < tokens.size) throw SyntaxException()
        return node
    }

    private fun parseExpr(): Node {
        // A pasted string can nest parens far deeper than any keypad ever will, and the
        // recursion is the JVM stack; refuse before it overflows rather than after.
        if (++depth > MAX_DEPTH) throw SyntaxException()
        try {
            var (node, _) = parseTerm()
            while (true) {
                val key = peekKey() ?: break
                if (key != KeyId.ADD && key != KeyId.SUBTRACT) break
                position++
                val (right, percent) = parseTerm()
                node = Node.Additive(node, right, key == KeyId.SUBTRACT, percent)
            }
            return node
        } finally {
            depth--
        }
    }

    /**
     * Returns the term and whether it ended in a percent, which the caller may need.
     *
     * A product or a quotient is never itself relative, however it was written. Carrying
     * the last factor's flag out of the term made `100 + 2 × 50%` and `100 + 50% × 2`
     * — the same two numbers multiplied in the other order — answer 200 and 101, and made
     * `100 + 20 ÷ 10%` answer 20100, which is no reading of the expression at all. The
     * relative rule applies only when `%` is the outermost node of the additive right
     * operand, which is what [Node.Additive] already documents.
     */
    private fun parseTerm(): Parsed {
        var (node, percent) = parseFactor()
        while (true) {
            val token = peek() ?: break
            val key = (token as? Token.Key)?.key
            when {
                key == KeyId.MULTIPLY -> {
                    position++
                    node = Node.Multiply(node, parseFactor().node)
                    percent = false
                }
                key == KeyId.DIVIDE -> {
                    position++
                    node = Node.Divide(node, parseFactor().node)
                    percent = false
                }
                // Juxtaposition is multiplication: 2π, 3(4+5), 2sin(30). Without this the
                // most natural way to write a coefficient is a syntax error.
                startsFactor(token) -> {
                    node = Node.Multiply(node, parseFactor().node)
                    percent = false
                }
                else -> break
            }
        }
        return Parsed(node, percent)
    }

    private fun parseFactor(): Parsed {
        // `√` recurses through parseFactor → parsePower → parsePostfix → parseAtom →
        // parseFactor without ever passing through parseExpr, so counting depth only there
        // left a run of radicals bounded by nothing but the JVM stack.
        if (++depth > MAX_DEPTH) throw SyntaxException()
        try {
            var negations = 0
            while (true) {
                val key = peekKey() ?: break
                when (key) {
                    KeyId.SUBTRACT -> {
                        negations++
                        position++
                    }
                    KeyId.ADD -> position++
                    else -> break
                }
            }
            val (node, percent) = parsePower()
            return Parsed(if (negations % 2 == 1) Node.Negate(node) else node, percent)
        } finally {
            depth--
        }
    }

    private fun parsePower(): Parsed {
        val (base, percent) = parsePostfix()
        if (peekKey() != KeyId.POWER) return Parsed(base, percent)
        position++
        // The exponent is a factor, not a power, which is what makes 2^3^2 associate to
        // the right and come out as 512 rather than 64.
        val exponent = parseFactor()
        return Parsed(Node.Power(base, exponent.node), false)
    }

    private fun parsePostfix(): Parsed {
        var node = parseAtom()
        var percent = false
        while (true) {
            when (peekKey()) {
                KeyId.FACTORIAL -> {
                    node = Node.Factorial(node)
                    percent = false
                }
                KeyId.PERCENT -> {
                    node = Node.Percent(node)
                    percent = true
                }
                KeyId.SQUARE -> {
                    node = Node.Square(node)
                    percent = false
                }
                else -> return Parsed(node, percent)
            }
            position++
        }
    }

    private fun parseAtom(): Node {
        val token = peek() ?: throw SyntaxException()
        if (token is Token.Number) {
            position++
            return literal(token.text)
        }
        val key = (token as Token.Key).key
        position++
        return when {
            key == KeyId.PI -> Node.Pi
            key == KeyId.E -> Node.EulerE
            key == KeyId.VARIABLE_X -> Node.Variable
            key == KeyId.LEFT_PAREN -> {
                val inner = parseExpr()
                consumeClose()
                inner
            }
            key.isFunction -> {
                val argument = parseExpr()
                consumeClose()
                Node.Call(key, argument)
            }
            key == KeyId.SQRT -> Node.Root(parseFactor().node)
            else -> throw SyntaxException()
        }
    }

    /**
     * Consumes the `)` that ends a group, tolerating its absence at end of input.
     *
     * The user gets an answer while still typing, so an expression is evaluated far more
     * often half-finished than finished. Auto-closing here is what makes `sin(30` show a
     * result instead of an error the moment before the paren key is pressed.
     */
    private fun consumeClose() {
        if (peekKey() == KeyId.RIGHT_PAREN) {
            position++
            return
        }
        if (position < tokens.size) throw SyntaxException()
    }

    private fun peek(): Token? = tokens.getOrNull(position)

    private fun peekKey(): KeyId? = (peek() as? Token.Key)?.key

    /**
     * Whether [token] could begin a factor, and therefore be an implicit multiplication.
     *
     * A leading `−` is excluded: it is the additive operator far more often than it is a
     * negation, and treating `2−3` as `2 × (−3)` would silently turn subtraction into a
     * product.
     */
    private fun startsFactor(token: Token): Boolean {
        if (token is Token.Number) return true
        val key = (token as Token.Key).key
        return key == KeyId.LEFT_PAREN || key.isFunction ||
            key == KeyId.SQRT || key == KeyId.PI || key == KeyId.E || key == KeyId.VARIABLE_X
    }

    private fun literal(text: String): Node.Literal {
        // Either case, even though the tokenizer only ever emits an upper-case E:
        // BoundedRational.parse accepts both, so looking only for 'E' here let a token from
        // anywhere else — a corrupt history row, a crafted clipboard blob — carry a
        // lower-case `1e300000000` straight past the bound and into BigInteger.pow.
        val exponentIndex = text.indexOfFirst { it == 'e' || it == 'E' }
        if (exponentIndex >= 0) {
            val exponent = text.substring(exponentIndex + 1).toIntOrNull()
                ?: throw SyntaxException()
            // 1E999999999 is four keystrokes of paste and a hundred million digits of
            // BigInteger; refuse it by name rather than discover it in the allocator.
            if (exponent > MAX_LITERAL_EXPONENT || exponent < -MAX_LITERAL_EXPONENT) {
                throw LiteralOverflowException()
            }
        }
        val rational = try {
            BoundedRational.parse(text)
        } catch (e: IllegalArgumentException) {
            throw SyntaxException()
        }
        return Node.Literal(rational, text.toDoubleOrNull() ?: rational.toDouble())
    }

    private data class Parsed(val node: Node, val percent: Boolean)

    private companion object {
        /**
         * Recursive cycles allowed before the token stream is called unparseable.
         *
         * Counted in both [parseExpr] and [parseFactor], so one level of parens costs two:
         * four hundred here is the same two hundred nested groups the parser has always
         * accepted, now with the `√` cycle counted as well.
         */
        const val MAX_DEPTH = 400
        const val MAX_LITERAL_EXPONENT = 100_000
    }
}
