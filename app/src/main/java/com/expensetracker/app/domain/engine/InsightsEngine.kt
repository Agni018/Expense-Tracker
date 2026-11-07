package com.expensetracker.app.domain.engine

import com.expensetracker.app.core.Money
import com.expensetracker.app.domain.model.DateRange
import com.expensetracker.app.domain.model.TxnType
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt

enum class InsightKind { POSITIVE, WARNING, INFO }

data class Insight(val id: String, val title: String, val message: String, val kind: InsightKind, val explanation: String)

data class UpcomingPayment(val name: String, val due: LocalDate, val amountMinor: Long)

/** Rule-based insights. Each insight states the numbers it was computed from. */
object InsightsEngine {

    data class Input(
        val current: List<LedgerEntry>,
        val previous: List<LedgerEntry>,
        val history: List<LedgerEntry>,
        val range: DateRange,
        val today: LocalDate,
        val categoryNames: Map<Long, String>,
        val budgets: List<Pair<String, BudgetStatus>>,
        val upcoming: List<UpcomingPayment>,
    )

    fun generate(input: Input): List<Insight> {
        val out = ArrayList<Insight>()
        val now = FinanceCalculator.summarize(input.current)
        val before = FinanceCalculator.summarize(input.previous)
        fun name(id: Long?) = id?.let { input.categoryNames[it] } ?: "Uncategorised"
        val periodWord = if (input.range.days in 28..31) "last month" else "the previous period"

        // Budget warnings first: they are actionable.
        for ((budgetName, status) in input.budgets) {
            when {
                status.isExceeded -> out += Insight(
                    "budget-over-${status.budget.id}", "$budgetName budget exceeded",
                    "You've spent ${Money.format(status.spentMinor, false)} of ${Money.format(status.effectiveLimitMinor, false)}.",
                    InsightKind.WARNING,
                    "Spent ${status.utilizationPct.roundToInt()}% of the monthly limit.",
                )
                status.willExceed -> out += Insight(
                    "budget-pace-${status.budget.id}", "Watch your $budgetName budget",
                    "Your current spending pace may exceed your $budgetName budget.",
                    InsightKind.WARNING,
                    "Projected ${Money.format(status.projectedMinor, false)} by month end against ${Money.format(status.effectiveLimitMinor, false)}.",
                )
            }
        }

        // Overall comparison.
        if (before.netSpend > 0 && now.netSpend > 0) {
            val change = (now.netSpend - before.netSpend) * 100.0 / before.netSpend
            if (abs(change) >= 10) {
                val up = change > 0
                out += Insight(
                    "overall-change", if (up) "Spending is up" else "Spending is down",
                    "You've spent ${abs(change).roundToInt()}% ${if (up) "more" else "less"} than $periodWord.",
                    if (up) InsightKind.WARNING else InsightKind.POSITIVE,
                    "${Money.format(now.netSpend, false)} now vs ${Money.format(before.netSpend, false)} before.",
                )
            }
        }

        // Category movements.
        val nowCat = FinanceCalculator.spendByCategory(input.current)
        val beforeCat = FinanceCalculator.spendByCategory(input.previous)
        (nowCat.keys + beforeCat.keys).map { id -> id to ((nowCat[id] ?: 0) - (beforeCat[id] ?: 0)) }
            .filter { (id, delta) -> abs(delta) >= 50_000 && abs(delta) >= (beforeCat[id] ?: 0) / 5 }
            .sortedByDescending { abs(it.second) }
            .take(2)
            .forEach { (id, delta) ->
                val more = delta > 0
                out += Insight(
                    "cat-change-$id", name(id),
                    "You spent ${Money.format(abs(delta), false)} ${if (more) "more" else "less"} on ${name(id)} than $periodWord.",
                    if (more) InsightKind.INFO else InsightKind.POSITIVE,
                    "${Money.format(nowCat[id] ?: 0, false)} now vs ${Money.format(beforeCat[id] ?: 0, false)} before.",
                )
            }

        // Biggest category share.
        if (now.netSpend > 0) {
            nowCat.maxByOrNull { it.value }?.let { (id, amount) ->
                val share = (amount * 100.0 / now.netSpend).roundToInt()
                if (share >= 20) out += Insight(
                    "top-share", "Top category",
                    "${name(id)} represents $share% of your expenses.",
                    InsightKind.INFO,
                    "${Money.format(amount, false)} of ${Money.format(now.netSpend, false)} net spend.",
                )
            }
        }

        // Month-end projection while the current month is in progress.
        val month = YearMonth.from(input.today)
        if (input.range == DateRange.month(month) && input.today.dayOfMonth in 5 until month.lengthOfMonth() && now.netSpend > 0) {
            val projected = now.netSpend * month.lengthOfMonth() / input.today.dayOfMonth
            out += Insight(
                "projection", "Month-end projection",
                "At this pace you'll spend about ${Money.format(projected, false)} this month.",
                InsightKind.INFO,
                "${Money.format(now.netSpend, false)} over ${input.today.dayOfMonth} days, extended to ${month.lengthOfMonth()} days.",
            )
        }

        // Savings rate.
        now.savingsRatePct?.let { rate ->
            out += Insight(
                "savings-rate", "Savings rate",
                if (rate >= 0) "You saved ${rate.roundToInt()}% of your income." else "You spent ${Money.format(-now.savings, false)} more than you earned.",
                if (rate >= 20) InsightKind.POSITIVE else if (rate < 0) InsightKind.WARNING else InsightKind.INFO,
                "Income ${Money.format(now.income, false)} − net spend ${Money.format(now.netSpend, false)}.",
            )
        }

        // Upcoming recurring payments in the next 7 days.
        val nextWeek = input.upcoming.filter { !it.due.isBefore(input.today) && !it.due.isAfter(input.today.plusDays(7)) }
        if (nextWeek.isNotEmpty()) {
            val total = nextWeek.sumOf { it.amountMinor }
            val count = nextWeek.size
            out += Insight(
                "upcoming", "Upcoming payments",
                if (count == 1) "${nextWeek[0].name} (${Money.format(total, false)}) is expected in the next 7 days."
                else "$count recurring payments (${Money.format(total, false)}) are expected in the next 7 days.",
                InsightKind.INFO,
                nextWeek.joinToString { "${it.name} on ${it.due.dayOfMonth}/${it.due.monthValue}" },
            )
        }

        // Unusual transactions compared with the category's history.
        val historyByCategory = input.history.filter { FinanceCalculator.counts(it) && it.type == TxnType.EXPENSE }.groupBy { it.categoryId }
        input.current.filter { FinanceCalculator.counts(it) && it.type == TxnType.EXPENSE && it.amountMinor >= 2_00_000 }
            .mapNotNull { e ->
                val past = historyByCategory[e.categoryId].orEmpty().filter { it.id != e.id }.map { it.amountMinor }.sorted()
                if (past.size < 5) return@mapNotNull null
                val median = past[past.size / 2]
                if (median > 0 && e.amountMinor >= median * 3) e to median else null
            }
            .maxByOrNull { it.first.amountMinor }
            ?.let { (e, median) ->
                out += Insight(
                    "unusual-${e.id}", "Unusual expense",
                    "${Money.format(e.amountMinor, false)}${e.merchant?.let { " at $it" } ?: ""} is ${(e.amountMinor / median)}× your typical ${name(e.categoryId)} spend.",
                    InsightKind.WARNING,
                    "Typical (median) ${name(e.categoryId)} transaction: ${Money.format(median, false)}.",
                )
            }

        // Most frequent merchant.
        input.current.filter { FinanceCalculator.counts(it) && it.type == TxnType.EXPENSE && it.merchant != null }
            .groupBy { it.merchant!! }
            .maxByOrNull { it.value.size }
            ?.takeIf { it.value.size >= 3 }
            ?.let { (merchant, list) ->
                out += Insight(
                    "merchant-$merchant", merchant,
                    "${list.size} payments to $merchant totalling ${Money.format(list.sumOf { it.amountMinor }, false)}.",
                    InsightKind.INFO,
                    "Your most frequent merchant in this period.",
                )
            }
        return out
    }
}
