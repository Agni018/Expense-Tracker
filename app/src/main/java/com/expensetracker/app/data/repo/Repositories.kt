package com.expensetracker.app.data.repo

import androidx.room.withTransaction
import com.expensetracker.app.data.db.AccountEntity
import com.expensetracker.app.data.db.AppDatabase
import com.expensetracker.app.data.db.BudgetEntity
import com.expensetracker.app.data.db.CategoryEntity
import com.expensetracker.app.data.db.GoalContributionEntity
import com.expensetracker.app.data.db.GoalEntity
import com.expensetracker.app.data.db.ImportEntity
import com.expensetracker.app.data.db.RecurringEntity
import com.expensetracker.app.data.db.RuleEntity
import com.expensetracker.app.domain.engine.Categorizer
import com.expensetracker.app.domain.engine.RecurringDetector
import com.expensetracker.app.domain.engine.RuleSpec
import com.expensetracker.app.domain.model.AccountType
import com.expensetracker.app.domain.model.DefaultCategories
import com.expensetracker.app.domain.model.ImportKind
import com.expensetracker.app.domain.model.ImportStatus
import com.expensetracker.app.domain.model.RecurringStatus
import com.expensetracker.app.domain.model.RuleMatchType
import com.expensetracker.app.domain.model.RuleOrigin
import com.expensetracker.app.domain.model.TxnType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.categoryDao()

    fun observeAll(): Flow<List<CategoryEntity>> = dao.observeAll()
    suspend fun all() = dao.all()

    suspend fun seedDefaultsIfEmpty() {
        if (dao.count() > 0) return
        DefaultCategories.all.forEachIndexed { i, seed ->
            dao.insert(CategoryEntity(name = seed.name, emoji = seed.emoji, colorArgb = seed.color.toInt(), kind = seed.kind, isDefault = true, sortOrder = i))
        }
    }

    suspend fun save(category: CategoryEntity): Long =
        if (category.id == 0L) dao.insert(category.copy(sortOrder = dao.count() + 1)) else category.id.also { dao.update(category) }

    /** Removes a category; its transactions become uncategorised and its rules/budgets are dropped. */
    suspend fun delete(category: CategoryEntity) = db.withTransaction {
        db.transactionDao().clearCategory(category.id)
        db.ruleDao().deleteForCategory(category.id)
        db.budgetDao().all().filter { it.categoryId == category.id }.forEach { db.budgetDao().delete(it.id) }
        dao.delete(category)
    }
}

@Singleton
class AccountRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.accountDao()

    fun observeAll(): Flow<List<AccountEntity>> = dao.observeAll()
    suspend fun all() = dao.all()

    suspend fun ensureCashAccount() {
        if (dao.cashAccount() == null) {
            dao.insert(AccountEntity(name = "Cash", type = AccountType.CASH, colorArgb = 0xFF10B981.toInt(), createdAt = System.currentTimeMillis()))
        }
    }

    suspend fun save(account: AccountEntity): Long =
        if (account.id == 0L) dao.insert(account.copy(createdAt = System.currentTimeMillis())) else account.id.also { dao.update(account) }

    suspend fun delete(account: AccountEntity) = db.withTransaction {
        db.transactionDao().clearAccount(account.id)
        dao.delete(account)
    }
}

@Singleton
class RuleRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.ruleDao()

    fun observeAll(): Flow<List<RuleEntity>> = dao.observeAll()

    /** Adds a rule and applies it to existing transactions that were not edited by hand. */
    suspend fun add(pattern: String, matchType: RuleMatchType, categoryId: Long, typeOverride: TxnType?, applyToExisting: Boolean): Int =
        db.withTransaction {
            val rule = RuleEntity(pattern = pattern.trim(), matchType = matchType, categoryId = categoryId, typeOverride = typeOverride, origin = RuleOrigin.USER, createdAt = System.currentTimeMillis())
            val id = dao.insert(rule)
            if (!applyToExisting) return@withTransaction 0
            val categorizer = Categorizer(emptyMap(), listOf(RuleSpec(id, rule.pattern, matchType, categoryId, typeOverride, RuleOrigin.USER)))
            var changed = 0
            val now = System.currentTimeMillis()
            db.transactionDao().all().filter { !it.userEdited }.forEach { t ->
                val r = categorizer.categorize(t.merchant, t.description, t.type, t.direction)
                if (r.ruleId == id) {
                    db.transactionDao().update(t.copy(categoryId = categoryId, type = typeOverride ?: t.type, categoryReason = r.reason, updatedAt = now))
                    changed++
                }
            }
            changed
        }

    suspend fun delete(id: Long) = dao.delete(id)
}

@Singleton
class BudgetRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.budgetDao()

    fun observeAll(): Flow<List<BudgetEntity>> = dao.observeAll()
    suspend fun all() = dao.all()

    suspend fun save(budget: BudgetEntity): Long = db.withTransaction {
        val existing = dao.all().firstOrNull { it.categoryId == budget.categoryId && it.id != budget.id }
        if (budget.id == 0L && existing != null) {
            dao.update(existing.copy(amountMinor = budget.amountMinor, rollover = budget.rollover))
            dao.clearAlerts(existing.id)
            existing.id
        } else if (budget.id == 0L) {
            dao.insert(budget.copy(createdAt = System.currentTimeMillis()))
        } else {
            dao.update(budget)
            dao.clearAlerts(budget.id)
            budget.id
        }
    }

    suspend fun delete(id: Long) = db.withTransaction {
        dao.clearAlerts(id)
        dao.delete(id)
    }
}

@Singleton
class GoalRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.goalDao()

    fun observeGoals(): Flow<List<GoalEntity>> = dao.observeAll()
    fun observeContributions(): Flow<List<GoalContributionEntity>> = dao.observeContributions()

    suspend fun save(goal: GoalEntity): Long =
        if (goal.id == 0L) dao.insert(goal.copy(createdAt = System.currentTimeMillis())) else goal.id.also { dao.update(goal) }

    suspend fun delete(id: Long) = db.withTransaction {
        dao.deleteContributionsFor(id)
        dao.delete(id)
    }

    suspend fun contribute(goalId: Long, amountMinor: Long, note: String?) =
        dao.insertContribution(GoalContributionEntity(goalId = goalId, amountMinor = amountMinor, date = System.currentTimeMillis(), note = note))

    suspend fun deleteContribution(id: Long) = dao.deleteContribution(id)
}

@Singleton
class RecurringRepository @Inject constructor(
    private val db: AppDatabase,
    private val transactions: TransactionRepository,
    private val zone: ZoneId,
) {
    private val dao = db.recurringDao()

    fun observeAll(): Flow<List<RecurringEntity>> = dao.observeAll()

    /** Re-runs detection over the last 400 days, keeping the user's confirm/dismiss choices. */
    suspend fun refresh(today: LocalDate = LocalDate.now(zone)) {
        val from = today.minusDays(400).atStartOfDay(zone).toInstant().toEpochMilli()
        val found = RecurringDetector.detect(transactions.ledgerSince(from), zone, today)
        val now = System.currentTimeMillis()
        db.withTransaction {
            for (c in found) {
                val existing = dao.byKey(c.merchantKey)
                dao.upsert(
                    RecurringEntity(
                        id = existing?.id ?: 0,
                        merchantKey = c.merchantKey,
                        displayName = c.displayName,
                        categoryId = c.categoryId,
                        frequency = c.frequency,
                        intervalDays = c.intervalDays,
                        expectedAmountMinor = c.expectedAmountMinor,
                        lastAmountMinor = c.lastAmountMinor,
                        lastPaidAt = c.lastPaidAt,
                        nextDueAt = c.nextDueAt,
                        occurrences = c.occurrences,
                        status = existing?.status ?: RecurringStatus.SUGGESTED,
                        reminderEnabled = existing?.reminderEnabled ?: true,
                        amountChanged = c.amountChanged,
                        isSubscription = c.isSubscription,
                        confidence = c.confidence,
                        lastReminderFor = existing?.lastReminderFor,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    suspend fun setStatus(item: RecurringEntity, status: RecurringStatus) = dao.update(item.copy(status = status, updatedAt = System.currentTimeMillis()))
    suspend fun setReminder(item: RecurringEntity, enabled: Boolean) = dao.update(item.copy(reminderEnabled = enabled))
    suspend fun markReminded(item: RecurringEntity) = dao.update(item.copy(lastReminderFor = item.nextDueAt))
    suspend fun all() = dao.all()
}

@Singleton
class ImportRepository @Inject constructor(private val db: AppDatabase) {
    private val dao = db.importDao()

    fun observeAll(): Flow<List<ImportEntity>> = dao.observeAll()
    fun observeLatest(kind: ImportKind): Flow<ImportEntity?> = dao.observeLatest(kind)
    suspend fun get(id: Long) = dao.get(id)

    suspend fun start(kind: ImportKind, fileName: String?, storedPath: String? = null, mimeType: String? = null, accountId: Long? = null): Long =
        dao.insert(
            ImportEntity(
                kind = kind, fileName = fileName, storedPath = storedPath, mimeType = mimeType, accountId = accountId,
                startedAt = System.currentTimeMillis(), status = ImportStatus.RUNNING,
            ),
        )

    data class Counts(val total: Int = 0, val imported: Int = 0, val duplicates: Int = 0, val failed: Int = 0, val skipped: Int = 0)

    /** An import is only a success when at least one valid transaction was read. */
    suspend fun finish(id: Long, counts: Counts, error: String? = null): ImportStatus {
        val status = when {
            error != null -> ImportStatus.FAILED
            counts.imported + counts.duplicates == 0 -> ImportStatus.NO_TRANSACTIONS
            counts.failed > 0 -> ImportStatus.PARTIAL
            else -> ImportStatus.SUCCESS
        }
        dao.get(id)?.let {
            dao.update(
                it.copy(
                    finishedAt = System.currentTimeMillis(), status = status, totalCount = counts.total, importedCount = counts.imported,
                    duplicateCount = counts.duplicates, failedCount = counts.failed, skippedCount = counts.skipped,
                    message = error ?: if (status == ImportStatus.NO_TRANSACTIONS) "No valid transactions were found" else null,
                ),
            )
        }
        return status
    }

    suspend fun delete(id: Long) = dao.delete(id)
}
