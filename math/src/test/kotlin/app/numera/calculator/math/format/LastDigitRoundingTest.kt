package app.numera.calculator.math.format

import app.numera.calculator.math.ConstructiveReal
import app.numera.calculator.math.UnifiedReal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The final displayed digit, against published expansions.
 *
 * This is the shape of bug the engine tests exist to catch: not a wrong answer, but a last
 * digit decided by where an approximation happened to land. Truncating the result of
 * `getAppr` — which is only guaranteed to within one unit in the last place — made π, √2,
 * ln 2, 1/7 and 2/3 come out correctly rounded and `e` come out one low, from the same code.
 * Expansions below are quoted far past the width under test so the expected digit is never
 * in doubt.
 */
class LastDigitRoundingTest {

    private val expansions = mapOf(
        "pi" to "3.14159265358979323846264338327950288419716939937510",
        "e" to "2.718281828459045235360287471352662497757247093699959",
        "sqrt2" to "1.41421356237309504880168872420969807856967187537694",
        "sqrt3" to "1.73205080756887729352744634150587236694280525381038",
        "ln2" to "0.69314718055994530941723212145817656807550013436025",
        "third" to "0.33333333333333333333333333333333333333333333333333",
        "seventh" to "0.14285714285714285714285714285714285714285714285714",
    )

    private fun realOf(name: String): ConstructiveReal = when (name) {
        "pi" -> ConstructiveReal.PI
        "e" -> ConstructiveReal.ONE.exp()
        "sqrt2" -> ConstructiveReal.valueOf(2).sqrt()
        "sqrt3" -> ConstructiveReal.valueOf(3).sqrt()
        "ln2" -> ConstructiveReal.valueOf(2).ln()
        "third" -> ConstructiveReal.ONE / ConstructiveReal.valueOf(3)
        "seventh" -> ConstructiveReal.ONE / ConstructiveReal.valueOf(7)
        else -> error("unknown constant $name")
    }

    /** [text] rounded half-up to [places] decimals, done on the published digits. */
    private fun roundExpansion(text: String, places: Int): String {
        val point = text.indexOf('.')
        val keep = text.substring(0, point + 1 + places)
        val next = text[point + 1 + places]
        if (next < '5') return keep
        // Carry by hand so the expectation never depends on the code under test.
        val chars = keep.toCharArray()
        var i = chars.size - 1
        while (i >= 0) {
            if (chars[i] == '.') { i--; continue }
            if (chars[i] != '9') { chars[i] = chars[i] + 1; break }
            chars[i] = '0'
            i--
        }
        val carried = String(chars)
        return if (i < 0) "1$carried" else carried
    }

    @Test
    fun `every constant rounds its last digit correctly at many widths`() {
        for ((name, expansion) in expansions) {
            for (places in listOf(1, 2, 5, 12, 17, 18, 20, 30, 40)) {
                assertEquals(
                    "$name to $places places",
                    roundExpansion(expansion, places),
                    realOf(name).toStringRounded(places),
                )
            }
        }
    }

    @Test
    fun `e is the one that used to come out one low`() {
        // 2.71828182845904523|53602..., so the eighteen-significant-digit form ends 524.
        assertEquals("2.71828182845904524", realOf("e").toStringRounded(17))
    }

    @Test
    fun `rounding that carries lengthens the whole part`() {
        // 9.999... to two places is 10.00, not 9.99 and not 0.00.
        val almostTen = ConstructiveReal.valueOf(10) - ConstructiveReal.ONE / ConstructiveReal.valueOf(100000)
        assertEquals("10.00", almostTen.toStringRounded(2))
    }

    @Test
    fun `zero places gives a rounded whole number`() {
        assertEquals("3", ConstructiveReal.PI.toStringRounded(0))
        assertEquals("3", realOf("e").toStringRounded(0))
        assertEquals("1", ConstructiveReal.valueOf(2).sqrt().toStringRounded(0))
    }

    @Test
    fun `negatives round away from zero and keep their sign`() {
        assertEquals("-3.14159", (-ConstructiveReal.PI).toStringRounded(5))
        assertEquals("-0.33333", (-realOf("third")).toStringRounded(5))
    }

    @Test
    fun `the formatter shows the rounded digit to the user`() {
        val e = UnifiedReal.E
        assertEquals(
            "2.71828182845904524",
            ResultFormatter.formatWithDigits(e, 17, Locale.ROOT),
        )
    }
}
