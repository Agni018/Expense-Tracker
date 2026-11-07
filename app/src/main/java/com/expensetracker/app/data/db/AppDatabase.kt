package com.expensetracker.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TransactionEntity::class,
        SourceRecordEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        RuleEntity::class,
        CategoryFeedbackEntity::class,
        SplitEntity::class,
        BudgetEntity::class,
        BudgetAlertEntity::class,
        RecurringEntity::class,
        GoalEntity::class,
        GoalContributionEntity::class,
        ImportEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun sourceRecordDao(): SourceRecordDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun ruleDao(): RuleDao
    abstract fun splitDao(): SplitDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringDao(): RecurringDao
    abstract fun goalDao(): GoalDao
    abstract fun importDao(): ImportDao

    companion object {
        const val NAME = "expense-tracker.db"
    }
}
