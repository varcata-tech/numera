package app.numera.calculator.feature.dates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.numera.calculator.R
import app.numera.calculator.ui.common.ModeScaffold
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import kotlinx.coroutines.delay

/**
 * Date arithmetic: differences, offsets, working days and age.
 *
 * All of it runs on [LocalDate] rather than an instant. A date-only question has no time
 * zone and no daylight-saving transition in it, and pulling a wall clock into the
 * calculation is how "days between" ends up off by one for half the year.
 *
 * Dates are held as epoch days in `rememberSaveable` rather than as `LocalDate` in `remember`.
 * There is no ViewModel here and `MainActivity` declares no `configChanges`, so plain
 * `remember` state dies with the Activity: rotating the phone used to reset both pickers to
 * today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeScreen(onBack: () -> Unit) {
    var tab: Int by rememberSaveable { mutableStateOf(0) }
    val titles = listOf(
        R.string.dates_tab_difference,
        R.string.dates_tab_add,
        R.string.dates_tab_business,
        R.string.dates_tab_age,
    )

    ModeScaffold(title = stringResource(R.string.title_datetime), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { index, titleRes ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(stringResource(titleRes)) },
                    )
                }
            }
            when (tab) {
                0 -> DifferenceTab()
                1 -> AddSubtractTab()
                2 -> BusinessDaysTab()
                else -> AgeTab()
            }
        }
    }
}

@Composable
private fun DifferenceTab() {
    var startDay: Long by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var endDay: Long by rememberSaveable {
        mutableStateOf(LocalDate.now().plusDays(30).toEpochDay())
    }
    val start: LocalDate = LocalDate.ofEpochDay(startDay)
    val end: LocalDate = LocalDate.ofEpochDay(endDay)
    DateField(stringResource(R.string.dates_start), start) { startDay = it.toEpochDay() }
    DateField(stringResource(R.string.dates_end), end) { endDay = it.toEpochDay() }

    val difference = DateMath.difference(start, end)
    ResultCard {
        Text(
            text = stringResource(R.string.dates_total_days, difference.totalDays),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(
                R.string.dates_breakdown,
                difference.years, difference.months, difference.days,
            ),
        )
        Text(
            stringResource(
                R.string.dates_weeks,
                difference.totalWeeks, difference.remainingDaysAfterWeeks,
            ),
        )
    }
}

@Composable
private fun AddSubtractTab() {
    var dateDay: Long by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var years: String by rememberSaveable { mutableStateOf("0") }
    var months: String by rememberSaveable { mutableStateOf("0") }
    var weeks: String by rememberSaveable { mutableStateOf("0") }
    var days: String by rememberSaveable { mutableStateOf("0") }
    var adding: Boolean by rememberSaveable { mutableStateOf(true) }
    val date: LocalDate = LocalDate.ofEpochDay(dateDay)

    DateField(stringResource(R.string.dates_start), date) { dateDay = it.toEpochDay() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(R.string.dates_years), years, Modifier.weight(1f)) { years = it }
        NumberField(stringResource(R.string.dates_months), months, Modifier.weight(1f)) { months = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(R.string.dates_weeks_field), weeks, Modifier.weight(1f)) { weeks = it }
        NumberField(stringResource(R.string.dates_days), days, Modifier.weight(1f)) { days = it }
    }
    // Chips bound to `adding`, not a fixed Button/OutlinedButton pair. The pair was styled
    // by which button it was rather than by the flag, so tapping Subtract changed the
    // computed date while the controls went on presenting Add as the chosen mode — and the
    // result card is a bare date, so this row is the only thing that can say which
    // direction produced it.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = adding,
            onClick = { adding = true },
            label = { Text(stringResource(R.string.dates_add)) },
        )
        FilterChip(
            selected = !adding,
            onClick = { adding = false },
            label = { Text(stringResource(R.string.dates_subtract)) },
        )
    }

    val y = years.toOffsetOrNull()
    val m = months.toOffsetOrNull()
    val w = weeks.toOffsetOrNull()
    val d = days.toOffsetOrNull()
    // Said out loud rather than substituted with zero. The old `?: 0` showed an unchanged
    // date for a field the parser had quietly thrown away, which looks like an answer.
    if (y == null || m == null || w == null || d == null) {
        Notice(R.string.dates_out_of_range)
        return
    }
    val result = if (adding) {
        DateMath.add(date, y, m, w, d)
    } else {
        DateMath.subtract(date, y, m, w, d)
    }
    ResultCard {
        Text(text = result.formatted(), style = MaterialTheme.typography.headlineSmall)
        // Stated out loud because 31 January + 1 month = 29 February surprises people, even
        // though it is what every calendar application does.
        Text(
            text = stringResource(R.string.dates_clamp_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BusinessDaysTab() {
    var startDay: Long by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var endDay: Long by rememberSaveable {
        mutableStateOf(LocalDate.now().plusDays(30).toEpochDay())
    }
    val start: LocalDate = LocalDate.ofEpochDay(startDay)
    val end: LocalDate = LocalDate.ofEpochDay(endDay)
    DateField(stringResource(R.string.dates_start), start) { startDay = it.toEpochDay() }
    DateField(stringResource(R.string.dates_end), end) { endDay = it.toEpochDay() }
    ResultCard {
        Text(
            text = stringResource(
                R.string.dates_business_result,
                DateMath.businessDays(start, end),
            ),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.dates_exclude_weekend),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgeTab() {
    var birthDay: Long by rememberSaveable {
        mutableStateOf(LocalDate.now().minusYears(30).toEpochDay())
    }
    val birth: LocalDate = LocalDate.ofEpochDay(birthDay)
    var today: LocalDate by remember { mutableStateOf(LocalDate.now()) }
    // Re-read at every midnight. Captured once in a plain `remember`, the date never moved,
    // so an Age tab left on screen overnight kept reporting yesterday's age and yesterday's
    // count of days until the birthday. The wait is recomputed each turn rather than armed
    // once, so a clock change cannot leave the screen stuck on a stale day.
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalDateTime.now()
            val untilMidnight = Duration
                .between(now, now.toLocalDate().plusDays(1).atStartOfDay())
                .toMillis()
            delay(untilMidnight.coerceAtLeast(1_000L))
            today = LocalDate.now()
        }
    }
    DateField(stringResource(R.string.dates_birth), birth) { birthDay = it.toEpochDay() }

    if (birth.isAfter(today)) {
        Notice(R.string.dates_out_of_range)
        return
    }
    val age = DateMath.age(birth, today)
    ResultCard {
        Text(
            text = stringResource(R.string.dates_age_result, age.years, age.months, age.days),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(stringResource(R.string.dates_age_total, age.totalDays))
        Text(
            stringResource(
                R.string.dates_next_birthday,
                age.nextBirthday.formatted(),
                age.daysUntilNextBirthday,
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: LocalDate, onChange: (LocalDate) -> Unit) {
    var showing: Boolean by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { showing = true }, modifier = Modifier.fillMaxWidth()) {
        Text("$label: ${value.formatted()}")
    }
    if (showing) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showing = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        // The picker reports UTC midnight; reading it back in UTC is what
                        // keeps a user east of Greenwich from getting yesterday's date.
                        onChange(
                            Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate(),
                        )
                    }
                    showing = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showing = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

/**
 * A signed whole-number field.
 *
 * The minus sign is only accepted in front. The old filter took it anywhere, so "5-2" passed,
 * `toIntOrNull` returned null, and the caller's `?: 0` silently used zero — the card then
 * showed an unchanged date with nothing to say the field had been discarded.
 */
@Composable
private fun NumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text ->
            onChange(text.filterIndexed { index, c -> c.isDigit() || (c == '-' && index == 0) })
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/**
 * Reads one offset field, or null if [DateMath] would refuse it.
 *
 * An empty field and a lone minus sign are ordinary states while typing, so they read as
 * zero. Anything that does not fit an Int, or that exceeds [DateMath.MAX_OFFSET], is refused
 * here rather than handed to `plusYears`, which answers by throwing `DateTimeException` out
 * of composition and taking the app with it.
 */
private fun String.toOffsetOrNull(): Int? {
    if (isEmpty() || this == "-") return 0
    val parsed = toIntOrNull() ?: return null
    return if (parsed >= -DateMath.MAX_OFFSET && parsed <= DateMath.MAX_OFFSET) parsed else null
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

/**
 * Formats using the app locale's medium date style rather than a hardcoded pattern.
 *
 * The locale has to be passed in. `ofLocalizedDate` alone reads `Locale.getDefault()`, which
 * under a per-app language is not the locale the rest of the screen is using — so the fields
 * printed "25/08/2026" in Latin digits directly above a result reading "٣٠ يوم" in
 * Arabic-Indic, two numbering systems in one card. It is the same trap the `NonObservableLocale`
 * lint check exists for, reached without ever naming `Locale.getDefault()`.
 */
@Composable
private fun LocalDate.formatted(): String {
    val locale = LocalConfiguration.current.locales[0]
    return format(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            // withLocale alone is not enough, and the gap is easy to miss: it settles the
            // month names and the field order but not the digits, which come from
            // DecimalStyle and default to ASCII whatever the locale. Without this the Dates
            // screen printed "٣٠ يوم" one line under "26/08/2026" — two numbering systems in
            // one card, in the one place the app shows both.
            .withDecimalStyle(DecimalStyle.of(locale)),
    )
}
