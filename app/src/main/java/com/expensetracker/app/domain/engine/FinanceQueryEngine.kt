package com.expensetracker.app.domain.engine

import com.expensetracker.app.core.Money
import com.expensetracker.app.domain.model.DateRange
import com.expensetracker.app.domain.model.TxnType
import com.expensetracker.app.domain.parser.MerchantDirectory
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Answers common questions with structured local queries. This is the extension point for a
 * future LLM assistant: an LLM could map free text to the same intents.
 */
class FinanceQueryEngine(private val zone: ZoneId, private val today: LocalDate) {

    data class Answer(val text: String, val details: List<String> = emptyList())

    fun answer(
        question: String,
        entries: List<LedgerEntry>,
        categoryNames: Map<Long, String>,
        subscriptions: List<RecurringCandidate>,
    ): Answer {
        val q = question.lowercase().trim()
        if (q.isBlank()) return help()
        val (range, label) = period(q)
        fun inRange(r: DateRange) = entries.filter { val d = date(it); !d.isBefore(r.start) && !d.isAfter(r.endInclusive) }
        val scoped = inRange(range)

        if (Regex("compare|\\bvs\\b|versus|compared").containsMatchIn(q)) {
            val thisMonth = DateRange.month(YearMonth.from(today))
            val lastMonth = thisMonth.previous()
            val a = FinanceCalculator.summarize(inRange(thisMonth))
            val b = FinanceCalculator.summarize(inRange(lastMonth))
            val diff = a.netSpend - b.netSpend
            return Answer(
                "This month you've spent ${Money.format(a.netSpend, false)}, ${if (diff >= 0) "${Money.format(diff, false)} more" else "${Money.format(-diff, false)} less"} than last month (${Money.format(b.netSpend, false)}).",
                listOf(
                    "Income: ${Money.format(a.income, false)} vs ${Money.format(b.income, false)}",
                    "Savings: ${Money.format(a.savings, false)} vs ${Money.format(b.savings, false)}",
                ),
            )
        }
        if (Regex("subscription|recurring|bills?\\b").containsMatchIn(q)) {
            if (subscriptions.isEmpty()) return Answer("I haven't detected any recurring payments yet. They appear after a few regular payments to the same merchant.")
            val monthly = subscriptions.sumOf { it.expectedAmountMinor * 30 / it.intervalDays }
            return Answer(
                "You have ${subscriptions.size} recurring payments, about ${Money.format(monthly, false)} per month.",
                subscriptions.map { "${it.displayName}: ${Money.format(it.expectedAmountMinor, false)} ${it.frequency.label.lowercase()}" },
            )
        }
        if (Regex("biggest|largest|highest|top expenses|most expensive").containsMatchIn(q)) {
            val top = scoped.filter { FinanceCalculator.counts(it) && it.type == TxnType.EXPENSE }.sortedByDescending { it.amountMinor }.take(5)
            if (top.isEmpty()) return Answer("No expenses found for $label.")
            return Answer("Your biggest expenses $label:", top.map { "${Money.format(it.amountMinor, false)} · ${it.merchant ?: categoryNames[it.categoryId] ?: "Unknown"} · ${fmt(date(it))}" })
        }
        if (Regex("top merchants|where.*(spend|spent) most|most (spent|money)").containsMatchIn(q)) {
            val merchants = FinanceCalculator.spendByMerchant(scoped).take(5)
            if (merchants.isEmpty()) return Answer("No merchant spending found for $label.")
            return Answer("Top merchants $label:", merchants.map { "${it.first}: ${Money.format(it.second, false)}" })
        }
        if (Regex("receive|received|income|earn|earned|got paid|credited|salary").containsMatchIn(q)) {
            val s = FinanceCalculator.summarize(scoped)
            val byCat = FinanceCalculator.incomeByCategory(scoped).entries.sortedByDescending { it.value }
            return Answer(
                "You received ${Money.format(s.income, false)} in income $label.",
                byCat.map { "${categoryNames[it.key] ?: "Uncategorised"}: ${Money.format(it.value, false)}" } +
                    listOfNotNull(s.refunds.takeIf { it > 0 }?.let { "Refunds (not income): ${Money.format(it, false)}" }),
            )
        }
        if (Regex("sav(e|ed|ing|ings)").containsMatchIn(q)) {
            val s = FinanceCalculator.summarize(scoped)
            return Answer("You saved ${Money.format(s.savings, false)} $label${s.savingsRatePct?.let { " (${it.toInt()}% of income)" } ?: ""}.")
        }

        // "spend on X" / "spent at X"
        val target = Regex("(?:on|at|for|to)\\s+([a-z0-9&' .-]+?)(?:\\s+(?:this|last|in|during|today|yesterday|since)\\b|\\?|$)").find(q)?.groupValues?.get(1)?.trim()
        if (target != null && target.length >= 2 && Regex("spen[dt]|expense|paid|pay|cost").containsMatchIn(q)) {
            val key = MerchantDirectory.compact(target)
            val category = categoryNames.entries.firstOrNull { MerchantDirectory.compact(it.value).let { n -> n == key || n.contains(key) || (key.length >= 4 && key.contains(n)) } }
            val relevant = if (category != null) {
                scoped.filter { it.categoryId == category.key }
            } else {
                scoped.filter { e -> e.merchant?.let { MerchantDirectory.compact(it).contains(key) } == true }
            }
            val total = if (category != null) FinanceCalculator.spendByCategory(relevant)[category.key] ?: 0L
            else relevant.filter { FinanceCalculator.counts(it) && it.type == TxnType.EXPENSE }.sumOf { it.amountMinor }
            val what = category?.value ?: target.replaceFirstChar { it.uppercase() }
            val count = relevant.count { FinanceCalculator.counts(it) && it.type == TxnType.EXPENSE }
            return Answer("You spent ${Money.format(total, false)} on $what $label across $count transaction${if (count == 1) "" else "s"}.")
        }
        if (Regex("spen[dt]|expense|cost").containsMatchIn(q)) {
            val s = FinanceCalculator.summarize(scoped)
            return Answer(
                "You spent ${Money.format(s.netSpend, false)} $label.",
                listOfNotNull(
                    "Gross expenses: ${Money.format(s.expenses, false)}",
                    s.refunds.takeIf { it > 0 }?.let { "Refunds: −${Money.format(it, false)}" },
                    "Transactions counted: ${s.countedTransactions}",
                ),
            )
        }
        if (Regex("how many|count|number of").containsMatchIn(q)) {
            return Answer("You have ${scoped.size} transactions $label.")
        }
        return help()
    }

    private fun help() = Answer(
        "I can answer questions about your spending using the data on this phone. Try:",
        listOf(
            "How much did I spend last month?",
            "How much did I spend on Swiggy?",
            "What were my biggest expenses this month?",
            "Compare this month with last month",
            "How much money did I receive this year?",
            "Show my subscriptions",
        ),
    )

    private fun date(e: LedgerEntry): LocalDate = Instant.ofEpochMilli(e.timestamp).atZone(zone).toLocalDate()

    private fun fmt(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))

    /** Period mentioned in the question; defaults to the current month. */
    fun period(q: String): Pair<DateRange, String> {
        val thisMonth = DateRange.month(YearMonth.from(today))
        Regex("last (\\d{1,3}) days").find(q)?.let {
            val n = it.groupValues[1].toLong()
            return DateRange(today.minusDays(n - 1), today) to "in the last $n days"
        }
        return when {
            q.contains("today") -> DateRange(today, today) to "today"
            q.contains("yesterday") -> today.minusDays(1).let { DateRange(it, it) } to "yesterday"
            q.contains("last week") -> {
                val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
                DateRange(start, start.plusDays(6)) to "last week"
            }
            q.contains("this week") -> DateRange(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today) to "this week"
            q.contains("last month") -> thisMonth.previous() to "last month"
            q.contains("last year") -> DateRange(LocalDate.of(today.year - 1, 1, 1), LocalDate.of(today.year - 1, 12, 31)) to "last year"
            q.contains("this year") || q.contains("year") -> DateRange(LocalDate.of(today.year, 1, 1), today) to "this year"
            else -> {
                val month = Month.entries.firstOrNull { m ->
                    Regex("\\b(${m.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase()}|${m.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase()})\\b").containsMatchIn(q)
                }
                if (month != null) {
                    val year = Regex("\\b(20\\d{2})\\b").find(q)?.groupValues?.get(1)?.toInt()
                        ?: if (month.value > today.monthValue) today.year - 1 else today.year
                    val ym = YearMonth.of(year, month)
                    DateRange.month(ym) to "in ${month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} $year"
                } else {
                    thisMonth to "this month"
                }
            }
        }
    }
}
