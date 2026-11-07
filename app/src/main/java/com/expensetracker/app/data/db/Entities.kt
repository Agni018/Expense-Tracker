package com.expensetracker.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.expensetracker.app.domain.engine.LedgerEntry
import com.expensetracker.app.domain.engine.SplitPart
import com.expensetracker.app.domain.model.AccountType
import com.expensetracker.app.domain.model.CategoryKind
import com.expensetracker.app.domain.model.Direction
import com.expensetracker.app.domain.model.ImportKind
import com.expensetracker.app.domain.model.ImportStatus
import com.expensetracker.app.domain.model.PaymentMethod
import com.expensetracker.app.domain.model.RecurringFrequency
import com.expensetracker.app.domain.model.RecurringStatus
import com.expensetracker.app.domain.model.RuleMatchType
import com.expensetracker.app.domain.model.RuleOrigin
import com.expensetracker.app.domain.model.SourceOutcome
import com.expensetracker.app.domain.model.SourceType
import com.expensetracker.app.domain.model.TxnStatus
import com.expensetracker.app.domain.model.TxnType

@Entity(
    tableName = "transactions",
    indices = [
        Index("timestamp"), Index("categoryId"), Index("accountId"), Index("merchant"),
        Index("amountMinor"), Index("reference"), Index("needsReview"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMinor: Long,
    val currency: String = "INR",
    val timestamp: Long,
    val direction: Direction,
    val type: TxnType,
    val status: TxnStatus,
    val merchant: String?,
    val rawMerchant: String? = null,
    val description: String? = null,
    val categoryId: Long?,
    val accountId: Long?,
    val paymentMethod: PaymentMethod,
    val reference: String? = null,
    val upiId: String? = null,
    val orderId: String? = null,
    val notes: String? = null,
    /** Comma separated, lower-case tags. */
    val tags: String = "",
    val sourceType: SourceType,
    val confidence: Float = 1f,
    val needsReview: Boolean = false,
    val reviewReason: String? = null,
    val possibleDuplicateOf: Long? = null,
    /** Refund -> original payment. */
    val linkedTxnId: Long? = null,
    val receiptPath: String? = null,
    val isSplit: Boolean = false,
    val userEdited: Boolean = false,
    val categoryReason: String? = null,
    val balanceAfterMinor: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val tagList: List<String> get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun toLedger(splits: List<SplitPart> = emptyList()) = LedgerEntry(
        id, amountMinor, direction, type, status, categoryId, accountId, merchant, timestamp, paymentMethod, splits,
    )
}

@Entity(
    tableName = "source_records",
    indices = [Index(value = ["sourceType", "contentHash"], unique = true), Index("transactionId"), Index("importId"), Index("bodyHash")],
)
data class SourceRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long?,
    val sourceType: SourceType,
    val contentHash: String,
    val bodyHash: String? = null,
    val sender: String?,
    /** Original text kept only for financial records so they can be verified. */
    val rawText: String?,
    val receivedAt: Long,
    val outcome: SourceOutcome,
    val note: String? = null,
    val importId: Long? = null,
    val parser: String? = null,
    val parsedAmountMinor: Long? = null,
    val parsedDirection: Direction? = null,
    val parsedType: TxnType? = null,
    val parsedStatus: TxnStatus? = null,
    val parsedTimestamp: Long? = null,
    val parsedMerchant: String? = null,
    val parsedAccountLast4: String? = null,
    val parsedReference: String? = null,
    val parsedMethod: PaymentMethod? = null,
    val parsedConfidence: Float? = null,
    val createdAt: Long,
)

@Entity(tableName = "accounts", indices = [Index("last4")])
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    val institution: String? = null,
    val last4: String? = null,
    val openingBalanceMinor: Long = 0,
    val openingBalanceAt: Long = 0,
    val reportedBalanceMinor: Long? = null,
    val reportedBalanceAt: Long? = null,
    val colorArgb: Int,
    val isArchived: Boolean = false,
    val autoDetected: Boolean = false,
    val createdAt: Long,
)

@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String,
    val colorArgb: Int,
    val kind: CategoryKind,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    val isArchived: Boolean = false,
)

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val matchType: RuleMatchType,
    val categoryId: Long,
    val typeOverride: TxnType? = null,
    val origin: RuleOrigin,
    val hitCount: Int = 0,
    val createdAt: Long,
)

@Entity(tableName = "category_feedback", primaryKeys = ["merchantKey", "categoryId"])
data class CategoryFeedbackEntity(
    val merchantKey: String,
    val categoryId: Long,
    val count: Int,
    val updatedAt: Long,
)

@Entity(tableName = "splits", indices = [Index("transactionId")])
data class SplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val categoryId: Long?,
    val amountMinor: Long,
    val note: String? = null,
)

@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Null = overall monthly budget. */
    val categoryId: Long?,
    val amountMinor: Long,
    val rollover: Boolean = false,
    val createdAt: Long,
)

@Entity(tableName = "budget_alerts", primaryKeys = ["budgetId", "monthKey", "threshold"])
data class BudgetAlertEntity(
    val budgetId: Long,
    val monthKey: Int,
    val threshold: Int,
    val sentAt: Long,
)

@Entity(tableName = "recurring", indices = [Index(value = ["merchantKey"], unique = true)])
data class RecurringEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val status: RecurringStatus,
    val reminderEnabled: Boolean = true,
    val amountChanged: Boolean = false,
    val isSubscription: Boolean = false,
    val confidence: Float = 0.5f,
    val lastReminderFor: Long? = null,
    val updatedAt: Long,
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String,
    val targetMinor: Long,
    val targetDate: Long? = null,
    val colorArgb: Int,
    val isArchived: Boolean = false,
    val createdAt: Long,
)

@Entity(tableName = "goal_contributions", indices = [Index("goalId")])
data class GoalContributionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val amountMinor: Long,
    val date: Long,
    val note: String? = null,
)

@Entity(tableName = "imports")
data class ImportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: ImportKind,
    val fileName: String?,
    val storedPath: String? = null,
    val mimeType: String? = null,
    val accountId: Long? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: ImportStatus,
    val totalCount: Int = 0,
    val importedCount: Int = 0,
    val duplicateCount: Int = 0,
    val failedCount: Int = 0,
    val skippedCount: Int = 0,
    val message: String? = null,
)
