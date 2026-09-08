package app.numera.calculator.feature.financial

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.numera.calculator.R
import app.numera.calculator.ui.common.ModeScaffold
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The financial calculators: loan, compound interest, tip, discount and tax.
 *
 * Everything is computed in [FinanceMath] on `BigDecimal`. No exchange rates are fetched and
 * no currency conversion is offered — the app holds no network permission, so the currency
 * shown is simply the device's own locale formatting.
 *
 * Every field is `rememberSaveable`. There is no ViewModel here and `MainActivity` declares no
 * `configChanges`, so plain `remember` state is destroyed with the Activity: rotating the
 * phone to read more of an amortisation schedule used to throw away the loan that produced it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinancialScreen(onBack: () -> Unit) {
    var tab: Int by rememberSaveable { mutableStateOf(0) }
    val tabState: SaveableStateHolder = rememberSaveableStateHolder()
    val titles = listOf(
        R.string.fin_tab_loan,
        R.string.fin_tab_interest,
        R.string.fin_tab_tip,
        R.string.fin_tab_discount,
        R.string.fin_tab_tax,
    )

    ModeScaffold(title = stringResource(R.string.title_financial), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                titles.forEachIndexed { index, titleRes ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(stringResource(titleRes)) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.fin_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Keyed by tab, for the reason CalculatorApp keys the mode `when` by route.
            // rememberSaveable only hands its value to the registry while it is still in
            // composition, and a tab that stops matching this `when` is simply forgotten —
            // so without a holder, filling in the loan and glancing at the tip tab threw the
            // loan away, which is exactly what this file's KDoc promises does not happen.
            tabState.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> LoanTab()
                    1 -> InterestTab()
                    2 -> TipTab()
                    3 -> DiscountTab()
                    else -> TaxTab()
                }
            }
        }
    }
}

@Composable
private fun LoanTab() {
    val separator = LocalConfiguration.current.locales[0].decimalSeparator()
    var principal: String by rememberSaveable { mutableStateOf("1000000") }
    var rate: String by rememberSaveable { mutableStateOf(seedAmount("8.5", separator)) }
    var months: String by rememberSaveable { mutableStateOf("240") }

    MoneyField(stringResource(R.string.fin_principal), principal) { principal = it }
    MoneyField(stringResource(R.string.fin_rate), rate) { rate = it }
    MoneyField(stringResource(R.string.fin_months), months, decimal = false) { months = it }

    val p = principal.toBigDecimalOrNull()
    val r = rate.toBigDecimalOrNull()
    val n = months.toIntOrNull()
    // Said out loud rather than returning silently. The early return used to take the result
    // card, the "Schedule" heading and every row off screen at once, which reads as a broken
    // app rather than as an unfinished number.
    if (p == null || r == null || n == null || p.signum() <= 0) {
        Notice(R.string.fin_invalid_input)
        return
    }
    if (n !in 1..FinanceMath.MAX_LOAN_MONTHS) {
        Notice(R.string.fin_out_of_range)
        return
    }

    val loan = FinanceMath.loan(p, r, n)
    ResultCard {
        Line(stringResource(R.string.fin_instalment), loan.instalment, emphasise = true)
        Line(stringResource(R.string.fin_total_paid), loan.totalPaid)
        Line(stringResource(R.string.fin_total_interest), loan.totalInterest)
    }
    Text(stringResource(R.string.fin_schedule), style = MaterialTheme.typography.titleSmall)
    // Capped in height so the schedule scrolls inside the page rather than making the whole
    // screen 240 rows tall.
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
        items(loan.schedule, key = { it.number }) { line ->
            Text(
                text = stringResource(
                    R.string.fin_schedule_line,
                    line.number,
                    line.payment.money(), line.principal.money(),
                    line.interest.money(), line.balance.money(),
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun InterestTab() {
    var principal: String by rememberSaveable { mutableStateOf("1000") }
    var rate: String by rememberSaveable { mutableStateOf("5") }
    var years: String by rememberSaveable { mutableStateOf("10") }
    var contribution: String by rememberSaveable { mutableStateOf("0") }
    // Held by name, not as the enum itself: the house rule for restoring an enum is to match
    // on name, so reordering or renaming a constant cannot resurrect as a different one.
    var compoundingName: String by rememberSaveable {
        mutableStateOf(FinanceMath.Compounding.ANNUAL.name)
    }
    val compounding: FinanceMath.Compounding =
        enumValues<FinanceMath.Compounding>().firstOrNull { it.name == compoundingName }
            ?: FinanceMath.Compounding.ANNUAL
    val periodic: Boolean = compounding != FinanceMath.Compounding.CONTINUOUS

    MoneyField(stringResource(R.string.fin_principal), principal) { principal = it }
    MoneyField(stringResource(R.string.fin_rate), rate) { rate = it }
    MoneyField(stringResource(R.string.fin_years), years) { years = it }
    // Hidden rather than ignored. Continuous compounding has no period, so a "contribution
    // per period" cannot enter the formula; leaving the field on screen invited the user to
    // fill it in and then showed a balance computed as though they had not.
    if (periodic) {
        MoneyField(stringResource(R.string.fin_contribution), contribution) { contribution = it }
    }

    Text(stringResource(R.string.fin_compounding), style = MaterialTheme.typography.labelMedium)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (option in FinanceMath.Compounding.entries) {
            FilterChip(
                selected = option == compounding,
                onClick = { compoundingName = option.name },
                label = { Text(stringResource(option.labelRes())) },
            )
        }
    }
    if (!periodic) {
        Text(
            text = stringResource(R.string.fin_contribution_continuous),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val p = principal.toBigDecimalOrNull()
    val r = rate.toBigDecimalOrNull()
    val y = years.toBigDecimalOrNull()
    val c = if (periodic) contribution.toOptionalBigDecimalOrNull() else BigDecimal.ZERO
    if (p == null || r == null || y == null || c == null) {
        Notice(R.string.fin_invalid_input)
        return
    }
    val growth = FinanceMath.compoundGrowth(p, r, y, compounding, c)
    if (growth == null) {
        Notice(R.string.fin_out_of_range)
        return
    }
    ResultCard {
        Line(stringResource(R.string.fin_final_balance), growth.balance, emphasise = true)
        // The interest comes from FinanceMath rather than from balance − principal, which
        // counts every contribution the investor made as interest they earned.
        Line(stringResource(R.string.fin_total_interest), growth.interest)
    }
}

@Composable
private fun TipTab() {
    var bill: String by rememberSaveable { mutableStateOf("80") }
    var percent: String by rememberSaveable { mutableStateOf("15") }
    var people: String by rememberSaveable { mutableStateOf("2") }
    var roundUp: Boolean by rememberSaveable { mutableStateOf(false) }

    MoneyField(stringResource(R.string.fin_bill), bill) { bill = it }
    MoneyField(stringResource(R.string.fin_tip_percent), percent) { percent = it }
    MoneyField(stringResource(R.string.fin_people), people, decimal = false) { people = it }
    // toggleable on the row, not onCheckedChange on the Switch: the switch alone is a 36dp
    // target with a 364px label beside it that did nothing at all when tapped, and TalkBack
    // read an unlabelled switch followed by an unrelated line of text. This is the same
    // shape SettingsScreen uses for its own switches.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = roundUp,
                role = Role.Switch,
                onValueChange = { roundUp = it },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = roundUp, onCheckedChange = null)
        Text(
            text = stringResource(R.string.fin_round_up),
            modifier = Modifier.padding(start = 12.dp),
        )
    }

    val b = bill.toBigDecimalOrNull()
    val pct = percent.toBigDecimalOrNull()
    val n = people.toIntOrNull()
    if (b == null || pct == null || n == null) {
        Notice(R.string.fin_invalid_input)
        return
    }
    // Refused, not raised to one. The old `coerceAtLeast(1)` answered "0 people" with a
    // full per-person figure and said nothing about having changed the question, which is
    // the one shape of wrong answer this screen must not produce.
    if (n < 1) {
        Notice(R.string.fin_out_of_range)
        return
    }
    val split = FinanceMath.tip(b, pct, n, roundUp)
    ResultCard {
        Line(stringResource(R.string.fin_tip_amount), split.tip)
        Line(stringResource(R.string.fin_total), split.total)
        Line(stringResource(R.string.fin_per_person), split.perPerson, emphasise = true)
        if (split.peoplePayingExtra > 0) {
            // Reported rather than hidden: three people splitting 10.00 cannot each pay
            // 3.33, and quietly losing the cent is how a bill fails to add up.
            Text(
                text = pluralStringResource(
                    R.plurals.fin_extra_cents,
                    split.peoplePayingExtra,
                    split.peoplePayingExtra,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiscountTab() {
    var price: String by rememberSaveable { mutableStateOf("100") }
    var first: String by rememberSaveable { mutableStateOf("20") }
    var second: String by rememberSaveable { mutableStateOf("10") }

    MoneyField(stringResource(R.string.fin_price), price) { price = it }
    MoneyField(stringResource(R.string.fin_discount_1), first) { first = it }
    MoneyField(stringResource(R.string.fin_discount_2), second) { second = it }

    val p = price.toBigDecimalOrNull()
    // Each discount is checked, not collected with listOfNotNull: dropping the one that did
    // not parse produced a confident final price computed from the other one alone.
    val d1 = first.toOptionalBigDecimalOrNull()
    val d2 = second.toOptionalBigDecimalOrNull()
    if (p == null || d1 == null || d2 == null) {
        Notice(R.string.fin_invalid_input)
        return
    }
    // Refused, not computed. A discount above 100% turns the price negative and the saving
    // larger than the price, and two of them multiply back into a positive price that looks
    // entirely reasonable — all three rendered in the same card as a correct answer, under a
    // note explaining how carefully the discounts were composed. Same shape as the tip tab's
    // headcount guard: an unaskable question gets no answer.
    if (!d1.isPercentage() || !d2.isPercentage()) {
        Notice(R.string.fin_out_of_range)
        return
    }
    val discounts = listOf(d1, d2).filter { it.signum() != 0 }
    val result = FinanceMath.successiveDiscount(p, discounts)
    ResultCard {
        Line(stringResource(R.string.fin_final_price), result.finalPrice, emphasise = true)
        Line(stringResource(R.string.fin_saved), result.saved)
        Line(stringResource(R.string.fin_effective), result.effectivePercent.percent())
        Text(
            text = stringResource(R.string.fin_successive_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TaxTab() {
    var amount: String by rememberSaveable { mutableStateOf("1000") }
    var rate: String by rememberSaveable { mutableStateOf("18") }
    var adding: Boolean by rememberSaveable { mutableStateOf(true) }

    MoneyField(stringResource(R.string.fin_amount), amount) { amount = it }
    MoneyField(stringResource(R.string.fin_tax_rate), rate) { rate = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = adding,
            onClick = { adding = true },
            label = { Text(stringResource(R.string.fin_add_tax)) },
        )
        FilterChip(
            selected = !adding,
            onClick = { adding = false },
            label = { Text(stringResource(R.string.fin_remove_tax)) },
        )
    }

    val a = amount.toBigDecimalOrNull()
    val r = rate.toBigDecimalOrNull()
    if (a == null || r == null) {
        Notice(R.string.fin_invalid_input)
        return
    }
    val breakdown = if (adding) FinanceMath.addTax(a, r) else FinanceMath.removeTax(a, r)
    ResultCard {
        Line(stringResource(R.string.fin_net), breakdown.net)
        Line(stringResource(R.string.fin_tax), breakdown.tax, emphasise = true)
        Line(stringResource(R.string.fin_gross), breakdown.gross)
    }
}

// ---------------------------------------------------------------------------- helpers

/**
 * A numeric field.
 *
 * Everything the field will accept is decided by [sanitiseAmount], including the length cap
 * that keeps a pasted thousand-digit amount out of the amortisation schedule. A field that
 * means a count rather than an amount is put on the plain number keypad with [decimal] off,
 * so the keyboard does not offer a '.' the field cannot use.
 */
@Composable
private fun MoneyField(
    label: String,
    value: String,
    decimal: Boolean = true,
    onChange: (String) -> Unit,
) {
    val separator = LocalConfiguration.current.locales[0].decimalSeparator()
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(sanitiseAmount(text, decimal, separator)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Says why the result card is missing, instead of leaving the screen looking broken. */
@Composable
private fun Notice(messageRes: Int) {
    Text(
        text = stringResource(messageRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ResultCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content() }
    }
}

@Composable
private fun Line(label: String, value: BigDecimal, emphasise: Boolean = false) {
    Line(label = label, value = value.money(), emphasise = emphasise)
}

/** The same row for a figure that is already a string, so it cannot drift from the others. */
@Composable
private fun Line(label: String, value: String, emphasise: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = if (emphasise) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
        )
    }
}

/**
 * Formats using the display locale's number conventions; no rate lookup is involved.
 *
 * Read through `LocalConfiguration`, not `Locale.getDefault()`. The latter is invisible to
 * Compose and, since per-app languages, need not even be the locale the surrounding labels
 * are drawn in — the screen would then pair French words with English digit grouping.
 */
@Composable
private fun BigDecimal.money(): String =
    formatAmount(this, LocalConfiguration.current.locales[0])

/** Formats a percentage the same way, so the two never disagree inside one card. */
@Composable
private fun BigDecimal.percent(): String =
    formatPercent(this, LocalConfiguration.current.locales[0])

/** Whether this reads as a share of something: 0 to 100 inclusive. */
private fun BigDecimal.isPercentage(): Boolean =
    signum() >= 0 && this <= BigDecimal("100")

/**
 * Reads a field back, in the same locale [MoneyField] wrote it in.
 *
 * Composable, and reading `LocalConfiguration`, for the reason [money] is: the field holds
 * the user's own separator, so parsing it against `Locale.getDefault()` would disagree with
 * the keypad the moment a per-app language differs from the system one.
 */
@Composable
private fun String.toBigDecimalOrNull(): BigDecimal? =
    parseAmount(this, LocalConfiguration.current.locales[0].decimalSeparator())

/** For fields that are allowed to be empty: blank is zero, but nonsense is still nothing. */
@Composable
private fun String.toOptionalBigDecimalOrNull(): BigDecimal? =
    if (isBlank()) BigDecimal.ZERO else toBigDecimalOrNull()

/**
 * The character this locale puts between the whole and fractional parts.
 *
 * One definition, used by the filter and by the parser, so the two can never disagree about
 * what the user is allowed to have typed.
 */
private fun Locale.decimalSeparator(): Char =
    DecimalFormatSymbols.getInstance(this).decimalSeparator

private fun FinanceMath.Compounding.labelRes(): Int = when (this) {
    FinanceMath.Compounding.ANNUAL -> R.string.fin_compound_annual
    FinanceMath.Compounding.SEMIANNUAL -> R.string.fin_compound_semiannual
    FinanceMath.Compounding.QUARTERLY -> R.string.fin_compound_quarterly
    FinanceMath.Compounding.MONTHLY -> R.string.fin_compound_monthly
    FinanceMath.Compounding.DAILY -> R.string.fin_compound_daily
    FinanceMath.Compounding.CONTINUOUS -> R.string.fin_compound_continuous
}
