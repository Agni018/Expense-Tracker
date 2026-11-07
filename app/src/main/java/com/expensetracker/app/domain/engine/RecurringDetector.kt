package com.expensetracker.app.domain.engine

import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.RecurringFrequency
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.domain.parser.MerchantDirectory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class RecurringCandidate(
    val merchantKey: String,
    val displayName: String,
    val categoryId: Long?,
    val frequency: RecurringFrequency,
    val intervalDays: Int,
    val expectedAmountMinor: Long,
    val lastAmountMinor: Long,
    val lastPaidAt: Long,
    val nextDueAt: Long,
    val occurrences: Int,
    val amountChanged: Boolean,
    val isSubscription: Boolean,
    val confidence: Float,
)

/**
 * Finds payments that repeat on a regular schedule with stable amounts. Expected dates are
 * only predictions: a payment is never assumed to have happened because its date passed.
 */
object RecurringDetector {

    private data class Pattern(val frequency: RecurringFrequency, val target: Int, val tolerance: Int)

    private val patterns = listOf(
        Pattern(RecurringFrequency.WEEKLY, 7, 2),
        Pattern(RecurringFrequency.MONTHLY, 30, 5),
        Pattern(RecurringFrequency.QUARTERLY, 91, 12),
        Pattern(RecurringFrequency.YEARLY, 365, 25),
    )

    fun detect(entries: List<LedgerEntry>, zone: ZoneId, today: LocalDate): List<RecurringCandidate> {
        val eligible = entries.filter {
            FinanceCalculator.counts(it) && it.direction == Direction.DEBIT && !it.merchant.isNullOrBlank() &&
                it.type in setOf(TxnType.EXPENSE, TxnType.INVESTMENT)
        }
        val result = ArrayList<RecurringCandidate>()
        for ((key, group) in eligible.groupBy { MerchantDirectory.compact(it.merchant!!) }) {
            if (key.isEmpty()) continue
            // Several payments within three days count as one occurrence.
            val occurrences = ArrayList<LedgerEntry>()
            for (e in group.sortedBy { it.timestamp }) {
                val last = occurrences.lastOrNull()
                if (last != null && e.timestamp - last.timestamp < 3 * 86_400_000L) {
                    occurrences[occurrences.size - 1] = if (e.amountMinor > last.amountMinor) e else last
                } else {
                    occurrences += e
                }
            }
            val known = MerchantDirectory.match(group.first().merchant)
            val isSubscription = known?.isSubscription == true
            val minCount = if (isSubscription) 2 else 3
            if (occurrences.size < minCount) continue

            val dates = occurrences.map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            val intervals = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }
            val median = intervals.sorted()[intervals.size / 2]
            val pattern = patterns.firstOrNull { abs(median - it.target) <= it.tolerance } ?: continue
            val regular = intervals.count { abs(it - median) <= pattern.tolerance }.toDouble() / intervals.size
            if (regular < 0.75) continue

            val amounts = occurrences.map { it.amountMinor }
            val medianAmount = amounts.sorted()[amounts.size / 2]
            val stable = amounts.count { abs(it - medianAmount) <= medianAmount * 0.2 }.toDouble() / amounts.size
            if (stable < 0.7) continue

            val last = occurrences.last()
            val lastDate = dates.last()
            // Stale: missed two cycles in a row.
            if (ChronoUnit.DAYS.between(lastDate, today) > median * 2 + pattern.tolerance) continue
            val previousAmount = amounts.getOrNull(amounts.size - 2)
            val changed = previousAmount != null && abs(last.amountMinor - previousAmount) > maxOf(100L, previousAmount / 20)
            val next = lastDate.plusDays(median.toLong())
            result += RecurringCandidate(
                merchantKey = key,
                displayName = group.last().merchant!!,
                categoryId = group.last().categoryId,
                frequency = pattern.frequency,
                intervalDays = median,
                expectedAmountMinor = medianAmount,
                lastAmountMinor = last.amountMinor,
                lastPaidAt = last.timestamp,
                nextDueAt = next.atStartOfDay(zone).toInstant().toEpochMilli(),
                occurrences = occurrences.size,
                amountChanged = changed,
                isSubscription = isSubscription,
                confidence = (0.4 + 0.4 * regular + 0.05 * minOf(occurrences.size, 4)).toFloat().coerceAtMost(0.98f),
            )
        }
        return result.sortedBy { it.nextDueAt }
    }
}
