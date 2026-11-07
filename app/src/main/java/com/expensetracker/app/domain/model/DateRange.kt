package com.expensetracker.app.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    fun startMillis(zone: ZoneId): Long = start.atStartOfDay(zone).toInstant().toEpochMilli()
    fun endMillisExclusive(zone: ZoneId): Long = endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val days: Long get() = endInclusive.toEpochDay() - start.toEpochDay() + 1

    /** The equally long range immediately before this one (used for comparisons). */
    fun previous(): DateRange {
        val firstOfMonth = start.dayOfMonth == 1
        val lastOfMonth = endInclusive == endInclusive.with(TemporalAdjusters.lastDayOfMonth())
        if (firstOfMonth && lastOfMonth && start.year == endInclusive.year && start.month == endInclusive.month) {
            val prev = YearMonth.from(start).minusMonths(1)
            return DateRange(prev.atDay(1), prev.atEndOfMonth())
        }
        val length = days
        return DateRange(start.minusDays(length), start.minusDays(1))
    }

    fun contains(date: LocalDate) = !date.isBefore(start) && !date.isAfter(endInclusive)

    companion object {
        fun month(ym: YearMonth) = DateRange(ym.atDay(1), ym.atEndOfMonth())
    }
}

enum class PeriodPreset(val label: String) {
    TODAY("Today"),
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    THIS_YEAR("This year"),
    CUSTOM("Custom");

    fun range(today: LocalDate): DateRange = when (this) {
        TODAY -> DateRange(today, today)
        THIS_WEEK -> DateRange(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today)
        THIS_MONTH -> DateRange(today.withDayOfMonth(1), YearMonth.from(today).atEndOfMonth())
        LAST_MONTH -> DateRange.month(YearMonth.from(today).minusMonths(1))
        THIS_YEAR -> DateRange(today.withDayOfYear(1), today.with(TemporalAdjusters.lastDayOfYear()))
        CUSTOM -> DateRange(today.withDayOfMonth(1), today)
    }
}
