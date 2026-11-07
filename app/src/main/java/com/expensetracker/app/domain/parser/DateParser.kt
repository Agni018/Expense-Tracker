package com.expensetracker.app.domain.parser

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Finds dates in free text (SMS, notifications, statement cells). Indian conventions are
 * day-first; month-first is only used when the caller has detected it for a whole column.
 */
object DateParser {

    data class Found(val date: LocalDate, val time: LocalTime?, val range: IntRange)

    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
    private const val MON =
        "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)(?![a-z])\\.?"

    private val isoRe = Regex("(?<!\\d)(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?!\\d)")
    private val numericRe = Regex("(?<![\\d.,])(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4}|\\d{2})(?![\\d,]|\\.\\d)")
    private val dayMonRe = Regex("(?i)(?<![\\da-z])(\\d{1,2})(?:st|nd|rd|th)?[\\s\\-/.,]*$MON(?:[\\s\\-/.,']*(\\d{4}|\\d{2}))?(?![\\d:])")
    private val monDayRe = Regex("(?i)(?<![a-z])$MON\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})(?!\\d)")
    private val shortNumericRe = Regex("(?i)\\bon\\s+(\\d{1,2})[-/](\\d{1,2})(?![\\d/-])")
    private val timeRe = Regex("(?i)(?<![\\d:])([01]?\\d|2[0-3]):([0-5]\\d)(?::([0-5]\\d))?\\s*(am|pm|hrs)?(?!\\d)")

    /**
     * Returns the earliest plausible date in [text]. Dates without a year take it from [reference].
     */
    fun findInText(
        text: String,
        reference: LocalDate,
        dayFirst: Boolean = true,
        earliest: LocalDate = reference.minusYears(15),
        latest: LocalDate = reference.plusDays(1),
    ): Found? {
        val found = ArrayList<Found>()
        isoRe.findAll(text).forEach { m ->
            date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { found += Found(it, null, m.range) }
        }
        numericRe.findAll(text).forEach { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val y = year(m.groupValues[3])
            val d = if (dayFirst) date(y, b, a) ?: date(y, a, b) else date(y, a, b) ?: date(y, b, a)
            d?.let { found += Found(it, null, m.range) }
        }
        dayMonRe.findAll(text).forEach { m ->
            val month = months[m.groupValues[2].lowercase().take(3)] ?: return@forEach
            val day = m.groupValues[1].toInt()
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { year(it) }
            val d = if (y != null) date(y, month, day) else inferYear(month, day, reference)
            d?.let { found += Found(it, null, m.range) }
        }
        monDayRe.findAll(text).forEach { m ->
            val month = months[m.groupValues[1].lowercase().take(3)] ?: return@forEach
            date(m.groupValues[3].toInt(), month, m.groupValues[2].toInt())?.let { found += Found(it, null, m.range) }
        }
        if (found.isEmpty()) {
            shortNumericRe.findAll(text).forEach { m ->
                val day = m.groupValues[1].toInt()
                val month = m.groupValues[2].toInt()
                inferYear(month, day, reference)?.let { found += Found(it, null, m.groups[1]!!.range.first..m.range.last) }
            }
        }
        val best = found
            .filter { !it.date.isAfter(latest) && !it.date.isBefore(earliest) }
            .minByOrNull { it.range.first }
            ?: return null
        return best.copy(time = findTime(text, best.range.last + 1))
    }

    /** Parses a whole cell value (statement date column). */
    fun parseDate(value: String, dayFirst: Boolean = true, reference: LocalDate = LocalDate.now()): LocalDate? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val found = findInText(
            trimmed, reference, dayFirst,
            earliest = LocalDate.of(1990, 1, 1),
            latest = reference.plusDays(31),
        ) ?: return null
        // A date cell starts with its date; otherwise it is probably a narration that mentions one.
        if (found.range.first > 3) return null
        return found.date
    }

    fun findTime(text: String, from: Int = 0): LocalTime? {
        if (from < text.length) {
            // Time immediately after the date ("2026-11-04:10:15:12", "04-11-26, 10:15").
            val rest = text.substring(from)
            val adjacent = Regex("^[\\s:,T@at]{0,4}").find(rest)?.value?.length ?: 0
            timeRe.find(rest.substring(adjacent))?.takeIf { it.range.first == 0 }?.let { return toTime(it) }
        }
        return timeRe.find(text)?.let { toTime(it) }
    }

    /**
     * Alerts usually arrive within minutes, so the written date is only trusted when it is
     * up to three days before delivery (late alerts); anything else is likely another date
     * mentioned in the text (bill date, due date) and the delivery time is kept.
     */
    fun resolveTimestamp(found: Found?, receivedAt: Long, zone: ZoneId): Long {
        if (found == null) return receivedAt
        val receivedDate = Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalDate()
        if (found.date == receivedDate) return receivedAt
        if (found.date.isAfter(receivedDate) || found.date.isBefore(receivedDate.minusDays(3))) return receivedAt
        val time = found.time ?: LocalTime.NOON
        return LocalDateTime.of(found.date, time).atZone(zone).toInstant().toEpochMilli()
    }

    private fun toTime(m: MatchResult): LocalTime? {
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        val second = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        when (m.groupValues[4].lowercase()) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        return try { LocalTime.of(hour, minute, second) } catch (e: DateTimeException) { null }
    }

    private fun year(text: String): Int = if (text.length == 2) 2000 + text.toInt() else text.toInt()

    private fun date(y: Int, m: Int, d: Int): LocalDate? =
        if (y !in 1990..2100 || m !in 1..12 || d !in 1..31) null
        else try { LocalDate.of(y, m, d) } catch (e: DateTimeException) { null }

    private fun inferYear(month: Int, day: Int, reference: LocalDate): LocalDate? {
        val candidate = date(reference.year, month, day) ?: return null
        return if (candidate.isAfter(reference.plusDays(1))) date(reference.year - 1, month, day) else candidate
    }
}
