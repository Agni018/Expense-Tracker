package com.expensetracker.app.data.ingest

import com.expensetracker.app.core.Money
import com.expensetracker.app.data.db.AppDatabase
import com.expensetracker.app.data.db.BudgetAlertEntity
import com.expensetracker.app.data.prefs.SettingsRepository
import com.expensetracker.app.data.repo.TransactionRepository
import com.expensetracker.app.domain.engine.BudgetCalculator
import com.expensetracker.app.domain.engine.BudgetSpec
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.notify.Notifier
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Side effects after new transactions arrive: notifications and budget threshold alerts. */
@Singleton
class PostIngest @Inject constructor(
    private val db: AppDatabase,
    private val transactions: TransactionRepository,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
    private val zone: ZoneId,
) {

    suspend fun onRealtime(outcome: TransactionIngestor.Outcome) {
        val created = outcome as? TransactionIngestor.Outcome.Created ?: return
        val s = settings.current()
        if (s.notifyNewTransactions) {
            val t = transactions.get(created.transactionId) ?: return
            val category = t.categoryId?.let { db.categoryDao().get(it) }
            val verb = if (t.direction == Direction.DEBIT) "Spent" else "Received"
            val status = if (t.status != TxnStatus.COMPLETED) " (${t.status.label.lowercase()})" else ""
            notifier.transaction(
                t.id,
                "$verb ${Money.format(t.amountMinor)}${t.merchant?.let { " · $it" } ?: ""}$status",
                listOfNotNull(category?.let { "${it.emoji} ${it.name}" }, if (t.needsReview) "Tap to review" else null).joinToString(" · ").ifEmpty { "Tap to view" },
            )
        }
        checkBudgets()
    }

    /** Sends each 50/80/100%/exceeded alert at most once per budget per month. */
    suspend fun checkBudgets() {
        if (!settings.current().budgetAlerts) return
        val budgets = db.budgetDao().all()
        if (budgets.isEmpty()) return
        val today = LocalDate.now(zone)
        val month = YearMonth.from(today)
        val from = month.minusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val ledger = transactions.ledgerSince(from)
        fun monthOf(ts: Long) = YearMonth.from(Instant.ofEpochMilli(ts).atZone(zone))
        val thisMonth = ledger.filter { monthOf(it.timestamp) == month }
        val lastMonth = ledger.filter { monthOf(it.timestamp) == month.minusMonths(1) }
        val categories = db.categoryDao().all().associateBy { it.id }
        val monthKey = month.year * 100 + month.monthValue
        for (b in budgets) {
            val status = BudgetCalculator.status(
                BudgetSpec(b.id, b.categoryId, b.amountMinor, b.rollover), month, today,
                BudgetCalculator.spentFor(b.categoryId, thisMonth), BudgetCalculator.spentFor(b.categoryId, lastMonth),
            )
            val level = when {
                status.isExceeded -> EXCEEDED
                else -> status.thresholdReached ?: continue
            }
            val levels = (BudgetCalculator.thresholds.filter { it <= level } + listOfNotNull(EXCEEDED.takeIf { level == EXCEEDED }))
            val inserted = levels.map { db.budgetDao().insertAlert(BudgetAlertEntity(b.id, monthKey, it, System.currentTimeMillis())) != -1L }
            if (!inserted.last()) continue
            val name = b.categoryId?.let { categories[it]?.name } ?: "Monthly"
            val title = if (level == EXCEEDED) "$name budget exceeded" else "$level% of $name budget used"
            notifier.budget(
                (b.id * 1000 + level).toInt(), title,
                "${Money.format(status.spentMinor, false)} spent of ${Money.format(status.effectiveLimitMinor, false)} this month.",
            )
        }
    }

    companion object {
        const val EXCEEDED = 101
    }
}
