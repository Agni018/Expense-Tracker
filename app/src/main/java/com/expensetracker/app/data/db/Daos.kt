package com.expensetracker.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.expensetracker.app.domain.model.SourceOutcome
import com.expensetracker.app.domain.model.SourceType
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(txn: TransactionEntity): Long

    @Update
    suspend fun update(txn: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observe(id: Long): Flow<TransactionEntity?>

    @Query("SELECT * FROM transactions WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp DESC")
    fun observeRange(from: Long, to: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp DESC")
    suspend fun range(from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    suspend fun all(): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE amountMinor = :amount AND timestamp BETWEEN :from AND :to")
    suspend fun findByAmount(amount: Long, from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE needsReview = 1 ORDER BY timestamp DESC")
    fun observeNeedsReview(): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions WHERE needsReview = 1")
    fun observeReviewCount(): Flow<Int>

    @Query("SELECT * FROM transactions WHERE merchant = :merchant ORDER BY timestamp DESC")
    fun observeByMerchant(merchant: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE merchant = :merchant AND id != :excludeId")
    suspend fun byMerchant(merchant: String, excludeId: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE accountId = :accountId ORDER BY timestamp DESC")
    fun observeByAccount(accountId: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE accountId = :accountId")
    suspend fun byAccount(accountId: Long): List<TransactionEntity>

    @Query(
        "SELECT * FROM transactions WHERE type = 'EXPENSE' AND direction = 'DEBIT' AND timestamp BETWEEN :from AND :to " +
            "AND amountMinor >= :amount ORDER BY timestamp DESC",
    )
    suspend fun refundCandidates(amount: Long, from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE timestamp >= :from ORDER BY timestamp ASC")
    suspend fun since(from: Long): List<TransactionEntity>

    @Query("UPDATE transactions SET categoryId = :categoryId, updatedAt = :now WHERE merchant = :merchant AND userEdited = 0")
    suspend fun recategorizeMerchant(merchant: String, categoryId: Long, now: Long): Int

    @Query("UPDATE transactions SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)

    @Query("UPDATE transactions SET accountId = NULL WHERE accountId = :accountId")
    suspend fun clearAccount(accountId: Long)

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int

    @Query("SELECT MIN(timestamp) FROM transactions")
    suspend fun earliest(): Long?
}

@Dao
interface SourceRecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: SourceRecordEntity): Long

    @Update
    suspend fun update(record: SourceRecordEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM source_records WHERE sourceType = :type AND contentHash = :hash)")
    suspend fun exists(type: SourceType, hash: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM source_records WHERE sourceType = :type AND bodyHash = :bodyHash AND receivedAt BETWEEN :from AND :to)")
    suspend fun existsBody(type: SourceType, bodyHash: String, from: Long, to: Long): Boolean

    @Query("SELECT * FROM source_records WHERE id = :id")
    suspend fun get(id: Long): SourceRecordEntity?

    @Query("SELECT * FROM source_records WHERE transactionId = :txnId ORDER BY receivedAt ASC")
    fun observeForTransaction(txnId: Long): Flow<List<SourceRecordEntity>>

    @Query("SELECT * FROM source_records WHERE transactionId = :txnId")
    suspend fun forTransaction(txnId: Long): List<SourceRecordEntity>

    @Query("SELECT * FROM source_records WHERE transactionId IN (:ids)")
    suspend fun forTransactions(ids: List<Long>): List<SourceRecordEntity>

    @Query("UPDATE source_records SET transactionId = :target WHERE transactionId = :from")
    suspend fun moveToTransaction(from: Long, target: Long)

    @Query("UPDATE source_records SET outcome = :outcome, transactionId = NULL WHERE transactionId = :txnId")
    suspend fun detachFromTransaction(txnId: Long, outcome: SourceOutcome)

    @Query("SELECT * FROM source_records WHERE outcome = 'UNPARSED' ORDER BY receivedAt DESC LIMIT 200")
    fun observeUnparsed(): Flow<List<SourceRecordEntity>>

    @Query("DELETE FROM source_records WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM source_records WHERE importId = :importId")
    suspend fun forImport(importId: Long): List<SourceRecordEntity>

    @Query("SELECT * FROM source_records")
    suspend fun all(): List<SourceRecordEntity>
}

@Dao
interface AccountDao {
    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("SELECT * FROM accounts ORDER BY isArchived ASC, name ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE last4 = :last4")
    suspend fun byLast4(last4: String): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE type = 'CASH' LIMIT 1")
    suspend fun cashAccount(): AccountEntity?
}

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("SELECT * FROM categories ORDER BY isArchived ASC, sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: Long): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}

@Dao
interface RuleDao {
    @Insert
    suspend fun insert(rule: RuleEntity): Long

    @Update
    suspend fun update(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM rules ORDER BY origin ASC, createdAt DESC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules")
    suspend fun all(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE matchType = 'MERCHANT_EXACT' AND pattern = :pattern LIMIT 1")
    suspend fun exactFor(pattern: String): RuleEntity?

    @Query("UPDATE rules SET hitCount = hitCount + 1 WHERE id = :id")
    suspend fun hit(id: Long)

    @Query("DELETE FROM rules WHERE categoryId = :categoryId")
    suspend fun deleteForCategory(categoryId: Long)

    @Query("SELECT * FROM category_feedback WHERE merchantKey = :key AND categoryId = :categoryId")
    suspend fun feedback(key: String, categoryId: Long): CategoryFeedbackEntity?

    @Upsert
    suspend fun upsertFeedback(feedback: CategoryFeedbackEntity)
}

@Dao
interface SplitDao {
    @Insert
    suspend fun insertAll(splits: List<SplitEntity>)

    @Query("DELETE FROM splits WHERE transactionId = :txnId")
    suspend fun deleteFor(txnId: Long)

    @Query("SELECT * FROM splits WHERE transactionId = :txnId")
    suspend fun forTransaction(txnId: Long): List<SplitEntity>

    @Query("SELECT * FROM splits WHERE transactionId = :txnId")
    fun observeForTransaction(txnId: Long): Flow<List<SplitEntity>>

    @Query("SELECT * FROM splits")
    fun observeAll(): Flow<List<SplitEntity>>

    @Query("SELECT * FROM splits")
    suspend fun all(): List<SplitEntity>
}

@Dao
interface BudgetDao {
    @Insert
    suspend fun insert(budget: BudgetEntity): Long

    @Update
    suspend fun update(budget: BudgetEntity)

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM budgets ORDER BY categoryId IS NOT NULL, createdAt")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets")
    suspend fun all(): List<BudgetEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlert(alert: BudgetAlertEntity): Long

    @Query("DELETE FROM budget_alerts WHERE budgetId = :budgetId")
    suspend fun clearAlerts(budgetId: Long)
}

@Dao
interface RecurringDao {
    @Upsert
    suspend fun upsert(item: RecurringEntity): Long

    @Update
    suspend fun update(item: RecurringEntity)

    @Query("SELECT * FROM recurring ORDER BY status ASC, nextDueAt ASC")
    fun observeAll(): Flow<List<RecurringEntity>>

    @Query("SELECT * FROM recurring")
    suspend fun all(): List<RecurringEntity>

    @Query("SELECT * FROM recurring WHERE merchantKey = :key")
    suspend fun byKey(key: String): RecurringEntity?

    @Query("DELETE FROM recurring WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface GoalDao {
    @Insert
    suspend fun insert(goal: GoalEntity): Long

    @Update
    suspend fun update(goal: GoalEntity)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM goals ORDER BY isArchived, createdAt")
    fun observeAll(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals")
    suspend fun all(): List<GoalEntity>

    @Insert
    suspend fun insertContribution(c: GoalContributionEntity): Long

    @Query("DELETE FROM goal_contributions WHERE id = :id")
    suspend fun deleteContribution(id: Long)

    @Query("DELETE FROM goal_contributions WHERE goalId = :goalId")
    suspend fun deleteContributionsFor(goalId: Long)

    @Query("SELECT * FROM goal_contributions ORDER BY date DESC")
    fun observeContributions(): Flow<List<GoalContributionEntity>>

    @Query("SELECT * FROM goal_contributions")
    suspend fun allContributions(): List<GoalContributionEntity>
}

@Dao
interface ImportDao {
    @Insert
    suspend fun insert(item: ImportEntity): Long

    @Update
    suspend fun update(item: ImportEntity)

    @Query("SELECT * FROM imports WHERE id = :id")
    suspend fun get(id: Long): ImportEntity?

    @Query("SELECT * FROM imports ORDER BY startedAt DESC LIMIT 300")
    fun observeAll(): Flow<List<ImportEntity>>

    @Query("SELECT * FROM imports WHERE kind = :kind ORDER BY startedAt DESC LIMIT 1")
    fun observeLatest(kind: com.expensetracker.app.domain.model.ImportKind): Flow<ImportEntity?>

    @Query("DELETE FROM imports WHERE id = :id")
    suspend fun delete(id: Long)
}
