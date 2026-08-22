package app.numera.calculator.feature.dates

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit

/**
 * A gap between two dates, reported both ways at once.
 *
 * Both answers are given because they answer different questions and users expect the pair:
 * "how many days until the exam" wants [totalDays], and "how old is this contract" wants
 * the calendar breakdown. Reporting only one of them makes the other look wrong.
 */
data class DateDifference(
    val totalDays: Long,
    val years: Int,
    val months: Int,
    val days: Int,
    val totalWeeks: Long,
    val remainingDaysAfterWeeks: Long,
)

/** Date arithmetic on [LocalDate], which minSdk 31 provides natively — no desugaring. */
object DateMath {

    /**
     * Largest magnitude accepted for a single field of [add] or [subtract].
     *
     * The offsets come from free-text fields, and `LocalDate` refuses to leave the
     * −999999999..999999999 proleptic year range by throwing `DateTimeException` from inside
     * `plusYears`/`ofEpochDay`. That exception is raised while the screen is composing, so an
     * unbounded field kills the app on the keystroke that crosses the boundary. A hundred
     * thousand of any unit stays far inside the range even with all four fields at the limit
     * at once, which is why one bound can serve all of them.
     */
    const val MAX_OFFSET: Int = 100_000

    private const val DAYS_IN_WEEK: Int = 7

    /** The gap between [start] and [end], in both total days and calendar units. */
    fun difference(start: LocalDate, end: LocalDate): DateDifference {
        val from = minOf(start, end)
        val to = maxOf(start, end)
        val period = Period.between(from, to)
        val totalDays = ChronoUnit.DAYS.between(from, to)
        return DateDifference(
            totalDays = totalDays,
            years = period.years,
            months = period.months,
            days = period.days,
            totalWeeks = totalDays / 7,
            remainingDaysAfterWeeks = totalDays % 7,
        )
    }

    /**
     * Adds a calendar duration to [date].
     *
     * Month and year arithmetic clamps rather than overflowing: 31 January plus one month is
     * 28 or 29 February, not 2 or 3 March. [Period] already has this behaviour and it is the
     * convention every calendar app uses, but it surprises people often enough that the UI
     * says so out loud.
     *
     * Each field is bounded by [MAX_OFFSET] and refused outside it. Letting an arbitrary Int
     * through means `LocalDate` throws `DateTimeException` instead, from a call site the
     * caller cannot usefully recover at.
     */
    fun add(
        date: LocalDate,
        years: Int = 0,
        months: Int = 0,
        weeks: Int = 0,
        days: Int = 0,
    ): LocalDate {
        requireInRange(years, months, weeks, days)
        return date
            .plusYears(years.toLong())
            .plusMonths(months.toLong())
            .plusWeeks(weeks.toLong())
            .plusDays(days.toLong())
    }

    fun subtract(
        date: LocalDate,
        years: Int = 0,
        months: Int = 0,
        weeks: Int = 0,
        days: Int = 0,
    ): LocalDate {
        requireInRange(years, months, weeks, days)
        return date
            .minusYears(years.toLong())
            .minusMonths(months.toLong())
            .minusWeeks(weeks.toLong())
            .minusDays(days.toLong())
    }

    private fun requireInRange(vararg offsets: Int) {
        for (offset in offsets) {
            require(offset >= -MAX_OFFSET && offset <= MAX_OFFSET) {
                "offset $offset is outside ±$MAX_OFFSET"
            }
        }
    }

    /**
     * Counts working days in `[start, end]`, inclusive of both ends.
     *
     * Computed from the week count, not by walking the range. The screen calls this straight
     * from composition, and the date picker's default range spans two centuries: a day-by-day
     * loop allocated one `LocalDate` per day — some seventy thousand of them — on the main
     * thread, on every recomposition, which is dropped frames rather than a wrong answer but
     * is just as visible.
     *
     * A holiday is only subtracted when it is inside the range *and* not already a weekend
     * day. Subtracting them unconditionally is what makes a naive closed form double-count a
     * holiday that falls on a Saturday.
     */
    fun businessDays(
        start: LocalDate,
        end: LocalDate,
        weekend: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
        holidays: Set<LocalDate> = emptySet(),
    ): Long {
        val from = minOf(start, end)
        val to = maxOf(start, end)
        val totalDays: Long = ChronoUnit.DAYS.between(from, to) + 1L
        val wholeWeeks: Long = totalDays / DAYS_IN_WEEK
        val leftover: Int = (totalDays % DAYS_IN_WEEK).toInt()

        // Every whole week contributes the same number of working days whatever day it
        // starts on; only the ragged tail depends on the starting weekday.
        var count: Long = wholeWeeks * (DAYS_IN_WEEK - weekend.size)
        var day: DayOfWeek = from.dayOfWeek
        repeat(leftover) {
            if (day !in weekend) count++
            day = day.plus(1L)
        }

        for (holiday in holidays) {
            if (holiday.isBefore(from) || holiday.isAfter(to)) continue
            if (holiday.dayOfWeek !in weekend) count--
        }
        return count
    }

    /**
     * Age at [on], plus how long until the next birthday.
     *
     * The two figures have to be derived from one convention or they contradict each other.
     * [Period.between] does not age a 29 February birth up until 1 March of a common year,
     * while a clamped anniversary lands on 28 February — so taking the clamped date as the
     * birthday reports a birthday on which the age line still says the person is a year
     * younger. Here the next birthday is defined as the first date on which
     * [Period.between] would report one more year, which is the same rule the age uses.
     */
    fun age(birth: LocalDate, on: LocalDate): Age {
        require(!birth.isAfter(on)) { "birth date is in the future" }
        val period = Period.between(birth, on)
        val nextAge = period.years + 1L
        var nextBirthday = birth.plusYears(nextAge)
        if (Period.between(birth, nextBirthday).years < nextAge) {
            // Only a leap-day birth in a common year reaches this: plusYears clamped to the
            // 28th, which Period does not yet count as a completed year.
            nextBirthday = nextBirthday.plusDays(1)
        }
        return Age(
            years = period.years,
            months = period.months,
            days = period.days,
            totalDays = ChronoUnit.DAYS.between(birth, on),
            nextBirthday = nextBirthday,
            daysUntilNextBirthday = ChronoUnit.DAYS.between(on, nextBirthday),
        )
    }

    /** Someone's age, and when they next have a birthday. */
    data class Age(
        val years: Int,
        val months: Int,
        val days: Int,
        val totalDays: Long,
        val nextBirthday: LocalDate,
        val daysUntilNextBirthday: Long,
    )
}
