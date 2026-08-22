package app.numera.calculator.feature.dates

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Calendar arithmetic, checked on the cases where naive implementations go wrong.
 *
 * The leap-year rules and the month-end clamp are the two places a hand-rolled date
 * calculator produces an answer that is off by exactly one day, which is precisely the
 * error nobody notices until a contract date is wrong.
 */
class DateMathTest {

    private fun d(text: String) = LocalDate.parse(text)

    @Test
    fun `the twentieth century contained 36524 days`() {
        // 1900 is not a leap year and 2000 is; getting either rule wrong shifts this by one.
        assertEquals(36524L, DateMath.difference(d("1900-01-01"), d("2000-01-01")).totalDays)
    }

    @Test
    fun `a difference is reported in days and in calendar units at once`() {
        val diff = DateMath.difference(d("2024-01-15"), d("2025-03-20"))
        assertEquals(1, diff.years)
        assertEquals(2, diff.months)
        assertEquals(5, diff.days)
        assertEquals(430L, diff.totalDays)
        assertEquals(61L, diff.totalWeeks)
        assertEquals(3L, diff.remainingDaysAfterWeeks)
    }

    @Test
    fun `the order of the two dates does not matter`() {
        val forward = DateMath.difference(d("2020-01-01"), d("2020-12-31"))
        val backward = DateMath.difference(d("2020-12-31"), d("2020-01-01"))
        assertEquals(forward, backward)
    }

    @Test
    fun `adding a month to the end of January clamps into February`() {
        assertEquals(d("2024-02-29"), DateMath.add(d("2024-01-31"), months = 1))
        assertEquals(d("2023-02-28"), DateMath.add(d("2023-01-31"), months = 1))
    }

    @Test
    fun `adding a year to a leap day clamps to the twenty eighth`() {
        assertEquals(d("2025-02-28"), DateMath.add(d("2024-02-29"), years = 1))
        // And four years on it lands back on a real leap day.
        assertEquals(d("2028-02-29"), DateMath.add(d("2024-02-29"), years = 4))
    }

    @Test
    fun `subtraction mirrors addition`() {
        assertEquals(d("2024-01-01"), DateMath.subtract(d("2024-03-01"), months = 2))
        assertEquals(d("2023-12-25"), DateMath.subtract(d("2024-01-01"), days = 7))
        assertEquals(d("2023-12-18"), DateMath.subtract(d("2024-01-01"), weeks = 2))
    }

    @Test
    fun `business days exclude weekends and are inclusive of both ends`() {
        // Monday 2024-01-01 through Friday 2024-01-05 is five working days.
        assertEquals(5L, DateMath.businessDays(d("2024-01-01"), d("2024-01-05")))
        // Adding the weekend adds nothing.
        assertEquals(5L, DateMath.businessDays(d("2024-01-01"), d("2024-01-07")))
        // A full fortnight of weekdays.
        assertEquals(10L, DateMath.businessDays(d("2024-01-01"), d("2024-01-12")))
    }

    @Test
    fun `a holiday on a weekday is removed but one on a weekend is not double counted`() {
        val weekdayHoliday = DateMath.businessDays(
            d("2024-01-01"), d("2024-01-05"), holidays = setOf(d("2024-01-03")),
        )
        assertEquals(4L, weekdayHoliday)

        // 2024-01-06 is a Saturday. It was never counted, so excluding it changes nothing.
        val weekendHoliday = DateMath.businessDays(
            d("2024-01-01"), d("2024-01-07"), holidays = setOf(d("2024-01-06")),
        )
        assertEquals(5L, weekendHoliday)
    }

    @Test
    fun `the weekend can be moved for a different working week`() {
        val fridaySaturday = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
        assertEquals(
            5L,
            DateMath.businessDays(d("2024-01-01"), d("2024-01-07"), weekend = fridaySaturday),
        )
    }

    @Test
    fun `age counts completed years and finds the next birthday`() {
        val age = DateMath.age(birth = d("1990-06-15"), on = d("2024-06-14"))
        assertEquals(33, age.years)
        assertEquals(d("2024-06-15"), age.nextBirthday)
        assertEquals(1L, age.daysUntilNextBirthday)

        val onBirthday = DateMath.age(birth = d("1990-06-15"), on = d("2024-06-15"))
        assertEquals(34, onBirthday.years)
        // On the day itself the next one is a full year away, not today.
        assertEquals(d("2025-06-15"), onBirthday.nextBirthday)
    }

    @Test
    fun `someone born on a leap day still has a birthday every year`() {
        val age = DateMath.age(birth = d("2000-02-29"), on = d("2023-03-01"))
        assertEquals(23, age.years)
        assertEquals(d("2024-02-29"), age.nextBirthday)
    }

    /**
     * The closed form has to agree with the day-by-day count it replaced, and the ragged
     * tail of the range is where a week-count formula goes wrong: it depends on which
     * weekday the range starts on, and getting that wrong is off by one or two, which
     * nobody notices.
     */
    @Test
    fun `the working-day count agrees with counting the days one at a time`() {
        var start = d("2024-01-01")
        // Seven starting weekdays times a fortnight of lengths covers every combination of
        // start weekday and leftover days.
        repeat(7) {
            for (length in 0L..14L) {
                val end = start.plusDays(length)
                assertEquals(
                    "$start..$end",
                    countOneAtATime(start, end),
                    DateMath.businessDays(start, end),
                )
            }
            start = start.plusDays(1)
        }
    }

    /**
     * The span the date picker allows end to end, which the Business days tab evaluates
     * straight from composition. Seventy-three thousand days is where the accumulated `+1`
     * of a per-day loop would show up, and where the closed form has to still be exact.
     */
    @Test
    fun `a two century span is counted without walking it`() {
        val start = d("1900-01-01")
        val end = d("2100-01-01")
        assertEquals(countOneAtATime(start, end), DateMath.businessDays(start, end))
    }

    @Test
    fun `holidays outside the range are ignored`() {
        assertEquals(
            5L,
            DateMath.businessDays(
                d("2024-01-01"), d("2024-01-05"),
                holidays = setOf(d("2023-12-25"), d("2024-01-08")),
            ),
        )
    }

    /** The reference implementation the closed form replaced. */
    private fun countOneAtATime(start: LocalDate, end: LocalDate): Long {
        val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        var count = 0L
        var cursor = start
        while (!cursor.isAfter(end)) {
            if (cursor.dayOfWeek !in weekend) count++
            cursor = cursor.plusDays(1)
        }
        return count
    }
}
