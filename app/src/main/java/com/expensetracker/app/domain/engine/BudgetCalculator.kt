package com.expensetracker.app.domain.engine

import java.time.LocalDate
import java.time.YearMonth

/** [categoryId] null means the overall monthly budget (measured against net spend). */
data class BudgetSpec(val id: Long, val categoryId: Long?, val limitMinor: Long, val rollover: Boolean)

data class BudgetStatus(
    val budget: BudgetSpec,
    val effectiveLimitMinor: Long,
    val carriedOverMinor: Long,
    val spentMinor: Long,
    val remainingMinor: Long,
    val utilizationPct: Double,
    val projectedMinor: Long,
    val thresholdReached: Int?,
) {
    val isExceeded: Boolean get() = spentMinor > effectiveLimitMinor
    val willExceed: Boolean get() = !isExceeded && projectedMinor > effectiveLimitMinor
}

object BudgetCalculator {

    val thresholds = listOf(50, 80, 100)

    /** Rollover carries forward only the unused part of last month's limit. */
    fun status(
        budget: BudgetSpec,
        month: YearMonth,
        today: LocalDate,
        spentMinor: Long,
        spentLastMonthMinor: Long,
    ): BudgetStatus {
        val carried = if (budget.rollover) maxOf(0L, budget.limitMinor - spentLastMonthMinor) else 0L
        val limit = budget.limitMinor + carried
        val utilization = if (limit > 0) spentMinor * 100.0 / limit else if (spentMinor > 0) 100.0 else 0.0
        val daysInMonth = month.lengthOfMonth()
        val elapsed = when {
            YearMonth.from(today) == month -> today.dayOfMonth
            YearMonth.from(today).isAfter(month) -> daysInMonth
            else -> 0
        }
        val projected = if (elapsed == 0) spentMinor else spentMinor * daysInMonth / elapsed
        val threshold = thresholds.lastOrNull { utilization >= it }
        return BudgetStatus(budget, limit, carried, spentMinor, limit - spentMinor, utilization, projected, threshold)
    }

    /** Spend that a budget is measured against for one month of ledger entries. */
    fun spentFor(categoryId: Long?, entries: List<LedgerEntry>): Long =
        if (categoryId == null) FinanceCalculator.summarize(entries).netSpend
        else FinanceCalculator.spendByCategory(entries)[categoryId] ?: 0L
}
