package com.expensetracker.app.domain.engine

import com.expensetracker.app.domain.model.DateRange
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class SplitPart(val categoryId: Long?, val amountMinor: Long)

/** Minimal, storage-independent view of a transaction used by all calculations. */
data class LedgerEntry(
    val id: Long,
    val amountMinor: Long,
    val direction: Direction,
    val type: TxnType,
    val status: TxnStatus,
    val categoryId: Long?,
    val accountId: Long?,
    val merchant: String?,
    val timestamp: Long,
    val paymentMethod: PaymentMethod = PaymentMethod.OTHER,
    val splits: List<SplitPart> = emptyList(),
)

data class FinanceSummary(
    val income: Long,
    val expenses: Long,
    val refunds: Long,
    val reimbursements: Long,
    val netSpend: Long,
    val savings: Long,
    val savingsRatePct: Double?,
    val investments: Long,
    val transfers: Long,
    val cashWithdrawals: Long,
    val cardBillPayments: Long,
    val loanProceeds: Long,
    val countedTransactions: Int,
    val pendingCount: Int,
    val failedCount: Int,
) {
    companion object {
        val EMPTY = FinanceSummary(0, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

data class MonthTotals(val month: YearMonth, val income: Long, val netSpend: Long)

/**
 * Deterministic money arithmetic shared by the dashboard, analytics, budgets and reports.
 *  - Only COMPLETED transactions count.
 *  - Spending = EXPENSE minus REFUND minus REIMBURSEMENT (net spend).
 *  - Income = INCOME only; transfers, card bill payments, cash withdrawals, investments and
 *    loan proceeds are tracked separately and never counted as spending or income.
 */
object FinanceCalculator {

    fun counts(e: LedgerEntry) = e.status == TxnStatus.COMPLETED

    fun summarize(entries: List<LedgerEntry>): FinanceSummary {
        var income = 0L
        var expenses = 0L
        var refunds = 0L
        var reimbursements = 0L
        var investments = 0L
        var transfers = 0L
        var cash = 0L
        var cardBills = 0L
        var loans = 0L
        var counted = 0
        var pending = 0
        var failed = 0
        for (e in entries) {
            when (e.status) {
                TxnStatus.PENDING -> { pending++; continue }
                TxnStatus.FAILED, TxnStatus.DECLINED, TxnStatus.REVERSED -> { failed++; continue }
                TxnStatus.COMPLETED -> Unit
            }
            counted++
            when (e.type) {
                TxnType.EXPENSE -> expenses += e.amountMinor
                TxnType.INCOME -> income += e.amountMinor
                TxnType.REFUND -> refunds += e.amountMinor
                TxnType.REIMBURSEMENT -> reimbursements += e.amountMinor
                TxnType.INVESTMENT -> investments += if (e.direction == Direction.DEBIT) e.amountMinor else -e.amountMinor
                TxnType.TRANSFER -> transfers += e.amountMinor
                TxnType.CASH_WITHDRAWAL -> cash += e.amountMinor
                TxnType.CREDIT_CARD_PAYMENT -> if (e.direction == Direction.DEBIT) cardBills += e.amountMinor
                TxnType.LOAN -> loans += e.amountMinor
            }
        }
        val netSpend = maxOf(0L, expenses - refunds - reimbursements)
        val savings = income - netSpend
        val rate = if (income > 0) savings.toDouble() * 100.0 / income else null
        return FinanceSummary(
            income, expenses, refunds, reimbursements, netSpend, savings, rate, investments, transfers,
            cash, cardBills, loans, counted, pending, failed,
        )
    }

    /** Net spending per category: expense (or its splits) minus refunds in that category. */
    fun spendByCategory(entries: List<LedgerEntry>): Map<Long?, Long> {
        val totals = HashMap<Long?, Long>()
        for (e in entries) {
            if (!counts(e)) continue
            when (e.type) {
                TxnType.EXPENSE -> {
                    var remaining = e.amountMinor
                    for (s in e.splits) {
                        val part = minOf(s.amountMinor, remaining)
                        if (part <= 0) continue
                        totals[s.categoryId] = (totals[s.categoryId] ?: 0) + part
                        remaining -= part
                    }
                    if (remaining > 0) totals[e.categoryId] = (totals[e.categoryId] ?: 0) + remaining
                }
                TxnType.REFUND, TxnType.REIMBURSEMENT -> totals[e.categoryId] = (totals[e.categoryId] ?: 0) - e.amountMinor
                else -> Unit
            }
        }
        return totals.filterValues { it > 0 }
    }

    fun incomeByCategory(entries: List<LedgerEntry>): Map<Long?, Long> =
        entries.filter { counts(it) && it.type == TxnType.INCOME }
            .groupBy { it.categoryId }
            .mapValues { (_, list) -> list.sumOf { it.amountMinor } }

    fun spendByMerchant(entries: List<LedgerEntry>): List<Pair<String, Long>> =
        entries.filter { counts(it) && it.type == TxnType.EXPENSE && !it.merchant.isNullOrBlank() }
            .groupBy { it.merchant!! }
            .map { (merchant, list) -> merchant to list.sumOf { it.amountMinor } }
            .sortedByDescending { it.second }

    fun spendByMethod(entries: List<LedgerEntry>): Map<PaymentMethod, Long> =
        entries.filter { counts(it) && it.type == TxnType.EXPENSE }
            .groupBy { it.paymentMethod }
            .mapValues { (_, list) -> list.sumOf { it.amountMinor } }

    fun spendByAccount(entries: List<LedgerEntry>): Map<Long?, Long> =
        entries.filter { counts(it) && it.type == TxnType.EXPENSE }
            .groupBy { it.accountId }
            .mapValues { (_, list) -> list.sumOf { it.amountMinor } }

    /** Daily net spend for every day of [range] (days without spending are zero). */
    fun dailySpend(entries: List<LedgerEntry>, range: DateRange, zone: ZoneId): List<Pair<LocalDate, Long>> {
        val byDay = HashMap<LocalDate, Long>()
        for (e in entries) {
            if (!counts(e)) continue
            val sign = when (e.type) {
                TxnType.EXPENSE -> 1
                TxnType.REFUND, TxnType.REIMBURSEMENT -> -1
                else -> 0
            }
            if (sign == 0) continue
            val day = Instant.ofEpochMilli(e.timestamp).atZone(zone).toLocalDate()
            if (!range.contains(day)) continue
            byDay[day] = (byDay[day] ?: 0) + sign * e.amountMinor
        }
        return generateSequence(range.start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(range.endInclusive) }
            .map { it to maxOf(0L, byDay[it] ?: 0L) }
            .toList()
    }

    fun monthly(entries: List<LedgerEntry>, months: List<YearMonth>, zone: ZoneId): List<MonthTotals> {
        val grouped = entries.groupBy { YearMonth.from(Instant.ofEpochMilli(it.timestamp).atZone(zone)) }
        return months.map { ym ->
            val s = summarize(grouped[ym].orEmpty())
            MonthTotals(ym, s.income, s.netSpend)
        }
    }

    /** Running balance of an account from its opening balance. */
    fun accountBalance(openingMinor: Long, entries: List<LedgerEntry>): Long =
        entries.filter { counts(it) }.fold(openingMinor) { acc, e ->
            if (e.direction == Direction.CREDIT) acc + e.amountMinor else acc - e.amountMinor
        }
}
