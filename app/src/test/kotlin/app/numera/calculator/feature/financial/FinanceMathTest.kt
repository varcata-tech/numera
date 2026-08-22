package app.numera.calculator.feature.financial

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Money arithmetic, checked against figures a user could verify elsewhere.
 *
 * The schedule-sums-to-principal test is the important one. Every individual line can be
 * correct to the cent and the schedule still be wrong, because rounding errors accumulate
 * in one direction; that failure is invisible line by line and obvious in the total.
 */
class FinanceMathTest {

    private fun bd(text: String) = BigDecimal(text)

    @Test
    fun `a standard loan instalment matches the annuity formula to the cent`() {
        // 1,000,000 at 8.5% over 20 years. Widely published figure: 8678.23 per month.
        val loan = FinanceMath.loan(bd("1000000"), bd("8.5"), 240)
        assertEquals(bd("8678.23"), loan.instalment)
        assertEquals(240, loan.schedule.size)
    }

    @Test
    fun `the principal column sums to exactly the principal`() {
        val principal = bd("1000000")
        val loan = FinanceMath.loan(principal, bd("8.5"), 240)
        val summed = loan.schedule.fold(BigDecimal.ZERO) { acc, line -> acc.add(line.principal) }
        // Not "close to" — exactly. This is what the final-instalment adjustment buys.
        assertEquals(0, principal.compareTo(summed))
        assertEquals(0, BigDecimal.ZERO.compareTo(loan.schedule.last().balance))
    }

    @Test
    fun `interest plus principal equals the payment on every line`() {
        val loan = FinanceMath.loan(bd("250000"), bd("6.25"), 60)
        for (line in loan.schedule) {
            assertEquals(
                "line ${line.number} does not balance",
                0,
                line.payment.compareTo(line.principal.add(line.interest)),
            )
        }
    }

    @Test
    fun `a zero rate loan does not divide by zero`() {
        // The annuity denominator is exactly zero here; the trivial answer is the right one.
        val loan = FinanceMath.loan(bd("1200"), BigDecimal.ZERO, 12)
        assertEquals(bd("100.00"), loan.instalment)
        assertEquals(0, BigDecimal.ZERO.compareTo(loan.totalInterest))
    }

    @Test
    fun `total interest is the total paid less the principal`() {
        val loan = FinanceMath.loan(bd("500000"), bd("9"), 120)
        assertEquals(0, loan.totalInterest.compareTo(loan.totalPaid.subtract(bd("500000"))))
        assertTrue(loan.totalInterest.signum() > 0)
    }

    @Test
    fun `annual compounding matches the textbook figure`() {
        // 1000 at 5% for 10 years, compounded annually, is 1628.89.
        val result = FinanceMath.compoundInterest(
            bd("1000"), bd("5"), bd("10"), FinanceMath.Compounding.ANNUAL,
        )
        assertEquals(bd("1628.89"), result)
    }

    @Test
    fun `more frequent compounding earns more, and continuous earns most`() {
        val amounts = listOf(
            FinanceMath.Compounding.ANNUAL,
            FinanceMath.Compounding.SEMIANNUAL,
            FinanceMath.Compounding.QUARTERLY,
            FinanceMath.Compounding.MONTHLY,
            FinanceMath.Compounding.DAILY,
            FinanceMath.Compounding.CONTINUOUS,
        ).map { FinanceMath.compoundInterest(bd("1000"), bd("5"), bd("10"), it) }

        for (i in 0 until amounts.size - 1) {
            assertTrue(
                "compounding order wrong at $i: ${amounts[i]} vs ${amounts[i + 1]}",
                amounts[i] < amounts[i + 1],
            )
        }
        // e^0.5 * 1000 = 1648.72
        assertEquals(bd("1648.72"), amounts.last())
    }

    @Test
    fun `regular contributions are added as an annuity not as a lump`() {
        val withContributions = FinanceMath.compoundInterest(
            bd("0"), bd("6"), bd("10"), FinanceMath.Compounding.MONTHLY, bd("100"),
        )
        // 100 a month for 120 months is 12000 contributed; growth must exceed that.
        assertTrue("expected over 16000, got $withContributions", withContributions > bd("16000"))
        assertTrue(withContributions < bd("17000"))
    }

    @Test
    fun `a tip split accounts for the odd cents instead of losing them`() {
        val split = FinanceMath.tip(bd("10.00"), BigDecimal.ZERO, people = 3)
        assertEquals(bd("3.33"), split.perPerson)
        // Three times 3.33 is 9.99, so one person covers the extra cent.
        assertEquals(1, split.peoplePayingExtra)

        val even = FinanceMath.tip(bd("12.00"), BigDecimal.ZERO, people = 4)
        assertEquals(bd("3.00"), even.perPerson)
        assertEquals(0, even.peoplePayingExtra)
    }

    /**
     * The screen used to hand a headcount of zero straight through `coerceAtLeast(1)`, which
     * answered a question the user had not asked and looked exactly like a real result. The
     * refusal has to live here so that raising it back to one cannot be done quietly.
     */
    @Test
    fun `a split among fewer than one person is refused rather than rounded up`() {
        assertThrows(IllegalArgumentException::class.java) {
            FinanceMath.tip(bd("80.00"), bd("15"), people = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FinanceMath.tip(bd("80.00"), bd("15"), people = -3)
        }
    }

    @Test
    fun `a tip is computed on the bill and added to it`() {
        val split = FinanceMath.tip(bd("80.00"), bd("15"), people = 1)
        assertEquals(bd("12.00"), split.tip)
        assertEquals(bd("92.00"), split.total)
    }

    @Test
    fun `successive discounts compose rather than add`() {
        // 20% then 10% is 28% off, not 30%. This is the arithmetic people get wrong in shops.
        val result = FinanceMath.successiveDiscount(bd("100"), listOf(bd("20"), bd("10")))
        assertEquals(bd("72.00"), result.finalPrice)
        assertEquals(bd("28.00"), result.saved)
        assertEquals(bd("28.00"), result.effectivePercent)
    }

    @Test
    fun `a single discount is straightforward`() {
        val result = FinanceMath.successiveDiscount(bd("250"), listOf(bd("15")))
        assertEquals(bd("212.50"), result.finalPrice)
        assertEquals(bd("37.50"), result.saved)
    }

    @Test
    fun `adding then removing the same tax rate round trips exactly`() {
        val net = bd("1000")
        val added = FinanceMath.addTax(net, bd("18"))
        assertEquals(bd("180.00"), added.tax)
        assertEquals(bd("1180.00"), added.gross)

        val removed = FinanceMath.removeTax(added.gross, bd("18"))
        // A naive "multiply the gross by 18%" gives 212.40 here and does not round trip.
        assertEquals(0, net.compareTo(removed.net))
        assertEquals(0, added.tax.compareTo(removed.tax))
    }

    @Test
    fun `tax extraction works on an awkward gross`() {
        val removed = FinanceMath.removeTax(bd("118"), bd("18"))
        assertEquals(bd("100.00"), removed.net)
        assertEquals(bd("18.00"), removed.tax)
    }
}
