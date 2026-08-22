package app.numera.calculator.feature.financial

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.ln

/**
 * Money arithmetic.
 *
 * Deliberately [BigDecimal] rather than the app's exact [app.numera.calculator.math.UnifiedReal].
 * Money is decimal by nature and *rounding is part of the domain*: an instalment really is
 * quoted to the cent, and a schedule of 240 of them has to add up to the principal exactly.
 * An engine that refuses to round cannot express that, and a `Double` accumulates a cent of
 * drift across the term — which is visible, and which people notice.
 */
object FinanceMath {

    /** Enough precision that intermediate compounding never limits the answer. */
    private val MATH = MathContext(34, RoundingMode.HALF_EVEN)

    private val HUNDRED = BigDecimal("100")

    private val TWO = BigDecimal(2)

    /**
     * Longest loan term, in months. A hundred years; beyond that it is not a loan.
     *
     * The term arrives from a text field, and [loan] builds one `BigDecimal`-heavy schedule
     * row per month, synchronously. Unbounded, a seven-digit term freezes the UI while it
     * allocates millions of rows, a nine-digit one runs out of memory, and anything above
     * 999999999 makes `BigDecimal.pow` throw before the allocation even starts. The early
     * "balance reached zero" exit does not save it: on a very long term the instalment barely
     * covers the interest, so the balance never settles and the loop runs the whole way.
     */
    const val MAX_LOAN_MONTHS: Int = 1200

    /**
     * Most compounding periods [compoundGrowth] will evaluate.
     *
     * A million periods is nearly three thousand years of daily compounding. The bound exists
     * because the count is derived from a text field and used as an `Int` exponent: an
     * unbounded count either exceeds `pow`'s own 999999999 limit, or — worse — wraps negative
     * when narrowed, and `pow` with a negative exponent returns the *reciprocal*, reporting a
     * colossal balance as 0.00.
     */
    const val MAX_PERIODS: Int = 1_000_000

    /**
     * Largest growth factor evaluated, expressed as its natural logarithm.
     *
     * e^1000 has 435 digits, which is already past the point of meaning anything. The bound is
     * on the exponent rather than on any single input because it is the *product* that hurts:
     * [money] expands a result to full decimal notation, so a growth factor with a hugely
     * negative scale materialises hundreds of thousands of digits on the main thread.
     */
    private const val MAX_GROWTH_EXPONENT: Double = 1000.0

    /** Rounds to whole currency subunits, the way a bank statement would. */
    fun money(value: BigDecimal, scale: Int = 2): BigDecimal =
        value.setScale(scale, RoundingMode.HALF_EVEN)

    // ------------------------------------------------------------------ loans

    /** One line of an amortisation schedule. */
    data class Instalment(
        val number: Int,
        val payment: BigDecimal,
        val principal: BigDecimal,
        val interest: BigDecimal,
        val balance: BigDecimal,
    )

    /** A loan, fully worked out. */
    data class Loan(
        val instalment: BigDecimal,
        val totalPaid: BigDecimal,
        val totalInterest: BigDecimal,
        val schedule: List<Instalment>,
    )

    /**
     * Equated instalments for a loan.
     *
     * Two things here are correctness requirements rather than niceties:
     *
     * 1. A zero rate must not divide by zero. The annuity formula has `(1+r)^n − 1` in its
     *    denominator, which is exactly zero when `r` is, and the right answer there is the
     *    trivial one — principal spread evenly.
     * 2. The **final instalment absorbs the accumulated rounding**. Each line is rounded to
     *    the cent, those roundings do not cancel, and a schedule whose principal column
     *    does not sum to the principal is simply wrong. Hoping the errors cancel is not a
     *    strategy; making the last payment settle the remaining balance is.
     */
    fun loan(principal: BigDecimal, annualRatePercent: BigDecimal, months: Int): Loan {
        require(months in 1..MAX_LOAN_MONTHS) {
            "a loan term must be 1 to $MAX_LOAN_MONTHS months, not $months"
        }
        require(principal.signum() > 0) { "principal must be positive" }

        val monthlyRate = annualRatePercent.divide(HUNDRED, MATH).divide(BigDecimal(12), MATH)

        val rawInstalment = if (monthlyRate.signum() == 0) {
            principal.divide(BigDecimal(months), MATH)
        } else {
            val growth = BigDecimal.ONE.add(monthlyRate).pow(months, MATH)
            principal.multiply(monthlyRate, MATH)
                .multiply(growth, MATH)
                .divide(growth.subtract(BigDecimal.ONE), MATH)
        }
        val instalment = money(rawInstalment)

        val schedule = ArrayList<Instalment>(months)
        var balance = principal
        var totalPaid = BigDecimal.ZERO

        for (n in 1..months) {
            val interest = money(balance.multiply(monthlyRate, MATH))
            var payment = instalment
            var principalPart = money(payment.subtract(interest))

            if (n == months || principalPart >= balance) {
                // Settle exactly. This is what makes the principal column sum to the
                // principal and the final balance land on zero rather than on a few cents.
                principalPart = balance
                payment = money(principalPart.add(interest))
            }

            balance = money(balance.subtract(principalPart))
            totalPaid = totalPaid.add(payment)
            schedule += Instalment(n, payment, principalPart, interest, balance)
            if (balance.signum() == 0 && n < months) break
        }

        return Loan(
            instalment = instalment,
            totalPaid = money(totalPaid),
            totalInterest = money(totalPaid.subtract(principal)),
            schedule = schedule,
        )
    }

    // ------------------------------------------------------------------ compound interest

    /** How often interest is added to the balance. */
    enum class Compounding(val perYear: Int) {
        ANNUAL(1), SEMIANNUAL(2), QUARTERLY(4), MONTHLY(12), DAILY(365),

        /**
         * Continuous compounding: `A = P·e^(rt)`.
         *
         * A genuinely different formula, not a large [perYear]. Approximating it with, say,
         * 100000 periods is both slower and less accurate than just using the exponential.
         */
        CONTINUOUS(0),
    }

    /**
     * What an investment grew to, and how much of that growth was actually interest.
     *
     * [interest] is reported separately because "balance − principal" is not the interest as
     * soon as there are contributions: it includes every payment the investor made. Labelling
     * that figure "total interest" overstates the return by the whole contributed sum.
     */
    data class Growth(
        val balance: BigDecimal,
        val contributed: BigDecimal,
        val interest: BigDecimal,
    )

    /**
     * Balance after [years], with optional level contributions each period.
     *
     * Null means the inputs describe a number this calculator will not compute — too many
     * periods, or a growth factor whose decimal expansion runs to hundreds of thousands of
     * digits. Returning null rather than clamping matters: a clamped answer is a wrong answer
     * presented as a right one, whereas the screen can say the inputs are out of range.
     *
     * Contributions and [Compounding.CONTINUOUS] are refused together rather than silently
     * ignored. Continuous compounding has no period, so "contribution per period" has no
     * meaning in it, and quietly dropping the contribution reports a balance that omits
     * everything the investor put in.
     */
    fun compoundGrowth(
        principal: BigDecimal,
        annualRatePercent: BigDecimal,
        years: BigDecimal,
        compounding: Compounding,
        contributionPerPeriod: BigDecimal = BigDecimal.ZERO,
    ): Growth? {
        require(compounding != Compounding.CONTINUOUS || contributionPerPeriod.signum() == 0) {
            "continuous compounding has no period to contribute in"
        }
        if (years.signum() < 0) return null
        val rate = annualRatePercent.divide(HUNDRED, MATH)

        if (compounding == Compounding.CONTINUOUS) {
            val exponent = rate.multiply(years, MATH)
            val magnitude = exponent.toDouble()
            if (!magnitude.isFinite() || magnitude > MAX_GROWTH_EXPONENT) return null
            val balance = money(principal.multiply(exp(exponent, MATH), MATH))
            return Growth(
                balance = balance,
                contributed = BigDecimal.ZERO,
                interest = money(balance.subtract(principal)),
            )
        }

        val n = compounding.perYear
        val periods = periodCount(years, n) ?: return null
        val periodRate = rate.divide(BigDecimal(n), MATH)
        val base = BigDecimal.ONE.add(periodRate)
        // A rate at or below −100% per period is not a growth rate; `pow` on a non-positive
        // base would alternate sign rather than produce a balance.
        if (base.signum() <= 0) return null
        val exponent = periods * ln(base.toDouble())
        if (!exponent.isFinite() || exponent > MAX_GROWTH_EXPONENT) return null

        val growth = base.pow(periods, MATH)
        val fromPrincipal = principal.multiply(growth, MATH)
        val contributed = contributionPerPeriod.multiply(BigDecimal(periods), MATH)
        val fromContributions = if (contributionPerPeriod.signum() == 0) {
            BigDecimal.ZERO
        } else if (periodRate.signum() == 0) {
            contributed
        } else {
            contributionPerPeriod
                .multiply(growth.subtract(BigDecimal.ONE), MATH)
                .divide(periodRate, MATH)
        }
        val balance = money(fromPrincipal.add(fromContributions))
        return Growth(
            balance = balance,
            contributed = money(contributed),
            interest = money(balance.subtract(principal).subtract(contributed)),
        )
    }

    /** Balance after [years]. Convenience over [compoundGrowth] for in-range inputs. */
    fun compoundInterest(
        principal: BigDecimal,
        annualRatePercent: BigDecimal,
        years: BigDecimal,
        compounding: Compounding,
        contributionPerPeriod: BigDecimal = BigDecimal.ZERO,
    ): BigDecimal = requireNotNull(
        compoundGrowth(principal, annualRatePercent, years, compounding, contributionPerPeriod),
    ) { "these inputs are outside the range this calculator evaluates" }.balance

    /**
     * How many whole compounding periods [years] contains, or null if that is too many.
     *
     * Deliberately not `BigDecimal.toInt()`. That resolves to `Number.intValue()`, which is
     * documented to keep only the low 32 bits rather than throwing, so 3.65e9 periods arrives
     * as a negative count and every check downstream sees a plausible small number.
     *
     * A part-period is dropped, because discrete compounding only credits interest at the end
     * of a period: ten and a half years compounded annually earns ten years of interest.
     */
    private fun periodCount(years: BigDecimal, perYear: Int): Int? {
        val whole = years.multiply(BigDecimal(perYear))
            .setScale(0, RoundingMode.FLOOR)
            .toBigInteger()
        if (whole.signum() < 0 || whole > BigInteger.valueOf(MAX_PERIODS.toLong())) return null
        return whole.toInt()
    }

    /**
     * `e^x` computed in [BigDecimal].
     *
     * Not `Math.exp`. A Double overflows to positive infinity above about 709.78, and
     * `BigDecimal(Double.POSITIVE_INFINITY)` throws `NumberFormatException` rather than
     * returning anything — so continuous compounding used to die on the keystroke that
     * crossed the threshold, from inside composition. A Double also carries only seventeen
     * significant digits, which cannot pin the cents of a balance past about 1e15 even when
     * it does not overflow.
     *
     * The argument is halved until the Taylor series converges quickly, then the result is
     * squared back. Squaring doubles the relative error each time, so the series is run with
     * enough guard digits to pay for every halving.
     */
    private fun exp(x: BigDecimal, mc: MathContext): BigDecimal {
        if (x.signum() == 0) return BigDecimal.ONE
        if (x.signum() < 0) return BigDecimal.ONE.divide(exp(x.negate(), mc), mc)

        var halvings = 0
        var reduced = x
        // Halving is exact in decimal, so no rounding creeps in before the series starts.
        while (reduced.compareTo(BigDecimal.ONE) > 0) {
            reduced = reduced.divide(TWO)
            halvings++
        }
        val working = MathContext(mc.precision + halvings + 10, mc.roundingMode)

        var term = BigDecimal.ONE
        var sum = BigDecimal.ONE
        var i = 1
        while (i < 1000) {
            term = term.multiply(reduced, working).divide(BigDecimal(i), working)
            if (term.signum() == 0) break
            val next = sum.add(term, working)
            if (next.compareTo(sum) == 0) break
            sum = next
            i++
        }
        repeat(halvings) { sum = sum.multiply(sum, working) }
        return sum.round(mc)
    }

    // ------------------------------------------------------------------ tip

    /** A bill split, with the odd cents accounted for rather than lost. */
    data class Split(
        val tip: BigDecimal,
        val total: BigDecimal,
        val perPerson: BigDecimal,
        /** How many people pay one subunit more, so the parts sum to [total] exactly. */
        val peoplePayingExtra: Int,
    )

    /**
     * Tip and split.
     *
     * The remainder matters: three people splitting 10.00 cannot each pay 3.33, because that
     * is 9.99. Rather than quietly losing a cent, the remainder is reported so the UI can
     * say "2 people pay 3.34".
     */
    fun tip(
        bill: BigDecimal,
        tipPercent: BigDecimal,
        people: Int = 1,
        roundUpTotal: Boolean = false,
    ): Split {
        require(people >= 1) { "at least one person must pay" }
        val rawTip = bill.multiply(tipPercent, MATH).divide(HUNDRED, MATH)
        var total = money(bill.add(rawTip))
        if (roundUpTotal) total = total.setScale(0, RoundingMode.CEILING).setScale(2)
        val tipAmount = money(total.subtract(bill))

        val subunits = total.movePointRight(2).toBigInteger()
        val headcount = java.math.BigInteger.valueOf(people.toLong())
        val base = subunits.divide(headcount)
        val remainder = subunits.subtract(base.multiply(headcount)).toInt()

        return Split(
            tip = tipAmount,
            total = total,
            perPerson = BigDecimal(base).movePointLeft(2).setScale(2),
            peoplePayingExtra = remainder,
        )
    }

    // ------------------------------------------------------------------ discount

    /**
     * Applies discounts one after another.
     *
     * Successive discounts do not add: 20% then 10% leaves 72% of the price, a 28% discount,
     * not 30%. This is the single most common piece of shop arithmetic people get wrong, so
     * the function takes a list and composes them properly rather than summing.
     */
    fun successiveDiscount(price: BigDecimal, percentages: List<BigDecimal>): Discount {
        var remaining = price
        for (percent in percentages) {
            val keep = HUNDRED.subtract(percent).divide(HUNDRED, MATH)
            remaining = remaining.multiply(keep, MATH)
        }
        val finalPrice = money(remaining)
        val saved = money(price.subtract(finalPrice))
        val effective = if (price.signum() == 0) {
            BigDecimal.ZERO
        } else {
            money(saved.multiply(HUNDRED, MATH).divide(price, MATH))
        }
        return Discount(finalPrice, saved, effective)
    }

    /** The result of one or more successive discounts. */
    data class Discount(
        val finalPrice: BigDecimal,
        val saved: BigDecimal,
        val effectivePercent: BigDecimal,
    )

    // ------------------------------------------------------------------ tax

    /** Adds [ratePercent] tax to a net amount. */
    fun addTax(net: BigDecimal, ratePercent: BigDecimal): TaxBreakdown {
        val tax = money(net.multiply(ratePercent, MATH).divide(HUNDRED, MATH))
        return TaxBreakdown(net = money(net), tax = tax, gross = money(net.add(tax)))
    }

    /**
     * Extracts [ratePercent] tax from a gross amount.
     *
     * The inverse of [addTax], and tested as such: adding then removing the same rate has to
     * return the original figure, which a naive "multiply by the rate again" does not.
     */
    fun removeTax(gross: BigDecimal, ratePercent: BigDecimal): TaxBreakdown {
        val divisor = HUNDRED.add(ratePercent).divide(HUNDRED, MATH)
        val net = money(gross.divide(divisor, MATH))
        return TaxBreakdown(net = net, tax = money(gross.subtract(net)), gross = money(gross))
    }

    /** Net, tax and gross for one transaction. */
    data class TaxBreakdown(val net: BigDecimal, val tax: BigDecimal, val gross: BigDecimal)
}
