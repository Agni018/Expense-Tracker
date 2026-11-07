package com.expensetracker.app.ui.common

import com.expensetracker.app.domain.model.DateRange
import com.expensetracker.app.domain.model.PeriodPreset
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object Fmt {
    val zone: ZoneId get() = ZoneId.systemDefault()
    private val day = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val dayShort = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val weekday = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val month = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    private val monthShort = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)

    fun date(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    fun dateTime(millis: Long): LocalDateTime = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    fun day(d: LocalDate): String = d.format(day)
    fun dayShort(d: LocalDate): String = d.format(dayShort)
    fun time(millis: Long): String = dateTime(millis).format(time)
    fun dateAndTime(millis: Long): String = "${day(date(millis))}, ${time(millis)}"
    fun month(d: LocalDate): String = d.format(month)
    fun monthShort(d: LocalDate): String = d.format(monthShort)

    /** "Today", "Yesterday", "Mon, 3 Nov" or "3 Nov 2025" for older years. */
    fun dayHeader(d: LocalDate, today: LocalDate = LocalDate.now(zone)): String = when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.year == today.year -> d.format(weekday)
        else -> day(d)
    }

    fun relative(millis: Long): String {
        if (millis <= 0) return "never"
        val minutes = ChronoUnit.MINUTES.between(Instant.ofEpochMilli(millis), Instant.now())
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} h ago"
            else -> dayHeader(date(millis))
        }
    }

    fun range(r: DateRange): String = when {
        r.start == r.endInclusive -> dayHeader(r.start)
        r.start.dayOfMonth == 1 && r.endInclusive == r.start.withDayOfMonth(r.start.lengthOfMonth()) -> month(r.start)
        r.start.year == r.endInclusive.year -> "${dayShort(r.start)} – ${day(r.endInclusive)}"
        else -> "${day(r.start)} – ${day(r.endInclusive)}"
    }
}

/** A selected reporting period: a preset or a custom date range. */
data class PeriodSelection(val preset: PeriodPreset = PeriodPreset.THIS_MONTH, val custom: DateRange? = null) {
    fun range(today: LocalDate = LocalDate.now(Fmt.zone)): DateRange =
        if (preset == PeriodPreset.CUSTOM && custom != null) custom else preset.range(today)
}
